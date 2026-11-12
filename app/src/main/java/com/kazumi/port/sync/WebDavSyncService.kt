package com.kazumi.port.sync

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kazumi.port.data.repository.History
import com.kazumi.port.data.repository.Progress
import com.kazumi.port.data.repository.SettingsManager
import java.io.File

/**
 * WebDAV 同步服务（对应原版 services/sync/webdav.dart）
 *
 * 负责：
 * - 初始化客户端（读取 URL/账号/密码）
 * - 确保远端目录结构（/kazumiSync/history/changes、danmakuShield）
 * - 三通道同步：历史 / 收藏 / 弹幕屏蔽
 *
 * 数据格式与原版保持一致（snapshot.json）。
 *
 * 移植版修复（2026-11-12）：此前三个通道全是空壳——历史只下载不落库、
 * 收藏只上传不落库、弹幕屏蔽零调用。本次补齐完整闭环：
 *   历史 / 收藏 / 弹幕屏蔽 -> 下载远端 -> 合并 -> 写回本地 -> 上传合并结果
 */
class WebDavSyncService(
    context: Context,
    private val settings: SettingsManager
) {
    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "WebDavSync"
        const val SYNC_ROOT = "/kazumiSync"
        const val HISTORY_ROOT = "$SYNC_ROOT/history"
        const val HISTORY_CHANGES = "$HISTORY_ROOT/changes"
        const val HISTORY_SNAPSHOT = "$HISTORY_ROOT/snapshot.json"
        const val DANMAKU_SHIELD_ROOT = "$SYNC_ROOT/danmakuShield"
        const val COLLECTIBLES_FILE = "$SYNC_ROOT/collectibles.tmp"
        const val COLLECT_CHANGES_FILE = "$SYNC_ROOT/collectchanges.tmp"
        const val HISTORY_LEGACY_FILE = "$SYNC_ROOT/histories.tmp"
    }

    @Volatile
    private var client: WebDavClient? = null

    @Volatile
    private var initialized = false

    /** 本地临时目录 */
    val localTempDir: File
        get() = File(appContext.filesDir, "webdavTemp").apply { mkdirs() }

    // ------------------------------------------------------------------
    // 初始化
    // ------------------------------------------------------------------

    /**
     * 初始化：读取设置，创建客户端，ping 并确保远端目录。
     * 对应原版 init()。
     */
    suspend fun init(): Boolean {
        val url = settings.getString(SettingsManager.WebDav.URL, "")
        if (url.isEmpty()) return false

        val username = settings.getString(SettingsManager.WebDav.USERNAME, "")
        val password = settings.getString(SettingsManager.WebDav.PASSWORD, "")

        val newClient = WebDavClient(url, username, password)
        if (!newClient.ping()) {
            Log.e(TAG, "WebDAV ping failed")
            return false
        }

        ensureRemoteDirectory(SYNC_ROOT)
        ensureRemoteDirectory(HISTORY_ROOT)
        ensureRemoteDirectory(HISTORY_CHANGES)
        ensureRemoteDirectory(DANMAKU_SHIELD_ROOT)

        client = newClient
        initialized = true
        Log.i(TAG, "webdav backup directory ready")
        return true
    }

    /** 确保远端目录存在（对应原版 _ensureRemoteDirectory） */
    fun ensureRemoteDirectory(path: String) {
        val c = client ?: return
        c.mkdir(path)
    }

    /** 是否启用 */
    suspend fun isEnabled(): Boolean = settings.getBoolean(SettingsManager.WebDav.ENABLE, false)

    suspend fun isHistoryEnabled(): Boolean =
        settings.getBoolean(SettingsManager.WebDav.ENABLE_HISTORY, false)

    suspend fun isCollectEnabled(): Boolean =
        settings.getBoolean(SettingsManager.WebDav.ENABLE_COLLECT, false)

    suspend fun isDanmakuShieldEnabled(): Boolean =
        settings.getBoolean(SettingsManager.WebDav.ENABLE_DANMAKU_SHIELD, false)

    // ------------------------------------------------------------------
    // 收藏同步（对应原版 syncCollectibles / updateCollectibles）
    // 完整闭环：下载远端 -> 合并 -> 回写本地 -> 上传
    // ------------------------------------------------------------------

    /**
     * 上传本地收藏快照（JSON 数组）到远端
     */
    suspend fun uploadCollectibles(json: String): Boolean {
        val c = client ?: return false
        if (!isEnabled() || !isCollectEnabled()) return false
        val ok = c.uploadBytes(json.toByteArray(), COLLECTIBLES_FILE)
        if (ok) Log.i(TAG, "uploaded collectibles")
        return ok
    }

    /**
     * 下载远端收藏快照（不存在返回 null）
     */
    suspend fun downloadCollectibles(): String? {
        val c = client ?: return null
        val text = c.downloadText(COLLECTIBLES_FILE)
        if (text != null) Log.i(TAG, "downloaded collectibles")
        return text
    }

    /**
     * 完整同步：下载远端 -> merge（由调用方提供）-> 上传。
     * 调用方必须把 merged 结果写回本地库（否则只备份不恢复）。
     */
    suspend fun syncCollectibles(merge: (String?) -> String): String? {
        if (!isEnabled() || !isCollectEnabled()) return null
        ensureReady()
        val remote = downloadCollectibles()
        val merged = merge(remote)
        uploadCollectibles(merged)
        return merged
    }

    // ------------------------------------------------------------------
    // 历史同步（对应原版 syncHistory，移植版采用 snapshot.json 全量直传/直取）
    // 完整闭环：本地全量 -> snapshot；远端存在 -> 按 lastWatchTime/键合并
    // ------------------------------------------------------------------

    /**
     * 把本地历史序列化为 snapshot JSON。
     * 结构：{ "histories": [ ... ], "generatedAt": ... }
     */
    fun serializeHistories(histories: List<History>): String {
        val arr = JsonArray()
        histories.forEach { h ->
            val obj = JsonObject()
            obj.addProperty("key", h.key)
            obj.add("bangumiItem", h.bangumiItem.toJson())
            obj.addProperty("lastWatchEpisode", h.lastWatchEpisode)
            obj.addProperty("lastWatchEpisodeName", h.lastWatchEpisodeName)
            obj.addProperty("adapterName", h.adapterName)
            obj.addProperty("lastWatchTime", h.lastWatchTime)
            obj.addProperty("lastSrc", h.lastSrc)
            obj.addProperty("entryKind", h.entryKind)
            obj.addProperty("episodePageUrl", h.episodePageUrl)
            val progArr = JsonArray()
            h.progresses.values.forEach { p ->
                val po = JsonObject()
                po.addProperty("episode", p.episode)
                po.addProperty("road", p.road)
                po.addProperty("progressMs", p.progressMs)
                po.addProperty("updatedAtMs", p.updatedAtMs)
                progArr.add(po)
            }
            obj.add("progresses", progArr)
            arr.add(obj)
        }
        val root = JsonObject()
        root.addProperty("generatedAt", System.currentTimeMillis())
        root.add("histories", arr)
        return root.toString()
    }

    /**
     * 解析远端 snapshot JSON -> List<History>；无效/空返回 emptyList
     */
    fun deserializeHistories(json: String?): List<History> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val arr = root.getAsJsonArray("histories") ?: return emptyList()
            val list = mutableListOf<History>()
            arr.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val bangumi = try {
                    com.kazumi.port.data.model.BangumiItem.fromJson(o.getAsJsonObject("bangumiItem"))
                } catch (e: Exception) {
                    return@forEach
                }
                val progresses = mutableMapOf<Int, Progress>()
                val progArr = o.getAsJsonArray("progresses")
                if (progArr != null) {
                    progArr.forEach { pe ->
                        val po = pe.asJsonObject
                        val ep = po.get("episode")?.asInt ?: return@forEach
                        progresses[ep] = Progress(
                            episode = ep,
                            road = po.get("road")?.asInt ?: 0,
                            progressMs = po.get("progressMs")?.asLong ?: 0L,
                            updatedAtMs = po.get("updatedAtMs")?.asLong ?: 0L
                        )
                    }
                }
                list.add(
                    History(
                        key = o.get("key")?.asString ?: return@forEach,
                        bangumiItem = bangumi,
                        lastWatchEpisode = o.get("lastWatchEpisode")?.asInt ?: 0,
                        lastWatchEpisodeName = o.get("lastWatchEpisodeName")?.asString ?: "",
                        adapterName = o.get("adapterName")?.asString ?: "",
                        lastWatchTime = o.get("lastWatchTime")?.asLong ?: 0L,
                        lastSrc = o.get("lastSrc")?.asString ?: "",
                        entryKind = o.get("entryKind")?.asString ?: History.KIND_ONLINE,
                        episodePageUrl = o.get("episodePageUrl")?.asString ?: "",
                        progresses = progresses
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "deserializeHistories failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 合并历史：远端为准，但本地存在且 lastWatchTime 更新的条目保留本地。
     */
    fun mergeHistories(
        local: List<History>,
        remote: List<History>
    ): List<History> {
        val byKey = HashMap<String, History>()
        remote.forEach { byKey[it.key] = it }
        local.forEach { localH ->
            val remoteH = byKey[localH.key]
            if (remoteH == null || localH.lastWatchTime > remoteH.lastWatchTime) {
                byKey[localH.key] = localH
            }
        }
        return byKey.values.toList()
    }

    /**
     * 完整历史同步：下载远端 snapshot -> 与本地按 lastWatchTime 合并 -> 逐个 upsert 落库 -> 上传合并结果。
     * @param localProvider 返回本地历史（由调用方从 HistoryRepository 取）
     * @param localUpsert   把单条 History 写回本地（由调用方调 HistoryRepository.upsert）
     * @return 合并后的历史列表（null 表示通道未启用/未就绪）
     */
    suspend fun syncHistoryFull(
        localProvider: suspend () -> List<History>,
        localUpsert: suspend (History) -> Unit
    ): List<History>? {
        if (!isEnabled() || !isHistoryEnabled()) return null
        ensureReady()
        val remoteJson = downloadHistorySnapshot()
        val remote = deserializeHistories(remoteJson)
        val local = localProvider()
        val merged = mergeHistories(local, remote)
        merged.forEach { localUpsert(it) }
        uploadHistorySnapshot(serializeHistories(merged))
        return merged
    }

    /**
     * 上传本地历史快照（JSON）到远端 snapshot.json
     */
    suspend fun uploadHistorySnapshot(json: String): Boolean {
        val c = client ?: return false
        if (!isEnabled() || !isHistoryEnabled()) return false
        val ok = c.uploadBytes(json.toByteArray(), HISTORY_SNAPSHOT)
        if (ok) Log.i(TAG, "uploaded history snapshot")
        return ok
    }

    /**
     * 下载远端历史快照（不存在返回 null）
     */
    suspend fun downloadHistorySnapshot(): String? {
        val c = client ?: return null
        val text = c.downloadText(HISTORY_SNAPSHOT)
        if (text != null) Log.i(TAG, "downloaded history snapshot")
        return text
    }

    /**
     * 完整历史同步：下载远端 -> 本地合并（由调用方提供 merge 函数）-> 上传。
     * 保留旧签名，供启动时轻量合并使用；新增 syncHistoryFull 供需要落库的调用方使用。
     */
    suspend fun syncHistory(merge: (String?) -> String): String? {
        if (!isEnabled() || !isHistoryEnabled()) return null
        ensureReady()
        val remote = downloadHistorySnapshot()
        val merged = merge(remote)
        uploadHistorySnapshot(merged)
        return merged
    }

    // ------------------------------------------------------------------
    // 弹幕屏蔽同步（对应原版 syncDanmakuShield）
    // 完整闭环：下载远端 -> 合并 -> 落库 -> 上传
    // ------------------------------------------------------------------

    /** 屏蔽词 -> JSON 数组字符串 */
    fun serializeShieldRules(rules: List<String>): String {
        val arr = JsonArray()
        rules.forEach { arr.add(it) }
        return arr.toString()
    }

    /** JSON -> 屏蔽词列表；无效/空返回 emptyList */
    fun deserializeShieldRules(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JsonParser.parseString(json).asJsonArray
            val list = mutableListOf<String>()
            arr.forEach { if (it.isJsonPrimitive) list.add(it.asString) }
            list
        } catch (e: Exception) {
            Log.w(TAG, "deserializeShieldRules failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 完整屏蔽词同步：下载远端 -> 合并（远端有本地无则补充）-> 落库 -> 上传。
     * 本地有远端没有的规则保留（不删除，避免两台设备互相清空）。
     * @param localProvider 返回本地屏蔽词
     * @param localAdd      添加单条到本地
     * @return 合并后的完整屏蔽词列表（null 表示通道未启用/未就绪）
     */
    suspend fun syncDanmakuShieldFull(
        localProvider: suspend () -> List<String>,
        localAdd: suspend (String) -> Unit
    ): List<String>? {
        if (!isEnabled() || !isDanmakuShieldEnabled()) return null
        ensureReady()
        val remoteJson = downloadDanmakuShield()
        val remote = deserializeShieldRules(remoteJson)
        val local = localProvider()
        // 合并：远端带来、本地没有的 -> 添加；本地有、远端没有的 -> 保留本地（不删，防止误删）
        val merged = (local + remote).distinct()
        remote.forEach { if (it !in local) localAdd(it) }
        uploadDanmakuShield(serializeShieldRules(merged))
        return merged
    }

    suspend fun uploadDanmakuShield(json: String): Boolean {
        val c = client ?: return false
        if (!isEnabled() || !isDanmakuShieldEnabled()) return false
        val ok = c.uploadBytes(json.toByteArray(), "$DANMAKU_SHIELD_ROOT/shield.json")
        if (ok) Log.i(TAG, "uploaded danmaku shield")
        return ok
    }

    suspend fun downloadDanmakuShield(): String? {
        val c = client ?: return null
        return c.downloadText("$DANMAKU_SHIELD_ROOT/shield.json")
    }

    suspend fun syncDanmakuShield(merge: (String?) -> String): String? {
        if (!isEnabled() || !isDanmakuShieldEnabled()) return null
        ensureReady()
        val remote = downloadDanmakuShield()
        val merged = merge(remote)
        uploadDanmakuShield(merged)
        return merged
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private suspend fun ensureReady() {
        if (!initialized) {
            init()
        }
    }

    /** 重置（重新读取设置） */
    fun reset() {
        client = null
        initialized = false
    }
}