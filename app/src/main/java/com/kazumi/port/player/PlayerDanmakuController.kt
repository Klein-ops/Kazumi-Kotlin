package com.kazumi.port.player

import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.model.DanmakuUtils
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.data.repository.BilibiliDanmakuApi
import com.kazumi.port.data.repository.DanmakuApi
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.util.FileLogger
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
    private val downloadRepository: com.kazumi.port.data.repository.DownloadRepository? = null,
    /** B 站弹幕源（免鉴权）。弹弹play 已强制 AppId 鉴权，B 站是当前默认源 */
    private val bilibiliApi: BilibiliDanmakuApi = BilibiliDanmakuApi()
) {

    /** 弹幕加载结果状态 */
    enum class DanmakuLoadStatus { SUCCESS, EMPTY, FAILED }

    data class DanmakuLoadResult(
        val danmakus: List<DanmakuEntry>,
        val bangumiID: Int,
        val status: DanmakuLoadStatus,
        /** 失败原因（status=FAILED 时用于给用户更准确提示，例如"未配置弹弹play凭据"） */
        val errorMessage: String? = null
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

            fun failed(bangumiID: Int, errorMessage: String? = null): DanmakuLoadResult =
                DanmakuLoadResult(emptyList(), bangumiID, DanmakuLoadStatus.FAILED, errorMessage)
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

    /**
     * 取 (fromMs, toMs] 之间跨过的所有秒的弹幕。
     *
     * 旧的 danmakusForPlaybackPosition() 只返回"当前这一秒"的快照，而播放页是定时轮询的：
     * 只要某次轮询跨过了整数秒（卡顿 / seek / 切后台回来），那一秒的弹幕就永久丢失。
     * 这里按区间累积，保证不漏。
     */
    suspend fun takeDanmakusBetween(fromMs: Long, toMs: Long): List<DanmakuEntry> {
        if (danDanmakus.isEmpty()) return emptyList()
        val toSecond = resolveDanmakuSecond(toMs) ?: return emptyList()
        // fromMs 为负表示"从头开始"，此时从第 0 秒取
        val fromSecond = if (fromMs < 0) 0 else (resolveDanmakuSecond(fromMs) ?: 0) + 1
        if (toSecond < fromSecond) return emptyList()
        // 跨度异常（seek 跳很远）时只取当前秒，避免一次性灌入上万条
        val start = if (toSecond - fromSecond > 30) toSecond else fromSecond
        val out = ArrayList<DanmakuEntry>()
        for (s in start..toSecond) {
            danDanmakus[s]?.let { out.addAll(it) }
        }
        return out
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
        isLocalPlayback: Boolean,
        bangumiName: String = ""
    ): DanmakuLoadResult {
        return if (isLocalPlayback) {
            _fetchCachedDanmaku(bangumiId, pluginName, episode, bangumiName)
        } else {
            _fetchDanDanmakuByBgmBangumiID(bangumiId, episode, bangumiName)
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
        episode: Int,
        bangumiName: String
    ): DanmakuLoadResult {
        var nextBangumiID = bangumiID
        val repo = downloadRepository ?: return _fetchDanDanmakuByBgmBangumiID(bangumiId, episode, bangumiName)
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
                    _fetchOnlineAndCache(bangumiId, pluginName, episode, nextBangumiID, bangumiName)
                }
            } else {
                _fetchOnlineAndCache(bangumiId, pluginName, episode, nextBangumiID, bangumiName)
            }
        } catch (e: Exception) {
            FileLogger.w("Danmaku", "本地弹幕缓存读取失败: ${e.javaClass.simpleName}: ${e.message}")
            DanmakuLoadResult.failed(nextBangumiID, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun _fetchOnlineAndCache(
        bangumiId: Int,
        pluginName: String,
        episode: Int,
        nextBangumiID: Int,
        bangumiName: String
    ): DanmakuLoadResult {
        val result = _autoMatchOnline(bangumiId, episode, bangumiName, nextBangumiID)
        if (result.danmakus.isNotEmpty()) {
            val cacheId = if (result.bangumiID != 0) result.bangumiID else nextBangumiID
            if (cacheId != 0) {
                saveDanmakuToCache(bangumiId, pluginName, episode, result.danmakus, cacheId)
            }
        }
        return result
    }

    /** 在线拉取（非本地播放），对应原版 _fetchDanDanmakuByBgmBangumiID */
    private suspend fun _fetchDanDanmakuByBgmBangumiID(
        bgmBangumiID: Int,
        episode: Int,
        bangumiName: String
    ): DanmakuLoadResult = _autoMatchOnline(bgmBangumiID, episode, bangumiName, bangumiID)

    /**
     * 自动装载弹幕（对应"看哪个番就装哪个"的预期，无需用户手动选择源）
     *
     * 源顺序（2026-10 调整）：
     *  1) **Bilibili**（免鉴权，默认开启）：番剧名搜索 → season → 按集号取 cid → 弹幕 XML。
     *     弹弹play 开放 API v2 已强制 AppId/AppSecret 鉴权，第三方无凭据必然 403，
     *     所以 B 站成为默认可用源。
     *  2) **弹弹play**：仅当设置里打开开关且已填写 AppId/AppSecret 时才尝试。
     *
     * 两者都拿不到时：B 站若报错 → 返回 FAILED 并带上原因；B 站只是没匹配到 → 返回 EMPTY。
     */
    private suspend fun _autoMatchOnline(
        bgmBangumiID: Int,
        episode: Int,
        bangumiName: String,
        fallbackBangumiID: Int
    ): DanmakuLoadResult {
        var nextBangumiID = fallbackBangumiID
        val title = bangumiName.trim()
        var biliError: String? = null

        // ---------- 源 1：Bilibili（免鉴权） ----------
        val biliOn = settings.getBoolean(SettingsManager.Danmaku.BILI_SOURCE, true)
        if (biliOn && title.isNotEmpty()) {
            try {
                FileLogger.i("Danmaku", "B站匹配: \"$title\" ep=$episode")
                val list = bilibiliApi.fetchDanmakuByTitle(title, episode)
                if (list.isNotEmpty()) {
                    FileLogger.i("Danmaku", "B站装载成功: ${list.size} 条")
                    return DanmakuLoadResult.success(list, nextBangumiID)
                }
                FileLogger.w("Danmaku", "B站未匹配到弹幕: \"$title\" ep=$episode")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                biliError = "${e.javaClass.simpleName}: ${e.message}"
                FileLogger.w("Danmaku", "B站装载失败: $biliError")
            }
        } else if (!biliOn) {
            FileLogger.i("Danmaku", "B站源已关闭（设置 → 弹幕 → BiliBili）")
        }

        // ---------- 源 2：弹弹play（需开关 + 凭据） ----------
        val danDanOn = settings.getBoolean(SettingsManager.Danmaku.DAN_DAN_SOURCE, false)
        val hasCred = settings.getString(SettingsManager.Danmaku.DAN_DAN_APP_ID, "").isNotBlank() &&
            settings.getString(SettingsManager.Danmaku.DAN_DAN_APP_SECRET, "").isNotBlank()
        if (!danDanOn || !hasCred) {
            FileLogger.i("Danmaku", "弹弹play 源跳过（开关=$danDanOn 凭据=$hasCred）")
            return if (biliError != null) {
                DanmakuLoadResult.failed(nextBangumiID, "B站: $biliError")
            } else {
                DanmakuLoadResult.success(emptyList(), nextBangumiID)
            }
        }

        try {
            if (bgmBangumiID > 0) {
                val danDanId = danmakuApi.getDanDanBangumiIDByBgmBangumiID(bgmBangumiID)
                FileLogger.i("Danmaku", "bgm=$bgmBangumiID -> danDan=$danDanId ep=$episode")
                if (danDanId != 0) {
                    nextBangumiID = danDanId
                    val res = danmakuApi.getDanDanmaku(danDanId, episode)
                    if (res.isNotEmpty()) {
                        FileLogger.i("Danmaku", "按 bgm 映射装载成功: ${res.size} 条")
                        return DanmakuLoadResult.success(res, nextBangumiID)
                    }
                    FileLogger.w("Danmaku", "bgm 映射命中但该集弹幕为空")
                }
            }
            if (title.isEmpty()) {
                FileLogger.w("Danmaku", "缺少番剧名，无法标题兜底")
                return DanmakuLoadResult.success(emptyList(), nextBangumiID)
            }
            FileLogger.i("Danmaku", "改用标题自动匹配: \"$title\" ep=$episode")
            val auto = autoMatchByTitle(title, episode)
            FileLogger.i("Danmaku", "标题自动匹配结果: ${auto.size} 条")
            return DanmakuLoadResult.success(auto, nextBangumiID)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w("Danmaku", "在线装载失败: ${e.javaClass.simpleName}: ${e.message}")
            return DanmakuLoadResult.failed(nextBangumiID, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 用标题搜索番剧并自动匹配分集（弹幕为空时的兜底路径） */
    private suspend fun autoMatchByTitle(title: String, episode: Int): List<DanmakuEntry> {
        if (title.isBlank()) return emptyList()
        return try {
            val animes = danmakuApi.searchAnimes(title).animes
            if (animes.isEmpty()) return emptyList()
            val anime = animes.first()
            val eps = danmakuApi.getDanDanEpisodesByDanDanBangumiID(anime.animeId).episodes
            if (eps.isEmpty()) return emptyList()
            val target = eps.getOrNull(episode - 1)
                ?: eps.firstOrNull { Regex("\\d+").find(it.episodeTitle)?.value?.toIntOrNull() == episode }
                ?: return emptyList()
            danmakuApi.getDanDanmakuByEpisodeID(target.episodeId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w("Danmaku", "标题匹配弹幕失败: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
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