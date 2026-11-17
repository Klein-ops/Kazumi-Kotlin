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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.media3.ui.PlayerView
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.data.repository.History
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.player.PlaybackInitParams
import com.kazumi.port.player.PlayerAspectRatio
import com.kazumi.port.player.PlayerController
import com.kazumi.port.service.VideoSourceFormat
import com.kazumi.port.service.WebViewVideoSourceService
import com.kazumi.port.ui.components.DanmakuOverlay
import com.kazumi.port.ui.components.DanmakuSettingsSheet
import com.kazumi.port.ui.components.EpisodeSelectionSheet
import com.kazumi.port.util.FileLogger
import com.kazumi.port.util.DanmakuFeature
import com.kazumi.port.ui.components.DanmakuSourceSheet
import com.kazumi.port.ui.components.PlayerAdjustmentHud
import com.kazumi.port.ui.components.PlayerAdjustmentHudType
import com.kazumi.port.ui.components.PlayerPlayPauseHud
import com.kazumi.port.ui.components.PlayerSeekHud
import com.kazumi.port.ui.components.PlayerSpeedHud
import com.kazumi.port.ui.components.SyncPlaySheet
import com.kazumi.port.ui.components.rememberToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kazumi.port.ui.components.KazumiLoadingIndicator

/** 可选倍速（对应原版 defaultPlaySpeedList） */
private val playSpeedList = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f, 3.0f)

/**
 * 播放页（对应原版 player_item.dart / video_page.dart）
 * 第 7 步补强：弹幕 / 截图 / 倍速菜单 / 手势（左右 seek、上下亮度音量）/ 同步播放 / 视频比例 / 锁屏
 */
@OptIn(ExperimentalFoundationApi::class)
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
    // 从历史记录/快捷入口直达时 roadsJson 为空，此时按补名标题反搜当前插件补线路，
    // 否则播放器内的选集/切线路不可用（历史记录页复现的"无法切换选集"）。
    // 注意：补线路逻辑依赖补名结果 bangumiTitle，因此放在下方 bangumiTitle 声明之后。
    var roads by remember(roadsJson) {
        mutableStateOf(
            if (roadsJson.isEmpty()) emptyList()
            else runCatching {
                val arr = com.google.gson.JsonParser.parseString(roadsJson).asJsonArray
                arr.mapNotNull { el ->
                    if (el.isJsonObject) com.kazumi.port.data.model.Road.fromJson(el.asJsonObject) else null
                }
            }.getOrDefault(emptyList())
        )
    }
    var showEpisodeSheet by remember { mutableStateOf(false) }

    // 路由携带的线路 JSON 优先；若从历史记录等入口进入（roadsJson 为空）且
    // roads 已通过补线路逻辑填充，则把当前 roads 序列化带出，切集/换线路时不丢线路。
    fun currentRoadsJson(): String {
        if (roadsJson.isNotEmpty()) return roadsJson
        if (roads.isEmpty()) return ""
        return runCatching {
            val arr = com.google.gson.JsonArray()
            roads.forEach { arr.add(it.toJson()) }
            arr.toString()
        }.getOrDefault("")
    }

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
    var danmakuArea by remember { mutableFloatStateOf(1.0f) }
    var danmakuFontSize by remember { mutableIntStateOf(25) }
    var danmakuFontWeight by remember { mutableIntStateOf(4) }
    var danmakuBorder by remember { mutableStateOf(true) }
    var danmakuBorderSize by remember { mutableFloatStateOf(1.5f) }
    var danmakuOpacity by remember { mutableFloatStateOf(1.0f) }
    var danmakuLineHeight by remember { mutableFloatStateOf(1.6f) }
    var danmakuShowScroll by remember { mutableStateOf(true) }
    var danmakuShowTop by remember { mutableStateOf(true) }
    var danmakuShowBottom by remember { mutableStateOf(false) }
    var danmakuMassive by remember { mutableStateOf(false) }

    // 弹幕批次：每次取到"新弹幕"就自增，渲染层据此消费，避免同秒重复/漏秒
    var danmakuBatch by remember { mutableIntStateOf(0) }

    // 番剧名：在线播放时路由只带 bangumiId，这里按 id 补名字，供弹幕自动匹配使用
    // 取名失败会静默导致 B 站弹幕匹配拿空关键词 → 永远匹配不到；这里带日志 + 最多 3 次重试
    var bangumiTitle by remember(bangumiId) { mutableStateOf("") }
    // 补名成功后保存完整条目（含封面图），历史记录写入时带上 images，
    // 否则历史页所有条目封面为空（BangumiItem 默认 images 为空 Map）。
    var bangumiItem by remember(bangumiId) { mutableStateOf<com.kazumi.port.data.model.BangumiItem?>(null) }
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
            if (name.isNotBlank()) {
                bangumiItem = item
                break
            }
            FileLogger.w("Danmaku", "补名尝试 $attempt/3 未取到名字: bgm=$bangumiId item=${item != null}")
            if (attempt < 3) delay(2_000)
        }
        FileLogger.i("Danmaku", "补名完成: bgm=$bangumiId name=\"$name\"")
        if (name.isNotBlank()) bangumiTitle = name
    }

    // 补线路：仅当 roadsJson 为空（无线路数据）且已补到番剧名时触发。
    // 从历史记录/快捷入口直达时路由不带 roadsJson，播放器内无选集/切线路；
    // 这里复用与详情页一致的路径：反搜当前插件命中 → 查线路。
    // 搜索可能因标题不完全匹配失败，失败则维持现状（能播但无选集），不影响播放。
    LaunchedEffect(roads.isEmpty(), bangumiTitle, pluginName) {
        if (roads.isNotEmpty()) return@LaunchedEffect
        if (isLocal || bangumiTitle.isBlank() || pluginName.isBlank()) return@LaunchedEffect
        FileLogger.d("PlayerResolve", "roadsJson 为空，按标题反搜补线路: plugin=$pluginName title=$bangumiTitle")
        val fetched = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val hit = app.pluginRepository.searchBangumi(pluginName, bangumiTitle).data.firstOrNull()
                if (hit != null) app.pluginRepository.queryChapterRoads(pluginName, hit.src) else emptyList()
            }.getOrDefault(emptyList())
        }
        if (fetched.isNotEmpty()) {
            roads = fetched
            FileLogger.d("PlayerResolve", "补线路成功: roads=${fetched.size}")
        }
    }

    // 切换上一集/下一集（对齐原版 handlePreNextEpisode：越界提示 toast，不走选集弹窗）
    // silent=true 用于自动连播：到最后一集时静默，不弹 toast 打扰
    fun playNextOrPrev(delta: Int, silent: Boolean = false) {
        if (roads.isEmpty()) return
        val target = episode + delta
        if (target <= 0) {
            if (!silent) toast("已经是第一集")
            return
        }
        val currentRoad = roads.getOrNull(road)
        val maxEp = currentRoad?.data?.size ?: 0
        if (target > maxEp) {
            if (!silent) toast("已经是最新一集")
            return
        }
        val newUrl = currentRoad?.data?.getOrNull(target - 1) ?: return
        // 切集：替换当前 PlayerPage（popUpTo 当前页 + launchSingleTop），避免导航栈叠出
        // 多个 PlayerPage（旧实例 dispose 恢复方向互相打架 → 横竖屏错乱；后台实例残留 → 无限重组）
        navController.navigate(
            com.kazumi.port.ui.navigation.Routes.player(
                bangumiId = bangumiId,
                pluginName = pluginName,
                src = newUrl,
                episode = target,
                road = road,
                isLocal = isLocal,
                roadsJson = currentRoadsJson()
            )
        ) {
            popUpTo(com.kazumi.port.ui.navigation.Routes.PLAYER) { inclusive = true }
            launchSingleTop = true
        }
    }

    // 自动连播：播放结束后自动切下一集（对齐原版 autoPlayNext；
    // 本地播放无线路列表、隐私模式、设置关闭时不自动跳）
    val endedTick by playerController.playbackEndedTick.collectAsState()
    LaunchedEffect(endedTick) {
        if (endedTick == 0) return@LaunchedEffect
        if (isLocal || roads.isEmpty()) return@LaunchedEffect
        val autoNext = runCatching { app.settings.autoPlayNext() }.getOrDefault(true)
        if (!autoNext || runCatching { app.settings.isPrivateMode() }.getOrDefault(false)) return@LaunchedEffect
        // 稍等 STATE_ENDED 状态稳定后再切下一集（静默，不弹 toast 打扰）
        delay(800)
        playNextOrPrev(1, silent = true)
    }

    // 切集/切线路：替换当前 PlayerPage，避免导航栈叠页（横竖屏错乱/无限重组）。
    // 竖屏下面板与选集弹窗共用（对齐原版 episode_selection_panel onSelect）
    fun switchEpisode(episodeIndex: Int, roadIndex: Int) {
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
                    roadsJson = currentRoadsJson()
                )
            ) {
                popUpTo(com.kazumi.port.ui.navigation.Routes.PLAYER) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // 播放中定期把最新观看历史同步到 WebDAV（对齐设置页"自动同步播放进度与历史记录"文案）；
    // 此前历史只有启动时同步一次，播放中产生的进度需要重启才上传
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            runCatching {
                if (app.settings.getBoolean(SettingsManager.WebDav.ENABLE, false) &&
                    app.settings.getBoolean(SettingsManager.WebDav.ENABLE_HISTORY, false) &&
                    !app.settings.isPrivateMode()
                ) {
                    app.webDavSyncService.syncHistoryFull(
                        localProvider = { app.historyRepository.getAll() },
                        localUpsert = { h -> app.historyRepository.upsert(h) }
                    )
                }
            }
        }
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
    // 垂直手势的累积基准：onVerticalDragStart 时记录系统音量/亮度，之后每次 delta 累加。
    // 不能用"系统当前值 + delta"（delta 是瞬时增量）：系统音量是离散级别，重读系统值会让
    // 小数增量被 toInt 吃掉 → 永远抖在某个级别附近，拖不上去（反复横跳）。
    var verticalDragBaseVolume by remember { mutableFloatStateOf(80f) }
    var verticalDragBaseBrightness by remember { mutableFloatStateOf(0.5f) }
    // 垂直手势累计位移：onVerticalDragStart 重置，onVerticalDrag 累加。
    // deltaPx 是单次事件的瞬时增量，若直接用"基准+瞬时增量"会在基准附近反复重置→拖不动；
    // 必须累加总位移再换算，持续拖必然累积到位。
    var verticalDragAccum by remember { mutableFloatStateOf(0f) }

    // HUD 音量初始值 = 系统媒体音量（STREAM_MUSIC），音量完全由系统控制
    LaunchedEffect(Unit) {
        hudVolume = playerController.systemVolume() * 100f
        // 亮度初始值 = 当前实际生效的屏幕亮度（窗口属性或系统亮度），
        // 不能取 panelState.brightness：那是"上次手势的记忆值"，默认 0。
        // 若用户系统亮度 100% 而记忆值是 0，HUD 初始化会显示 0%，且手势基准也会错
        // → 从底部轻轻上滑一点就掉到最低亮度（用户反馈的"100 亮度轻滑变最低"）。
        hudBrightness = currentScreenBrightness(context)
    }

    // 面板
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    // 双击播放/暂停中央反馈（短暂显示，避免静默切换用户不知道是否已暂停）
    var showPlayPauseHud by remember { mutableStateOf(false) }
    // 长按画面临时倍速指示（长按期间显示倍速 HUD，松开立即隐藏）
    var speedHudVisible by remember { mutableStateOf(false) }
    // 全屏状态（对齐原版 video_page：进入默认竖屏——顶部 16:9 播放器 + 下方选集面板，
    // 点全屏按钮才切横屏；原旧版一进播放页就强制横屏，选集/切线路只能点小按钮，用户反馈不便）
    // rememberSaveable：横屏旋转会触发 Activity 重建，普通 remember 会丢状态退回竖屏
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    // 长按临时倍速前的原速度（null 表示当前不在长按倍速中）
    var speedBeforeLongPress by remember { mutableStateOf<Float?>(null) }
    // 快进/快退按钮的跳转秒数（原版 buttonSkipTime，默认 80）
    var buttonSkipTime by remember { mutableIntStateOf(80) }
    var showSkipTimeDialog by remember { mutableStateOf(false) }
    var skipTimeInput by remember { mutableStateOf("80") }
    LaunchedEffect(Unit) {
        buttonSkipTime = app.settings.getInt(
            com.kazumi.port.data.repository.SettingsManager.Player.BUTTON_SKIP_TIME, 80
        )
        skipTimeInput = buttonSkipTime.toString()
    }

    // 长按快进/快退按钮：修改跳转秒数（对应原版 _showForwardChange）
    if (showSkipTimeDialog) {
        AlertDialog(
            onDismissRequest = { showSkipTimeDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("快进/快退时长") },
            text = {
                Column {
                    OutlinedTextField(
                        value = skipTimeInput,
                        onValueChange = { skipTimeInput = it.filter { ch -> ch.isDigit() }.take(3) },
                        singleLine = true,
                        label = { Text("秒") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                    Text(
                        text = "点击前进/后退按钮时跳转的秒数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = skipTimeInput.toIntOrNull()?.coerceIn(5, 600) ?: buttonSkipTime
                    buttonSkipTime = value
                    showSkipTimeDialog = false
                    scope.launch {
                        app.settings.setInt(
                            com.kazumi.port.data.repository.SettingsManager.Player.BUTTON_SKIP_TIME,
                            value
                        )
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSkipTimeDialog = false }) { Text("取消") }
            }
        )
    }

    // 倍速/视频比例选择对话框（竖屏顶部「更多」菜单与横屏宽控件统一入口；
    // 不用 DropdownMenu——竖屏时底部 anchor 不在组合中，DropdownMenu 会失去锚点）
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showAspectDialog by remember { mutableStateOf(false) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var aspectMenuOpen by remember { mutableStateOf(false) }

    // 倍速选择对话框（竖屏「更多」菜单与横屏宽控件共用）
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("播放倍速") },
            text = {
                Column {
                    playSpeedList.forEach { s ->
                        TextButton(
                            onClick = {
                                playerController.setPlaybackSpeed(s)
                                showSpeedDialog = false
                                toast("倍速 ${formatSpeed(s)}x")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${formatSpeed(s)}x",
                                color = if (s == uiState.playbackSpeed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("关闭") }
            }
        )
    }

    // 视频比例选择对话框（竖屏「更多」菜单与横屏宽控件共用）
    if (showAspectDialog) {
        AlertDialog(
            onDismissRequest = { showAspectDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("视频比例") },
            text = {
                Column {
                    PlayerAspectRatio.entries.forEach { mode ->
                        TextButton(
                            onClick = {
                                playerController.setAspectRatio(mode)
                                scope.launch {
                                    app.settings.setInt(SettingsManager.Player.DEFAULT_ASPECT_RATIO_TYPE, mode.storageValue)
                                }
                                showAspectDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = mode.label,
                                color = if (mode == panelState.aspectRatioMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAspectDialog = false }) { Text("关闭") }
            }
        )
    }

    // Sheet
    var showDanmakuSource by remember { mutableStateOf(false) }
    var showSyncPlay by remember { mutableStateOf(false) }
    // 播放器内弹幕设置 Sheet（对齐原版 danmaku_settings_sheet）
    var showDanmakuSettings by remember { mutableStateOf(false) }
    // 发送弹幕输入框（对齐原版 _danmakuTextField）
    var danmakuInput by remember { mutableStateOf("") }
    var danmakuInputFocus by remember { mutableStateOf(false) }

    // 发送弹幕输入框获得焦点时自动暂停（对齐原版 pauseOnDanmakuEdit，默认开）
    // 记录暂停前的播放状态，取消焦点/发送后若原本在播放则恢复
    var pauseOnDanmakuEdit by remember { mutableStateOf(true) }
    var wasPlayingBeforeDanmakuInput by remember { mutableStateOf(false) }
    LaunchedEffect(danmakuInputFocus) {
        if (danmakuInputFocus) {
            // 首次获得焦点才记录暂停前状态并暂停
            wasPlayingBeforeDanmakuInput = uiState.isPlaying
            runCatching { pauseOnDanmakuEdit = app.settings.getBoolean(SettingsManager.Danmaku.PAUSE_ON_EDIT, true) }.getOrDefault(true)
            if (pauseOnDanmakuEdit && uiState.isPlaying && !isLocal) {
                playerController.pause(enableSync = false)
            }
        } else {
            // 焦点离开：原本在播放且设置了暂停弹幕编辑，则恢复播放
            if (pauseOnDanmakuEdit && wasPlayingBeforeDanmakuInput && !isLocal) {
                playerController.play(enableSync = false)
            }
            wasPlayingBeforeDanmakuInput = false
        }
    }

    // 发送弹幕：原版逻辑——输入非空、当前集有弹幕、长度<=100；本地回显（弹幕发送接口无官方支持）
    fun sendDanmaku() {
        val msg = danmakuInput.trim()
        if (msg.isEmpty()) {
            toast("弹幕内容为空")
            return
        }
        if (playerController.danmaku.danDanmakus.isEmpty()) {
            toast("当前剧集不支持弹幕发送")
            return
        }
        if (msg.length > 100) {
            toast("弹幕内容过长")
            return
        }
        // 本地回显（对齐原版：无官方发送 API，本地加一条弹幕）
        playerController.danmaku.addDanmakuLocal(msg)
        danmakuInput = ""
        danmakuInputFocus = false
        toast("弹幕已发送（本地回显）")
    }

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

    // 续播：进入播放页时查询历史里本集的观看进度，playResume 开启时注入 offset
    // （对齐原版 video_page._initOnlineMode：lastWatching/findProgress 取进度 → historyOffset）
    var resumeOffsetMs by remember(src, episode, road, isLocal) { mutableLongStateOf(0L) }
    var resumeChecked by remember(src, episode, road, isLocal) { mutableStateOf(false) }
    LaunchedEffect(src, episode, road, isLocal) {
        resumeChecked = false
        // playResume 关闭或隐私模式开启 → 不续播
        if (!app.settings.playResume() || app.settings.isPrivateMode()) {
            resumeChecked = true
            return@LaunchedEffect
        }
        val progress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                app.historyRepository.get(pluginName, bangumiId, if (isLocal) History.KIND_OFFLINE else History.KIND_ONLINE)
                    ?.progresses?.get(episode)
            }.getOrNull()
        }
        resumeOffsetMs = progress?.progressMs ?: 0L
        resumeChecked = true
        if (progress != null) {
            FileLogger.d("PlayerResume", "续播: bgm=$bangumiId ep=$episode posMs=${progress.progressMs}")
        }
    }

    // 续播 seek：等历史进度查询完成 + 播放器 init 就绪后，一次性 seek 到续播位置
    // （不改 params，避免重建打断视频解析；只在真正播放起来后补一次 seek）
    LaunchedEffect(resumeChecked, resumeOffsetMs, src, episode, road) {
        if (!resumeChecked || resumeOffsetMs <= 0L) return@LaunchedEffect
        // 等 init 完成（currentParams 就绪且播放意图就位），最多等 10 秒
        // 用 wantsToPlay()（playWhenReady）而不是 isPlaying()：缓冲期 isPlaying()=false，
        // 旧条件在弱网下可能 10 秒都等不到"实际在播"而丢掉续播位置
        var waited = 0
        while ((playerController.currentParams == null || !playerController.wantsToPlay()) && waited < 100) {
            delay(100)
            waited++
        }
        if (playerController.currentParams == null) return@LaunchedEffect
        val target = resumeOffsetMs
        // 一次性续播：seek 后清零，避免后续重组重复 seek
        resumeOffsetMs = 0L
        playerController.seekTo(target, enableSync = false)
        FileLogger.d("PlayerResume", "续播 seek 完成: $target ms")
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
                // 带标题装载：直链场景同样可能在解析完成前还没补名，这里带上当前已补到的名字
                playerController.fetchDanmaku(bangumiTitle.ifBlank { null })
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
                    // 带补名结果装载弹幕：params.bangumiName 在视频解析期间往往还是空串，
                    // 若不带标题，B 站兜底会因 title.isEmpty() 被跳过，弹弹play 失败后直接 EMPTY。
                    playerController.fetchDanmaku(bangumiTitle.ifBlank { null })
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

    // 读取弹幕设置（进页面 + 播放器内弹幕设置 sheet 变更后共用，
    // 保证显示区域/字号/不透明度等外观参数实时生效，无需跳转/重进）
    suspend fun reloadDanmakuAppearance() {
        val s = app.settings
        danmakuArea = s.getDouble(SettingsManager.Danmaku.AREA, 1.0).toFloat()
        danmakuFontSize = s.getDouble(SettingsManager.Danmaku.FONT_SIZE, 16.0).toInt()
        danmakuFontWeight = s.getInt(SettingsManager.Danmaku.FONT_WEIGHT, 4)
        danmakuBorder = s.getBoolean(SettingsManager.Danmaku.BORDER, true)
        danmakuBorderSize = s.getDouble(SettingsManager.Danmaku.BORDER_SIZE, 1.5).toFloat()
        danmakuOpacity = s.getDouble(SettingsManager.Danmaku.OPACITY, 1.0).toFloat()
        danmakuLineHeight = s.getDouble(SettingsManager.Danmaku.LINE_HEIGHT, 1.6).toFloat()
        danmakuShowScroll = s.getBoolean(SettingsManager.Danmaku.SCROLL, true)
        danmakuShowTop = s.getBoolean(SettingsManager.Danmaku.TOP, true)
        danmakuShowBottom = s.getBoolean(SettingsManager.Danmaku.BOTTOM, false)
        danmakuMassive = s.getBoolean(SettingsManager.Danmaku.MASSIVE, false)
        brightnessVolumeGesture = s.getBoolean(SettingsManager.Player.BRIGHTNESS_VOLUME_GESTURE, true)
    }
    LaunchedEffect(Unit) { reloadDanmakuAppearance() }

    // 定时刷新进度 + 弹幕调度
    LaunchedEffect(Unit) {
        var lastQueryMs = -1L
        // 历史写入节流：播放中每 3 秒写一次（对齐原版每秒但降低写频），
        // 首次进入播放页也会立即建历史条目（progressMs=0）。
        var lastHistoryWriteMs = -1L
        // 补名完成前先用 id 构造条目，补名后下次写入自动带名字
        var lastHistoryName = ""
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
            // 观看历史写入（对齐原版：播放中 + 非加载中 + 可记录才写）
            val canRecordHistory = playerController.isPlaying() &&
                !isResolving && uiState.isLoading.not()
            if (canRecordHistory) {
                val now = System.currentTimeMillis()
                val nameChanged = bangumiTitle.isNotBlank() && bangumiTitle != lastHistoryName
                if (lastHistoryWriteMs < 0 || now - lastHistoryWriteMs >= 3_000 || nameChanged) {
                    lastHistoryWriteMs = now
                    lastHistoryName = bangumiTitle
                    val dur = uiState.durationMs
                    val progress = if (dur > 0 && pos >= dur - 5_000) 0L else pos
                    scope.launch {
                        // 隐私模式跳过（对齐原版 getPrivateMode）
                        if (app.settings.isPrivateMode()) return@launch
                        // 优先用补名拿到的完整条目（含封面 images），否则退回标题构造
                        val item = bangumiItem
                            ?: if (bangumiTitle.isNotBlank()) {
                                com.kazumi.port.data.model.BangumiItem(
                                    id = bangumiId,
                                    name = bangumiTitle,
                                    nameCn = bangumiTitle
                                )
                            } else {
                                com.kazumi.port.data.model.BangumiItem(id = bangumiId, name = "第${episode}集")
                            }
                        app.historyRepository.recordWatch(
                            adapterName = pluginName,
                            bangumiItem = item,
                            episode = episode,
                            episodeName = "第${episode}集",
                            src = src,
                            entryKind = if (isLocal) History.KIND_OFFLINE else History.KIND_ONLINE,
                            progressMs = progress,
                            road = road
                        )
                    }
                }
            }
            delay(200)
        }
    }

    // 自动隐藏控制条（拖动进度条期间不能隐藏，否则滑到一半控件消失）
    // 隐藏延时读取设置（原版 controllerLayerDisappearTime，默认 4000ms）
    var controlsHideDelayMs by remember { mutableIntStateOf(4000) }
    LaunchedEffect(Unit) {
        controlsHideDelayMs = app.settings.getInt(
            com.kazumi.port.data.repository.SettingsManager.Player.PLAYER_CONTROLLER_LAYER_DISAPPEAR_TIME, 4000
        )
    }
    LaunchedEffect(controlsVisible, locked, barDragging) {
        if (!controlsVisible || locked || barDragging) return@LaunchedEffect
        delay(controlsHideDelayMs.toLong())
        if (!interactiveSeeking && !speedMenuOpen && !moreMenuOpen && !barDragging) {
            controlsVisible = false
        }
    }

    // 返回键：锁屏时先解锁，全屏时先退全屏，菜单展开时先收起，其余情况退出播放页
    BackHandler {
        if (locked) {
            locked = false
            controlsVisible = true
            playerController.setPanel { it.copy(lockPanel = false) }
        } else if (isFullscreen) {
            isFullscreen = false
            controlsVisible = true
        } else if (moreMenuOpen || speedMenuOpen || aspectMenuOpen) {
            moreMenuOpen = false
            speedMenuOpen = false
            aspectMenuOpen = false
        } else {
            navController.popBackStack()
        }
    }

    DisposableEffect(Unit) {
        // 进入播放页默认竖屏（对齐原版 video_page：顶部 16:9 播放器 + 下方选集面板）。
        // 方向由 isFullscreen 状态驱动（LaunchedEffect(isFullscreen)），这里只记录
        // 进入前的方向并注册退出恢复。切集是"替换"当前页（旧实例 dispose + 新实例
        // 组合同时发生），用全局引用计数避免"旧实例退出恢复竖屏、新实例又设横屏"
        // 中间状态导致的横竖屏错乱；计数归零才真正恢复进入前的方向。
        val activity = context.findActivity()
        if (activity != null) {
            PlayerOrientationManager.enter(activity)
        }
        onDispose {
            if (activity != null) {
                PlayerOrientationManager.exit(activity)
            }
            playerController.dispose()
            // 释放 WebView 解析服务
            scope.launch {
                videoSourceService.dispose()
            }
        }
    }

    // 方向随全屏状态联动：全屏=横屏，退出全屏=恢复进入前方向（通常竖屏）
    LaunchedEffect(isFullscreen) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        PlayerOrientationManager.setFullscreen(activity, isFullscreen)
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

    // 播放页布局（对齐原版 video_page）：
    // - 默认竖屏进入：顶部 16:9 播放器 + 下方"选集/线路"面板（可由下面板直接选集/切线路）。
    //   方向由 isFullscreen 驱动（默认竖屏，点全屏按钮才切横屏）。
    // - 全屏（isFullscreen=true）：隐藏下面板，播放器占满整屏。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 播放器容器：竖屏=16:9 置顶；全屏=占满剩余（唯一子级即满屏）
        Box(
            modifier = if (isFullscreen) {
                Modifier.fillMaxWidth().weight(1f)
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            }
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

            // 缓冲/加载指示：STATE_BUFFERING 时 uiState.isLoading=true。
            // 排除 isResolving——解析期有独立遮罩层，避免双层提示。
            if (uiState.isLoading && !isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        KazumiLoadingIndicator(color = Color.White, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在缓冲…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
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
                playbackSpeed = uiState.playbackSpeed,
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
                    // 双击播放/暂停中央反馈：暂停/播放都有明确的圆形图标提示
                    showPlayPauseHud = true
                    scope.launch {
                        delay(220)
                        showPlayPauseHud = false
                    }
                },
                // 长按画面临时倍速（原版 onLongPressStart/End）：按住用 defaultShortcutForwardPlaySpeed，松开恢复
                onLongPressSpeedStart = {
                    speedBeforeLongPress = uiState.playbackSpeed
                    speedHudVisible = true
                    scope.launch {
                        val speed = app.settings.getDouble(
                            com.kazumi.port.data.repository.SettingsManager.Player.DEFAULT_SHORTCUT_FORWARD_SPEED,
                            2.0
                        ).toFloat()
                        playerController.setPlaybackSpeed(speed)
                    }
                },
                onLongPressSpeedEnd = {
                    val restore = speedBeforeLongPress
                    speedBeforeLongPress = null
                    speedHudVisible = false
                    if (restore != null) playerController.setPlaybackSpeed(restore)
                },
                onHorizontalDragStart = {
                    // 只记录基准，不立即暂停。
                    // 旧实现一过 touch slop（约 8dp）就 pause：横屏观看时手指轻微横向一动
                    // 就触发暂停 → 体感"动不动就自动暂停"。现在等位移累计超过阈值（0.3 秒）才进入 seek。
                    if (!locked) {
                        seekStartPos = playerController.currentPosition()
                        seekDragAccum = 0L
                        // 用"播放意图"（playWhenReady）而不是 isPlaying()：缓冲期 isPlaying()=false，
                        // 旧代码误判会导致 seek 后不恢复播放 → "加载出来了但不播，滑一下才动"。
                        seekWasPlaying = playerController.wantsToPlay()
                        seekCancelPending = false
                    }
                },
                onHorizontalDrag = { deltaPx, viewportWidth ->
                    if (!locked) {
                        val duration = playerController.duration()
                        val seekPerFullDrag = 120_000L // 全宽 120 秒
                        // 累加而不是每次重新算：onHorizontalDrag 回调的是单次事件瞬时增量
                        seekDragAccum += (deltaPx / viewportWidth * seekPerFullDrag).toLong()
                        if (interactiveSeeking) {
                            var target = seekStartPos + seekDragAccum
                            if (target < 0) target = 0
                            if (duration > 0 && target > duration) target = duration
                            seekHudPosition = target
                            seekHudDirection = if (seekDragAccum >= 0) 1 else -1
                        } else if (kotlin.math.abs(seekDragAccum) >= 300L) {
                            // 位移换算超过 0.3 秒才真正进入 seek（防误触：轻微横向抖动不改播放状态）
                            interactiveSeeking = true
                            playerController.pause(enableSync = false)
                            showSeekHud = true
                            var target = seekStartPos + seekDragAccum
                            if (target < 0) target = 0
                            if (duration > 0 && target > duration) target = duration
                            seekHudPosition = target
                            seekHudDirection = if (seekDragAccum >= 0) 1 else -1
                        }
                    }
                },
                onHorizontalDragEnd = { cancelled ->
                    if (!locked) {
                        val wasInteractive = interactiveSeeking
                        interactiveSeeking = false
                        showSeekHud = false
                        if (wasInteractive) {
                            // 没达到阈值的手势从未暂停过，不 seek 也不恢复（保持原播放状态）
                            if (!cancelled) {
                                playerController.seekTo(seekHudPosition, enableSync = false)
                            }
                            // 恢复播放用播放意图：缓冲期 seek 完也要能接着播
                            if (seekWasPlaying) {
                                playerController.play(enableSync = false)
                            }
                        }
                        controlsVisible = true
                    }
                },
                onVerticalDragStart = {
                    // 手势起点：锁定基准值并重置累计位移。
                    // 音量基准 = 系统当前媒体音量（实时）；亮度基准 = 当前实际生效的屏幕亮度，
                    // 不能取 panelState.brightness（上次手势记忆值，默认 0）——否则系统 100% 亮度时
                    // 基准却是 0，轻滑一下 delta>0 → 亮度 0+eps ≈ 最低（用户反馈"100 亮度轻滑变最低"）。
                    if (!locked && brightnessVolumeGesture) {
                        verticalDragBaseVolume = playerController.systemVolume() * 100f
                        verticalDragBaseBrightness = currentScreenBrightness(context)
                        verticalDragAccum = 0f
                    }
                },
                onVerticalDrag = { deltaPx, viewportHeight, startX, viewportWidth ->
                    if (!locked && brightnessVolumeGesture) {
                        // 左侧调亮度，右侧调音量
                        val isLeft = startX < viewportWidth / 2
                        // 亮度：拖 60% 屏高走满全量（保持原手感）
                        val fullDrag = viewportHeight * 0.6f
                        // 音量：拖满约 3 个屏高才走到 100%，滑动更精细，
                        // 避免随手一划（1/4 屏）就把音量拉满（旧值 0.6 屏=满音量，误触直接社死）
                        val volumeFullDrag = viewportHeight * 3f
                        // 累加总位移：deltaPx 是单次事件瞬时增量，必须累积后再换算，
                        // 否则每次"基准+瞬时增量"会在基准附近反复重置 → 拖不动/原地抖
                        verticalDragAccum += deltaPx
                        if (isLeft) {
                            showBrightnessHud = true
                            showVolumeHud = false
                            val delta = -verticalDragAccum / fullDrag
                            hudBrightness = (verticalDragBaseBrightness + delta).coerceIn(0f, 1f)
                            playerController.setBrightness(hudBrightness.toDouble())
                            setScreenBrightness(context, hudBrightness)
                        } else {
                            showVolumeHud = true
                            showBrightnessHud = false
                            val delta = -verticalDragAccum / volumeFullDrag * 100f
                            hudVolume = (verticalDragBaseVolume + delta).coerceIn(0f, 100f)
                            playerController.setSystemVolume(hudVolume / 100f)
                        }
                    }
                },
                onVerticalDragEnd = {
                    showVolumeHud = false
                    showBrightnessHud = false
                }
            )

            // 视频源解析状态层（在线播放 WebView 解析时显示）
            // 注意：必须放在手势层【之后】（最上层），否则被 PlayerGestureLayer 盖住，
            // 错误页的"重试"按钮点了没反应（手势层全屏消费点击事件）。
            if (isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        KazumiLoadingIndicator(color = Color.White)
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
        }

        // 顶部控制条（对齐原版 player_item_panel._buildTopControls）：
        // 返回 + 标题 + （竖屏）弹幕开关 + 更多菜单；全屏按钮在底部控制条最右。
        // 竖屏（非全屏）把弹幕开关放顶部，横屏时它归入底部宽控件行，避免顶底重复。
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                    Text(
                        text = buildString {
                            append(bangumiTitle.ifEmpty { uiState.bangumiName.ifEmpty { "播放" } })
                            if (episode > 0 && roads.isNotEmpty()) append(" [第${episode}集]")
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    // 弹幕开关（对齐原版 _buildTopControls：仅 compact（竖屏）时在顶部显示）
                    if (!isFullscreen) {
                        IconButton(
                            onClick = {
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
                            },
                            enabled = !danmakuLoading
                        ) {
                            if (danmakuLoading) {
                                KazumiLoadingIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                            } else {
                                Icon(
                                    Icons.Filled.Subtitles,
                                    contentDescription = if (danmakuOn) "关闭弹幕" else "打开弹幕",
                                    tint = if (danmakuOn) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }
                    }
                    // 更多菜单（对齐原版 compact 更多菜单：选集/下一集/倍速/视频比例/弹幕设置/截图/锁定/弹幕源/一起看）
                    Box {
                        IconButton(onClick = { moreMenuOpen = !moreMenuOpen }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多选项", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = moreMenuOpen,
                            onDismissRequest = { moreMenuOpen = false },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            if (roads.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("选集") },
                                    onClick = {
                                        moreMenuOpen = false
                                        showEpisodeSheet = true
                                    }
                                )
                            }
                            if (roads.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("下一集") },
                                    onClick = {
                                        moreMenuOpen = false
                                        playNextOrPrev(1)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("倍速 ${formatSpeed(uiState.playbackSpeed)}x") },
                                onClick = {
                                    moreMenuOpen = false
                                    showSpeedDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("视频比例") },
                                onClick = {
                                    moreMenuOpen = false
                                    showAspectDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (danmakuOn) "弹幕设置" else "打开弹幕") },
                                onClick = {
                                    moreMenuOpen = false
                                    if (danmakuOn) {
                                        showDanmakuSettings = true
                                    } else if (danmakuLoading) {
                                        return@DropdownMenuItem
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
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("截图") },
                                onClick = {
                                    moreMenuOpen = false
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
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (locked) "解锁" else "锁定") },
                                onClick = {
                                    moreMenuOpen = false
                                    locked = !locked
                                    controlsVisible = !locked
                                    playerController.setPanel { it.copy(lockPanel = locked) }
                                }
                            )
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
                PlayerSpeedHud(visible = speedHudVisible, speed = uiState.playbackSpeed)
            }
            // 双击播放/暂停中央反馈（对齐原版"切换播控有明确提示"的体验）
            Box(modifier = Modifier.align(Alignment.Center)) {
                PlayerPlayPauseHud(visible = showPlayPauseHud, isPlaying = uiState.isPlaying)
            }
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)) {
                PlayerAdjustmentHud(
                    visible = showVolumeHud || showBrightnessHud,
                    type = if (showVolumeHud) PlayerAdjustmentHudType.Volume else PlayerAdjustmentHudType.Brightness,
                    value = if (showVolumeHud) hudVolume else hudBrightness
                )
            }
        }

        // 底部控制条（对齐原版 flutter PlayerTransportBar / player_item_panel._buildBottomControls）
        // 竖屏（compact，!isFullscreen）：Column [时间] / [进度条] / [播放-暂停 下一集 --- 全屏]——单行不超高
        // 全屏（isFullscreen）：Row [播放-暂停] [下一集] [快退] [快进] [静音] [弹幕开关] [倍速] [视频比例] [弹幕设置] --- [全屏]
        // 全屏按钮固定在底部最右（原版 compact 也是底部最右，不放顶部）
        if (controlsVisible && !locked) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // 当前/总时间（竖屏在进度条上方，对齐原版 aboveProgress）
                Text(
                    text = formatMs(uiState.currentPositionMs) + " / " + formatMs(uiState.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                // 进度条：可拖动 + 拖动时间气泡（对齐原版 player_progress_bar）
                val barProgress = if (uiState.durationMs > 0) {
                    val raw = if (barDragging) barValue
                    else uiState.currentPositionMs.toFloat() / uiState.durationMs.toFloat()
                    raw.coerceIn(0f, 1f)
                } else 0f
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (barDragging && uiState.durationMs > 0 && maxWidth > 0.dp) {
                        val frac = barValue.coerceIn(0f, 1f)
                        val bubbleText = formatMs((barValue * uiState.durationMs).toLong())
                        val bubbleWidth = 68.dp
                        val left = (maxWidth - bubbleWidth) * frac
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .offset(x = left, y = (-42).dp)
                                .width(bubbleWidth)
                                .height(32.dp)
                                .zIndex(1f)
                                .background(
                                    Color.Black.copy(alpha = 0.85f),
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = bubbleText,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
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
                }
                // 按钮行：竖屏只保留 播放/暂停 + 下一集 + 全屏；宽控件仅横屏显示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 播放/暂停（原版 playPause）
                    IconButton(onClick = { playerController.playOrPause() }) {
                        Icon(
                            if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "暂停" else "播放",
                            tint = Color.White
                        )
                    }
                    // 下一集（原版 nextEpisode）
                    if (roads.isNotEmpty()) {
                        IconButton(onClick = { playNextOrPrev(1) }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "下一集", tint = Color.White)
                        }
                    }
                    // 以下宽控件仅在横屏全屏时显示（对齐原版：竖屏 compact 收起）
                    if (isFullscreen) {
                        val skipBack = {
                            val target = (uiState.currentPositionMs - buttonSkipTime * 1000L).coerceAtLeast(0L)
                            playerController.seekTo(target, enableSync = false)
                        }
                        val skipForward = {
                            val max = uiState.durationMs
                            var target = uiState.currentPositionMs + buttonSkipTime * 1000L
                            if (max > 0 && target > max) target = max
                            playerController.seekTo(target, enableSync = false)
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .combinedClickable(
                                    onClick = { skipBack() },
                                    onLongClick = { showSkipTimeDialog = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.FastRewind, contentDescription = "快退 ${buttonSkipTime} 秒", tint = Color.White)
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .combinedClickable(
                                    onClick = { skipForward() },
                                    onLongClick = { showSkipTimeDialog = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.FastForward, contentDescription = "快进 ${buttonSkipTime} 秒", tint = Color.White)
                        }
                        // 静音
                        IconButton(onClick = { playerController.toggleSystemMute() }) {
                            Icon(
                                if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (uiState.isMuted) "取消静音" else "静音",
                                tint = Color.White
                            )
                        }
                        // 弹幕开关（横屏在底部，竖屏在顶部）
                        IconButton(
                            onClick = {
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
                            },
                            enabled = !danmakuLoading
                        ) {
                            if (danmakuLoading) {
                                KazumiLoadingIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                            } else {
                                Icon(
                                    Icons.Filled.Subtitles,
                                    contentDescription = if (danmakuOn) "关闭弹幕" else "打开弹幕",
                                    tint = if (danmakuOn) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }
                        // 倍速（点击弹选择对话框）
                        IconButton(onClick = { showSpeedDialog = true }) {
                            Text(
                                text = "${formatSpeed(uiState.playbackSpeed)}x",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        // 视频比例（点击弹选择对话框）
                        IconButton(onClick = { showAspectDialog = true }) {
                            Icon(Icons.Filled.AspectRatio, contentDescription = "视频比例", tint = Color.White)
                        }
                        // 弹幕设置（弹幕开启时显示）
                        if (danmakuOn) {
                            IconButton(onClick = { showDanmakuSettings = true }) {
                                Icon(Icons.Filled.Tune, contentDescription = "弹幕设置", tint = Color.White)
                            }
                            // 发送弹幕输入框（对齐原版 _danmakuTextField：仅横屏全屏时显示）
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(40.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.15f))
                            ) {
                                if (!danmakuInputFocus && danmakuInput.isEmpty()) {
                                    Text(
                                        text = "发个友善的弹幕见证当下",
                                        color = Color.White.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(horizontal = 8.dp)
                                    )
                                }
                                androidx.compose.foundation.text.BasicTextField(
                                    value = danmakuInput,
                                    onValueChange = { danmakuInput = it.take(100) },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontSize = 13.sp),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                )
                                // 发送按钮
                                if (danmakuInput.isNotBlank()) {
                                    TextButton(
                                        onClick = { sendDanmaku() },
                                        modifier = Modifier.align(Alignment.CenterEnd)
                                    ) {
                                        Text("发送", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 全屏/退出全屏（对齐原版：底部最右）
                    IconButton(onClick = { isFullscreen = !isFullscreen }) {
                        Icon(
                            if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                            tint = Color.White
                        )
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
        }

        // 竖屏下面板（非全屏时显示，占满播放器下方剩余空间）：对齐原版 episode_selection_panel.dart，
        // 在播放器下方展示：标题 + 线路下拉选择器 + 工具栏（计数/定位当前集）+ 剧集卡片组列表
        if (!isFullscreen && roads.isNotEmpty()) {
            NonFullscreenEpisodePanel(
                title = bangumiTitle.ifEmpty { uiState.bangumiName },
                roads = roads,
                selectedRoad = road,
                selectedEpisode = episode,
                isPlaying = uiState.isPlaying,
                onSelect = { epIdx, roadIdx -> switchEpisode(epIdx, roadIdx) },
                modifier = Modifier.weight(1f)
            )
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

        // 播放器内弹幕设置 sheet（对齐原版 danmaku_settings_sheet）
        if (showDanmakuSettings) {
            DanmakuSettingsSheet(
                settings = app.settings,
                onDismiss = { showDanmakuSettings = false },
                onChanged = { scope.launch { reloadDanmakuAppearance() } },
                // 仅简繁转换变更触发：重新拉弹幕（fetchDanmaku 内部已清空旧弹幕，
                // 重新按新 chConvert 设置拉取，本地转换在 addDanmakus 统一做。
                // 必须 forceReload=true：简繁转换改了设置，幂等复用会拦截"同集重装"导致不生效）
                onChConvertChanged = {
                    scope.launch {
                        toast("已更新简繁转换，重新装载弹幕…")
                        playerController.fetchDanmaku(forceReload = true)
                    }
                }
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
        // 选集切换 sheet（线路数据来自详情页；竖屏下面板存在时此弹窗作为"更多"入口的补充）
        if (showEpisodeSheet && roads.isNotEmpty()) {
            EpisodeSelectionSheet(
                title = bangumiTitle.ifEmpty { uiState.bangumiName },
                roads = roads,
                selectedRoad = road,
                selectedEpisode = episode,
                onSelect = { episodeIndex, roadIndex ->
                    showEpisodeSheet = false
                    switchEpisode(episodeIndex, roadIndex)
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
    // 本地文件：file:// 或 Android 本地存储绝对路径（/data/...、/storage/...、/sdcard/...）
    // 注意：网站相对路径（/voddetail/xxx.html、/play/xxx 等以 / 开头但无本地前缀）不算直链，
    // 必须走 WebView 解析出真实媒体地址，否则 ExoPlayer 拿到 HTML/相对路径 → Malformed URL。
    if (url.startsWith("file://")) return true
    if (url.startsWith("/data/") || url.startsWith("/storage/") || url.startsWith("/sdcard")) return true
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
    onVerticalDragStart: () -> Unit = {},
    onVerticalDrag: (deltaPx: Float, viewportHeight: Float, startX: Float, viewportWidth: Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
    /** 长按画面临时倍速（原版 onLongPressStart/End） */
    onLongPressSpeedStart: () -> Unit = {},
    onLongPressSpeedEnd: () -> Unit = {}
) {
    var horizontalDragActive by remember { mutableStateOf(false) }
    var verticalDragActive by remember { mutableStateOf(false) }
    /** vertical 手势的起点 X（锁定左右半屏判定，horizontal 不碰它，避免滑音量途中被覆盖成亮度） */
    var verticalStartX by remember { mutableFloatStateOf(0f) }
    var longPressSpeedActive by remember { mutableStateOf(false) }
    Box(
        modifier = modifier.pointerInput(locked) {
            if (locked) return@pointerInput
            detectTapGestures(
                onPress = {
                    longPressSpeedActive = false
                    tryAwaitRelease()
                    if (longPressSpeedActive) {
                        longPressSpeedActive = false
                        onLongPressSpeedEnd()
                    }
                },
                onLongPress = {
                    longPressSpeedActive = true
                    onLongPressSpeedStart()
                },
                onTap = { onTap() },
                onDoubleTap = { onDoubleTap() }
            )
        }.pointerInput(locked) {
            if (locked) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    // 垂直手势进行中时忽略水平手势（一次手势只做一个动作，防止滑音量时误触发 seek/暂停）
                    if (!verticalDragActive) {
                        horizontalDragActive = true
                        onHorizontalDragStart()
                    }
                },
                onDragEnd = {
                    horizontalDragActive = false
                    onHorizontalDragEnd(false)
                },
                onDragCancel = {
                    horizontalDragActive = false
                    onHorizontalDragEnd(true)
                },
                onHorizontalDrag = { change, dragAmount ->
                    if (horizontalDragActive) {
                        change.consume()
                        onHorizontalDrag(dragAmount, size.width.toFloat())
                    }
                }
            )
        }.pointerInput(locked) {
            if (locked) return@pointerInput
            detectVerticalDragGestures(
                onDragStart = { offset ->
                    if (!horizontalDragActive) {
                        verticalStartX = offset.x
                        verticalDragActive = true
                        onVerticalDragStart()
                    }
                },
                onDragEnd = {
                    verticalDragActive = false
                    onVerticalDragEnd()
                },
                onDragCancel = {
                    verticalDragActive = false
                    onVerticalDragEnd()
                },
                onVerticalDrag = { change, dragAmount ->
                    if (verticalDragActive) {
                        change.consume()
                        onVerticalDrag(dragAmount, size.height.toFloat(), verticalStartX, size.width.toFloat())
                    }
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

/**
 * 读取当前实际生效的屏幕亮度（0..1）：
 * 1) 若本 Activity 窗口已设置过 screenBrightness（>0 表示窗口级覆盖），取窗口值；
 * 2) 否则读系统亮度 Settings.System.SCREEN_BRIGHTNESS（0..255）归一化到 0..1。
 * 用于手势亮度基准与 HUD 初始化——绝不能取面板记忆值（默认 0），否则系统 100% 亮度
 * 时基准为 0，轻滑即掉到最低（用户反馈的亮度 bug 根因）。
 */
private fun currentScreenBrightness(context: android.content.Context): Float {
    val activity = context.findActivity()
    val windowValue = activity?.window?.attributes?.screenBrightness
    if (windowValue != null && windowValue >= 0f) return windowValue.coerceIn(0f, 1f)
    return try {
        val sys = android.provider.Settings.System.getInt(
            context.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS
        )
        (sys.coerceIn(0, 255) / 255f).coerceIn(0f, 1f)
    } catch (e: Exception) {
        0.5f
    }
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

/**
 * 播放页方向引用计数管理器。
 *
 * 进入播放页只记录进入前的方向并 +1（不强制横屏）；方向完全由全屏状态
 * （setFullscreen）驱动——退出全屏时恢复进入前方向（通常竖屏），点全屏才横屏。
 * 切集 = "替换"当前页（旧实例 dispose + 新实例组合同时发生），若没有计数，
 * 旧实例 dispose 会把方向恢复成竖屏、新实例又设横屏，中间状态导致横竖屏错乱。
 * 计数归零才真正恢复进入前的方向（保证退出播放页始终回竖屏）。
 */
private object PlayerOrientationManager {
    private var refCount = 0
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Synchronized
    fun enter(activity: Activity) {
        if (refCount == 0) {
            previousOrientation = activity.requestedOrientation
            // 内置播放页自身的全屏状态由外部 setFullscreen 驱动，这里不强行设横屏
        }
        refCount++
    }

    /** 全屏联动：true=横屏，false=恢复进入前方向（通常竖屏） */
    @Synchronized
    fun setFullscreen(activity: Activity, fullscreen: Boolean) {
        if (refCount <= 0) return
        activity.requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            previousOrientation.let { if (it == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else it }
        }
    }

    @Synchronized
    fun exit(activity: Activity) {
        if (refCount <= 0) return
        refCount--
        if (refCount == 0) {
            activity.requestedOrientation =
                previousOrientation.let { if (it == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else it }
        }
    }
}

/**
 * 竖屏下面板（非全屏播放时显示，对齐原版 episode_selection_panel.dart）：
 * 在播放器下方直接展示：标题 + 线路下拉选择器 + 工具栏（计数/定位当前集/缓存）
 * + 剧集卡片组列表（首尾 20dp 大圆角、中间 4dp、行间 2dp、左右 16dp）。
 * 全屏时不渲染（由调用处 if (!isFullscreen) 控制）。
 */
@Composable
private fun NonFullscreenEpisodePanel(
    title: String,
    roads: List<com.kazumi.port.data.model.Road>,
    selectedRoad: Int,
    selectedEpisode: Int,
    isPlaying: Boolean = false,
    onSelect: (episodeIndex: Int, roadIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var visibleRoad by remember { mutableIntStateOf(selectedRoad.coerceIn(0, (roads.size - 1).coerceAtLeast(0))) }
    val road = roads.getOrNull(visibleRoad)
    val count = road?.data?.size ?: 0
    val scope = rememberCoroutineScope()
    // 列表滚动状态（用于「定位当前集」与自动滚到当前集）
    val listState = rememberLazyListState()
    // 进入时把当前集滚到可视区（对齐原版 revealCurrentEpisode）
    LaunchedEffect(visibleRoad, selectedEpisode, count) {
        if (visibleRoad == selectedRoad && selectedEpisode > 0 && count > 0) {
            val idx = (selectedEpisode - 1).coerceIn(0, count - 1)
            listState.animateScrollToItem(idx)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(bottom = 8.dp)
    ) {
        // 标题（对齐原版：titleLarge + w700）
        Text(
            text = title.ifBlank { "剧集列表" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
        )
        // 线路选择器（对齐原版 _RoadSelector：下拉式按钮，非横向 chips）
        if (roads.size > 1) {
            var roadMenuOpen by remember { mutableStateOf(false) }
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { roadMenuOpen = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "播放线路",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = road?.name?.trim()?.ifEmpty { "播放线路 ${visibleRoad + 1}" } ?: "暂无线路",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Filled.ExpandMore, contentDescription = "切换线路", tint = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(
                    expanded = roadMenuOpen,
                    onDismissRequest = { roadMenuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    roads.forEachIndexed { index, r ->
                        val name = r.name.trim().ifEmpty { "播放线路 ${index + 1}" }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${r.data.size} 集",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                roadMenuOpen = false
                                if (visibleRoad == index) return@DropdownMenuItem
                                visibleRoad = index
                                // 点线路立即切到该线路的当前集播放（与原版一致）
                                if (index != selectedRoad) {
                                    val target = roads.getOrNull(index)
                                    val maxIdx = (target?.data?.size ?: 0) - 1
                                    val epIdx = (selectedEpisode - 1).coerceIn(0, maxIdx.coerceAtLeast(0))
                                    onSelect(epIdx, index)
                                }
                            }
                        )
                    }
                }
            }
        }
        // 工具栏（对齐原版 _EpisodeToolbar：计数 - 定位当前集 - 缓存）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "全部剧集 · $count 集",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    if (visibleRoad == selectedRoad && selectedEpisode > 0 && count > 0) {
                        scope.launch {
                            listState.animateScrollToItem((selectedEpisode - 1).coerceIn(0, count - 1))
                        }
                    }
                },
                enabled = visibleRoad == selectedRoad && selectedEpisode > 0 && count > 0
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "定位当前集", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (count == 0) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("这条线路暂无剧集", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        // 剧集卡片组列表（对齐原版 _EpisodeRow：首尾 20dp、中间 4dp、行间 2dp、左右 16dp）
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(count, key = { visibleRoad to it }) { idx ->
                val ep = idx + 1
                val name = road?.identifier?.getOrNull(idx)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "第${ep}集"
                val selected = visibleRoad == selectedRoad && ep == selectedEpisode
                val first = idx == 0
                val last = idx == count - 1
                val shape = if (selected) {
                    RoundedCornerShape(20.dp)
                } else {
                    RoundedCornerShape(
                        topStart = if (first) 20.dp else 4.dp,
                        topEnd = if (first) 20.dp else 4.dp,
                        bottomStart = if (last) 20.dp else 4.dp,
                        bottomEnd = if (last) 20.dp else 4.dp
                    )
                }
                val subtitle = when {
                    selected && isPlaying -> "正在播放"
                    selected -> "当前选集"
                    else -> "播放"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                        .clickable { onSelect(idx, visibleRoad) }
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
