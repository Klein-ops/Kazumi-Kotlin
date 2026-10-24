package com.kazumi.port.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kazumi.port.data.model.DanmakuChConvert
import com.kazumi.port.data.repository.SettingsManager
import kotlinx.coroutines.launch

/**
 * 播放器内弹幕设置 Sheet（对齐原版 danmaku_settings_sheet：外观/播放两个 tab）
 * 提供字体大小 / 不透明度 / 显示区域 / 弹幕时长 / 是否跟随倍速 / 顶部 / 底部 / 滚动 等调节。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmakuSettingsSheet(
    settings: SettingsManager,
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var fontSize by remember { mutableFloatStateOf(25f) }
    var opacity by remember { mutableFloatStateOf(1.0f) }
    var area by remember { mutableFloatStateOf(1.0f) }
    var lineHeight by remember { mutableFloatStateOf(1.6f) }
    var duration by remember { mutableFloatStateOf(8f) }
    var followSpeed by remember { mutableStateOf(true) }
    var showTop by remember { mutableStateOf(true) }
    var showBottom by remember { mutableStateOf(false) }
    var showScroll by remember { mutableStateOf(true) }
    // 简繁转换（原版 DanmakuChConvertTile：不转换/转为简体/转为繁体）
    var chConvert by remember { mutableIntStateOf(0) }
    var chConvertMenuOpen by remember { mutableStateOf(false) }
    // 从 DataStore 加载当前值（suspend 必须在协程里）
    LaunchedEffect(Unit) {
        fontSize = settings.getDouble(SettingsManager.Danmaku.FONT_SIZE, 25.0).toFloat()
        opacity = settings.getDouble(SettingsManager.Danmaku.OPACITY, 1.0).toFloat()
        area = settings.getDouble(SettingsManager.Danmaku.AREA, 1.0).toFloat()
        lineHeight = settings.getDouble(SettingsManager.Danmaku.LINE_HEIGHT, 1.6).toFloat()
        duration = settings.getDouble(SettingsManager.Danmaku.DURATION, 8.0).toFloat()
        followSpeed = settings.getBoolean(SettingsManager.Danmaku.FOLLOW_SPEED, true)
        showTop = settings.getBoolean(SettingsManager.Danmaku.TOP, true)
        showBottom = settings.getBoolean(SettingsManager.Danmaku.BOTTOM, false)
        showScroll = settings.getBoolean(SettingsManager.Danmaku.SCROLL, true)
        chConvert = settings.getInt(SettingsManager.Danmaku.CH_CONVERT, 0)
    }

    fun save() {
        onChanged()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "弹幕设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // 外观
            Text("显示效果", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SliderRow("字体大小", "${fontSize.toInt()}", fontSize, 10f..48f) {
                fontSize = it
                scope.launch { settings.setDouble(SettingsManager.Danmaku.FONT_SIZE, it.toDouble()) }; save()
            }
            SliderRow("不透明度", "${(opacity * 100).toInt()}%", opacity, 0.1f..1f) {
                opacity = it
                scope.launch { settings.setDouble(SettingsManager.Danmaku.OPACITY, it.toDouble()) }; save()
            }
            SliderRow("显示区域", "${(area * 100).toInt()}%", area, 0f..1f) {
                area = it
                scope.launch { settings.setDouble(SettingsManager.Danmaku.AREA, it.toDouble()) }; save()
            }
            SliderRow("行间距", String.format("%.1f", lineHeight), lineHeight, 0f..3f) {
                lineHeight = it
                scope.launch { settings.setDouble(SettingsManager.Danmaku.LINE_HEIGHT, it.toDouble()) }; save()
            }

            // 播放
            Text("播放", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            SliderRow("弹幕时长", "${duration.toInt()}s", duration, 3f..15f) {
                duration = it
                scope.launch { settings.setDouble(SettingsManager.Danmaku.DURATION, it.toDouble()) }; save()
            }
            SwitchRow("跟随倍速", followSpeed) {
                followSpeed = it
                scope.launch { settings.setBoolean(SettingsManager.Danmaku.FOLLOW_SPEED, it) }; save()
            }
            SwitchRow("顶部弹幕", showTop) {
                showTop = it
                scope.launch { settings.setBoolean(SettingsManager.Danmaku.TOP, it) }; save()
            }
            SwitchRow("底部弹幕", showBottom) {
                showBottom = it
                scope.launch { settings.setBoolean(SettingsManager.Danmaku.BOTTOM, it) }; save()
            }
            SwitchRow("滚动弹幕", showScroll) {
                showScroll = it
                scope.launch { settings.setBoolean(SettingsManager.Danmaku.SCROLL, it) }; save()
            }

            // 简繁转换（对齐原版 danmaku_ch_convert_tile.dart：不转换/转为简体/转为繁体）
            Text("文字", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("简繁转换", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { chConvertMenuOpen = true }) {
                        Text(
                            text = when (DanmakuChConvert.fromValue(chConvert)) {
                                DanmakuChConvert.NONE -> "不转换"
                                DanmakuChConvert.SIMPLIFIED -> "转为简体"
                                DanmakuChConvert.TRADITIONAL -> "转为繁体"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = chConvertMenuOpen,
                        onDismissRequest = { chConvertMenuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        listOf(
                            DanmakuChConvert.NONE to "不转换",
                            DanmakuChConvert.SIMPLIFIED to "转为简体",
                            DanmakuChConvert.TRADITIONAL to "转为繁体"
                        ).forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    chConvert = mode.value
                                    chConvertMenuOpen = false
                                    scope.launch { settings.setInt(SettingsManager.Danmaku.CH_CONVERT, mode.value) }; save()
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChanged: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChanged, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}