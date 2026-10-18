package com.kazumi.port.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.kazumi.port.data.repository.DanmakuApi
import com.kazumi.port.data.repository.DownloadRepository
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.network.HttpClientFactory
import com.kazumi.port.service.VideoSourceFormat
import com.kazumi.port.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放器初始化参数（对应原版 PlaybackInitParams）
 */
data class PlaybackInitParams(
    val videoUrl: String,
    val offset: Int = 0,
    val isLocalPlayback: Boolean = false,
    val bangumiId: Int = 0,
    val pluginName: String = "",
    val episode: Int = 1,
    val danmakuEpisodeNumber: Int = 0,
    val episodeTitle: String = "",
    val referer: String = "",
    val httpHeaders: Map<String, String> = emptyMap(),
    val adBlockerEnabled: Boolean = false,
    val currentRoad: Int = 0,
    val coverUrl: String? = null,
    val bangumiName: String = "",
    val videoSourceFormat: VideoSourceFormat = VideoSourceFormat.AUTO
)

/**
 * 播放器状态（供 Compose 观察）
 */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val errorMessage: String? = null,
    val videoUrl: String = "",
    val isLocalPlayback: Boolean = false,
    val episodeTitle: String = "",
    val bangumiName: String = "",
    val coverUrl: String? = null
)

/**
 * 媒体播放器（Media3 ExoPlayer 封装）
 *
 * 对应原版 player_controller.dart + player_playback_controller.dart 的 Kotlin 侧整合。
 * 第 7 步补强后能力：
 * - 在线 HLS / 直链 mp4（带 Referer/UA）/ 本地文件
 * - 倍速（默认列表 0.25~3.0）/ 音量 / 静音 / seek（含交互式拖拽）/ 暂停 / 播放
 * - 弹幕加载与调度（PlayerDanmakuController）
 * - 截图（Android 直接保存相册）
 * - 同步播放（Syncplay）
 * - 面板状态（比例/亮度/锁屏/控制条显隐/各类 HUD）
 */
class PlayerController(
    private val context: Context,
    private val settings: SettingsManager,
    private val downloadRepository: DownloadRepository
) {

    private val tag = "PlayerController"

    private var exoPlayer: ExoPlayer? = null

    /** 当前播放参数（用于换集/换线路） */
    @Volatile
    var currentParams: PlaybackInitParams? = null
        private set

    /** 播放速度（记住当前值，供 HUD/菜单显示） */
    @Volatile
    var playbackSpeed: Float = 1.0f
        private set

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * 当前 ExoPlayer 实例（供 Compose 观察）。
     *
     * 必须做成 StateFlow：PlayerView 绑定播放器这件事不能只靠 AndroidView 的
     * update 回调——如果 update 里读不到 Compose 状态，播放器实例从 null 变成
     * 非 null 时不会触发任何失效，表现为"有声音、能拖进度、但永远没有画面"
     * （ExoPlayer 播音频不需要 Surface，视频渲染器则因拿不到 Surface 而不初始化）。
     */
    private val _playerState = MutableStateFlow<ExoPlayer?>(null)
    val playerState: StateFlow<ExoPlayer?> = _playerState.asStateFlow()

    /** 面板状态（比例/锁屏/HUD 等） */
    private val _panelState = MutableStateFlow(PlayerPanelState())
    val panelState: StateFlow<PlayerPanelState> = _panelState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 弹幕控制器 */
    val danmaku: PlayerDanmakuController = PlayerDanmakuController(
        danmakuApi = DanmakuApi(settings = settings),
        settings = settings,
        downloadRepository = downloadRepository
    )

    /** 截图控制器 */
    val screenshots: PlayerScreenshotController = PlayerScreenshotController(context)

    /** 同步播放控制器（须在 seeking 前声明，seeking 回调引用它） */
    val syncplay: PlayerSyncPlayController = PlayerSyncPlayController(
        settings = settings,
        bangumiIdProvider = { currentParams?.bangumiId ?: 0 },
        currentEpisodeProvider = { currentParams?.episode ?: 1 },
        currentRoadProvider = { currentParams?.currentRoad ?: 0 },
        playingProvider = { exoPlayer?.isPlaying ?: false },
        currentPositionMsProvider = { exoPlayer?.currentPosition ?: 0L },
        playerPositionMsProvider = { exoPlayer?.currentPosition ?: 0L },
        durationMsProvider = { exoPlayer?.duration ?: 0L },
        pauseAction = { enableSync -> pause(enableSync = enableSync) },
        playAction = { enableSync -> play(enableSync = enableSync) },
        seekAction = { targetMs, enableSync -> seekTo(targetMs, enableSync = enableSync) },
        changeEpisodeAction = { _, _, _ -> }
    )

    /** seek 控制器 */
    val seeking: PlayerSeekController = PlayerSeekController(
        playerProvider = { exoPlayer },
        pauseAction = { pause(enableSync = false) },
        playAction = { play(enableSync = false) },
        onSeekCompleted = { enableSync ->
            if (syncplay.hasSession) {
                syncplay.setCurrentPosition()
                if (enableSync) syncplay.requestSync(doSeek = true)
            }
        }
    )

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            FileLogger.i(tag, "playbackState=$stateName")
            val loading = playbackState == Player.STATE_BUFFERING
            _uiState.update { it.copy(isLoading = loading) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(tag, "playback error: ${error.message}", error)
            FileLogger.e(
                tag,
                "playback error: code=${error.errorCodeName}(${error.errorCode}) " +
                    "msg=${error.message} cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}"
            )
            _uiState.update {
                it.copy(
                    errorMessage = error.errorCodeName,
                    isLoading = false
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                _uiState.update { it.copy(durationMs = exoPlayer?.duration ?: 0L) }
            }
        }
    }

    /**
     * 渲染诊断：黑屏排查需要区分"没解码"和"解码了但没显示"，
     * onRenderedFirstFrame 只有真正出帧才会回调。
     */
    private val analyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long
        ) {
            FileLogger.i(tag, "first frame rendered: output=${output.javaClass.simpleName} renderTime=${renderTimeMs}ms")
        }

        override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
            FileLogger.i(
                tag,
                "videoSize=${videoSize.width}x${videoSize.height} ratio=${videoSize.pixelWidthHeightRatio}"
            )
        }
    }

    /**
     * 初始化并开始播放
     * 对应原版 PlayerController.init()
     */
    suspend fun init(params: PlaybackInitParams): Boolean {
        release()

        currentParams = params
        _uiState.value = PlayerUiState(
            isLoading = true,
            videoUrl = params.videoUrl,
            isLocalPlayback = params.isLocalPlayback,
            episodeTitle = params.episodeTitle,
            bangumiName = params.bangumiName,
            coverUrl = params.coverUrl
        )
        _panelState.value = PlayerPanelState()

        // 读默认设置
        playbackSpeed = settings.defaultPlaySpeed().toFloat()
        _uiState.update { it.copy(playbackSpeed = playbackSpeed) }
        _panelState.value = _panelState.value.copy(
            aspectRatioMode = PlayerAspectRatio.fromStorageValue(
                settings.getInt(SettingsManager.Player.DEFAULT_ASPECT_RATIO_TYPE, 1)
            )
        )

        val player = ExoPlayer.Builder(context).build().apply {
            addListener(playerListener)
            addAnalyticsListener(analyticsListener)
            setPlayWhenReady(true)
            repeatMode = Player.REPEAT_MODE_OFF
            playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed)
        }
        exoPlayer = player
        _playerState.value = player

        return try {
            val mediaSource = buildMediaSource(params)
            FileLogger.i(
                tag,
                "init: url=${params.videoUrl.take(160)} format=${params.videoSourceFormat} " +
                    "offset=${params.offset} local=${params.isLocalPlayback} " +
                    "mediaSource=${mediaSource.javaClass.simpleName}"
            )
            player.setMediaSource(mediaSource)
            player.prepare()
            if (params.offset > 0) {
                player.seekTo(params.offset * 1000L)
            }
            player.play()
            _uiState.update { it.copy(isLoading = false) }
            true
        } catch (e: Exception) {
            Log.w(tag, "failed to init playback: ${e.message}")
            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
            false
        }
    }

    private fun buildMediaSource(params: PlaybackInitParams): MediaSource {
        val url = params.videoUrl
        val dataSourceFactory = buildHttpDataSourceFactory(params)

        // 本地文件：统一走 Progressive（ExoPlayer 自动识别容器）
        if (params.isLocalPlayback || url.startsWith("/") || url.startsWith("file://")) {
            val localUri = if (url.startsWith("file://")) Uri.parse(url) else Uri.fromFile(java.io.File(url))
            return ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(localUri))
        }

        val uri = Uri.parse(url)
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(params.episodeTitle.ifEmpty { params.bangumiName })
                    .setArtist(params.bangumiName)
                    .build()
            )
            .build()

        // 显式指定的格式优先（规则解析时已知 HLS），否则交给 DefaultMediaSourceFactory
        // 按内容自动探测（HLS/DASH/MP4 等），不再手搓 .m3u8 后缀判断——
        // 原版 media_kit 也是自动探测，手搓后缀正是 PARSING_CONTAINER_UNSUPPORTED 的根因。
        return when (params.videoSourceFormat) {
            VideoSourceFormat.HLS ->
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            VideoSourceFormat.AUTO -> {
                val factory = DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(dataSourceFactory)
                factory.createMediaSource(mediaItem)
            }
        }
    }

    private fun buildHttpDataSourceFactory(params: PlaybackInitParams): HttpDataSource.Factory {
        // 播放器网络复用全局 OkHttp（media3-datasource-okhttp 标准桥接）：
        // 自动带随机浏览器 UA + 代理设置 + 连接复用，与原版一致性最高
        //（原版 media_kit 也走应用网络栈：随机 UA + http-proxy）。
        val factory = OkHttpDataSource.Factory(HttpClientFactory.getClient())
        // 注意：setDefaultRequestProperties 是"整体替换"而不是合并，
        // 以前在循环里逐个 set 会让最终只剩最后一个头（UA 或 Referer 被丢掉）。
        val headers = LinkedHashMap<String, String>()
        headers.putAll(params.httpHeaders)
        if (params.referer.isNotBlank() &&
            headers.keys.none { it.equals("referer", ignoreCase = true) }
        ) {
            headers["referer"] = params.referer
        }
        if (headers.isNotEmpty()) {
            factory.setDefaultRequestProperties(headers)
        }
        FileLogger.i(tag, "http headers=${headers.keys.joinToString(",")}")
        return factory
    }

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    fun play(enableSync: Boolean = true) {
        val player = exoPlayer ?: return
        danmaku.setDanmakuEnabled(true)
        player.play()
        _uiState.update { it.copy(isPlaying = true) }
        if (syncplay.hasSession && enableSync) {
            syncplay.setCurrentPosition()
            syncplay.requestSync()
        }
    }

    fun pause(enableSync: Boolean = true) {
        val player = exoPlayer ?: return
        danmaku.setDanmakuEnabled(false)
        player.pause()
        _uiState.update { it.copy(isPlaying = false) }
        if (syncplay.hasSession && enableSync) {
            syncplay.setCurrentPosition()
            syncplay.requestSync()
        }
    }

    fun playOrPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) pause() else play()
    }

    fun seekTo(positionMs: Long, enableSync: Boolean = true) {
        seeking.seekTo(positionMs, enableSync = enableSync)
    }

    fun seekBy(offsetMs: Long, enableSync: Boolean = true) {
        seeking.seekBy(offsetMs, enableSync = enableSync)
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        exoPlayer?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
        // 弹幕跟随倍速由渲染层读取
        scope.launch { danmaku.danmakuDurationForSpeed(speed) }
    }

    fun setVolume(volume: Float) {
        val player = exoPlayer ?: return
        player.volume = volume.coerceIn(0f, 1f)
        _uiState.update {
            it.copy(
                volume = player.volume,
                isMuted = player.volume == 0f
            )
        }
    }

    fun toggleMute() {
        val player = exoPlayer ?: return
        if (player.volume > 0f) {
            player.volume = 0f
        } else {
            player.volume = 1f
        }
        _uiState.update {
            it.copy(
                volume = player.volume,
                isMuted = player.volume == 0f
            )
        }
    }

    /** 当前播放位置（ms），由 Compose 轮询或 Listener 驱动 */
    fun currentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    fun duration(): Long = exoPlayer?.duration ?: 0L

    fun isPlaying(): Boolean = exoPlayer?.isPlaying ?: false

    /** 暴露 ExoPlayer 给 Compose AndroidView（PlayerView.player 赋值用） */
    fun exoPlayerCompat(): ExoPlayer? = exoPlayer

    /**
     * 定时刷新进度（供 Compose LaunchedEffect 调用）
     */
    suspend fun tickProgress() {
        val player = exoPlayer ?: return
        _uiState.update {
            it.copy(
                currentPositionMs = player.currentPosition,
                durationMs = player.duration
            )
        }
    }

    // ------------------------------------------------------------------
    // 面板控制
    // ------------------------------------------------------------------

    fun setPanel(transform: (PlayerPanelState) -> PlayerPanelState) {
        _panelState.update(transform)
    }

    fun setAspectRatio(mode: PlayerAspectRatio) {
        _panelState.update { it.copy(aspectRatioMode = mode) }
    }

    fun setBrightness(value: Double) {
        _panelState.update { it.copy(brightness = value.coerceIn(0.0, 1.0)) }
    }

    fun toggleLockPanel() {
        _panelState.update { it.copy(lockPanel = !it.lockPanel) }
    }

    fun setShowVideoController(show: Boolean) {
        _panelState.update { it.copy(showVideoController = show) }
    }

    // ------------------------------------------------------------------
    // 弹幕
    // ------------------------------------------------------------------

    /** 加载当前剧集弹幕（对应原版 fetchDanmaku） */
    suspend fun fetchDanmaku(): PlayerDanmakuController.DanmakuLoadResult {
        val params = currentParams ?: return PlayerDanmakuController.DanmakuLoadResult.failed(0)
        danmaku.beginDanmakuLoad()
        val result = danmaku.fetchDanmaku(
            bangumiId = params.bangumiId,
            pluginName = params.pluginName,
            episode = params.episode,
            isLocalPlayback = params.isLocalPlayback
        )
        val enableByDefault = settings.getBoolean(SettingsManager.Danmaku.ENABLED_BY_DEFAULT, false)
        danmaku.applyDanmakuLoad(result, enableDanmaku = result.hasDanmakus && enableByDefault)
        return result
    }

    // ------------------------------------------------------------------
    // 同步播放
    // ------------------------------------------------------------------

    fun createSyncPlayRoom(room: String, username: String) {
        scope.launch {
            val ok = syncplay.createRoom(room, username)
            if (ok) {
                syncplay.setPlayingBangumi()
            }
        }
    }

    fun exitSyncPlayRoom() {
        syncplay.exitRoom()
    }

    fun sendSyncPlayChatMessage(message: String) {
        syncplay.sendChatMessage(message)
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    /** 截图并保存到相册（Android 路径） */
    suspend fun captureScreenshot(captureBitmap: () -> android.graphics.Bitmap?): Boolean =
        screenshots.captureAndSave(captureBitmap)

    // ------------------------------------------------------------------
    // 释放资源
    // ------------------------------------------------------------------

    /** 释放资源 */
    fun release() {
        val player = exoPlayer
        if (player != null) {
            player.removeListener(playerListener)
            player.release()
        }
        exoPlayer = null
        _playerState.value = null
        _uiState.value = PlayerUiState()
        _panelState.value = PlayerPanelState()
    }

    /** 彻底销毁（页面退出） */
    fun dispose() {
        release()
        syncplay.dispose()
        scope.cancel()
    }
}