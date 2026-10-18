package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import kotlinx.coroutines.launch

/**
 * 播放设置页（对应原版 player_settings.dart）
 */
@Composable
fun PlayerSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()

    var hAenable by remember { mutableStateOf(false) }
    var androidEnableOpenSLES by remember { mutableStateOf(false) }
    var androidAutoEnterPIP by remember { mutableStateOf(false) }
    var playResume by remember { mutableStateOf(true) }
    var privateMode by remember { mutableStateOf(false) }
    var showPlayerError by remember { mutableStateOf(false) }
    var playerDebugMode by remember { mutableStateOf(false) }
    var autoPlayNext by remember { mutableStateOf(true) }
    var backgroundPlayback by remember { mutableStateOf(false) }
    var playerDisableAnimations by remember { mutableStateOf(false) }
    var forceAdBlocker by remember { mutableStateOf(false) }
    var brightnessVolumeGesture by remember { mutableStateOf(true) }
    var playerLogLevel by remember { mutableIntStateOf(0) }
    var defaultPlaySpeed by remember { mutableDoubleStateOf(1.0) }
    var defaultShortcutForwardPlaySpeed by remember { mutableDoubleStateOf(2.0) }
    var playerArrowKeySkipTime by remember { mutableIntStateOf(5) }
    var playerButtonSkipTime by remember { mutableIntStateOf(30) }
    var playerControllerLayerDisappearTime by remember { mutableIntStateOf(3000) }

    LaunchedEffect(Unit) {
        hAenable = settings.getBoolean(SettingsManager.Player.HA_ENABLE, false)
        androidEnableOpenSLES = settings.getBoolean(SettingsManager.Player.ANDROID_ENABLE_OPENSLES, false)
        androidAutoEnterPIP = settings.getBoolean(SettingsManager.Player.ANDROID_AUTO_ENTER_PIP, false)
        playResume = settings.getBoolean(SettingsManager.Player.PLAY_RESUME, true)
        privateMode = settings.getBoolean(SettingsManager.Player.PRIVATE_MODE, false)
        showPlayerError = settings.getBoolean(SettingsManager.Player.SHOW_PLAYER_ERROR, false)
        playerDebugMode = settings.getBoolean(SettingsManager.Player.PLAYER_DEBUG_MODE, false)
        autoPlayNext = settings.getBoolean(SettingsManager.Player.AUTO_PLAY_NEXT, true)
        backgroundPlayback = settings.getBoolean(SettingsManager.Player.BACKGROUND_PLAYBACK, false)
        playerDisableAnimations = settings.getBoolean(SettingsManager.Player.PLAYER_DISABLE_ANIMATIONS, false)
        forceAdBlocker = settings.getBoolean(SettingsManager.Player.FORCE_AD_BLOCKER, false)
        brightnessVolumeGesture = settings.getBoolean(SettingsManager.Player.BRIGHTNESS_VOLUME_GESTURE, true)
        playerLogLevel = settings.getInt(SettingsManager.Player.PLAYER_LOG_LEVEL, 0)
        defaultPlaySpeed = settings.getDouble(SettingsManager.Player.DEFAULT_PLAY_SPEED, 1.0)
        defaultShortcutForwardPlaySpeed = settings.getDouble(SettingsManager.Player.DEFAULT_SHORTCUT_FORWARD_SPEED, 2.0)
        playerArrowKeySkipTime = settings.getInt(SettingsManager.Player.ARROW_KEY_SKIP_TIME, 5)
        playerButtonSkipTime = settings.getInt(SettingsManager.Player.BUTTON_SKIP_TIME, 30)
        playerControllerLayerDisappearTime = settings.getInt(SettingsManager.Player.PLAYER_CONTROLLER_LAYER_DISAPPEAR_TIME, 3000)
    }

    fun saveBoolean(key: String, value: Boolean) {
        scope.launch { settings.setBoolean(key, value) }
    }

    SettingsSubPageScaffold(
        title = "播放设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("解码与渲染") {
                SettingSwitchRow(
                    title = "硬件解码",
                    leading = Icons.Filled.Memory,
                    checked = hAenable,
                    onCheckedChange = { hAenable = it; saveBoolean(SettingsManager.Player.HA_ENABLE, it) }
                )
                SettingSwitchRow(
                    title = "低延迟音频",
                    subtitle = "启用OpenSLES音频输出以降低延时",
                    leading = Icons.Filled.GraphicEq,
                    checked = androidEnableOpenSLES,
                    onCheckedChange = { androidEnableOpenSLES = it; saveBoolean(SettingsManager.Player.ANDROID_ENABLE_OPENSLES, it) }
                )
                SettingClickRow(
                    title = "超分辨率",
                    leading = Icons.Filled.AutoAwesome,
                    onClick = {}
                )
            }

            SettingGroup("播放行为") {
                SettingSwitchRow(
                    title = "后台播放",
                    subtitle = "应用退到后台或熄屏时继续播放音频",
                    leading = Icons.Filled.Headphones,
                    checked = backgroundPlayback,
                    onCheckedChange = { backgroundPlayback = it; saveBoolean(SettingsManager.Player.BACKGROUND_PLAYBACK, it) }
                )
                SettingSwitchRow(
                    title = "自动跳转",
                    subtitle = "跳转到上次播放位置",
                    leading = Icons.Filled.History,
                    checked = playResume,
                    onCheckedChange = { playResume = it; saveBoolean(SettingsManager.Player.PLAY_RESUME, it) }
                )
                SettingSwitchRow(
                    title = "自动连播",
                    subtitle = "当前视频播放完毕后自动播放下一集",
                    leading = Icons.Filled.PlaylistPlay,
                    checked = autoPlayNext,
                    onCheckedChange = { autoPlayNext = it; saveBoolean(SettingsManager.Player.AUTO_PLAY_NEXT, it) }
                )
                SettingSwitchRow(
                    title = "自动进入画中画",
                    subtitle = "切到后台时，自动进入画中画",
                    leading = Icons.Filled.PictureInPictureAlt,
                    checked = androidAutoEnterPIP,
                    onCheckedChange = { androidAutoEnterPIP = it; saveBoolean(SettingsManager.Player.ANDROID_AUTO_ENTER_PIP, it) }
                )
                SettingSwitchRow(
                    title = "广告过滤",
                    subtitle = "强制启用HLS广告过滤，忽略规则设置",
                    leading = Icons.Filled.Block,
                    checked = forceAdBlocker,
                    onCheckedChange = { forceAdBlocker = it; saveBoolean(SettingsManager.Player.FORCE_AD_BLOCKER, it) }
                )
                SettingSwitchRow(
                    title = "禁用动画",
                    subtitle = "禁用播放器内的过渡动画",
                    leading = Icons.Filled.Animation,
                    checked = playerDisableAnimations,
                    onCheckedChange = { playerDisableAnimations = it; saveBoolean(SettingsManager.Player.PLAYER_DISABLE_ANIMATIONS, it) }
                )
                SettingSwitchRow(
                    title = "滑动手势",
                    subtitle = "竖向滑动调节音量和亮度",
                    leading = Icons.Filled.SwipeVertical,
                    checked = brightnessVolumeGesture,
                    onCheckedChange = { brightnessVolumeGesture = it; saveBoolean(SettingsManager.Player.BRIGHTNESS_VOLUME_GESTURE, it) }
                )
                SettingSwitchRow(
                    title = "隐身模式",
                    subtitle = "不保留观看记录",
                    leading = Icons.Filled.VisibilityOff,
                    checked = privateMode,
                    onCheckedChange = { privateMode = it; saveBoolean(SettingsManager.Player.PRIVATE_MODE, it) }
                )
            }

            SettingGroup("诊断") {
                SettingSwitchRow(
                    title = "错误提示",
                    subtitle = "显示播放器内部错误提示",
                    leading = Icons.Filled.ErrorOutline,
                    checked = showPlayerError,
                    onCheckedChange = { showPlayerError = it; saveBoolean(SettingsManager.Player.SHOW_PLAYER_ERROR, it) }
                )
                SettingSwitchRow(
                    title = "调试模式",
                    subtitle = "记录播放器内部日志",
                    leading = Icons.Filled.BugReport,
                    checked = playerDebugMode,
                    onCheckedChange = { playerDebugMode = it; saveBoolean(SettingsManager.Player.PLAYER_DEBUG_MODE, it) }
                )
                SettingClickRow(
                    title = "日志等级",
                    subtitle = "播放器内部日志等级",
                    leading = Icons.Filled.ReceiptLong,
                    trailingValue = when (playerLogLevel) { 0 -> "无"; 1 -> "错误"; 2 -> "警告"; 3 -> "信息"; else -> "调试" },
                    onClick = {
                        playerLogLevel = (playerLogLevel + 1) % 5
                        scope.launch { settings.setInt(SettingsManager.Player.PLAYER_LOG_LEVEL, playerLogLevel) }
                    }
                )
            }

            SettingGroup("播放参数") {
                Slider(
                    value = defaultPlaySpeed.toFloat(),
                    onValueChange = { defaultPlaySpeed = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Player.DEFAULT_PLAY_SPEED, defaultPlaySpeed) }
                    },
                    valueRange = 0.25f..3f
                )
                Text(
                    text = "默认倍速 ${"%.2f".format(defaultPlaySpeed)}x",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = defaultShortcutForwardPlaySpeed.toFloat(),
                    onValueChange = { defaultShortcutForwardPlaySpeed = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Player.DEFAULT_SHORTCUT_FORWARD_SPEED, defaultShortcutForwardPlaySpeed) }
                    },
                    valueRange = 1.25f..3f
                )
                Text(
                    text = "长按倍速 ${"%.2f".format(defaultShortcutForwardPlaySpeed)}x",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                SettingClickRow(
                    title = "方向键跳转",
                    subtitle = "左右方向键的快进/快退秒数",
                    leading = Icons.Filled.SwapHoriz,
                    trailingValue = "$playerArrowKeySkipTime 秒",
                    onClick = {
                        playerArrowKeySkipTime = (playerArrowKeySkipTime + 1) % 16
                        scope.launch { settings.setInt(SettingsManager.Player.ARROW_KEY_SKIP_TIME, playerArrowKeySkipTime) }
                    }
                )
                SettingClickRow(
                    title = "跳过时长",
                    subtitle = "顶栏跳过按钮的秒数",
                    leading = Icons.Filled.SkipNext,
                    trailingValue = "$playerButtonSkipTime 秒",
                    onClick = {
                        playerButtonSkipTime = if (playerButtonSkipTime >= 60) 10 else playerButtonSkipTime + 10
                        scope.launch { settings.setInt(SettingsManager.Player.BUTTON_SKIP_TIME, playerButtonSkipTime) }
                    }
                )
                SettingClickRow(
                    title = "控制栏消失时间",
                    subtitle = "播放控制器自动隐藏前的停留时长",
                    leading = Icons.Filled.Timer,
                    trailingValue = "${playerControllerLayerDisappearTime / 1000} 秒",
                    onClick = {
                        playerControllerLayerDisappearTime = if (playerControllerLayerDisappearTime >= 10000) 1000 else playerControllerLayerDisappearTime + 1000
                        scope.launch { settings.setInt(SettingsManager.Player.PLAYER_CONTROLLER_LAYER_DISAPPEAR_TIME, playerControllerLayerDisappearTime) }
                    }
                )
                SettingClickRow(
                    title = "默认视频比例",
                    leading = Icons.Filled.AspectRatio,
                    onClick = {}
                )
            }

            SettingGroup {
                SettingClickRow(
                    title = "恢复默认设置",
                    subtitle = "将播放相关设置恢复为默认值",
                    leading = Icons.Filled.Restore,
                    onClick = {
                        scope.launch {
                            settings.setBoolean(SettingsManager.Player.HA_ENABLE, false)
                            settings.setBoolean(SettingsManager.Player.ANDROID_ENABLE_OPENSLES, false)
                            settings.setBoolean(SettingsManager.Player.ANDROID_AUTO_ENTER_PIP, false)
                            settings.setBoolean(SettingsManager.Player.PLAY_RESUME, true)
                            settings.setBoolean(SettingsManager.Player.PRIVATE_MODE, false)
                            settings.setBoolean(SettingsManager.Player.AUTO_PLAY_NEXT, true)
                            settings.setBoolean(SettingsManager.Player.BACKGROUND_PLAYBACK, false)
                            settings.setBoolean(SettingsManager.Player.FORCE_AD_BLOCKER, false)
                            settings.setDouble(SettingsManager.Player.DEFAULT_PLAY_SPEED, 1.0)
                            settings.setDouble(SettingsManager.Player.DEFAULT_SHORTCUT_FORWARD_SPEED, 2.0)
                        }
                    }
                )
            }
        }
    }
}