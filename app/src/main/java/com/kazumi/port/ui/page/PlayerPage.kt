package com.kazumi.port.ui.page

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewGroup
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.media3.ui.PlayerView
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.player.PlaybackInitParams
import com.kazumi.port.player.PlayerAspectRatio
import com.kazumi.port.player.PlayerController
import com.kazumi.port.service.VideoSourceFormat
import com.kazumi.port.service.WebViewVideoSourceService
import com.kazumi.port.ui.components.DanmakuOverlay
import com.kazumi.port.util.FileLogger
import com.kazumi.port.util.DanmakuFeature
import com.kazumi.port.ui.components.DanmakuSourceSheet
import com.kazumi.port.ui.components.PlayerAdjustmentHud
import com.kazumi.port.ui.components.PlayerAdjustmentHudType
import com.kazumi.port.ui.components.PlayerSeekHud
import com.kazumi.port.ui.components.PlayerSpeedHud
import com.kazumi.port.ui.components.SyncPlaySheet
import com.kazumi.port.ui.components.rememberToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 可选倍速（对应原版 defaultPlaySpeedList） */
private val playSpeedList = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f, 3.0f)

/**
 * 播放页（对应原版 player_item.dart / video_page.dart）
 * 第 7 步补强：弹幕 / 截图 / 倍速菜单 / 手势（左右 seek、上下亮度音量）/ 同步播放 / 视频比例 / 锁屏
 */
@Composable
fun PlayerPage(
    navController: NavHostController,
    bangumiId: Int,
    pluginName: String,
    src: String,
    episode: Int,
    road: Int,
    isLocal: Boolean,
    roadsJson: String = ""
) {
    val context = LocalContext.current
    val app = remember { KazumiApp.get() }
    FileLogger.i("PlayerResolve", "PlayerPage 组合开始: src=${src.take(100)} plugin=$pluginName ep=$episode road=$road isLocal=$isLocal")
    val playerController = remember {
        PlayerController(context, app.settings, app.downloadRepository)
    }
    FileLogger.d("PlayerResolve", "PlayerController 已创建")
    val uiState by playerController.uiState.collectAsState()
    // 播放器实例必须作为 Compose 状态来读：实例从 null 变成非 null 时要触发重组，
    // 否则 PlayerView 永远拿不到 player（没 Surface → 只有声音没有画面）
    val exoPlayer by playerController.playerState.collectAsState()
    val panelState by playerController.panelState.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    // 选集切换：路由携带的线路 JSON（DetailPage 传来），解析出 Road 列表
    val roads = remember(roadsJson) {
        if (roadsJson.isEmpty()) emptyList()
        else runCatching {
            val arr = com.google.gson.JsonParser.parseString(roadsJson).asJsonArray
            arr.mapNotNull { el ->
                if (el.isJsonObject) com.kazumi.port.data.model.Road.fromJson(el.asJsonObject) else null
            }
        }.getOrDefault(emptyList())
    }
    var showEpisodeSheet by remember { mutableStateOf(false) }

    var resolvedLocalPath by remember { mutableStateOf<String?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    // 视频源解析服务（复用单个 WebView 实例）
    val videoSourceService = remember {
        WebViewVideoSourceService(context.applicationContext)
    }
    // 视频源解析状态（在线播放先 WebView 解析真实媒体 URL）
    var isResolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var resolveTick by remember { mutableStateOf(0) }

    // 弹幕状态
    val danmakuOn by playerController.danmaku.danmakuOn.collectAsState()
    val danmakuLoading by playerController.danmaku.danmakuLoading.collectAsState()
    var danmakuGeneration by remember { mutableIntStateOf(0) }
    var currentDanmakus by remember { mutableStateOf<List<DanmakuEntry>>(emptyList()) }
    var danmakuDurationSec by remember { mutableDoubleStateOf(8.0) }
    var danmakuArea by remember { mutableFloatStateOf(0.5f) }
    var danmakuFontSize by remember { mutableIntStateOf(24) }
    var danmakuFontWeight by remember { mutableIntStateOf(4) }
    var danmakuBorder by remember { mutableStateOf(true) }
    var danmakuBorderSize by remember { mutableFloatStateOf(1.0f) }
    var danmakuOpacity by remember { mutableFloatStateOf(0.8f) }
    var danmakuLineHeight by remember { mutableFloatStateOf(1.0f) }
    var danmakuShowScroll by remember { mutableStateOf(true) }
    var danmakuShowTop by remember { mutableStateOf(true) }
    var danmakuShowBottom by remember { mutableStateOf(true) }
    var danmakuMassive by remember { mutableStateOf(false) }

    // 弹幕批次：每次取到"新弹幕"就自增，渲染层据此消费，避免同秒重复/漏秒
    var danmakuBatch by remember { mutableIntStateOf(0) }

    // 番剧名：在线播放时路由只带 bangumiId，这里按 id 补名字，供弹幕自动匹配使用
    // 取名失败会静默导致 B 站弹幕匹配拿空关键词 → 永远匹配不到；这里带日志 + 最多 3 次重试
    var bangumiTitle by remember(bangumiId) { mutableStateOf("") }
    LaunchedEffect(bangumiId) {
        if (isLocal || bangumiId <= 0 || bangumiTitle.isNotEmpty()) return@LaunchedEffect
        FileLogger.i("Danmaku", "补名开始: bgm=$bangumiId")
        var name = ""
        for (attempt in 1..3) {
            val item = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    BangumiApi(BangumiClient(app.settings)).getBangumiInfoByID(bangumiId)
                }.getOrNull()
            }
            name = item?.displayName().orEmpty()
            if (name.isNotBlank()) break
            FileLogger.w("Danmaku", "补名尝试 $attempt/3 未取到名字: bgm=$bangumiId item=${item != null}")
            if (attempt < 3) delay(2_000)
        }
        FileLogger.i("Danmaku", "补名完成: bgm=$bangumiId name=\"$name\"")
        if (name.isNotBlank()) bangumiTitle = name
    }

    // 手势/HUD 状态
    var showSeekHud by remember { mutableStateOf(false) }
    var seekHudPosition by remember { mutableLongStateOf(0L) }
    var seekHudDirection by remember { mutableIntStateOf(0) }
    var seekCancelPending by remember { mutableStateOf(false) }
    var showVolumeHud by remember { mutableStateOf(false) }
    var showBrightnessHud by remember { mutableStateOf(false) }
    var hudVolume by remember { mutableFloatStateOf(80f) }
    var hudBrightness by remember { mutableFloatStateOf(0.5f) }
    var brightnessVolumeGesture by remember { mutableStateOf(true) }

    // 面板
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var aspectMenuOpen by remember { mutableStateOf(false) }

    // Sheet
    var showDanmakuSource by remember { mutableStateOf(false) }
    var showSyncPlay by remember { mutableStateOf(false) }

    // 截图
    var screenshotCapturing by remember { mutableStateOf(false) }

    // 手势 seek 会话
    var seekStartPos by remember { mutableLongStateOf(0L) }
    // 手势拖动累计增量：detectHorizontalDragGestures 每次回调的是"单次事件的瞬时增量"，
    // 不是从起点开始的累计值。若不自己累加，seekHudPosition 每次只在起点附近摆动，
    // 表现为 HUD 永远显示 ±0 秒、拖动松手后几乎不跳转。
    var seekDragAccum by remember { mutableLongStateOf(0L) }
    var seekWasPlaying by remember { mutableStateOf(false) }
    var interactiveSeeking by remember { mutableStateOf(false) }

    // 底部进度条拖动状态（拖动时用本地值，松手才真正 seek）
    var barDragging by remember { mutableStateOf(false) }
    var barValue by remember { mutableFloatStateOf(0f) }

    // 本地播放路径解析
    LaunchedEffect(isLocal, bangumiId, pluginName, episode) {
        if (isLocal) {
            resolvedLocalPath = app.downloadRepository
                .getEpisodeEntity(bangumiId, pluginName, episode)
                ?.let { app.downloadManager.getLocalVideoPath(it) }
        }
    }

    // 组装播放参数
    // 注意：remember 键里不能放 bangumiTitle！它异步返回会重建 params，
    // 进而让 LaunchedEffect(params, resolveTick) 取消重启——正在进行的 WebView
    // 视频解析会被中途打断，表现为"视频解析错误"。bangumiName 只用于 UI 标题，
    // 弹幕自动装载的"补名重试"由下方单独的 LaunchedEffect(bangumiTitle) 负责。
    val params = remember(src, episode, road, isLocal, resolvedLocalPath) {
        if (isLocal) {
            PlaybackInitParams(
                videoUrl = resolvedLocalPath ?: src,
                isLocalPlayback = true,
                bangumiId = bangumiId,
                pluginName = pluginName,
                episode = episode,
                currentRoad = road,
                bangumiName = "本地播放"
            )
        } else {
            val plugin = app.pluginRepository.getPlugin(pluginName)
            PlaybackInitParams(
                videoUrl = src,
                isLocalPlayback = false,
                bangumiId = bangumiId,
                pluginName = pluginName,
                episode = episode,
                currentRoad = road,
                episodeTitle = "第${episode}集",
                bangumiName = bangumiTitle,
                httpHeaders = plugin?.buildHttpHeaders() ?: emptyMap(),
                referer = plugin?.referer ?: "",
                adBlockerEnabled = plugin?.adBlocker ?: false
            )
        }
    }

    // 是否需要 WebView 视频源解析（非直链媒体 URL）
    // 原版：Road.data 存的是"集数页 URL"，播放前必须用 WebViewVideoSourceService.resolve()
    // 解析出真实媒体 URL（m3u8/mp4），否则 ExoPlayer 拿到 HTML 页 → PARSING_CONTAINER_UNSUPPORTED。

    // 播放初始化（参数变化时重新解析+init）
    // 自动重试：解析失败后自动重试（最多 2 次，间隔 2s），仍失败才显示错误页。
    // 手动重试按钮（resolveTick++）与自动重试共用同一条路径。
    LaunchedEffect(params, resolveTick) {
        if (isLocal) {
            playerController.init(params)
            if (DanmakuFeature.ENABLED) playerController.fetchDanmaku()
        } else {
            // 在线播放：先识别是否直链媒体，否则走 WebView 解析
            val direct = isDirectMediaUrl(params.videoUrl)
            FileLogger.i("PlayerResolve", "init: src=${params.videoUrl} direct=$direct plugin=${params.pluginName}")
            if (direct) {
                playerController.init(params)
                playerController.fetchDanmaku()
            } else {
                isResolving = true
                resolveError = null
                val plugin = app.pluginRepository.getPlugin(params.pluginName)
                var parsed = null as com.kazumi.port.service.VideoSource? // 见 service 包定义
                var attempt = 0
                val maxAttempts = 3 // 首次 + 2 次自动重试
                while (attempt < maxAttempts) {
                    attempt++
                    FileLogger.i("PlayerResolve", "start WebView resolve: url=${params.videoUrl} legacy=${plugin?.useLegacyParser ?: false} attempt=$attempt/$maxAttempts")
                    parsed = try {
                        videoSourceService.resolve(
                            episodeUrl = params.videoUrl,
                            useLegacyParser = plugin?.useLegacyParser ?: false,
                            offset = 0,
                            timeoutMillis = 20_000
                        )
                    } catch (e: Exception) {
                        // 协程取消（换集/重试）必须向上传播，不能当作解析失败
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        FileLogger.w("PlayerResolve", "WebView resolve FAILED(attempt=$attempt): ${e.javaClass.simpleName}: ${e.message} | url=${params.videoUrl}")
                        null
                    }
                    if (parsed != null && parsed.url.isNotBlank()) break
                    // 失败：等待后自动重试（最后一次失败不再等）
                    if (attempt < maxAttempts) {
                        FileLogger.i("PlayerResolve", "resolve failed, auto retry in 2s... (attempt=$attempt)")
                        delay(2_000)
                    }
                }
                isResolving = false
                if (parsed != null && parsed.url.isNotBlank()) {
                    FileLogger.i("PlayerResolve", "resolved OK: ${parsed.url} format=${parsed.format} offset=${parsed.offset}")
                    val resolvedParams = params.copy(
                        videoUrl = parsed.url,
                        videoSourceFormat = parsed.format,
                        offset = parsed.offset
                    )
                    playerController.init(resolvedParams)
                    playerController.fetchDanmaku()
                } else {
                    // 3 次（首次+2 重试）都失败：不把 HTML 页塞给播放器（那正是 PARSING_CONTAINER_UNSUPPORTED 的根因），
                    // 显示错误 + 手动重试按钮。
                    FileLogger.e("PlayerResolve", "resolve failed after $maxAttempts attempts, show error UI | url=${params.videoUrl}")
                    resolveError = "视频源解析失败（已自动重试 $maxAttempts 次），可重试"
                    playerController.release()
                }
            }
        }
    }

    // 补名后重试弹幕：番剧名异步返回后，用名字重新自动匹配一次。
    // 不能依赖 params 重建触发（那会打断视频解析），这里单独监听 bangumiTitle。
    var lastDanmakuTitle by remember { mutableStateOf("") }
    LaunchedEffect(bangumiTitle) {
        if (isLocal || bangumiTitle.isBlank() || bangumiTitle == lastDanmakuTitle) return@LaunchedEffect
        lastDanmakuTitle = bangumiTitle
        // 等 init 完成（currentParams 就绪）再重载，最多等 3 秒
        var waited = 0
        while (playerController.currentParams == null && waited < 30) {
            delay(100)
            waited++
        }
        if (playerController.currentParams == null) return@LaunchedEffect
        // 等首轮弹幕装载跑完，避免并发装载造成重复
        var loadWaited = 0
        while (playerController.danmaku.danmakuLoading.value && loadWaited < 50) {
            delay(100)
            loadWaited++
        }
        // 首轮已经按 bangumiId 映射装到弹幕了，就不要再用标题重拉
        // （重拉会先清空现有弹幕，万一标题匹配失败反而把好的结果弄丢）
        if (playerController.danmaku.danDanmakus.isNotEmpty()) return@LaunchedEffect
        val result = playerController.fetchDanmaku(bangumiTitle)
        if (result.hasDanmakus && !danmakuOn) {
            toast("已自动装载弹幕")
        }
    }

    // 读取弹幕设置
    LaunchedEffect(Unit) {
        val s = app.settings
        danmakuArea = s.getDouble(SettingsManager.Danmaku.AREA, 0.5).toFloat()
        danmakuFontSize = s.getInt(SettingsManager.Danmaku.FONT_SIZE, 24)
        danmakuFontWeight = s.getInt(SettingsManager.Danmaku.FONT_WEIGHT, 4)
        danmakuBorder = s.getBoolean(SettingsManager.Danmaku.BORDER, true)
        danmakuBorderSize = s.getDouble(SettingsManager.Danmaku.BORDER_SIZE, 1.0).toFloat()
        danmakuOpacity = s.getDouble(SettingsManager.Danmaku.OPACITY, 0.8).toFloat()
        danmakuLineHeight = s.getDouble(SettingsManager.Danmaku.LINE_HEIGHT, 1.0).toFloat()
        danmakuShowScroll = s.getBoolean(SettingsManager.Danmaku.SCROLL, true)
        danmakuShowTop = s.getBoolean(SettingsManager.Danmaku.TOP, true)
        danmakuShowBottom = s.getBoolean(SettingsManager.Danmaku.BOTTOM, true)
        danmakuMassive = s.getBoolean(SettingsManager.Danmaku.MASSIVE, false)
        brightnessVolumeGesture = s.getBoolean(SettingsManager.Player.BRIGHTNESS_VOLUME_GESTURE, true)
    }

    // 定时刷新进度 + 弹幕调度
    LaunchedEffect(Unit) {
        var lastQueryMs = -1L
        while (true) {
            playerController.tickProgress()
            val pos = playerController.currentPosition()
            val gen = playerController.danmaku.scheduledDanmakuGeneration
            if (gen != danmakuGeneration) {
                // seek / 切源：重置取用游标，从当前位置重新开始
                danmakuGeneration = gen
                lastQueryMs = -1L
            }
            if (danmakuOn) {
                if (lastQueryMs >= 0 && pos < lastQueryMs - 1500) {
                    // 后退 seek：游标回退到当前位置，避免把中间秒全部补出来
                    lastQueryMs = pos
                }
                // 按区间取弹幕（(lastQueryMs, pos] 内跨过的所有秒），避免轮询跨秒导致丢弹幕
                val fresh = playerController.danmaku.takeDanmakusBetween(lastQueryMs, pos)
                lastQueryMs = pos
                if (fresh.isNotEmpty()) {
                    currentDanmakus = fresh
                    danmakuBatch++
                }
                danmakuDurationSec = playerController.danmaku.danmakuDurationForSpeed(
                    playerController.playbackSpeed
                )
            }
            delay(200)
        }
    }

    // 自动隐藏控制条（拖动进度条期间不能隐藏，否则滑到一半控件消失）
    LaunchedEffect(controlsVisible, locked, barDragging) {
        if (!controlsVisible || locked || barDragging) return@LaunchedEffect
        delay(4000)
        if (!interactiveSeeking && !speedMenuOpen && !moreMenuOpen && !barDragging) {
            controlsVisible = false
        }
    }

    // 返回键：锁屏时先解锁，菜单展开时先收起，其余情况退出播放页
    BackHandler {
        if (locked) {
            locked = false
            controlsVisible = true
            playerController.setPanel { it.copy(lockPanel = false) }
        } else if (moreMenuOpen || speedMenuOpen || aspectMenuOpen) {
            moreMenuOpen = false
            speedMenuOpen = false
            aspectMenuOpen = false
        } else {
            navController.popBackStack()
        }
    }

    DisposableEffect(Unit) {
        // 进入播放页强制横屏（Manifest 已声明 configChanges=orientation|screenSize，
        // 旋转不会重建 Activity）；退出时恢复原方向
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            playerController.dispose()
            // 释放 WebView 解析服务
            scope.launch {
                videoSourceService.dispose()
            }
        }
    }

    // 兜底绑定：不依赖 AndroidView 的 update 回调是否被再次执行。
    // 只要 PlayerView 与 ExoPlayer 都存在，就保证绑定成立（否则没有 Surface，
    // 表现就是有声音、能拖进度，但永远黑屏）。
    LaunchedEffect(exoPlayer, playerView) {
        val view = playerView ?: return@LaunchedEffect
        if (view.player !== exoPlayer) {
            FileLogger.i("PlayerResolve", "PlayerView bind(LaunchedEffect) player=${exoPlayer != null}")
            view.player = exoPlayer
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 视频画面（含比例）
        val aspect = when (panelState.aspectRatioMode) {
            PlayerAspectRatio.Automatic -> null
            PlayerAspectRatio.Crop -> null // 自动模式用 PlayerView 默认（Crop 通过 resizeMode 实现）
            PlayerAspectRatio.Stretch -> null
            PlayerAspectRatio.Ratio4x3 -> 4f / 3f
        }
        Box(
            modifier = if (aspect != null) {
                Modifier.fillMaxSize().aspectRatio(aspect)
            } else {
                Modifier.fillMaxSize()
            }
        ) {
            AndroidView(
                factory = { ctx ->
                    FileLogger.i("PlayerResolve", "PlayerView 创建")
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = false
                        keepScreenOn = true
                        player = null
                        // 视频比例（Crop=RESIZE_MODE_ZOOM, Stretch=RESIZE_MODE_FILL, 其余=RESIZE_MODE_FIT）
                        resizeMode = when (panelState.aspectRatioMode) {
                            PlayerAspectRatio.Crop -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            PlayerAspectRatio.Stretch -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }.also { playerView = it }
                },
                update = { view ->
                    playerView = view
                    // 仅在播放器实例变化时重新绑定：PlayerView.setPlayer 会重新挂载
                    // Surface，每帧重复赋值可能导致画面黑屏/闪烁
                    if (view.player !== exoPlayer) {
                        FileLogger.i(
                            "PlayerResolve",
                            "PlayerView bind player=${exoPlayer != null} surface=${view.videoSurfaceView?.javaClass?.simpleName}"
                        )
                        view.player = exoPlayer
                    }
                    view.resizeMode = when (panelState.aspectRatioMode) {
                        PlayerAspectRatio.Crop -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        PlayerAspectRatio.Stretch -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 视频源解析状态层（在线播放 WebView 解析时显示）
            if (isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在解析视频源…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else if (resolveError != null && !uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = resolveError ?: "",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { resolveTick++ }) {
                            Text("重试", color = Color.White)
                        }
                    }
                }
            }

            // 弹幕层
            DanmakuOverlay(
                modifier = Modifier.fillMaxSize(),
                playbackPositionMs = uiState.currentPositionMs,
                visible = true,
                danmakuOn = danmakuOn,
                currentDanmakus = currentDanmakus,
                batch = danmakuBatch,
                playing = uiState.isPlaying,
                generation = danmakuGeneration,
                durationSeconds = danmakuDurationSec,
                area = danmakuArea,
                fontSize = danmakuFontSize,
                fontWeight = danmakuFontWeight,
                border = danmakuBorder,
                borderSize = danmakuBorderSize,
                opacity = danmakuOpacity,
                lineHeight = danmakuLineHeight,
                showScroll = danmakuShowScroll,
                showTop = danmakuShowTop,
                showBottom = danmakuShowBottom,
                massive = danmakuMassive
            )

            // 手势层
            PlayerGestureLayer(
                modifier = Modifier.fillMaxSize(),
                locked = locked,
                onTap = {
                    controlsVisible = !controlsVisible
                },
                onDoubleTap = {
                    playerController.playOrPause()
                },
                onHorizontalDragStart = {
                    if (!locked) {
                        interactiveSeeking = true
                        seekStartPos = playerController.currentPosition()
                        seekDragAccum = 0L
                        seekWasPlaying = playerController.isPlaying()
                        playerController.pause(enableSync = false)
                        showSeekHud = true
                        seekHudPosition = seekStartPos
                        seekHudDirection = 0
                        seekCancelPending = false
                    }
                },
                onHorizontalDrag = { deltaPx, viewportWidth ->
                    if (!locked) {
                        val duration = playerController.duration()
                        val seekPerFullDrag = 120_000L // 全宽 120 秒
                        // 累加而不是每次重新算：onHorizontalDrag 回调的是单次事件瞬时增量
                        seekDragAccum += (deltaPx / viewportWidth * seekPerFullDrag).toLong()
                        var target = seekStartPos + seekDragAccum
                        if (target < 0) target = 0
                        if (duration > 0 && target > duration) target = duration
                        seekHudPosition = target
                        seekHudDirection = if (seekDragAccum >= 0) 1 else -1
                    }
                },
                onHorizontalDragEnd = { cancelled ->
                    if (!locked) {
                        interactiveSeeking = false
                        showSeekHud = false
                        if (!cancelled) {
                            playerController.seekTo(seekHudPosition, enableSync = false)
                        }
                        if (seekWasPlaying) {
                            playerController.play(enableSync = false)
                        }
                        controlsVisible = true
                    }
                },
                onVerticalDrag = { deltaPx, viewportHeight, startX, viewportWidth ->
                    if (!locked && brightnessVolumeGesture) {
                        // 左侧调亮度，右侧调音量
                        val isLeft = startX < viewportWidth / 2
                        val fullDrag = viewportHeight * 0.6f
                        if (isLeft) {
                            showBrightnessHud = true
                            showVolumeHud = false
                            val delta = -deltaPx / fullDrag
                            hudBrightness = (hudBrightness + delta).coerceIn(0f, 1f)
                            playerController.setBrightness(hudBrightness.toDouble())
                            setScreenBrightness(context, hudBrightness)
                        } else {
                            showVolumeHud = true
                            showBrightnessHud = false
                            val delta = -deltaPx / fullDrag * 100f
                            hudVolume = (hudVolume + delta).coerceIn(0f, 100f)
                            playerController.setVolume(hudVolume / 100f)
                        }
                    }
                },
                onVerticalDragEnd = {
                    showVolumeHud = false
                    showBrightnessHud = false
                }
            )
        }

        // 顶部控制条
        if (controlsVisible && !locked) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Text(
                        text = bangumiTitle.ifEmpty { uiState.bangumiName.ifEmpty { "播放" } },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    // 选集切换（原版 video_page 选集面板入口）
                    if (roads.isNotEmpty()) {
                        IconButton(onClick = { showEpisodeSheet = true }) {
                            Icon(
                                Icons.Filled.PlaylistPlay,
                                contentDescription = "选集",
                                tint = Color.White
                            )
                        }
                    }
                    // 弹幕开关（弹幕是自动装载的，这里只做开关；
                    // 没有弹幕时自动重新匹配一次，而不是把"选番剧/选分集"丢给用户）
                    IconButton(onClick = {
                        if (danmakuLoading) return@IconButton
                        if (danmakuOn) {
                            playerController.danmaku.setDanmakuEnabled(false)
                        } else if (playerController.danmaku.danDanmakus.isNotEmpty()) {
                            playerController.danmaku.setDanmakuEnabled(true)
                        } else {
                            scope.launch {
                                toast("正在匹配弹幕…")
                                val result = playerController.fetchDanmaku()
                                toast(
                                    when {
                                        result.hasDanmakus -> "弹幕已装载"
                                        result.isFailed -> {
                                            val msg = result.errorMessage.orEmpty()
                                            if (msg.contains("未配置弹弹play凭据"))
                                                "弹幕需要弹弹play凭据：设置 → 弹幕 → 弹弹play 凭据"
                                            else
                                                "弹幕匹配失败（$msg），请稍后再试"
                                        }
                                        else -> "没找到这一集的弹幕，可在「更多」里手动切换弹幕源"
                                    }
                                )
                            }
                        }
                    }) {
                        if (danmakuLoading) {
                            Text("…", color = Color.White)
                        } else {
                            Icon(
                                Icons.Filled.Subtitles,
                                contentDescription = if (danmakuOn) "关闭弹幕" else "打开弹幕",
                                tint = if (danmakuOn) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }
                    // 截图
                    IconButton(onClick = {
                        scope.launch {
                            screenshotCapturing = true
                            val view = playerView
                            val ok = playerController.captureScreenshot {
                                capturePlayerBitmap(view)
                            }
                            screenshotCapturing = false
                            toast(
                                if (ok) "截图已保存到相册"
                                else playerController.screenshots.state.value.message ?: "截图失败"
                            )
                        }
                    }) {
                        if (screenshotCapturing) {
                            Text("…", color = Color.White)
                        } else {
                            Icon(Icons.Filled.CameraAlt, contentDescription = "截图", tint = Color.White)
                        }
                    }
                    // 锁屏
                    IconButton(onClick = {
                        locked = !locked
                        controlsVisible = !locked
                        playerController.setPanel { it.copy(lockPanel = locked) }
                    }) {
                        Icon(
                            if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            contentDescription = if (locked) "解锁" else "锁定",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // HUD（顶部中央）
        if (!locked) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) {
                PlayerSeekHud(
                    visible = showSeekHud,
                    currentPositionMs = seekHudPosition,
                    playerPositionMs = uiState.currentPositionMs,
                    durationMs = uiState.durationMs,
                    direction = seekHudDirection,
                    cancelPending = seekCancelPending
                )
            }
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) {
                PlayerSpeedHud(visible = false, speed = uiState.playbackSpeed)
            }
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) {
                PlayerAdjustmentHud(
                    visible = showVolumeHud || showBrightnessHud,
                    type = if (showVolumeHud) PlayerAdjustmentHudType.Volume else PlayerAdjustmentHudType.Brightness,
                    value = if (showVolumeHud) hudVolume else hudBrightness
                )
            }
        }

        // 底部控制条
        if (controlsVisible && !locked) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 进度条：必须可拖动（原实现是 LinearProgressIndicator，只能看不能拖，
                // 用户拖动时没有任何响应，表现为"拖不到正确点位"）
                val barProgress = if (uiState.durationMs > 0) {
                    val raw = if (barDragging) {
                        barValue
                    } else {
                        uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat()
                    }
                    raw.coerceIn(0f, 1f)
                } else 0f
                Slider(
                    value = barProgress,
                    onValueChange = { v ->
                        barDragging = true
                        barValue = v
                    },
                    onValueChangeFinished = {
                        barDragging = false
                        if (uiState.durationMs > 0) {
                            playerController.seekTo(
                                (barValue * uiState.durationMs).toLong(),
                                enableSync = false
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatMs(uiState.currentPositionMs) + " / " + formatMs(uiState.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row {
                        IconButton(onClick = { playerController.playOrPause() }) {
                            Icon(
                                if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (uiState.isPlaying) "暂停" else "播放",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { playerController.toggleMute() }) {
                            Icon(
                                if (uiState.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                contentDescription = if (uiState.isMuted) "取消静音" else "静音",
                                tint = Color.White
                            )
                        }
                        // 倍速菜单
                        Box {
                            IconButton(onClick = { speedMenuOpen = !speedMenuOpen }) {
                                Text(
                                    text = "${formatSpeed(uiState.playbackSpeed)}x",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            DropdownMenu(
                                expanded = speedMenuOpen,
                                onDismissRequest = { speedMenuOpen = false }
                            ) {
                                playSpeedList.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text("${formatSpeed(s)}x") },
                                        onClick = {
                                            playerController.setPlaybackSpeed(s)
                                            speedMenuOpen = false
                                            toast("倍速 ${formatSpeed(s)}x")
                                        }
                                    )
                                }
                            }
                        }
                        // 视频比例菜单
                        Box {
                            IconButton(onClick = { aspectMenuOpen = !aspectMenuOpen }) {
                                Icon(Icons.Filled.AspectRatio, contentDescription = "视频比例", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = aspectMenuOpen,
                                onDismissRequest = { aspectMenuOpen = false }
                            ) {
                                PlayerAspectRatio.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = {
                                            playerController.setAspectRatio(mode)
                                            scope.launch {
                                                app.settings.setInt(SettingsManager.Player.DEFAULT_ASPECT_RATIO_TYPE, mode.storageValue)
                                            }
                                            aspectMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        // 更多（弹幕源/一起看）
                        Box {
                            IconButton(onClick = { moreMenuOpen = !moreMenuOpen }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多选项", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = moreMenuOpen,
                                onDismissRequest = { moreMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("弹幕源切换") },
                                    onClick = {
                                        moreMenuOpen = false
                                        showDanmakuSource = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("一起看") },
                                    onClick = {
                                        moreMenuOpen = false
                                        showSyncPlay = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 错误提示（含重试按钮）
        if (uiState.errorMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "播放出错: ${uiState.errorMessage}",
                        color = Color.White
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { resolveTick++ }) {
                        Text("重试", color = Color.White)
                    }
                }
            }
        }

        // 弹幕源切换 sheet
        if (showDanmakuSource) {
            DanmakuSourceSheet(
                bangumiId = bangumiId,
                initialKeyword = uiState.bangumiName.ifEmpty { "" },
                danmakuController = playerController.danmaku,
                danmakuApi = com.kazumi.port.data.repository.DanmakuApi(settings = app.settings),
                settings = app.settings,
                onDismiss = { showDanmakuSource = false },
                toast = toast
            )
        }

        // 同步播放 sheet
        if (showSyncPlay) {
            SyncPlaySheet(
                syncplay = playerController.syncplay,
                settings = app.settings,
                onDismiss = { showSyncPlay = false },
                toast = toast
            )
        }
        // 选集切换 sheet（线路数据来自详情页）
        if (showEpisodeSheet && roads.isNotEmpty()) {
            EpisodeSelectionSheet(
                title = bangumiTitle.ifEmpty { uiState.bangumiName },
                roads = roads,
                selectedRoad = road,
                selectedEpisode = episode,
                onSelect = { episodeIndex, roadIndex ->
                    showEpisodeSheet = false
                    val newUrl = roads.getOrNull(roadIndex)?.data?.getOrNull(episodeIndex)
                    if (newUrl != null && (episodeIndex + 1 != episode || roadIndex != road)) {
                        navController.navigate(
                            com.kazumi.port.ui.navigation.Routes.player(
                                bangumiId = bangumiId,
                                pluginName = pluginName,
                                src = newUrl,
                                episode = episodeIndex + 1,
                                road = roadIndex,
                                isLocal = isLocal,
                                roadsJson = roadsJson
                            )
                        ) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                onDismiss = { showEpisodeSheet = false }
            )
        }
    }
}

/**
 * 判断 URL 是否已是直链媒体（无需 WebView 解析）。
 * 原版 Road.data 是"集数页 URL"；若已是媒体直链（.m3u8/.mp4/.ts/.flv/.webm/.mkv 等）
 * 直接播放，否则走 WebView 解析出真实媒体地址。
 */
private fun isDirectMediaUrl(url: String): Boolean {
    if (url.isBlank()) return false
    // 本地路径
    if (url.startsWith("/") || url.startsWith("file://")) return true
    // blob:/data: 视频流
    if (url.startsWith("blob:") || url.startsWith("data:")) return true
    // 带媒体扩展名
    val path = runCatching { android.net.Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
    val mediaExts = listOf(".m3u8", ".mp4", ".ts", ".flv", ".webm", ".mkv", ".mp3", ".aac", ".m4a", ".mov")
    if (mediaExts.any { path.endsWith(it) }) return true
    // 无扩展名但有清晰媒体 query 标记
    return url.contains(".m3u8", ignoreCase = true) ||
        url.contains("mime=video", ignoreCase = true) ||
        url.contains("type=mpegurl", ignoreCase = true)
}

/** 手势层：处理点击/双击/左右 seek 拖动/上下亮度音量 */
@Composable
private fun PlayerGestureLayer(
    modifier: Modifier = Modifier,
    locked: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onHorizontalDragStart: () -> Unit,
    onHorizontalDrag: (deltaPx: Float, viewportWidth: Float) -> Unit,
    onHorizontalDragEnd: (cancelled: Boolean) -> Unit,
    onVerticalDrag: (deltaPx: Float, viewportHeight: Float, startX: Float, viewportWidth: Float) -> Unit,
    onVerticalDragEnd: () -> Unit
) {
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier.pointerInput(locked) {
            if (locked) return@pointerInput
            detectTapGestures(
                onTap = { onTap() },
                onDoubleTap = { onDoubleTap() }
            )
        }.pointerInput(locked) {
            if (locked) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    dragStartX = offset.x
                    dragStartY = offset.y
                    onHorizontalDragStart()
                },
                onDragEnd = {
                    onHorizontalDragEnd(false)
                },
                onDragCancel = {
                    onHorizontalDragEnd(true)
                },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    onHorizontalDrag(dragAmount, size.width.toFloat())
                }
            )
        }.pointerInput(locked) {
            if (locked) return@pointerInput
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    dragStartX = offset.x
                    dragStartY = offset.y
                },
                onDragEnd = { onVerticalDragEnd() },
                onDragCancel = { onVerticalDragEnd() },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    onVerticalDrag(dragAmount, size.height.toFloat(), dragStartX, size.width.toFloat())
                }
            )
        }
    ) {}
}

/** 近似应用屏幕亮度（通过 WindowManager） */
private fun setScreenBrightness(context: android.content.Context, value: Float) {
    val window = (context as? android.app.Activity)?.window ?: return
    val lp = window.attributes
    lp.screenBrightness = value.coerceIn(0.05f, 1f)
    window.attributes = lp
}

/** 从 PlayerView 截取当前帧（TextureView.bitmap） */
private fun capturePlayerBitmap(view: PlayerView?): Bitmap? {
    val tv = view?.videoSurfaceView as? TextureView ?: return null
    if (tv.isAvailable) {
        return tv.bitmap
    }
    return null
}

private fun formatSpeed(speed: Float): String = if (speed == speed.toInt().toFloat()) {
    speed.toInt().toString()
} else {
    "%.2f".format(speed).trimEnd('0').trimEnd('.')
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

/** 从 Compose 的 Context 向上找到宿主 Activity（LocalContext 通常是 ContextThemeWrapper） */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}