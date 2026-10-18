package com.kazumi.port.player

import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.model.DanmakuUtils
import com.kazumi.port.data.repository.DanmakuApi
import com.kazumi.port.data.repository.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 弹幕加载与调度状态（对应原版 player_danmaku_controller.dart）
 *
 * 职责：
 * - 按 DanDanPlay 番剧 ID + 集号拉取弹幕（在线）或从下载缓存读取（本地）
 * - 弹幕按秒索引，供 Compose 弹幕层按播放位置取用
 * - 去重（合并重复弹幕）
 * - 时间轴偏移（danmakuTimeOffset）与倍速跟随（danmakuFollowSpeed）
 * - 缓存写入下载实体（danmakuData / danDanBangumiID）
 */
class PlayerDanmakuController(
    private val danmakuApi: DanmakuApi,
    private val settings: SettingsManager,
    private val downloadRepository: com.kazumi.port.data.repository.DownloadRepository? = null
) {

    /** 弹幕加载结果状态 */
    enum class DanmakuLoadStatus { SUCCESS, EMPTY, FAILED }

    data class DanmakuLoadResult(
        val danmakus: List<DanmakuEntry>,
        val bangumiID: Int,
        val status: DanmakuLoadStatus
    ) {
        val hasDanmakus: Boolean get() = status == DanmakuLoadStatus.SUCCESS
        val isFailed: Boolean get() = status == DanmakuLoadStatus.FAILED

        companion object {
            fun success(danmakus: List<DanmakuEntry>, bangumiID: Int): DanmakuLoadResult =
                DanmakuLoadResult(
                    danmakus = danmakus,
                    bangumiID = bangumiID,
                    status = if (danmakus.isEmpty()) DanmakuLoadStatus.EMPTY else DanmakuLoadStatus.SUCCESS
                )

            fun failed(bangumiID: Int): DanmakuLoadResult =
                DanmakuLoadResult(emptyList(), bangumiID, DanmakuLoadStatus.FAILED)
        }
    }

    /** 弹幕按秒索引：second -> 弹幕列表 */
    val danDanmakus: MutableMap<Int, MutableList<DanmakuEntry>> = mutableMapOf()

    private val _danmakuOn = MutableStateFlow(false)
    val danmakuOn: StateFlow<Boolean> = _danmakuOn.asStateFlow()

    private val _danmakuLoading = MutableStateFlow(false)
    val danmakuLoading: StateFlow<Boolean> = _danmakuLoading.asStateFlow()

    var bangumiID: Int = 0
        private set

    /** 弹幕渲染期数（切源/seek 时自增以丢弃过期调度） */
    private var _scheduledDanmakuGeneration = 0

    /** 时间轴偏移（秒） */
    suspend fun timelineOffsetSeconds(): Double =
        settings.getDouble(SettingsManager.Danmaku.TIME_OFFSET, 0.0)

    /** 播放位置 -> 源时间秒；负值返回 null（弹幕未到时间点） */
    suspend fun resolveDanmakuSecond(playbackPositionMs: Long): Int? {
        val offset = timelineOffsetSeconds()
        val sourceMs = playbackPositionMs - (offset * 1000).toLong().round()
        if (sourceMs < 0) return null
        return (sourceMs / 1000).toInt()
    }

    private fun Long.round(): Long = (this + 500) / 1000 * 1000

    /** 当前秒内要显示的弹幕 */
    suspend fun danmakusForPlaybackPosition(playbackPositionMs: Long): List<DanmakuEntry> {
        val second = resolveDanmakuSecond(playbackPositionMs) ?: return emptyList()
        return danDanmakus[second] ?: emptyList()
    }

    fun setDanmakuEnabled(value: Boolean) {
        _danmakuOn.value = value
    }

    /** 清空并让已排期的弹幕失效（seek/切源时调用） */
    fun clearAndInvalidateScheduledDanmakus() {
        _scheduledDanmakuGeneration++
    }

    val scheduledDanmakuGeneration: Int get() = _scheduledDanmakuGeneration

    /**
     * 加载弹幕（本地播放走下载缓存，在线走 DanDanPlay）
     * 对应原版 fetchDanmaku()
     */
    suspend fun fetchDanmaku(
        bangumiId: Int,
        pluginName: String,
        episode: Int,
        isLocalPlayback: Boolean
    ): DanmakuLoadResult {
        return if (isLocalPlayback) {
            _fetchCachedDanmaku(bangumiId, pluginName, episode)
        } else {
            _fetchDanDanmakuByBgmBangumiID(bangumiId, episode)
        }
    }

    fun beginDanmakuLoad() {
        danDanmakus.clear()
        _danmakuLoading.value = true
    }

    suspend fun applyDanmakuLoad(result: DanmakuLoadResult, enableDanmaku: Boolean) {
        bangumiID = result.bangumiID
        addDanmakus(result.danmakus)
        _danmakuOn.value = enableDanmaku
        _danmakuLoading.value = false
    }

    fun applyUnavailableDanmakuLoad(result: DanmakuLoadResult) {
        bangumiID = result.bangumiID
        danDanmakus.clear()
        _danmakuOn.value = false
        _danmakuLoading.value = false
    }

    fun finishDanmakuLoad(disableDanmaku: Boolean = false) {
        if (disableDanmaku) {
            danDanmakus.clear()
            _danmakuOn.value = false
        }
        _danmakuLoading.value = false
    }

    /** 本地播放：先读下载缓存的弹幕；没有则在线拉取并回写缓存 */
    private suspend fun _fetchCachedDanmaku(
        bangumiId: Int,
        pluginName: String,
        episode: Int
    ): DanmakuLoadResult {
        var nextBangumiID = bangumiID
        val repo = downloadRepository ?: return _fetchDanDanmakuByBgmBangumiID(bangumiId, episode)
        return try {
            val entity = repo.getEpisodeEntity(bangumiId, pluginName, episode)
            val cached = entity?.danmakuData
            if (!cached.isNullOrEmpty() && entity.danDanBangumiID != 0) {
                // 从缓存解析（danmakuData 为 JSON 数组字符串，与原版保存格式一致）
                nextBangumiID = entity.danDanBangumiID
                val parsed = parseCachedDanmakus(cached)
                if (parsed.isNotEmpty()) {
                    DanmakuLoadResult.success(parsed, nextBangumiID)
                } else {
                    // 缓存空：尝试在线
                    _fetchOnlineAndCache(bangumiId, pluginName, episode, nextBangumiID)
                }
            } else {
                _fetchOnlineAndCache(bangumiId, pluginName, episode, nextBangumiID)
            }
        } catch (e: Exception) {
            DanmakuLoadResult.failed(nextBangumiID)
        }
    }

    private suspend fun _fetchOnlineAndCache(
        bangumiId: Int,
        pluginName: String,
        episode: Int,
        nextBangumiID: Int
    ): DanmakuLoadResult {
        return try {
            val id = danmakuApi.getDanDanBangumiIDByBgmBangumiID(bangumiId)
            if (id != 0) {
                val res = danmakuApi.getDanDanmaku(id, episode)
                if (res.isNotEmpty()) {
                    saveDanmakuToCache(bangumiId, pluginName, episode, res, id)
                }
                DanmakuLoadResult.success(res, id)
            } else {
                DanmakuLoadResult.success(emptyList(), nextBangumiID)
            }
        } catch (e: Exception) {
            DanmakuLoadResult.failed(nextBangumiID)
        }
    }

    /** 在线拉取（非本地播放），对应原版 _fetchDanDanmakuByBgmBangumiID */
    private suspend fun _fetchDanDanmakuByBgmBangumiID(
        bgmBangumiID: Int,
        episode: Int
    ): DanmakuLoadResult {
        var nextBangumiID = bangumiID
        return try {
            nextBangumiID = danmakuApi.getDanDanBangumiIDByBgmBangumiID(bgmBangumiID)
            if (nextBangumiID == 0) {
                DanmakuLoadResult.success(emptyList(), nextBangumiID)
            } else {
                val res = danmakuApi.getDanDanmaku(nextBangumiID, episode)
                DanmakuLoadResult.success(res, nextBangumiID)
            }
        } catch (e: Exception) {
            DanmakuLoadResult.failed(nextBangumiID)
        }
    }

    /** 按分集 ID 获取弹幕（弹幕源切换用） */
    suspend fun getDanDanmakuByEpisodeID(episodeID: Int): Boolean {
        _danmakuLoading.value = true
        return try {
            danDanmakus.clear()
            val res = danmakuApi.getDanDanmakuByEpisodeID(episodeID)
            addDanmakus(res)
            res.isNotEmpty()
        } finally {
            _danmakuLoading.value = false
        }
    }

    /** 写入下载缓存 */
    suspend fun saveDanmakuToCache(
        bangumiId: Int,
        pluginName: String,
        episode: Int,
        danmakus: List<DanmakuEntry>,
        danDanID: Int
    ) {
        val repo = downloadRepository ?: return
        try {
            // 原版把弹幕保存为 JSON 数组字符串（含 m / p 字段）
            val jsonArray = com.google.gson.JsonArray().apply {
                danmakus.forEach { add(it.toJson()) }
            }
            val newEpisode = com.kazumi.port.data.repository.DownloadEpisode(
                episodeNumber = episode,
                episodeName = "",
                road = 0,
                status = com.kazumi.port.data.db.DownloadStatus.COMPLETED,
                progressPercent = 1.0,
                totalSegments = 0,
                downloadedSegments = 0,
                localM3u8Path = "",
                downloadDirectory = "",
                networkM3u8Url = "",
                completedAt = System.currentTimeMillis(),
                errorMessage = "",
                totalBytes = 0,
                episodePageUrl = "",
                danmakuData = jsonArray.toString(),
                danDanBangumiID = danDanID
            )
            repo.updateEpisode("${pluginName}_$bangumiId", episode, newEpisode)
        } catch (e: Exception) {
            // 缓存失败不影响播放
        }
    }

    private fun parseCachedDanmakus(data: String): List<DanmakuEntry> {
        return try {
            val arr = com.google.gson.JsonParser.parseString(data).asJsonArray
            arr.mapNotNull { e -> if (e.isJsonObject) DanmakuEntry.fromJson(e.asJsonObject) else null }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 添加弹幕（按秒分组；开启去重时合并重复弹幕）
     * 对应原版 addDanmakus()
     */
    suspend fun addDanmakus(danmakus: List<DanmakuEntry>) {
        val dedup = settings.getBoolean(SettingsManager.Danmaku.DEDUPLICATION, false)
        val listToAdd = if (dedup) DanmakuUtils.mergeDuplicateDanmakus(danmakus, 5.0) else danmakus
        for (element in listToAdd) {
            val second = element.time.toInt()
            (danDanmakus.getOrPut(second) { mutableListOf() }).add(element)
        }
    }

    /** 弹幕倍速跟随（原版 updateDanmakuSpeed）：由渲染层读设置实现，这里提供计算 */
    suspend fun danmakuDurationForSpeed(playerSpeed: Float): Double {
        val base = settings.getDouble(SettingsManager.Danmaku.DURATION, 8.0)
        val follow = settings.getBoolean(SettingsManager.Danmaku.FOLLOW_SPEED, false)
        return if (follow) base / playerSpeed else base
    }
}