package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import kotlinx.coroutines.launch

/**
 * 操作设置（键盘映射）（对应原版 keyboard_settings.dart）
 * 分组：播放控制 / 音量 / 画面与弹幕 / 倍速
 * 按键录制需要播放器焦点监听，此处先对齐结构与默认值持久化，
 * 键帽点击进入录制态由播放器端实现。
 */
private val defaultShortcuts: Map<String, List<String>> = mapOf(
    "playorpause" to listOf("space"),
    "forward" to listOf("arrowright"),
    "rewind" to listOf("arrowleft"),
    "skip" to listOf("s"),
    "next" to listOf("n"),
    "prev" to listOf("p"),
    "volumeup" to listOf("arrowup"),
    "volumedown" to listOf("arrowdown"),
    "togglemute" to listOf("m"),
    "fullscreen" to listOf("f"),
    "exitfullscreen" to listOf("escape"),
    "screenshot" to listOf("c"),
    "toggledanmaku" to listOf("d"),
    "speed1" to listOf("1"),
    "speed2" to listOf("2"),
    "speed3" to listOf("3"),
    "speedup" to listOf("+"),
    "speeddown" to listOf("-")
)

private val shortcutChineseName: Map<String, String> = mapOf(
    "playorpause" to "播放/暂停",
    "forward" to "快进",
    "rewind" to "快退",
    "skip" to "跳过",
    "next" to "下一集",
    "prev" to "上一集",
    "volumeup" to "音量加",
    "volumedown" to "音量减",
    "togglemute" to "静音",
    "fullscreen" to "全屏",
    "exitfullscreen" to "退出全屏",
    "screenshot" to "截图",
    "toggledanmaku" to "弹幕开关",
    "speed1" to "倍速 1x",
    "speed2" to "倍速 2x",
    "speed3" to "倍速 3x",
    "speedup" to "加速",
    "speeddown" to "减速"
)

private val keyAliases: Map<String, String> = mapOf(
    "space" to "空格",
    "arrowright" to "→",
    "arrowleft" to "←",
    "arrowup" to "↑",
    "arrowdown" to "↓",
    "escape" to "Esc",
    "enter" to "回车",
    "backspace" to "退格"
)

private data class ShortcutGroup(val title: String, val functions: List<String>)

private val shortcutGroups = listOf(
    ShortcutGroup("播放控制", listOf("playorpause", "forward", "rewind", "skip", "next", "prev")),
    ShortcutGroup("音量", listOf("volumeup", "volumedown", "togglemute")),
    ShortcutGroup("画面与弹幕", listOf("fullscreen", "exitfullscreen", "screenshot", "toggledanmaku")),
    ShortcutGroup("倍速", listOf("speed1", "speed2", "speed3", "speedup", "speeddown"))
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()

    val shortcuts = remember { mutableStateMapOf<String, List<String>>() }
    var showHelpDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        defaultShortcuts.forEach { (func, defaults) ->
            val stored = settings.getStringList("shortcut_$func", defaults)
            val cleaned = stored.filter { it.isNotEmpty() && it != "..." }
            shortcuts[func] = if (cleaned.isEmpty()) defaults else cleaned
        }
    }

    fun restoreDefaults() {
        scope.launch {
            defaultShortcuts.forEach { (func, defaults) ->
                settings.setStringList("shortcut_$func", defaults)
                shortcuts[func] = defaults
            }
        }
    }

    SettingsSubPageScaffold(
        title = "操作设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "点按按键标签，再按下新按键完成修改",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            )
            shortcutGroups.forEach { group ->
                SettingGroup(group.title) {
                    group.functions.forEach { func ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = shortcutChineseName[func] ?: func,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                shortcuts[func].orEmpty().forEach { key ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = keyAliases[key] ?: key,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Text(
                                        text = "+",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SettingGroup {
                SettingClickRow(
                    title = "恢复默认快捷键",
                    subtitle = "将按键映射恢复为默认值",
                    leading = Icons.Filled.Restore,
                    onClick = { restoreDefaults() }
                )
            }
            SettingGroup("说明") {
                SettingClickRow(
                    title = "快捷键说明",
                    subtitle = "播放控制 / 音量 / 画面与弹幕 / 倍速",
                    leading = Icons.Filled.Keyboard,
                    onClick = { showHelpDialog = true }
                )
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("快捷键说明") },
            text = {
                Text(
                    "空格：播放/暂停\n" +
                        "← / →：快退 / 快进\n" +
                        "↑ / ↓：音量加减\n" +
                        "M：静音切换\n" +
                        "F：全屏\n" +
                        "S：跳过片头\n" +
                        "N / P：下一集 / 上一集\n" +
                        "1 / 2 / 3：倍速档位\n" +
                        "快捷键可在上方列表中点击修改"
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("知道了") }
            }
        )
    }
}
