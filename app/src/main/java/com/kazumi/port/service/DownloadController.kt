package com.kazumi.port.service

import android.content.Context
import android.util.Log
import com.kazumi.port.data.db.DownloadEpisodeEntity
import com.kazumi.port.data.db.DownloadStatus
import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.model.Plugin
import com.kazumi.port.data.repository.DanmakuApi
import com.kazumi.port.data.repository.DownloadEpisode
import com.kazumi.port.data.repository.DownloadRecord
import com.kazumi.port.data.repository.DownloadRepository
import com.kazumi.port.data.repository.SettingsManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 下载控制器
 *
 * 对应原版 lib/pages/download/download_controller.dart。
 *
 * 职责：
 * - 解析租约队列：把待解析的集数按并发度分发给 [VideoSourceResolverPool]
 * - 解析成功后置 downloading 并调用 [DownloadManager.enqueue] 入队下载
 * - 转发 [DownloadManager] 进度回调 → 仓库更新 + UI 刷新 + 前台服务通知
 * - 可选下载弹幕缓存（danmaku.json）
 * - pause / pauseAll / retry / priority / delete / resumeAll 动作编排
 */
class DownloadController(
    context: Context,
    private val repository: DownloadRepository,
    private val downloadManager: DownloadManager,
    private val plugins: List<Plugin>
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolverPool = VideoSourceResolverPool(appContext)

    /** 复用 KazumiApp 全局单例 settings（避免 new SettingsManager 造成 DataStore 并发冲突） */
    private val settings = com.kazumi.port.KazumiApp.get().settings

    /** 待解析队列（原版 _resolveQueue） */
    private val resolveQueue = CopyOnWriteArrayList<ResolveRequest>()

    /** 活跃解析租约（key → lease，原版 _activeResolveLeases） */
    private val activeResolveLeases = ConcurrentHashMap<String, VideoSourceResolverLease>()

    /** 各任务速度（key: pluginName_bangumiId_episodeNumber → bytes/s） */
    private val speeds = ConcurrentHashMap<String, Double>()

    @Volatile
    private var lastUiUpdateTime = System.currentTimeMillis()

    @Volatile
    private var backgroundServiceStarted = false

    /** 通知更新去抖（对应原版 _LatestAsyncRunner） */
    private var notificationJob: Job? = null

    @Volatile
    private var notificationNeedsRun = false

    // ------------------------------------------------------------------
    // 可观察数据（对应原版 records / recordKeys / recordByKey）
    // ------------------------------------------------------------------

    @Volatile
    var records: List<DownloadRecord> = emptyList()
        private set

    @Volatile
    var recordKeys: List<String> = emptyList()
        private set

    private val recordByKey = ConcurrentHashMap<String, DownloadRecord>()

    fun getRecordSnapshot(recordKey: String): DownloadRecord? = recordByKey[recordKey]

    suspend fun refreshRecords() {
        replaceRecords(repository.getAllRecords())
    }

    private suspend fun replaceRecords(nextRecords: List<DownloadRecord>) {
        records = nextRecords
        recordKeys = nextRecords.map { it.key }
        recordByKey.clear()
        nextRecords.forEach { recordByKey[it.key] = it }
    }

    private suspend fun refreshRecord(recordKey: String) {
        val record = repository.getRecord(recordKey)
        if (record == null || record.episodes.isEmpty()) {
            recordByKey.remove(recordKey)
            records = records.filterNot { it.key == recordKey }
            recordKeys = records.map { it.key }
            return
        }
        recordByKey[recordKey] = record
        if (recordKeys.none { it == recordKey }) {
            recordKeys = recordKeys + recordKey
        }
        if (records.none { it.key == recordKey }) {
            records = records + record
        } else {
            records = records.map { if (it.key == recordKey) record else it }
        }
    }

    // ------------------------------------------------------------------
    // 初始化（对应原版 init()）
    // ------------------------------------------------------------------

    suspend fun init() {
        replaceRecords(repository.getAllRecords())

        // 启动时把 downloading/resolving/pending 重置为 paused（内存队列重启后丢失）
        var resetIncomplete = false
        for (record in records) {
            var changed = false
            for (episode in record.episodes.values) {
                if (episode.status == DownloadStatus.DOWNLOADING ||
                    episode.status == DownloadStatus.RESOLVING ||
                    episode.status == DownloadStatus.PENDING
                ) {
                    changed = true
                }
            }
            if (changed) {
                resetIncomplete = true
                val updated = record.copy(
                    episodes = record.episodes.mapValues { (_, ep) ->
                        if (ep.status == DownloadStatus.DOWNLOADING ||
                            ep.status == DownloadStatus.RESOLVING ||
                            ep.status == DownloadStatus.PENDING
                        ) ep.copy(status = DownloadStatus.PAUSED) else ep
                    }
                )
                repository.putRecord(updated)
            }
        }
        if (resetIncomplete) {
            replaceRecords(repository.getAllRecords())
        }

        // 迁移旧弹幕数据到独立文件（清空实体中的 danmakuData）
        migrateDanmakuDataToFiles()

        downloadManager.onProgress = { recordKey, episodeNumber, entity, speed ->
            onDownloadProgress(recordKey, episodeNumber, entity, speed)
        }
    }

    private suspend fun migrateDanmakuDataToFiles() {
        var migrated = 0
        for (record in repository.getAllRecords()) {
            var recordChanged = false
            for (episode in record.episodes.values) {
                if (episode.danmakuData.isEmpty() || episode.downloadDirectory.isEmpty()) {
                    continue
                }
                try {
                    val file = File(danmakuFilePath(episode.downloadDirectory))
                    file.writeText(
                        "{\"danDanBangumiID\":${episode.danDanBangumiID}," +
                            "\"danmakus\":${episode.danmakuData}}"
                    )
                    repository.updateEpisode(
                        record.key,
                        episode.episodeNumber,
                        episode.copy(danmakuData = "")
                    )
                    recordChanged = true
                    migrated++
                } catch (e: Exception) {
                    Log.w(TAG, "danmaku migration failed: ${e.message}")
                }
            }
            if (recordChanged) {
                replaceRecords(repository.getAllRecords())
            }
        }
        if (migrated > 0) {
            Log.i(TAG, "migrated danmaku data for $migrated episodes")
        }
    }

    // ------------------------------------------------------------------
    // 进度回调（对应原版 _onDownloadProgress）
    // ------------------------------------------------------------------

    private fun onDownloadProgress(
        recordKey: String,
        episodeNumber: Int,
        entity: DownloadEpisodeEntity,
        speed: Double
    ) {
        scope.launch {
            val record = repository.getRecord(recordKey) ?: return@launch
            if (!record.episodes.containsKey(episodeNumber)) return@launch

            val episode = entity.toDomain()
            repository.updateEpisode(recordKey, episodeNumber, episode)

            val speedKey = "${recordKey}_$episodeNumber"
            val isFinalState = episode.status == DownloadStatus.COMPLETED ||
                episode.status == DownloadStatus.FAILED ||
                episode.status == DownloadStatus.PAUSED
            if (isFinalState) {
                speeds.remove(speedKey)
            } else {
                speeds[speedKey] = speed
            }

            val now = System.currentTimeMillis()
            if (isFinalState || now - lastUiUpdateTime >= UI_UPDATE_INTERVAL_MS) {
                lastUiUpdateTime = now
                refreshRecord(recordKey)
                queueBackgroundNotificationUpdate()
            }
        }
    }

    private fun queueBackgroundNotificationUpdate() {
        notificationNeedsRun = true
        if (notificationJob?.isActive == true) return
        notificationJob = scope.launch {
            while (notificationNeedsRun) {
                notificationNeedsRun = false
                try {
                    updateBackgroundNotification()
                } catch (e: Exception) {
                    Log.w(TAG, "background notification update failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun updateBackgroundNotification() {
        if (!BackgroundDownloadService.isRunning) return

        val stats = getDownloadStats()
        if (!stats.hasWork) {
            BackgroundDownloadService.stop(appContext)
            return
        }

        var totalSpeed = 0.0
        for (key in stats.activeKeys) {
            totalSpeed += speeds[key] ?: 0.0
        }

        BackgroundDownloadService.updateProgress(
            appContext,
            activeCount = stats.activeCount,
            totalCount = stats.totalCount,
            overallProgress = stats.overallProgress,
            speedText = formatSpeed(totalSpeed)
        )
    }

    private suspend fun startBackgroundServiceIfNeeded() {
        if (backgroundServiceStarted) return
        BackgroundDownloadService.start(appContext)
        backgroundServiceStarted = true
    }

    private data class DownloadStats(
        val activeCount: Int,
        val pendingCount: Int,
        val totalCount: Int,
        val overallProgress: Double,
        val activeKeys: Set<String>
    ) {
        val hasWork: Boolean get() = activeCount > 0 || pendingCount > 0
    }

    private suspend fun getDownloadStats(): DownloadStats {
        var activeCount = 0
        var pendingCount = 0
        var totalCount = 0
        var totalProgress = 0.0
        val activeKeys = mutableSetOf<String>()

        for (record in repository.getAllRecords()) {
            for (episode in record.episodes.values) {
                when (episode.status) {
                    DownloadStatus.DOWNLOADING -> {
                        activeCount++
                        totalCount++
                        totalProgress += episode.progressPercent
                        activeKeys.add("${record.key}_${episode.episodeNumber}")
                    }
                    DownloadStatus.RESOLVING, DownloadStatus.PENDING -> {
                        pendingCount++
                        totalCount++
                    }
                    else -> Unit
                }
            }
        }
        return DownloadStats(
            activeCount = activeCount,
            pendingCount = pendingCount,
            totalCount = totalCount,
            overallProgress = if (totalCount > 0) totalProgress / totalCount else 0.0,
            activeKeys = activeKeys
        )
    }

    fun getSpeed(bangumiId: Int, pluginName: String, episodeNumber: Int): Double =
        speeds["${pluginName}_${bangumiId}_$episodeNumber"] ?: 0.0

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    suspend fun getRecord(bangumiId: Int, pluginName: String): DownloadRecord? =
        repository.getRecordByBangumiId(bangumiId, pluginName)

    suspend fun getEpisode(bangumiId: Int, pluginName: String, episodeNumber: Int): DownloadEpisode? =
        repository.getEpisode(bangumiId, pluginName, episodeNumber)

    suspend fun getEpisodeByUrl(bangumiId: Int, pluginName: String, episodePageUrl: String): DownloadEpisode? =
        repository.getEpisodeByUrl(bangumiId, pluginName, episodePageUrl)

    suspend fun getLocalVideoPath(bangumiId: Int, pluginName: String, episodeNumber: Int): String? {
        val entity = repository.getEpisodeEntity(bangumiId, pluginName, episodeNumber)
        return downloadManager.getLocalVideoPath(entity)
    }

    suspend fun getCompletedEpisodes(bangumiId: Int, pluginName: String): List<DownloadEpisode> =
        repository.getCompletedEpisodes(bangumiId, pluginName)

    fun completedCount(record: DownloadRecord): Int =
        record.episodes.values.count { it.status == DownloadStatus.COMPLETED }

    // ------------------------------------------------------------------
    // 开始下载（对应原版 startDownload）
    // ------------------------------------------------------------------

    suspend fun startDownload(
        bangumiId: Int,
        bangumiName: String,
        bangumiCover: String,
        pluginName: String,
        episodeNumber: Int,
        episodeName: String,
        road: Int,
        episodePageUrl: String
    ) {
        val recordKey = "${pluginName}_$bangumiId"
        val record = repository.getRecord(recordKey)
            ?: DownloadRecord(
                key = recordKey,
                bangumiId = bangumiId,
                bangumiName = bangumiName,
                bangumiCover = bangumiCover,
                pluginName = pluginName,
                createdAt = System.currentTimeMillis(),
                episodes = emptyMap()
            )

        // 同一集数页面 URL 已存在则跳过
        if (episodePageUrl.isNotEmpty()) {
            for (existing in record.episodes.values) {
                if (existing.episodePageUrl == episodePageUrl) {
                    Log.i(TAG, "episode URL already exists, skipping")
                    return
                }
            }
        }

        val episode = DownloadEpisode(
            episodeNumber = episodeNumber,
            episodeName = episodeName,
            road = road,
            status = DownloadStatus.RESOLVING,
            progressPercent = 0.0,
            totalSegments = 0,
            downloadedSegments = 0,
            localM3u8Path = "",
            downloadDirectory = "",
            networkM3u8Url = "",
            completedAt = null,
            errorMessage = "",
            totalBytes = 0,
            episodePageUrl = episodePageUrl
        )

        repository.putRecord(
            record.copy(episodes = record.episodes + (episodeNumber to episode))
        )
        refreshRecords()

        resolveQueue.add(
            ResolveRequest(
                recordKey = recordKey,
                bangumiId = bangumiId,
                pluginName = pluginName,
                episodeNumber = episodeNumber,
                episodePageUrl = episodePageUrl
            )
        )
        processResolveQueue()
    }

    // ------------------------------------------------------------------
    // 解析队列（对应原版 _processResolveQueue / _resolveAndEnqueue）
    // ------------------------------------------------------------------

    private suspend fun processResolveQueue() {
        if (resolveQueue.isEmpty()) return

        resolverPool.resize(
            settings.getInt(SettingsManager.Download.PARALLEL_EPISODES, 2)
        )

        var index = 0
        while (index < resolveQueue.size) {
            val request = resolveQueue[index]
            val key = resolveTaskKey(request.recordKey, request.episodeNumber)

            if (activeResolveLeases.containsKey(key)) {
                index++
                continue
            }

            val lease = resolverPool.tryAcquire(key) ?: return
            resolveQueue.remove(request)
            activeResolveLeases[key] = lease
            scope.launch {
                resolveAndEnqueue(request, lease)
            }
        }
    }

    private suspend fun resolveAndEnqueue(
        request: ResolveRequest,
        lease: VideoSourceResolverLease
    ) {
        val key = resolveTaskKey(request.recordKey, request.episodeNumber)
        var wasCancelled = false

        try {
            val plugin = findPlugin(request.pluginName)
            if (plugin == null) {
                failEpisode(request.recordKey, request.episodeNumber, "找不到插件 ${request.pluginName}")
                return
            }

            val record = repository.getRecord(request.recordKey) ?: return
            val episode = record.episodes[request.episodeNumber] ?: return
            if (episode.status != DownloadStatus.RESOLVING) return

            val fullUrl = plugin.buildFullUrl(request.episodePageUrl)
            Log.i(TAG, "resolving video URL for episode ${request.episodeNumber} from $fullUrl")

            var m3u8Url: String? = null
            try {
                if (lease.isCancelledFlag) throw VideoSourceCancelledException()
                val source = lease.resolve(
                    episodeUrl = fullUrl,
                    useLegacyParser = plugin.useLegacyParser,
                    timeoutMillis = 30_000
                )
                m3u8Url = source.url
            } catch (e: VideoSourceTimeoutException) {
                if (lease.isCancelledFlag) {
                    wasCancelled = true
                } else {
                    Log.w(TAG, "WebView resolution timed out")
                }
            } catch (e: VideoSourceCancelledException) {
                wasCancelled = true
                Log.i(TAG, "WebView resolution cancelled")
            } catch (e: Exception) {
                if (lease.isCancelledFlag) {
                    wasCancelled = true
                } else {
                    lease.retire()
                    Log.w(TAG, "WebView resolution failed: ${e.message}")
                }
            }

            if (wasCancelled || lease.isCancelledFlag) return

            if (m3u8Url.isNullOrEmpty()) {
                failEpisode(request.recordKey, request.episodeNumber, "解析视频源超时")
                return
            }

            Log.i(TAG, "resolved M3U8 URL for episode ${request.episodeNumber}: $m3u8Url")

            val freshRecord = repository.getRecord(request.recordKey) ?: return
            val freshEpisode = freshRecord.episodes[request.episodeNumber] ?: return
            if (freshEpisode.status != DownloadStatus.RESOLVING) return

            val updatedEpisode = freshEpisode.copy(
                networkM3u8Url = m3u8Url,
                status = DownloadStatus.DOWNLOADING
            )
            repository.updateEpisode(request.recordKey, request.episodeNumber, updatedEpisode)
            refreshRecord(request.recordKey)

            startBackgroundServiceIfNeeded()

            val httpHeaders = plugin.buildHttpHeaders()
            val adBlockerEnabled = repository.getForceAdBlocker() || plugin.adBlocker

            downloadManager.enqueue(
                DownloadRequest(
                    recordKey = request.recordKey,
                    bangumiId = request.bangumiId,
                    pluginName = request.pluginName,
                    episodeNumber = request.episodeNumber,
                    m3u8Url = m3u8Url,
                    httpHeaders = httpHeaders,
                    adBlockerEnabled = adBlockerEnabled,
                    episode = updatedEpisode.toEntity(request.recordKey)
                )
            )

            if (settings.getBoolean(SettingsManager.Download.DANMAKU, true)) {
                fetchAndCacheDanmakuAsync(
                    request.recordKey,
                    request.bangumiId,
                    request.episodeNumber
                )
            }
        } finally {
            releaseResolveLease(key, lease)
        }
    }

    private fun releaseResolveLease(key: String, lease: VideoSourceResolverLease) {
        if (activeResolveLeases[key] === lease) {
            activeResolveLeases.remove(key)
        }
        resolverPool.release(lease)
        scope.launch { processResolveQueue() }
    }

    private fun resolveTaskKey(recordKey: String, episodeNumber: Int): String =
        "${recordKey.length}:$recordKey:$episodeNumber"

    private fun resolveTaskKeyBelongsToRecord(key: String, recordKey: String): Boolean =
        key.startsWith("${recordKey.length}:$recordKey:")

    private fun cancelResolve(recordKey: String, episodeNumber: Int) {
        val key = resolveTaskKey(recordKey, episodeNumber)
        resolveQueue.removeAll { r ->
            r.recordKey == recordKey && r.episodeNumber == episodeNumber
        }
        activeResolveLeases.remove(key)?.cancel()
        resolverPool.cancel(key)
    }

    private fun cancelResolveRecord(recordKey: String) {
        resolveQueue.removeAll { it.recordKey == recordKey }
        val activeKeys = activeResolveLeases.keys
            .filter { resolveTaskKeyBelongsToRecord(it, recordKey) }
            .toList()
        for (key in activeKeys) {
            activeResolveLeases.remove(key)?.cancel()
            resolverPool.cancel(key)
        }
    }

    private fun cancelAllResolves() {
        resolveQueue.clear()
        activeResolveLeases.values.toList().forEach { it.cancel() }
        activeResolveLeases.clear()
        resolverPool.cancelAll()
    }

    private fun findPlugin(pluginName: String): Plugin? =
        plugins.firstOrNull { it.name == pluginName }

    private suspend fun failEpisode(recordKey: String, episodeNumber: Int, message: String) {
        val record = repository.getRecord(recordKey) ?: return
        val episode = record.episodes[episodeNumber] ?: return
        repository.updateEpisode(
            recordKey,
            episodeNumber,
            episode.copy(status = DownloadStatus.FAILED, errorMessage = message)
        )
        refreshRecord(recordKey)
        Log.w(TAG, "episode $episodeNumber failed: $message")
    }

    // ------------------------------------------------------------------
    // 动作（对应原版 pause / pauseAll / retry / priority / delete / resumeAll）
    // ------------------------------------------------------------------

    suspend fun pauseDownload(bangumiId: Int, pluginName: String, episodeNumber: Int) {
        val recordKey = "${pluginName}_$bangumiId"
        downloadManager.pause(recordKey, episodeNumber)
        cancelResolve(recordKey, episodeNumber)

        val record = repository.getRecord(recordKey)
        if (record != null) {
            val episode = record.episodes[episodeNumber]
            if (episode != null) {
                repository.updateEpisode(
                    recordKey,
                    episodeNumber,
                    episode.copy(status = DownloadStatus.PAUSED)
                )
                refreshRecord(recordKey)
                queueBackgroundNotificationUpdate()
            }
        }
    }

    suspend fun pauseAllDownloads() {
        Log.i(TAG, "pausing all downloads")
        cancelAllResolves()

        for (record in records) {
            for (episode in record.episodes.values) {
                if (episode.status == DownloadStatus.DOWNLOADING ||
                    episode.status == DownloadStatus.RESOLVING ||
                    episode.status == DownloadStatus.PENDING
                ) {
                    val recordKey = "${record.pluginName}_${record.bangumiId}"
                    downloadManager.pause(recordKey, episode.episodeNumber)
                    repository.updateEpisode(
                        recordKey,
                        episode.episodeNumber,
                        episode.copy(status = DownloadStatus.PAUSED)
                    )
                }
            }
        }

        refreshRecords()
        BackgroundDownloadService.stop(appContext)
    }

    suspend fun retryDownload(bangumiId: Int, pluginName: String, episodeNumber: Int) {
        val recordKey = "${pluginName}_$bangumiId"
        val record = repository.getRecord(recordKey) ?: return
        val episode = record.episodes[episodeNumber] ?: return

        val plugin = findPlugin(pluginName)
        if (plugin == null) {
            failEpisode(recordKey, episodeNumber, "找不到插件 $pluginName")
            return
        }

        if (episode.networkM3u8Url.isNotEmpty()) {
            val updated = episode.copy(
                status = DownloadStatus.DOWNLOADING,
                errorMessage = "",
                progressPercent = 0.0,
                downloadedSegments = 0
            )
            repository.updateEpisode(recordKey, episodeNumber, updated)
            refreshRecord(recordKey)
            startBackgroundServiceIfNeeded()

            val httpHeaders = plugin.buildHttpHeaders()
            val adBlockerEnabled = repository.getForceAdBlocker() || plugin.adBlocker
            downloadManager.enqueue(
                DownloadRequest(
                    recordKey = recordKey,
                    bangumiId = bangumiId,
                    pluginName = pluginName,
                    episodeNumber = episodeNumber,
                    m3u8Url = episode.networkM3u8Url,
                    httpHeaders = httpHeaders,
                    adBlockerEnabled = adBlockerEnabled,
                    episode = updated.toEntity(recordKey)
                )
            )
        } else {
            cancelResolve(recordKey, episodeNumber)
            val updated = episode.copy(
                status = DownloadStatus.RESOLVING,
                errorMessage = "",
                progressPercent = 0.0,
                downloadedSegments = 0
            )
            repository.updateEpisode(recordKey, episodeNumber, updated)
            refreshRecord(recordKey)

            resolveQueue.add(
                ResolveRequest(
                    recordKey = recordKey,
                    bangumiId = bangumiId,
                    pluginName = pluginName,
                    episodeNumber = episodeNumber,
                    episodePageUrl = episode.episodePageUrl
                )
            )
            processResolveQueue()
        }
    }

    suspend fun priorityDownload(bangumiId: Int, pluginName: String, episodeNumber: Int) {
        val recordKey = "${pluginName}_$bangumiId"
        val record = repository.getRecord(recordKey) ?: return
        val episode = record.episodes[episodeNumber] ?: return

        val plugin = findPlugin(pluginName)
        if (plugin == null) {
            failEpisode(recordKey, episodeNumber, "找不到插件 $pluginName")
            return
        }

        cancelResolve(recordKey, episodeNumber)

        if (episode.networkM3u8Url.isNotEmpty()) {
            val updated = episode.copy(
                status = DownloadStatus.DOWNLOADING,
                errorMessage = ""
            )
            repository.updateEpisode(recordKey, episodeNumber, updated)
            refreshRecord(recordKey)
            startBackgroundServiceIfNeeded()

            val httpHeaders = plugin.buildHttpHeaders()
            val adBlockerEnabled = repository.getForceAdBlocker() || plugin.adBlocker
            downloadManager.enqueuePriority(
                DownloadRequest(
                    recordKey = recordKey,
                    bangumiId = bangumiId,
                    pluginName = pluginName,
                    episodeNumber = episodeNumber,
                    m3u8Url = episode.networkM3u8Url,
                    httpHeaders = httpHeaders,
                    adBlockerEnabled = adBlockerEnabled,
                    episode = updated.toEntity(recordKey)
                )
            )
        } else {
            val updated = episode.copy(
                status = DownloadStatus.RESOLVING,
                errorMessage = ""
            )
            repository.updateEpisode(recordKey, episodeNumber, updated)
            refreshRecord(recordKey)

            resolveQueue.add(
                0,
                ResolveRequest(
                    recordKey = recordKey,
                    bangumiId = bangumiId,
                    pluginName = pluginName,
                    episodeNumber = episodeNumber,
                    episodePageUrl = episode.episodePageUrl
                )
            )
            processResolveQueue()
        }
    }

    suspend fun resumeAllDownloads(bangumiId: Int, pluginName: String) {
        val recordKey = "${pluginName}_$bangumiId"
        val record = repository.getRecord(recordKey) ?: return

        val incompleteEpisodes = record.episodes.values
            .filter {
                it.status == DownloadStatus.PAUSED ||
                    it.status == DownloadStatus.FAILED ||
                    it.status == DownloadStatus.PENDING
            }
            .sortedBy { it.episodeNumber }

        for (episode in incompleteEpisodes) {
            retryDownload(bangumiId, pluginName, episode.episodeNumber)
        }

        if (incompleteEpisodes.isNotEmpty()) {
            Log.i(TAG, "resumed ${incompleteEpisodes.size} downloads for $recordKey")
        }
    }

    suspend fun deleteRecord(bangumiId: Int, pluginName: String) {
        val recordKey = "${pluginName}_$bangumiId"
        val record = repository.getRecord(recordKey)
        val episodes = record?.episodes ?: emptyMap()
        cancelResolveRecord(recordKey)
        stopEpisodes(recordKey, episodes)
        try {
            downloadManager.deleteRecordFiles(bangumiId, pluginName, record = null)
            repository.deleteRecord(recordKey)
        } finally {
            refreshRecord(recordKey)
            queueBackgroundNotificationUpdate()
        }
    }

    suspend fun deleteEpisode(bangumiId: Int, pluginName: String, episodeNumber: Int) {
        val recordKey = "${pluginName}_$bangumiId"
        val episode = repository.getEpisode(bangumiId, pluginName, episodeNumber)
        cancelResolve(recordKey, episodeNumber)
        stopEpisodes(recordKey, mapOf(episodeNumber to episode))
        try {
            downloadManager.deleteEpisodeFiles(
                bangumiId,
                pluginName,
                episodeNumber,
                episode = episode?.toEntity(recordKey)
            )
            repository.deleteEpisode(recordKey, episodeNumber)
        } finally {
            refreshRecord(recordKey)
            queueBackgroundNotificationUpdate()
        }
    }

    private suspend fun stopEpisodes(recordKey: String, episodes: Map<Int, DownloadEpisode?>) {
        for (episodeNumber in episodes.keys) {
            downloadManager.cancel(recordKey, episodeNumber)
            speeds.remove("${recordKey}_$episodeNumber")
        }
        for (episode in episodes.values) {
            val e = episode ?: continue
            if (e.status == DownloadStatus.DOWNLOADING ||
                e.status == DownloadStatus.RESOLVING ||
                e.status == DownloadStatus.PENDING
            ) {
                repository.updateEpisode(
                    recordKey,
                    e.episodeNumber,
                    e.copy(status = DownloadStatus.PAUSED)
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 弹幕缓存（对应原版 _fetchAndCacheDanmakuAsync / getCachedDanmakus）
    // ------------------------------------------------------------------

    private fun fetchAndCacheDanmakuAsync(recordKey: String, bangumiId: Int, episodeNumber: Int) {
        scope.launch {
            try {
                Log.i(TAG, "fetching danmaku for episode $episodeNumber (async)")
                val danmakuApi = DanmakuApi(settings = settings)
                val danDanBangumiID = danmakuApi.getDanDanBangumiIDByBgmBangumiID(bangumiId)
                if (danDanBangumiID == 0) {
                    Log.w(TAG, "failed to get DanDan bangumiID for $bangumiId")
                    return@launch
                }
                val danmakus = danmakuApi.getDanDanmaku(danDanBangumiID, episodeNumber)
                if (danmakus.isEmpty()) {
                    Log.i(TAG, "no danmaku found for episode $episodeNumber")
                    return@launch
                }

                // 等待 downloadDirectory 就绪
                var downloadDirectory = ""
                for (i in 0 until 10) {
                    val record = repository.getRecord(recordKey) ?: return@launch
                    val episode = record.episodes[episodeNumber] ?: return@launch
                    if (episode.status == DownloadStatus.FAILED ||
                        episode.status == DownloadStatus.PAUSED
                    ) {
                        return@launch
                    }
                    if (episode.downloadDirectory.isNotEmpty()) {
                        downloadDirectory = episode.downloadDirectory
                        break
                    }
                    delay(3000)
                }
                if (downloadDirectory.isEmpty()) {
                    Log.w(TAG, "downloadDirectory not ready for episode $episodeNumber, skipping danmaku cache")
                    return@launch
                }

                writeDanmakuToFile(downloadDirectory, danmakus, danDanBangumiID)
                Log.i(TAG, "cached ${danmakus.size} danmakus for episode $episodeNumber")
            } catch (e: Exception) {
                Log.w(TAG, "failed to fetch danmaku: ${e.message}")
            }
        }
    }

    suspend fun getCachedDanmakus(
        bangumiId: Int,
        pluginName: String,
        episodeNumber: Int
    ): List<DanmakuEntry>? {
        val episode = repository.getEpisode(bangumiId, pluginName, episodeNumber) ?: return null
        val fromFile = readDanmakuFromFile(episode.downloadDirectory) ?: return null
        return fromFile.danmakus.takeIf { it.isNotEmpty() }
    }

    suspend fun updateCachedDanmakus(
        bangumiId: Int,
        pluginName: String,
        episodeNumber: Int,
        danmakus: List<DanmakuEntry>,
        danDanBangumiID: Int
    ) {
        val recordKey = "${pluginName}_$bangumiId"
        val record = repository.getRecord(recordKey) ?: return
        val episode = record.episodes[episodeNumber] ?: return
        try {
            writeDanmakuToFile(episode.downloadDirectory, danmakus, danDanBangumiID)
            if (episode.danmakuData.isNotEmpty()) {
                repository.updateEpisode(
                    recordKey,
                    episodeNumber,
                    episode.copy(danmakuData = "")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to update cached danmaku: ${e.message}")
        }
    }

    private fun danmakuFilePath(downloadDirectory: String): String =
        "$downloadDirectory/danmaku.json"

    private data class DanmakuFileData(
        val danmakus: List<DanmakuEntry>,
        val danDanBangumiID: Int
    )

    private suspend fun readDanmakuFromFile(downloadDirectory: String): DanmakuFileData? {
        if (downloadDirectory.isEmpty()) return null
        val file = File(danmakuFilePath(downloadDirectory))
        if (!file.exists()) return null
        return try {
            val content = file.readText()
            val gson = com.google.gson.Gson()
            val decoded = gson.fromJson(content, Map::class.java) ?: return null
            val danDanBangumiID = (decoded["danDanBangumiID"] as? Double)?.toInt() ?: 0
            val jsonList = decoded["danmakus"] as? List<*> ?: emptyList<Any?>()
            val type = object : com.google.gson.reflect.TypeToken<List<DanmakuEntry>>() {}.type
            val danmakus = gson.fromJson<List<DanmakuEntry>>(
                gson.toJson(jsonList), type
            ) ?: emptyList()
            DanmakuFileData(danmakus, danDanBangumiID)
        } catch (e: Exception) {
            Log.w(TAG, "failed to read danmaku file: ${e.message}")
            null
        }
    }

    private suspend fun writeDanmakuToFile(
        downloadDirectory: String,
        danmakus: List<DanmakuEntry>,
        danDanBangumiID: Int
    ) {
        if (downloadDirectory.isEmpty()) return
        val wrapper = mapOf(
            "danDanBangumiID" to danDanBangumiID,
            "danmakus" to danmakus.map { it.toJson() }
        )
        File(danmakuFilePath(downloadDirectory)).writeText(com.google.gson.Gson().toJson(wrapper))
    }

    /** 释放资源 */
    fun dispose() {
        cancelAllResolves()
        scope.cancel()
    }

    companion object {
        private const val TAG = "DownloadController"
        private const val UI_UPDATE_INTERVAL_MS = 500L
    }
}

/** 解析请求（对应原版 _ResolveRequest） */
private data class ResolveRequest(
    val recordKey: String,
    val bangumiId: Int,
    val pluginName: String,
    val episodeNumber: Int,
    val episodePageUrl: String
)

// ------------------------------------------------------------------
// 转换辅助（实体 ↔ 领域模型）
// ------------------------------------------------------------------

/** DownloadEpisodeEntity → DownloadEpisode */
fun DownloadEpisodeEntity.toDomain(): DownloadEpisode =
    DownloadEpisode(
        episodeNumber = episodeNumber,
        episodeName = episodeName,
        road = road,
        status = status,
        progressPercent = progressPercent,
        totalSegments = totalSegments,
        downloadedSegments = downloadedSegments,
        localM3u8Path = localM3u8Path,
        downloadDirectory = downloadDirectory,
        networkM3u8Url = networkM3u8Url,
        completedAt = completedAt,
        errorMessage = errorMessage,
        totalBytes = totalBytes,
        episodePageUrl = episodePageUrl,
        danmakuData = danmakuData,
        danDanBangumiID = danDanBangumiID
    )

/** DownloadEpisode → DownloadEpisodeEntity */
fun DownloadEpisode.toEntity(recordKey: String, id: Long = 0): DownloadEpisodeEntity =
    DownloadEpisodeEntity(
        id = id,
        recordKey = recordKey,
        episodeNumber = episodeNumber,
        episodeName = episodeName,
        road = road,
        status = status,
        progressPercent = progressPercent,
        totalSegments = totalSegments,
        downloadedSegments = downloadedSegments,
        localM3u8Path = localM3u8Path,
        downloadDirectory = downloadDirectory,
        networkM3u8Url = networkM3u8Url,
        completedAt = completedAt,
        errorMessage = errorMessage,
        totalBytes = totalBytes,
        episodePageUrl = episodePageUrl,
        danmakuData = danmakuData,
        danDanBangumiID = danDanBangumiID
    )

/** 速度/字节格式化（对应原版 formatSpeed / formatBytes） */
fun formatSpeed(bytesPerSecond: Double): String = when {
    bytesPerSecond <= 0 -> ""
    bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / 1024 / 1024)
    bytesPerSecond >= 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024)
    else -> String.format("%.0f B/s", bytesPerSecond)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Plugin.buildFullUrl 便捷（对应原版 buildFullUrl） */
fun Plugin.buildFullUrl(url: String): String = when {
    url.startsWith("http://") || url.startsWith("https://") -> url
    baseUrl.isEmpty() -> url
    baseUrl.endsWith("/") -> baseUrl + url.removePrefix("/")
    else -> "$baseUrl/${url.removePrefix("/")}"
}
