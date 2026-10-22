package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatLineSpacing
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.kazumi.port.ui.components.rememberToast
import kotlinx.coroutines.launch

/**
 * 弹幕设置页（对应原版 danmaku_settings.dart）
 */
@Composable
fun DanmakuSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val showToast = rememberToast()

    var biliSource by remember { mutableStateOf(true) }
    var gamerSource by remember { mutableStateOf(false) }
    var danDanSource by remember { mutableStateOf(false) }
    var danDanAppId by remember { mutableStateOf("") }
    var danDanAppSecret by remember { mutableStateOf("") }
    var showCredDialog by remember { mutableStateOf(false) }
    var editAppId by remember { mutableStateOf("") }
    var editAppSecret by remember { mutableStateOf("") }
    var followSpeed by remember { mutableStateOf(false) }
    var danmakuTop by remember { mutableStateOf(true) }
    var danmakuBottom by remember { mutableStateOf(true) }
    var danmakuScroll by remember { mutableStateOf(true) }
    var danmakuMassive by remember { mutableStateOf(false) }
    var danmakuDeduplication by remember { mutableStateOf(false) }
    var danmakuBorder by remember { mutableStateOf(true) }
    var danmakuColor by remember { mutableStateOf(true) }
    var danmakuArea by remember { mutableDoubleStateOf(0.5) }
    var danmakuDuration by remember { mutableDoubleStateOf(8.0) }
    var danmakuLineHeight by remember { mutableDoubleStateOf(1.0) }
    var danmakuBorderSize by remember { mutableDoubleStateOf(1.0) }
    var danmakuFontSize by remember { mutableDoubleStateOf(24.0) }
    var danmakuFontWeight by remember { mutableIntStateOf(4) }
    var danmakuOpacity by remember { mutableDoubleStateOf(0.8) }

    LaunchedEffect(Unit) {
        biliSource = settings.getBoolean(SettingsManager.Danmaku.BILI_SOURCE, true)
        gamerSource = settings.getBoolean(SettingsManager.Danmaku.GAMER_SOURCE, false)
        danDanSource = settings.getBoolean(SettingsManager.Danmaku.DAN_DAN_SOURCE, true)
        danDanAppId = settings.getString(SettingsManager.Danmaku.DAN_DAN_APP_ID, "")
        danDanAppSecret = settings.getString(SettingsManager.Danmaku.DAN_DAN_APP_SECRET, "")
        followSpeed = settings.getBoolean(SettingsManager.Danmaku.FOLLOW_SPEED, false)
        danmakuTop = settings.getBoolean(SettingsManager.Danmaku.TOP, true)
        danmakuBottom = settings.getBoolean(SettingsManager.Danmaku.BOTTOM, true)
        danmakuScroll = settings.getBoolean(SettingsManager.Danmaku.SCROLL, true)
        danmakuMassive = settings.getBoolean(SettingsManager.Danmaku.MASSIVE, false)
        danmakuDeduplication = settings.getBoolean(SettingsManager.Danmaku.DEDUPLICATION, false)
        danmakuBorder = settings.getBoolean(SettingsManager.Danmaku.BORDER, true)
        danmakuColor = settings.getBoolean(SettingsManager.Danmaku.COLOR, true)
        danmakuArea = settings.getDouble(SettingsManager.Danmaku.AREA, 0.5)
        danmakuDuration = settings.getDouble(SettingsManager.Danmaku.DURATION, 8.0)
        danmakuLineHeight = settings.getDouble(SettingsManager.Danmaku.LINE_HEIGHT, 1.0)
        danmakuBorderSize = settings.getDouble(SettingsManager.Danmaku.BORDER_SIZE, 1.0)
        danmakuFontSize = settings.getDouble(SettingsManager.Danmaku.FONT_SIZE, 24.0)
        danmakuFontWeight = settings.getInt(SettingsManager.Danmaku.FONT_WEIGHT, 4)
        danmakuOpacity = settings.getDouble(SettingsManager.Danmaku.OPACITY, 0.8)
    }

    fun saveBoolean(key: String, value: Boolean) {
        scope.launch { settings.setBoolean(key, value) }
    }

    SettingsSubPageScaffold(
        title = "弹幕设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("弹幕来源") {
                SettingSwitchRow(
                    title = "BiliBili",
                    subtitle = "免鉴权，按番剧名自动匹配，当前默认弹幕源",
                    leading = Icons.Filled.LiveTv,
                    checked = biliSource,
                    onCheckedChange = { biliSource = it; saveBoolean(SettingsManager.Danmaku.BILI_SOURCE, it) }
                )
                SettingSwitchRow(
                    title = "Gamer",
                    subtitle = "巴哈姆特动画疯（接口在当前网络不可达，暂未启用）",
                    leading = Icons.Filled.SportsEsports,
                    checked = gamerSource,
                    onCheckedChange = { gamerSource = it; saveBoolean(SettingsManager.Danmaku.GAMER_SOURCE, it) }
                )
                SettingSwitchRow(
                    title = "弹弹play",
                    subtitle = "官方接口已强制 AppId 鉴权，需先填写下方凭据",
                    leading = Icons.Filled.Forum,
                    checked = danDanSource,
                    onCheckedChange = { danDanSource = it; saveBoolean(SettingsManager.Danmaku.DAN_DAN_SOURCE, it) }
                )
            }

            SettingGroup("弹弹play 凭据") {
                SettingClickRow(
                    title = "AppId / AppSecret",
                    subtitle = "弹弹play 开放接口需鉴权，在 dandanplay.com 开发者中心创建应用后填入；仅保存在本机",
                    leading = Icons.Filled.Key,
                    trailingValue = if (danDanAppId.isNotBlank() && danDanAppSecret.isNotBlank()) "已配置" else "未配置",
                    onClick = {
                        editAppId = danDanAppId
                        editAppSecret = danDanAppSecret
                        showCredDialog = true
                    }
                )
            }

            SettingGroup("弹幕屏蔽") {
                SettingClickRow(
                    title = "关键词屏蔽",
                    subtitle = "屏蔽含指定关键词的弹幕",
                    leading = Icons.Filled.Block,
                    onClick = { showToast("关键词屏蔽开发中，敬请期待") }
                )
            }

            SettingGroup("弹幕显示") {
                SettingSwitchRow(
                    title = "弹幕跟随视频倍速",
                    subtitle = "开启后弹幕速度会随视频倍速而改变",
                    leading = Icons.Filled.Speed,
                    checked = followSpeed,
                    onCheckedChange = { followSpeed = it; saveBoolean(SettingsManager.Danmaku.FOLLOW_SPEED, it) }
                )
                SettingSwitchRow(
                    title = "顶部弹幕",
                    leading = Icons.Filled.VerticalAlignTop,
                    checked = danmakuTop,
                    onCheckedChange = { danmakuTop = it; saveBoolean(SettingsManager.Danmaku.TOP, it) }
                )
                SettingSwitchRow(
                    title = "底部弹幕",
                    leading = Icons.Filled.VerticalAlignBottom,
                    checked = danmakuBottom,
                    onCheckedChange = { danmakuBottom = it; saveBoolean(SettingsManager.Danmaku.BOTTOM, it) }
                )
                SettingSwitchRow(
                    title = "滚动弹幕",
                    leading = Icons.Filled.SwapHoriz,
                    checked = danmakuScroll,
                    onCheckedChange = { danmakuScroll = it; saveBoolean(SettingsManager.Danmaku.SCROLL, it) }
                )
                SettingSwitchRow(
                    title = "海量弹幕",
                    subtitle = "弹幕过多时进行叠加绘制",
                    leading = Icons.Filled.Layers,
                    checked = danmakuMassive,
                    onCheckedChange = { danmakuMassive = it; saveBoolean(SettingsManager.Danmaku.MASSIVE, it) }
                )
                SettingSwitchRow(
                    title = "弹幕去重",
                    subtitle = "相同内容弹幕过多时合并为一条弹幕",
                    leading = Icons.Filled.FilterAlt,
                    checked = danmakuDeduplication,
                    onCheckedChange = { danmakuDeduplication = it; saveBoolean(SettingsManager.Danmaku.DEDUPLICATION, it) }
                )
                Text(
                    text = "弹幕区域 ${(danmakuArea * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = danmakuArea.toFloat(),
                    onValueChange = { danmakuArea = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Danmaku.AREA, danmakuArea) }
                    },
                    valueRange = 0f..1f
                )
                Text(
                    text = "弹幕持续时间 ${danmakuDuration.toInt()} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = danmakuDuration.toFloat(),
                    onValueChange = { danmakuDuration = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Danmaku.DURATION, danmakuDuration) }
                    },
                    valueRange = 2f..16f
                )
                Text(
                    text = "弹幕行高 ${"%.1f".format(danmakuLineHeight)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = danmakuLineHeight.toFloat(),
                    onValueChange = { danmakuLineHeight = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Danmaku.LINE_HEIGHT, danmakuLineHeight) }
                    },
                    valueRange = 0f..3f
                )
            }

            SettingGroup("弹幕样式") {
                SettingSwitchRow(
                    title = "弹幕描边",
                    leading = Icons.Filled.BorderColor,
                    checked = danmakuBorder,
                    onCheckedChange = { danmakuBorder = it; saveBoolean(SettingsManager.Danmaku.BORDER, it) }
                )
                Text(
                    text = "弹幕描边粗细 ${"%.1f".format(danmakuBorderSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = danmakuBorderSize.toFloat(),
                    onValueChange = { danmakuBorderSize = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Danmaku.BORDER_SIZE, danmakuBorderSize) }
                    },
                    valueRange = 0.1f..3f
                )
                SettingSwitchRow(
                    title = "弹幕颜色",
                    leading = Icons.Filled.Palette,
                    checked = danmakuColor,
                    onCheckedChange = { danmakuColor = it; saveBoolean(SettingsManager.Danmaku.COLOR, it) }
                )
                Text(
                    text = "字体大小 ${danmakuFontSize.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = danmakuFontSize.toFloat(),
                    onValueChange = { danmakuFontSize = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Danmaku.FONT_SIZE, danmakuFontSize) }
                    },
                    valueRange = 10f..48f
                )
                Text(
                    text = "字体字重 $danmakuFontWeight",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = danmakuFontWeight.toFloat(),
                    onValueChange = { danmakuFontWeight = it.toInt() },
                    onValueChangeFinished = {
                        scope.launch { settings.setInt(SettingsManager.Danmaku.FONT_WEIGHT, danmakuFontWeight) }
                    },
                    valueRange = 1f..9f
                )
                Text(
                    text = "弹幕不透明度 ${(danmakuOpacity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = danmakuOpacity.toFloat(),
                    onValueChange = { danmakuOpacity = it.toDouble() },
                    onValueChangeFinished = {
                        scope.launch { settings.setDouble(SettingsManager.Danmaku.OPACITY, danmakuOpacity) }
                    },
                    valueRange = 0.1f..1f
                )
            }

            SettingGroup {
                SettingClickRow(
                    title = "恢复默认设置",
                    subtitle = "将弹幕相关设置恢复为默认值",
                    leading = Icons.Filled.Restore,
                    onClick = {
                        scope.launch {
                            settings.setBoolean(SettingsManager.Danmaku.BILI_SOURCE, true)
                            settings.setBoolean(SettingsManager.Danmaku.GAMER_SOURCE, false)
                            settings.setBoolean(SettingsManager.Danmaku.DAN_DAN_SOURCE, false)
                            settings.setBoolean(SettingsManager.Danmaku.TOP, true)
                            settings.setBoolean(SettingsManager.Danmaku.BOTTOM, true)
                            settings.setBoolean(SettingsManager.Danmaku.SCROLL, true)
                            settings.setBoolean(SettingsManager.Danmaku.BORDER, true)
                            settings.setBoolean(SettingsManager.Danmaku.COLOR, true)
                            settings.setDouble(SettingsManager.Danmaku.AREA, 0.5)
                            settings.setDouble(SettingsManager.Danmaku.DURATION, 8.0)
                            settings.setDouble(SettingsManager.Danmaku.LINE_HEIGHT, 1.0)
                            settings.setDouble(SettingsManager.Danmaku.FONT_SIZE, 24.0)
                            settings.setDouble(SettingsManager.Danmaku.OPACITY, 0.8)
                        }
                    }
                )
            }
        }
    }

    if (showCredDialog) {
        AlertDialog(
            onDismissRequest = { showCredDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("弹弹play 凭据") },
            text = {
                Column {
                    Text(
                        text = "在 dandanplay.com 开发者中心创建应用并审核通过后，可获 1 个 AppId 和 2 个 AppSecret（任选其一）。凭据只保存在本机，不上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = editAppId,
                        onValueChange = { editAppId = it.trim() },
                        label = { Text("AppId") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                    OutlinedTextField(
                        value = editAppSecret,
                        onValueChange = { editAppSecret = it.trim() },
                        label = { Text("AppSecret") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    danDanAppId = editAppId.trim()
                    danDanAppSecret = editAppSecret.trim()
                    scope.launch {
                        settings.setString(SettingsManager.Danmaku.DAN_DAN_APP_ID, danDanAppId)
                        settings.setString(SettingsManager.Danmaku.DAN_DAN_APP_SECRET, danDanAppSecret)
                    }
                    showCredDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showCredDialog = false }) { Text("取消") }
            }
        )
    }
}