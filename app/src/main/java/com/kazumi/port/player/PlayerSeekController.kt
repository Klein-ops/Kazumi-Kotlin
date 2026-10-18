package com.kazumi.port.player

import androidx.media3.exoplayer.ExoPlayer

/**
 * 播放 seek 控制器（对应原版 player_seek_controller.dart）
 *
 * 支持：
 * - seekTo / seekBy（普通 seek，结束后回调 onSeekCompleted 以联动弹幕/同步播放）
 * - 交互式拖拽 seek（进度条拖动/手势拖动时暂停播放并预览目标位置，
 *   松手才真正 seek；拖到边缘取消则回到起始位置）
 */
class PlayerSeekController(
    private val playerProvider: () -> ExoPlayer?,
    private val pauseAction: () -> Unit,
    private val playAction: () -> Unit,
    private val onSeekCompleted: (enableSync: Boolean) -> Unit
) {

    /** 交互式 seek 会话 */
    class InteractiveSeekSession(
        val initialPosition: Long,
        val wasPlaying: Boolean,
        val player: ExoPlayer?
    ) {
        var target: Long = initialPosition
        var completion: Boolean? = null
    }

    private var interactiveSession: InteractiveSeekSession? = null
    private var seekTail: kotlinx.coroutines.Job? = null

    fun hasActiveInteractiveSeek(): Boolean = interactiveSession != null

    fun seekTo(targetMs: Long, enableSync: Boolean = true) {
        val player = playerProvider() ?: return
        val normalized = normalize(targetMs, player)
        // 原版用 _seekTail 串行化 seek；ExoPlayer 内部本身串行，这里直接调用
        player.seekTo(normalized)
        onSeekCompleted(enableSync)
    }

    fun seekBy(offsetMs: Long, enableSync: Boolean = true) {
        val player = playerProvider() ?: return
        seekTo(player.currentPosition + offsetMs, enableSync)
    }

    /** 开始交互式拖拽：记录起始位置与播放状态，并暂停播放 */
    fun beginInteractiveSeek() {
        val player = playerProvider() ?: return
        val previous = interactiveSession
        val wasPlaying = if (previous != null && isCurrent(previous)) {
            previous.wasPlaying
        } else {
            player.isPlaying
        }
        pauseAction()
        interactiveSession = InteractiveSeekSession(
            initialPosition = player.currentPosition,
            wasPlaying = wasPlaying,
            player = player
        )
    }

    /** 拖拽过程中更新目标位置（仅预览，不真正 seek），返回是否接受 */
    fun updateInteractiveSeek(targetMs: Long): Boolean {
        val session = interactiveSession ?: return false
        if (!isCurrent(session) || session.completion != null) return false
        val player = playerProvider() ?: return false
        session.target = normalize(targetMs, player)
        return true
    }

    /** 结束交互式拖拽：取消则回到起始位置（不 seek），否则 seek 到目标并恢复播放 */
    suspend fun finishInteractiveSeek(cancelled: Boolean): Boolean {
        val session = interactiveSession ?: return false
        session.completion = true
        return try {
            if (!isCurrent(session)) return false
            val player = playerProvider() ?: return false
            if (!cancelled) {
                seekTo(session.target, enableSync = false)
                if (!isCurrent(session)) return false
            }
            if (!cancelled || session.wasPlaying) {
                playAction()
            }
            isCurrent(session)
        } finally {
            if (interactiveSession === session) {
                interactiveSession = null
            }
        }
    }

    /** 使当前交互会话失效（如播放器切换） */
    fun invalidateInteractiveSeek() {
        interactiveSession = null
    }

    private fun normalize(targetMs: Long, player: ExoPlayer): Long {
        var ms = targetMs
        if (ms < 0) ms = 0
        val duration = player.duration
        if (duration > 0 && ms > duration) ms = duration
        return ms
    }

    private fun isCurrent(session: InteractiveSeekSession): Boolean =
        interactiveSession === session && playerProvider() === session.player
}