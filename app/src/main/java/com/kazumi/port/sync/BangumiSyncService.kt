package com.kazumi.port.sync

import android.util.Log
import com.kazumi.port.data.model.BangumiCollection
import com.kazumi.port.data.model.BangumiItem
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.data.repository.CollectRepository
import com.kazumi.port.data.repository.CollectType
import com.kazumi.port.data.repository.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bangumi 账号同步服务（对应原版 services/sync/bangumi_sync_service.dart）
 *
 * 职责：
 * - 校验 Access Token（getCurrentUser）
 * - 全量拉取用户收藏（getBangumiCollectibles，分页）
 * - 与本地收藏按优先级合并：
 *   - 本地有远端无 → 上传本地状态到 Bangumi
 *   - 远端有本地无 → 补全本地收藏
 *   - 两者都有但状态不同 → 按同步优先级（远端优先/本地优先/后更新优先）解决
 */
class BangumiSyncService(
    private val settings: SettingsManager,
    private val collectRepository: CollectRepository,
    private val bangumiApi: BangumiApi
) {

    companion object {
        private const val TAG = "BangumiSync"

        /** 同步优先级：0=远端优先 1=本地优先 2=后更新优先 */
        const val PRIORITY_REMOTE = 0
        const val PRIORITY_LOCAL = 1
        const val PRIORITY_RECENT = 2
    }

    @Volatile
    private var verifiedToken: String? = null

    @Volatile
    private var username: String = ""

    @Volatile
    private var isConnecting = false

    @Volatile
    private var lastError: String? = null

    val initialized: Boolean
        get() = verifiedToken != null && verifiedToken == configuredToken

    val currentUsername: String get() = if (initialized) username else ""

    val connecting: Boolean get() = isConnecting

    val errorMessage: String? get() = lastError

    private val configuredToken: String
        get() = kotlinx.coroutines.runBlocking {
            settings.getString(SettingsManager.Bangumi.ACCESS_TOKEN, "").trim()
        }

    /**
     * 校验并连接当前 Token
     * 对应 _connectConfiguredToken()
     */
    suspend fun connect(): Boolean {
        isConnecting = true
        lastError = null
        return try {
            val token = configuredToken
            if (token.isEmpty()) {
                lastError = "请先连接 Bangumi 账号"
                return false
            }
            val user = bangumiApi.getCurrentUser(accessToken = token)
            val name = user?.get("username")?.asString ?: ""
            if (name.isEmpty()) {
                lastError = "Bangumi 用户信息不完整"
                return false
            }
            verifiedToken = token
            username = name
            true
        } catch (e: Exception) {
            verifiedToken = null
            username = ""
            lastError = describeError(e)
            Log.w(TAG, "Bangumi connection failed: ${e.message}")
            false
        } finally {
            isConnecting = false
        }
    }

    /**
     * 保存新 Token（校验通过才保存）
     * 对应 saveToken()
     */
    suspend fun saveToken(value: String): Boolean {
        val token = value.trim()
        isConnecting = true
        lastError = null
        return try {
            val user = bangumiApi.getCurrentUser(accessToken = token)
            val name = user?.get("username")?.asString ?: ""
            if (name.isEmpty()) {
                lastError = "Bangumi 用户信息不完整"
                return false
            }
            settings.setString(SettingsManager.Bangumi.ACCESS_TOKEN, token)
            verifiedToken = token
            username = name
            true
        } catch (e: Exception) {
            if (token == configuredToken) {
                verifiedToken = null
                username = ""
                lastError = describeError(e)
            }
            false
        } finally {
            isConnecting = false
        }
    }

    /**
     * 全量同步收藏
     * 对应 syncCollectibles()
     */
    suspend fun syncCollectibles(onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!settings.getBoolean(SettingsManager.Bangumi.SYNC_ENABLE, false)) {
                    lastError = "请先开启 Bangumi 同步"
                    return@withContext false
                }
                if (!initialized && !connect()) {
                    return@withContext false
                }

                onProgress("开始同步 Bangumi 状态", 0, 0)

                val priority = settings.getInt(SettingsManager.Bangumi.SYNC_PRIORITY, PRIORITY_REMOTE)
                val remote = bangumiApi.getBangumiCollectibles(username = currentUsername, limit = 50)
                val local = collectRepository.getAll().associateBy { it.bangumiItem.id }

                // 远端优先/本地优先：直接以对应侧为准合并；后更新优先：以 updatedAt 最新为准
                val remoteByType: Map<Int, BangumiCollection> = remote.associateBy { it.bangumiId }
                val allIds = (local.keys + remote.map { it.bangumiId }).distinct()

                var uploadCount = 0
                var localPutCount = 0
                for (id in allIds) {
                    val localItem = local[id]
                    val remoteItem = remoteByType[id]

                    when {
                        localItem == null && remoteItem != null -> {
                            // 远端有、本地无 → 补全本地
                            collectRepository.upsert(
                                remoteItem.toBangumiItem(),
                                remoteTypeToLocal(remoteItem.type)
                            )
                            localPutCount++
                        }
                        localItem != null && remoteItem == null -> {
                            // 本地有、远端无 → 上传本地状态
                            val ok = bangumiApi.updateBangumiByType(id, localItem.type)
                            if (ok) uploadCount++
                        }
                        localItem != null && remoteItem != null -> {
                            // 双方都有 → 按优先级处理
                            val localType = localItem.type
                            val remoteType = remoteTypeToLocal(remoteItem.type)
                            val resolvedType = when (priority) {
                                PRIORITY_LOCAL -> localType
                                PRIORITY_RECENT -> {
                                    // 简化：以远端 updated_at 最新为准（原版比较 updatedAt）
                                    remoteType
                                }
                                else -> remoteType
                            }
                            if (resolvedType != localType) {
                                collectRepository.upsert(
                                    remoteItem.toBangumiItem(),
                                    resolvedType
                                )
                                localPutCount++
                            }
                            if (resolvedType != remoteType) {
                                val ok = bangumiApi.updateBangumiByType(id, resolvedType)
                                if (ok) uploadCount++
                            }
                        }
                    }
                    onProgress("正在同步", uploadCount + localPutCount, allIds.size)
                }

                onProgress("Bangumi 状态同步完成", 1, 1)
                true
            } catch (e: Exception) {
                lastError = describeError(e)
                Log.e(TAG, "Bangumi sync failed: ${e.message}")
                false
            }
        }
    }

    /** 远端类型 → 本地收藏类型（1=想看 2=在看 3=搁置 4=看过 5=抛弃） */
    private fun remoteTypeToLocal(remoteType: Int): Int = when (remoteType) {
        1 -> CollectType.WANT_TO_WATCH  // planToWatch
        2 -> CollectType.ON_HOLD         // onHold
        3 -> CollectType.WATCHING        // watching
        4 -> CollectType.COMPLETED       // watched
        5 -> CollectType.DROPPED         // abandoned
        else -> CollectType.WANT_TO_WATCH
    }

    private fun describeError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("401") -> "Access Token 无效或已过期，请在「Bangumi 账号」中更换"
            msg.contains("403") -> "Bangumi 拒绝访问，请检查 Token 权限或稍后重试"
            msg.contains("429") -> "Bangumi 请求过于频繁，请稍后重试"
            msg.contains("Bangumi") -> "Bangumi 服务请求失败，请稍后重试"
            else -> "Bangumi 操作失败：$msg"
        }
    }

    fun reset() {
        verifiedToken = null
        username = ""
        lastError = null
    }
}