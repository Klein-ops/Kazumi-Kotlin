package com.kazumi.port.player

import com.kazumi.port.data.repository.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Syncplay 房间控制器（对应原版 player_syncplay_controller.dart）
 *
 * 负责：
 * - 创建/加入房间（连接服务器 -> Hello -> 就绪）
 * - 播放状态上报（播放/暂停/seek -> State 消息）
 * - 接收远端状态（暂停/播放/seek/换集 -> 回调驱动本地播放器）
 * - 聊天室消息收发
 * - 断开/退出房间
 */
class PlayerSyncPlayController(
    private val settings: SettingsManager,
    private val bangumiIdProvider: () -> Int,
    private val currentEpisodeProvider: () -> Int,
    private val currentRoadProvider: () -> Int,
    private val playingProvider: () -> Boolean,
    private val currentPositionMsProvider: () -> Long,
    private val playerPositionMsProvider: () -> Long,
    private val durationMsProvider: () -> Long,
    private val pauseAction: (enableSync: Boolean) -> Unit,
    private val playAction: (enableSync: Boolean) -> Unit,
    private val seekAction: (targetMs: Long, enableSync: Boolean) -> Unit,
    private val changeEpisodeAction: (episode: Int, currentRoad: Int, offset: Int) -> Unit
) {

    data class SyncPlayState(
        val connected: Boolean = false,
        val room: String = "",
        val clientRtt: Int = 0,
        /** 连接中（已创建 client 但尚未进入房间） */
        val connecting: Boolean = false,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(SyncPlayState())
    val state: StateFlow<SyncPlayState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var client: SyncplayClient? = null

    private val officialEndPoints = setOf("syncplay.pl:8995", "syncplay.pl:8996", "syncplay.pl:8997")

    val hasSession: Boolean get() = client != null

    /** 发送聊天（给 UI 用） */
    fun sendChatMessage(message: String) {
        client?.sendChatMessage(message)
    }

    /** 上报当前播放位置与暂停状态 */
    fun setCurrentPosition(forceSyncPlaying: Boolean? = null, forceSyncPosition: Double? = null) {
        val c = client ?: return
        val playing = forceSyncPlaying ?: playingProvider()
        c.setPaused(!playing)
        val pos = forceSyncPosition ?: if (
            kotlin.math.abs(currentPositionMsProvider() - playerPositionMsProvider()) > 2000
        ) {
            currentPositionMsProvider() / 1000.0
        } else {
            playerPositionMsProvider() / 1000.0
        }
        c.setPosition(pos)
    }

    /** 设置当前播放的番剧（换集/开局时调用） */
    fun setPlayingBangumi(forceSyncPlaying: Boolean? = null, forceSyncPosition: Double? = null) {
        val c = client ?: return
        try {
            c.setSyncPlayPlaying(
                "${bangumiIdProvider()}[${currentEpisodeProvider()}]",
                10800.0,
                220514438
            )
            if (client !== c) return
            setCurrentPosition(forceSyncPlaying = forceSyncPlaying, forceSyncPosition = forceSyncPosition)
            c.sendSyncPlaySyncRequest(null)
        } catch (e: Exception) {
            // 忽略（best-effort）
        }
    }

    /** 请求同步（seek 完成 / 播放状态变化后调用） */
    fun requestSync(doSeek: Boolean? = null) {
        try {
            client?.sendSyncPlaySyncRequest(doSeek)
        } catch (e: Exception) {
            // best-effort
        }
    }

    /**
     * 创建/加入房间
     */
    suspend fun createRoom(
        room: String,
        username: String
    ): Boolean {
        val previous = client
        client = null
        _state.value = SyncPlayState(connecting = true)
        previous?.disconnect()

        val endPoint = settings.getString(SettingsManager.Player.SYNC_PLAY_END_POINT, "syncplay.pl:8996")
        val parsed = parseEndPoint(endPoint) ?: run {
            _state.value = SyncPlayState(connecting = false, errorMessage = "SyncPlay: 服务器地址不合法 $endPoint")
            return false
        }
        val enableTLS = "${parsed.host.lowercase()}:${parsed.port}" in officialEndPoints

        val c = SyncplayClient(parsed.host, parsed.port)
        client = c
        return try {
            withContext(Dispatchers.IO) { c.connect(enableTLS = enableTLS) }
            if (client !== c) {
                c.disconnect()
                return false
            }
            c.joinRoom(room, username)
            _state.value = SyncPlayState(connected = true, room = room)
            true
        } catch (e: Exception) {
            if (client === c) {
                client = null
                c.disconnect()
            }
            _state.value = SyncPlayState(
                connected = false,
                connecting = false,
                errorMessage = "SyncPlay: 连接失败 ${e.message}"
            )
            false
        }
    }

    /** 退出房间 */
    fun exitRoom() {
        val c = client
        client = null
        _state.value = SyncPlayState()
        c?.disconnect()
    }

    fun dispose() {
        exitRoom()
        scope.cancel()
    }

    /** 解析 host:port */
    private fun parseEndPoint(endPoint: String): SyncPlayEndPoint? {
        val input = endPoint.trim()
        if (input.isEmpty()) return null
        val idx = input.lastIndexOf(':')
        if (idx <= 0 || idx == input.length - 1) return null
        val host = input.substring(0, idx)
        val port = input.substring(idx + 1).toIntOrNull() ?: return null
        if (port <= 0 || port > 65535) return null
        return SyncPlayEndPoint(host, port)
    }
}

data class SyncPlayEndPoint(val host: String, val port: Int)