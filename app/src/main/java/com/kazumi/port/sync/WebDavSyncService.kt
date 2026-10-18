package com.kazumi.port.sync

import android.content.Context
import android.util.Log
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
 * 数据格式与原版保持一致（snapshot.json + 每设备 jsonl 事件日志）。
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
    // 简化实现：把本地收藏 JSON 上传到远端 collectibles.tmp，
    // 远端存在则下载合并后回传。
    // ------------------------------------------------------------------

    /**
     * 上传本地收藏快照（JSON 数组）到远端
     */
    suspend fun uploadCollectibles(json: String): Boolean {
        val c = client ?: return false
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
     * 完整同步：下载远端 → 本地合并（由调用方提供 merge 函数）→ 上传。
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
    // 历史同步（对应原版 syncHistory，简化为 snapshot.json 直传/直取）
    // ------------------------------------------------------------------

    /**
     * 上传本地历史快照（JSON）到远端 snapshot.json
     */
    suspend fun uploadHistorySnapshot(json: String): Boolean {
        val c = client ?: return false
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
     * 完整历史同步：下载远端 → 本地合并 → 上传。
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
    // 弹幕屏蔽同步（对应原版 syncDanmakuShield，简化：单文件）
    // ------------------------------------------------------------------

    suspend fun uploadDanmakuShield(json: String): Boolean {
        val c = client ?: return false
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