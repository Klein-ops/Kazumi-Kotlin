package com.kazumi.port.service

import android.os.Handler
import android.os.Looper
import com.kazumi.port.data.db.DownloadDao
import com.kazumi.port.data.db.DownloadEpisodeEntity
import com.kazumi.port.data.db.DownloadRecordEntity
import com.kazumi.port.data.db.DownloadStatus
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.network.DownloadHttpClient
import com.kazumi.port.network.NetworkException
import com.kazumi.port.network.NetworkExceptionType
import com.kazumi.port.util.M3u8AdFilter
import com.kazumi.port.util.M3u8Parser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 非 M3U8 异常（URL 不是 m3u8 播放列表时抛出，触发直链 mp4 回退）
 */
private class NotM3u8Exception(message: String) : Exception(message)

/**
 * 存储空间不足异常
 */
private class InsufficientStorageException(
    val availableBytes: Long,
    val requiredBytes: Long
) : Exception("存储空间不足")

/**
 * 下载任务
 * 对应原版 DownloadTask
 */
class DownloadTask(
    val recordKey: String,
    val episodeNumber: Int,
    val cancelToken: DownloadHttpClient.CancelToken = DownloadHttpClient.CancelToken()
) {
    @Volatile
    var isPaused: Boolean = false
}

/**
 * 下载请求
 * 对应原版 DownloadRequest
 */
data class DownloadRequest(
    val recordKey: String,
    val bangumiId: Int,
    val pluginName: String,
    val episodeNumber: Int,
    val m3u8Url: String,
    val httpHeaders: Map<String, String>,
    val adBlockerEnabled: Boolean,
    val episode: DownloadEpisodeEntity
)

/**
 * 下载进度回调
 * 对应原版 ProgressCallback
 */
fun interface DownloadProgressCallback {
    fun onProgress(
        recordKey: String,
        episodeNumber: Int,
        episode: DownloadEpisodeEntity,
        speed: Double
    )
}

/**
 * 下载管理器
 *
 * 对应原版 lib/services/download/download_manager.dart。
 *
 * 职责：
 * - 任务队列与并发控制（集数并发 / 分片并发）
 * - m3u8 抓取 → master 选路 → 媒体列表解析 → 嵌套展开 → VOD 校验 →
 *   广告过滤 → KEY 下载 → 断点续传检测 → 分片并发下载 → 本地 m3u8 重建
 * - 直链 mp4 下载回退（支持 Range 断点续传与 416 重试）
 * - 存储空间检查（StatFs，最小 100MB）
 * - 暂停 / 恢复 / 取消 / 删除
 * - 下载速度跟踪
 */
class DownloadManager(
    private val settings: SettingsManager,
    private val downloadDao: DownloadDao,
    private val directoryService: DownloadDirectoryService
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    var onProgress: DownloadProgressCallback? = null

    private val activeTasks = ConcurrentHashMap<String, DownloadTask>()
    private val queue = CopyOnWriteArrayList<DownloadRequest>()
    private val speedTrackers = ConcurrentHashMap<String, SpeedTracker>()
    private val episodeCache = ConcurrentHashMap<String, DownloadEpisodeEntity>()

    @Volatile
    var maxParallelEpisodes: Int = 2

    @Volatile
    var maxParallelSegments: Int = 3

    private val runningCount = AtomicInteger(0)

    init {
        scope.launch {
            maxParallelEpisodes = settings.getInt(
                SettingsManager.Download.PARALLEL_EPISODES, 2
            ).coerceAtLeast(1)
            maxParallelSegments = settings.getInt(
                SettingsManager.Download.PARALLEL_SEGMENTS, 3
            ).coerceAtLeast(1)
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 公开接口
    // ------------------------------------------------------------------

    fun isDownloading(recordKey: String, episodeNumber: Int): Boolean =
        activeTasks.containsKey(taskKey(recordKey, episodeNumber))

    fun getSpeed(recordKey: String, episodeNumber: Int): Double =
        speedTrackers[taskKey(recordKey, episodeNumber)]?.currentSpeed ?: 0.0

    /** 对应原版 enqueue：空闲则立即执行，否则入队 */
    fun enqueue(request: DownloadRequest) {
        val key = taskKey(request.recordKey, request.episodeNumber)
        if (activeTasks.containsKey(key)) return

        val task = DownloadTask(request.recordKey, request.episodeNumber)
        activeTasks[key] = task

        if (runningCount.get() < maxParallelEpisodes) {
            runningCount.incrementAndGet()
            launchEpisode(task, request)
        } else {
            updateEpisode(request.episode.copy(status = DownloadStatus.PENDING))
            queue.add(request)
        }
    }

    /** 对应原版 enqueuePriority：绕过并发限制立即执行 */
    fun enqueuePriority(request: DownloadRequest) {
        val key = taskKey(request.recordKey, request.episodeNumber)

        queue.removeAll { r ->
            r.recordKey == request.recordKey && r.episodeNumber == request.episodeNumber
        }
        activeTasks.remove(key)

        val task = DownloadTask(request.recordKey, request.episodeNumber)
        activeTasks[key] = task
        runningCount.incrementAndGet()
        launchEpisode(task, request)
    }

    /** 对应原版 pause：取消在途请求并标记暂停 */
    fun pause(recordKey: String, episodeNumber: Int) {
        val key = taskKey(recordKey, episodeNumber)
        val task = activeTasks[key] ?: return
        task.isPaused = true
        task.cancelToken.cancel("paused")
    }

    /** 对应原版 resume：重建任务并重新入队/执行 */
    fun resume(request: DownloadRequest) {
        val key = taskKey(request.recordKey, request.episodeNumber)
        activeTasks.remove(key)

        val task = DownloadTask(request.recordKey, request.episodeNumber)
        activeTasks[key] = task

        if (runningCount.get() < maxParallelEpisodes) {
            runningCount.incrementAndGet()
            launchEpisode(task, request)
        } else {
            queue.add(request)
        }
    }

    /** 对应原版 cancel：取消任务并从队列移除，留下 paused 状态 */
    fun cancel(recordKey: String, episodeNumber: Int) {
        val key = taskKey(recordKey, episodeNumber)
        val task = activeTasks[key] ?: return
        // 收尾协程随后会把该集标记为 paused（与原版一致：取消后保持 paused 非 active）
        task.isPaused = true
        task.cancelToken.cancel("cancelled")
        activeTasks.remove(key)
        queue.removeAll { r ->
            r.recordKey == recordKey && r.episodeNumber == episodeNumber
        }
    }

    /** 已完成的本地视频路径（m3u8 或直链 mp4）；未完成/文件缺失返回 null */
    fun getLocalVideoPath(episode: DownloadEpisodeEntity?): String? {
        if (episode == null) return null
        if (episode.status != DownloadStatus.COMPLETED) return null
        if (episode.localM3u8Path.isEmpty()) return null
        val file = File(episode.localM3u8Path)
        if (!file.exists()) return null
        return episode.localM3u8Path
    }

    /** 删除某集的文件（按记录的目录或默认位置） */
    suspend fun deleteEpisodeFiles(
        bangumiId: Int,
        pluginName: String,
        episodeNumber: Int,
        episode: DownloadEpisodeEntity? = null
    ) {
        val dir = File(episodeDirForDeletion(episode, bangumiId, pluginName, episodeNumber))
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    /** 删除整部文件 */
    suspend fun deleteRecordFiles(
        bangumiId: Int,
        pluginName: String,
        record: DownloadRecordEntity? = null
    ) {
        if (record == null) {
            val dir = File(
                File(directoryService.getDefaultDownloadDirectory(), "${bangumiId}_$pluginName")
                    .absolutePath
            )
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            return
        }

        val parentDirs = mutableSetOf<String>()
        val episodes = downloadDao.getAllEpisodes(record.key)
        for (episode in episodes) {
            val episodeDir = File(
                episodeDirForDeletion(episode, bangumiId, pluginName, episode.episodeNumber)
            )
            if (episodeDir.exists()) {
                episodeDir.deleteRecursively()
            }
            parentDirs.add(episodeDir.parent ?: episodeDir.absolutePath)
        }

        for (parentDir in parentDirs) {
            try {
                File(parentDir).delete()
            } catch (_: Exception) {
                // 父目录不存在或仍含其他文件，保留
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private fun taskKey(recordKey: String, episodeNumber: Int): String =
        "${recordKey}_$episodeNumber"

    private fun launchEpisode(task: DownloadTask, request: DownloadRequest) {
        scope.launch {
            runEpisodeDownload(
                task = task,
                bangumiId = request.bangumiId,
                pluginName = request.pluginName,
                m3u8Url = request.m3u8Url,
                httpHeaders = request.httpHeaders,
                adBlockerEnabled = request.adBlockerEnabled
            )
        }
    }

    private fun processQueue() {
        while (runningCount.get() < maxParallelEpisodes && queue.isNotEmpty()) {
            val request = queue.removeAt(0)
            val key = taskKey(request.recordKey, request.episodeNumber)
            val existingTask = activeTasks[key]
            if (existingTask == null ||
                existingTask.isPaused ||
                existingTask.cancelToken.cancelled
            ) {
                activeTasks.remove(key)
                continue
            }

            runningCount.incrementAndGet()
            launchEpisode(existingTask, request)
        }
    }

    /** 集目录：默认 <base>/<bangumiId>_<pluginName>/<episodeNumber> */
    private suspend fun episodeDir(
        downloadBase: String,
        bangumiId: Int,
        pluginName: String,
        episodeNumber: Int
    ): String {
        return File(
            File(downloadBase, "${bangumiId}_$pluginName"),
            episodeNumber.toString()
        ).absolutePath
    }

    /** 准备集目录：优先沿用已记录的目录，校验可写与存储空间，并持久化 */
    private suspend fun prepareEpisodeDir(
        episode: DownloadEpisodeEntity,
        bangumiId: Int,
        pluginName: String,
        episodeNumber: Int
    ): String {
        val storedDir = episode.downloadDirectory.trim()
        val episodeDir = if (storedDir.isNotEmpty()) {
            storedDir
        } else {
            episodeDir(
                directoryService.getDownloadDirectory(),
                bangumiId,
                pluginName,
                episodeNumber
            )
        }
        directoryService.ensureDirectoryWritable(episodeDir)
        checkStorageSpace(episodeDir)
        updateEpisode(episode.copy(downloadDirectory = episodeDir))
        return episodeDir
    }

    /** 删除时定位目录：已记录目录优先，否则默认位置 */
    private suspend fun episodeDirForDeletion(
        episode: DownloadEpisodeEntity?,
        bangumiId: Int,
        pluginName: String,
        episodeNumber: Int
    ): String {
        val storedDir = episode?.downloadDirectory?.trim().orEmpty()
        if (storedDir.isNotEmpty()) return storedDir
        return episodeDir(
            directoryService.getDefaultDownloadDirectory(),
            bangumiId,
            pluginName,
            episodeNumber
        )
    }

    /** 可用空间（StatFs）；无法确定返回 -1 */
    private fun getAvailableStorage(path: String): Long =
        directoryService.getAvailableStorage(path)

    /** 存储空间检查：可用空间未知则跳过，小于需求抛异常 */
    private suspend fun checkStorageSpace(downloadDir: String, requiredBytes: Long = 0) {
        val available = getAvailableStorage(downloadDir)
        if (available == -1L) return
        val required = if (requiredBytes > 0) requiredBytes else MIN_REQUIRED_SPACE
        if (available < required) {
            throw InsufficientStorageException(available, required)
        }
    }

    private fun getStorageErrorMessage(e: IOException): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("No space left") -> "存储空间不足，请清理后重试"
            message.contains("Permission denied") -> "存储权限被拒绝"
            message.contains("Read-only") -> "存储为只读，无法写入"
            else -> "存储错误: $message"
        }
    }

    /** 读取某集最新实体（缓存优先，其次 DAO） */
    private fun loadEpisode(
        task: DownloadTask,
        fallback: DownloadEpisodeEntity
    ): DownloadEpisodeEntity {
        val key = taskKey(task.recordKey, task.episodeNumber)
        return episodeCache[key] ?: downloadDao.getEpisode(
            task.recordKey, task.episodeNumber
        ) ?: fallback
    }

    /**
     * 更新实体：写缓存 + 持久化 + 主线程进度回调。
     * 只有该任务仍是活跃所有者时才回调（对应原版 _notifyProgress 的 identical 检查）。
     */
    private fun updateEpisode(
        episode: DownloadEpisodeEntity,
        task: DownloadTask? = null
    ) {
        val key = taskKey(episode.recordKey, episode.episodeNumber)
        episodeCache[key] = episode
        downloadDao.upsertEpisode(episode)

        if (task != null) {
            if (activeTasks[key] !== task) return
            val speed = speedTrackers[key]?.currentSpeed ?: 0.0
            val snapshot = episode
            mainHandler.post {
                onProgress?.onProgress(task.recordKey, task.episodeNumber, snapshot, speed)
            }
        }
    }

    // ------------------------------------------------------------------
    // 主流程：m3u8 分片下载
    // ------------------------------------------------------------------

    /**
     * 单集下载主流程（对应原版 _runEpisodeDownload）。
     * 任何异常都会在这里被转换为 episode 状态并落库。
     */
    private suspend fun runEpisodeDownload(
        task: DownloadTask,
        bangumiId: Int,
        pluginName: String,
        m3u8Url: String,
        httpHeaders: Map<String, String>,
        adBlockerEnabled: Boolean
    ) {
        val key = taskKey(task.recordKey, task.episodeNumber)
        try {
            val initial = downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: DownloadEpisodeEntity(
                    id = 0,
                    recordKey = task.recordKey,
                    episodeNumber = task.episodeNumber,
                    episodeName = "",
                    road = 0,
                    status = DownloadStatus.PENDING,
                    progressPercent = 0.0,
                    totalSegments = 0,
                    downloadedSegments = 0,
                    localM3u8Path = "",
                    downloadDirectory = "",
                    networkM3u8Url = "",
                    completedAt = null,
                    errorMessage = "",
                    totalBytes = 0,
                    episodePageUrl = "",
                    danmakuData = "",
                    danDanBangumiID = 0
                )
            var episode = loadEpisode(task, initial)

            val episodeDir = prepareEpisodeDir(episode, bangumiId, pluginName, task.episodeNumber)
            episode = loadEpisode(task, initial)

            episode = episode.copy(
                status = DownloadStatus.DOWNLOADING,
                networkM3u8Url = m3u8Url
            )
            updateEpisode(episode, task)

            val m3u8Content = try {
                fetchM3u8(m3u8Url, httpHeaders, task.cancelToken)
            } catch (e: NotM3u8Exception) {
                runDirectFileDownload(
                    task = task,
                    bangumiId = bangumiId,
                    pluginName = pluginName,
                    videoUrl = m3u8Url,
                    httpHeaders = httpHeaders
                )
                return
            }

            val type = M3u8Parser.detectType(m3u8Content)
            var mediaM3u8Content = m3u8Content
            var mediaM3u8Url = m3u8Url

            if (type == M3u8Parser.M3u8Type.MASTER) {
                val master = M3u8Parser.parseMasterPlaylist(m3u8Content, m3u8Url)
                val bestVariant = master.bestVariant
                mediaM3u8Url = bestVariant.uri
                mediaM3u8Content = fetchM3u8(mediaM3u8Url, httpHeaders, task.cancelToken)
            }

            val playlist = M3u8Parser.parseMediaPlaylist(mediaM3u8Content, mediaM3u8Url)

            // 展开嵌套 m3u8 分片（部分源将实际内容嵌套在 m3u8 引用中）
            val resolvedSegments = M3u8Parser.resolveNestedSegments(
                segments = playlist.segments,
                fetcher = { url ->
                    fetchM3u8(url, httpHeaders, task.cancelToken)
                }
            )
            val resolvedPlaylist = M3u8Parser.M3u8MediaPlaylist(
                segments = resolvedSegments,
                targetDuration = playlist.targetDuration,
                isVod = playlist.isVod
            )

            if (!resolvedPlaylist.isVod) {
                episode = loadEpisode(task, initial)
                updateEpisode(
                    episode.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = "不支持下载直播流 (无有效分片)"
                    ),
                    task
                )
                return
            }

            if (resolvedPlaylist.segments.isEmpty()) {
                episode = loadEpisode(task, initial)
                updateEpisode(
                    episode.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = "M3U8 中未找到可下载的分片"
                    ),
                    task
                )
                return
            }

            var segments = resolvedPlaylist.segments
            if (adBlockerEnabled) {
                segments = M3u8AdFilter.filterAds(segments)
            }

            // 下载 KEY 文件（按 uri 去重）
            val keys = M3u8Parser.extractUniqueKeys(
                M3u8Parser.M3u8MediaPlaylist(
                    segments = segments,
                    targetDuration = resolvedPlaylist.targetDuration,
                    isVod = true
                )
            )
            val keyUriToLocal = mutableMapOf<String, String>()
            for (i in keys.indices) {
                val keyFile = "key_$i.key"
                val keyPath = File(episodeDir, keyFile).absolutePath
                downloadFile(keys[i].uri, keyPath, httpHeaders, task.cancelToken)
                keyUriToLocal[keys[i].uri] = keyFile
            }

            episode = loadEpisode(task, initial)
            episode = episode.copy(
                totalSegments = segments.size,
                downloadedSegments = 0
            )
            updateEpisode(episode, task)

            // 清理历史 .tmp 文件
            File(episodeDir).listFiles()?.forEach { f ->
                if (f.name.endsWith(".tmp")) {
                    f.delete()
                }
            }

            // 断点续传检测：统计已存在且非空的 seg_xxxxx.ts
            val existingSegments = mutableSetOf<Int>()
            var existingBytes = 0L
            for (i in segments.indices) {
                val segFile = File(episodeDir, "seg_${i.toString().padStart(5, '0')}.ts")
                if (segFile.exists()) {
                    val len = segFile.length()
                    if (len <= 0) continue
                    existingSegments.add(i)
                    episode = loadEpisode(task, initial).copy(
                        downloadedSegments = (episode.downloadedSegments + 1)
                    )
                    existingBytes += len
                }
            }
            episode = loadEpisode(task, initial).copy(
                totalBytes = existingBytes.toInt(),
                progressPercent = if (episode.totalSegments > 0) {
                    episode.downloadedSegments.toDouble() / episode.totalSegments
                } else 0.0
            )
            updateEpisode(episode, task)

            val pendingIndices = segments.indices.filter { it !in existingSegments }
            val sessionBytes = AtomicLong(0L)
            val completedCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val semaphore = Semaphore(maxParallelSegments)
            speedTrackers[key] = SpeedTracker()

            if (pendingIndices.isNotEmpty()) {
                coroutineScope {
                    val jobs = mutableListOf<kotlinx.coroutines.Job>()
                    for (idx in pendingIndices) {
                        if (task.isPaused || task.cancelToken.cancelled) break
                        jobs.add(
                            launch {
                                semaphore.withPermit {
                                    if (task.isPaused || task.cancelToken.cancelled) return@withPermit
                                    try {
                                        val bytes = downloadSegmentWithRetry(
                                            segments[idx].uri,
                                            File(episodeDir, "seg_${idx.toString().padStart(5, '0')}.ts").absolutePath,
                                            httpHeaders,
                                            task.cancelToken
                                        )
                                        sessionBytes.addAndGet(bytes)
                                        completedCount.incrementAndGet()
                                        val cur = loadEpisode(task, initial)
                                        val updated = cur.copy(
                                            downloadedSegments = cur.downloadedSegments + 1,
                                            totalBytes = (existingBytes + sessionBytes.get()).toInt(),
                                            progressPercent = cur.totalSegments.takeIf { it > 0 }
                                                ?.let { (cur.downloadedSegments + 1).toDouble() / it }
                                                ?: 0.0
                                        )
                                        speedTrackers[key]?.update(sessionBytes.get())
                                        updateEpisode(updated, task)
                                    } catch (e: Exception) {
                                        if (task.cancelToken.cancelled || task.isPaused) {
                                            return@withPermit
                                        }
                                        failedCount.incrementAndGet()
                                    }
                                }
                            }
                        )
                    }
                    jobs.forEach { it.join() }
                }
            }

            if (task.isPaused || task.cancelToken.cancelled) {
                episode = loadEpisode(task, initial)
                if (task.isPaused) {
                    episode = episode.copy(status = DownloadStatus.PAUSED)
                }
                updateEpisode(episode, task)
                return
            }

            if (failedCount.get() > 0) {
                episode = loadEpisode(task, initial)
                updateEpisode(
                    episode.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = "${failedCount.get()} 个分片下载失败"
                    ),
                    task
                )
                return
            }

            // 本地 m3u8 重建
            val targetDuration = if (adBlockerEnabled) {
                M3u8AdFilter.calculateTargetDuration(segments)
            } else {
                resolvedPlaylist.targetDuration
            }
            val localM3u8 = M3u8Parser.buildLocalM3u8(
                segments,
                targetDuration = targetDuration,
                keyUriToLocal = keyUriToLocal
            )
            val m3u8Path = File(episodeDir, "playlist.m3u8").absolutePath
            File(m3u8Path).writeText(localM3u8)

            val finalVideoBytes = calculateM3u8VideoBytes(episodeDir)

            episode = loadEpisode(task, initial)
            updateEpisode(
                episode.copy(
                    status = DownloadStatus.COMPLETED,
                    localM3u8Path = m3u8Path,
                    progressPercent = 1.0,
                    completedAt = System.currentTimeMillis(),
                    totalBytes = if (finalVideoBytes > 0) finalVideoBytes.toInt()
                        else (existingBytes + sessionBytes.get()).toInt(),
                    errorMessage = ""
                ),
                task
            )
        } catch (e: InsufficientStorageException) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            updateEpisode(
                episode.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "存储空间不足 (可用: ${formatBytes(e.availableBytes)})"
                ),
                task
            )
        } catch (e: IOException) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            updateEpisode(
                episode.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = getStorageErrorMessage(e)
                ),
                task
            )
        } catch (e: NetworkException) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            if (e.type == NetworkExceptionType.CANCEL) {
                if (task.isPaused) {
                    updateEpisode(episode.copy(status = DownloadStatus.PAUSED), task)
                }
            } else {
                updateEpisode(
                    episode.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message ?: "网络错误"
                    ),
                    task
                )
            }
        } catch (e: Exception) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            updateEpisode(
                episode.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "下载失败"
                ),
                task
            )
        } finally {
            onTaskComplete(task)
        }
    }

    private fun fallbackEpisode(task: DownloadTask): DownloadEpisodeEntity =
        DownloadEpisodeEntity(
            id = 0,
            recordKey = task.recordKey,
            episodeNumber = task.episodeNumber,
            episodeName = "",
            road = 0,
            status = DownloadStatus.FAILED,
            progressPercent = 0.0,
            totalSegments = 0,
            downloadedSegments = 0,
            localM3u8Path = "",
            downloadDirectory = "",
            networkM3u8Url = "",
            completedAt = null,
            errorMessage = "",
            totalBytes = 0,
            episodePageUrl = "",
            danmakuData = "",
            danDanBangumiID = 0
        )

    /** 统计集目录内 seg_xxxxx.ts 文件总字节数 */
    private fun calculateM3u8VideoBytes(episodeDir: String): Long {
        val dir = File(episodeDir)
        if (!dir.exists()) return 0L
        val pattern = Regex("^seg_\\d+\\.ts$")
        var total = 0L
        dir.listFiles()?.forEach { f ->
            if (f.isFile && pattern.matches(f.name)) {
                try {
                    total += f.length()
                } catch (_: Exception) {
                }
            }
        }
        return total
    }

    // ------------------------------------------------------------------
    // 直链 mp4 下载回退
    // ------------------------------------------------------------------

    /** 对应原版 _runDirectFileDownload：支持 Range 断点续传与 416 重试 */
    private suspend fun runDirectFileDownload(
        task: DownloadTask,
        bangumiId: Int,
        pluginName: String,
        videoUrl: String,
        httpHeaders: Map<String, String>
    ) {
        val key = taskKey(task.recordKey, task.episodeNumber)
        try {
            val initial = downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task)
            var episode = loadEpisode(task, initial)
            val episodeDir = prepareEpisodeDir(episode, bangumiId, pluginName, task.episodeNumber)
            episode = loadEpisode(task, initial)

            val filePath = File(episodeDir, "video.mp4").absolutePath
            val tmpPath = "$filePath.tmp"

            var existingBytes = 0L
            val tmpFile = File(tmpPath)
            if (tmpFile.exists()) {
                existingBytes = tmpFile.length()
            }

            episode = episode.copy(
                totalSegments = 1,
                downloadedSegments = 0
            )
            updateEpisode(episode, task)

            val requestHeaders = httpHeaders.toMutableMap()
            val useRange = existingBytes > 0
            if (useRange) {
                requestHeaders["Range"] = "bytes=$existingBytes-"
            }

            // getStream 返回原始 Response（状态码检查由调用方负责）；
            // 416 需要在读取响应体之前处理：删除临时文件从头重下。
            var response = try {
                DownloadHttpClient.getStream(
                    videoUrl,
                    headers = requestHeaders,
                    cancelToken = task.cancelToken,
                    receiveTimeoutMillis = 30 * 60 * 1000L
                )
            } catch (e: NetworkException) {
                if (e.statusCode == 416 && useRange) {
                    // 416 Range Not Satisfiable：删除临时文件从头重下
                    tmpFile.delete()
                    existingBytes = 0
                    requestHeaders.remove("Range")
                    DownloadHttpClient.getStream(
                        videoUrl,
                        headers = requestHeaders,
                        cancelToken = task.cancelToken,
                        receiveTimeoutMillis = 30 * 60 * 1000L
                    )
                } else {
                    throw e
                }
            }

            // 状态码检查：非 2xx（含 416 未重试场景）直接失败
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                if (code == 416 && useRange) {
                    // getStream 网络层不抛 416，这里兜底重试一次
                    tmpFile.delete()
                    existingBytes = 0
                    requestHeaders.remove("Range")
                    response = DownloadHttpClient.getStream(
                        videoUrl,
                        headers = requestHeaders,
                        cancelToken = task.cancelToken,
                        receiveTimeoutMillis = 30 * 60 * 1000L
                    )
                    if (!response.isSuccessful) {
                        throw NetworkException(
                            NetworkExceptionType.BAD_RESPONSE,
                            "HTTP ${response.code}",
                            statusCode = response.code
                        )
                    }
                } else {
                    throw NetworkException(
                        NetworkExceptionType.BAD_RESPONSE,
                        "HTTP $code",
                        statusCode = code
                    )
                }
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw NetworkException(
                        NetworkExceptionType.BAD_RESPONSE,
                        "HTTP ${resp.code}",
                        statusCode = resp.code
                    )
                }

                val contentRange = resp.header("Content-Range")
                val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                val totalSize = if (contentRange != null) {
                    Regex("/(\\d+)").find(contentRange)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                } else {
                    existingBytes + contentLength
                }

                val body = resp.body
                    ?: throw NetworkException(NetworkExceptionType.BAD_RESPONSE, "空响应体")

                tmpFile.parentFile?.mkdirs()
                val raf = tmpFile.outputStream()
                var received = existingBytes

                try {
                    body.byteStream().use { input ->
                        raf.use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (task.isPaused || task.cancelToken.cancelled) break
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                received += read
                                val cur = loadEpisode(task, initial)
                                val updated = cur.copy(
                                    totalBytes = received.toInt(),
                                    progressPercent = if (totalSize > 0) {
                                        received.toDouble() / totalSize
                                    } else 0.0
                                )
                                speedTrackers[key]?.update(received)
                                updateEpisode(updated, task)
                            }
                        }
                    }
                } catch (e: IOException) {
                    // 读流中断：由 task 状态决定是暂停还是失败
                    throw e
                }

                if (task.isPaused || task.cancelToken.cancelled) {
                    episode = loadEpisode(task, initial)
                    if (task.isPaused) {
                        episode = episode.copy(status = DownloadStatus.PAUSED)
                    }
                    updateEpisode(episode, task)
                    return@use
                }

                // 临时文件改名为正式文件
                tmpFile.renameTo(File(filePath))

                episode = loadEpisode(task, initial)
                updateEpisode(
                    episode.copy(
                        status = DownloadStatus.COMPLETED,
                        localM3u8Path = filePath,
                        downloadedSegments = 1,
                        progressPercent = 1.0,
                        completedAt = System.currentTimeMillis(),
                        totalBytes = File(filePath).length().toInt(),
                        errorMessage = ""
                    ),
                    task
                )
            }
        } catch (e: InsufficientStorageException) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            updateEpisode(
                episode.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "存储空间不足 (可用: ${formatBytes(e.availableBytes)})"
                ),
                task
            )
        } catch (e: IOException) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            if (task.isPaused) {
                updateEpisode(episode.copy(status = DownloadStatus.PAUSED), task)
            } else {
                updateEpisode(
                    episode.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = getStorageErrorMessage(e)
                    ),
                    task
                )
            }
        } catch (e: NetworkException) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            if (e.type == NetworkExceptionType.CANCEL) {
                if (task.isPaused) {
                    updateEpisode(episode.copy(status = DownloadStatus.PAUSED), task)
                }
            } else {
                updateEpisode(
                    episode.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message ?: "网络错误"
                    ),
                    task
                )
            }
        } catch (e: Exception) {
            val episode = loadEpisode(task, downloadDao.getEpisode(task.recordKey, task.episodeNumber)
                ?: fallbackEpisode(task))
            updateEpisode(
                episode.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "下载失败"
                ),
                task
            )
        }
    }

    // ------------------------------------------------------------------
    // 网络辅助
    // ------------------------------------------------------------------

    /** 抓取 m3u8 文本；非 m3u8 抛 [NotM3u8Exception] */
    private suspend fun fetchM3u8(
        url: String,
        headers: Map<String, String>,
        cancelToken: DownloadHttpClient.CancelToken
    ): String {
        if (cancelToken.cancelled) {
            throw NetworkException(
                NetworkExceptionType.CANCEL,
                "请求已被取消，请重新请求"
            )
        }

        val content = try {
            DownloadHttpClient.getPlain(
                url = url,
                headers = headers,
                cancelToken = cancelToken,
                receiveTimeoutMillis = 15_000,
                maxBytes = 2 * 1024 * 1024,
                onReceiveProgress = { received, _ ->
                    if (cancelToken.cancelled) {
                        // 通过抛出异常中断读取（getPlain 内部以 maxBytes 兜底）
                    }
                }
            )
        } catch (e: NetworkException) {
            if (cancelToken.cancelled) throw e
            if (e.type == NetworkExceptionType.CANCEL) {
                throw NotM3u8Exception("响应过大，非 M3U8 播放列表")
            }
            throw e
        }

        val trimmed = content.trimStart()
        if (!trimmed.startsWith("#EXTM3U")) {
            throw NotM3u8Exception("URL 不是 M3U8 播放列表")
        }
        return content
    }

    /** 下载单个文件（KEY） */
    private suspend fun downloadFile(
        url: String,
        savePath: String,
        headers: Map<String, String>,
        cancelToken: DownloadHttpClient.CancelToken
    ) {
        DownloadHttpClient.download(
            url,
            savePath,
            headers = headers,
            cancelToken = cancelToken
        )
    }

    /**
     * 分片下载 + 重试（1s/3s/9s 退避，最多 3 次）。
     * 成功后将 .tmp 重命名为正式分片并返回字节数。
     */
    private suspend fun downloadSegmentWithRetry(
        url: String,
        savePath: String,
        headers: Map<String, String>,
        cancelToken: DownloadHttpClient.CancelToken,
        maxRetries: Int = 3
    ): Long {
        val tmpPath = "$savePath.tmp"
        var retryCount = 0
        while (true) {
            try {
                DownloadHttpClient.download(
                    url,
                    tmpPath,
                    headers = headers,
                    cancelToken = cancelToken
                )
                File(tmpPath).renameTo(File(savePath))
                return File(savePath).length()
            } catch (e: Exception) {
                try {
                    val tmpFile = File(tmpPath)
                    if (tmpFile.exists()) tmpFile.delete()
                } catch (_: Exception) {
                }
                if (cancelToken.cancelled) throw e
                retryCount++
                if (retryCount >= maxRetries) throw e
                delay(listOf(1000L, 3000L, 9000L)[retryCount - 1])
            }
        }
    }

    /** 任务收尾：释放并发槽位并处理队列（对应原版 _onTaskComplete） */
    private fun onTaskComplete(task: DownloadTask) {
        val key = taskKey(task.recordKey, task.episodeNumber)
        // 若任务已被取消/替换，不清理新任务
        if (activeTasks[key] == null || activeTasks[key] === task) {
            activeTasks.remove(key)
            speedTrackers.remove(key)
        }
        runningCount.decrementAndGet()
        processQueue()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format("%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        }
        if (bytes >= 1024 * 1024) {
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        }
        if (bytes >= 1024) {
            return String.format("%.1f KB", bytes / 1024.0)
        }
        return "$bytes B"
    }

    companion object {
        private const val MIN_REQUIRED_SPACE = 100L * 1024 * 1024 // 100MB
    }
}

/**
 * 速度跟踪器
 * 对应原版 _SpeedTracker
 */
private class SpeedTracker {
    private var lastBytes = 0L
    private var lastTime = System.currentTimeMillis()
    var currentSpeed = 0.0 // bytes/sec
        private set

    fun update(totalBytes: Long) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTime
        if (elapsed > 500) {
            val bytesDownloaded = totalBytes - lastBytes
            currentSpeed = bytesDownloaded / (elapsed / 1000.0)
            lastBytes = totalBytes
            lastTime = now
        }
    }

    fun reset() {
        lastBytes = 0
        lastTime = System.currentTimeMillis()
        currentSpeed = 0.0
    }
}