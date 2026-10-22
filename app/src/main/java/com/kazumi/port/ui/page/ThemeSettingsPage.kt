package com.kazumi.port.ui.page

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.theme.KazumiThemeColors
import kotlinx.coroutines.launch

/**
 * 外观设置页（对应原版 theme_settings_page.dart）
 * 外观：深色模式 / 配色方案(9色) / 动态配色 / 使用系统字体；显示：OLED优化
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()

    var themeMode by remember { mutableStateOf("system") }
    var themeColor by remember { mutableStateOf("default") }
    var oledEnhance by remember { mutableStateOf(false) }
    var useDynamicColor by remember { mutableStateOf(false) }
    var useSystemFont by remember { mutableStateOf(false) }
    var showPaletteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        themeMode = settings.getString(SettingsManager.Theme.MODE, "system")
        themeColor = settings.getString(SettingsManager.Theme.COLOR, "default")
        oledEnhance = settings.getBoolean(SettingsManager.Theme.OLED_ENHANCE, false)
        useDynamicColor = settings.getBoolean(SettingsManager.Theme.USE_DYNAMIC_COLOR, false)
        useSystemFont = settings.getBoolean(SettingsManager.Theme.USE_SYSTEM_FONT, false)
    }

    val themeModeLabels = mapOf(
        "system" to "跟随系统",
        "light" to "浅色",
        "dark" to "深色"
    )
    val themeModeOrder = listOf("system", "light", "dark")

    SettingsSubPageScaffold(
        title = "外观设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("外观") {
                SettingClickRow(
                    title = "深色模式",
                    leading = Icons.Filled.DarkMode,
                    trailingValue = themeModeLabels[themeMode] ?: "跟随系统",
                    onClick = {
                        val idx = themeModeOrder.indexOf(themeMode)
                        themeMode = themeModeOrder[(idx + 1) % themeModeOrder.size]
                        scope.launch { settings.setString(SettingsManager.Theme.MODE, themeMode) }
                    }
                )
                SettingClickRow(
                    title = "配色方案",
                    subtitle = if (themeColor == "default") "默认（绿色）" else "自定义配色",
                    leading = Icons.Filled.Palette,
                    enabled = !useDynamicColor,
                    onClick = { showPaletteDialog = true }
                )
                SettingSwitchRow(
                    title = "动态配色",
                    subtitle = "跟随系统取色，仅支持安卓12及以上",
                    leading = Icons.Filled.Contrast,
                    checked = useDynamicColor,
                    onCheckedChange = { value ->
                        useDynamicColor = value
                        scope.launch { settings.setBoolean(SettingsManager.Theme.USE_DYNAMIC_COLOR, value) }
                    }
                )
                SettingSwitchRow(
                    title = "使用系统字体",
                    subtitle = "关闭后使用 MI Sans 字体",
                    leading = Icons.Filled.FontDownload,
                    checked = useSystemFont,
                    onCheckedChange = { value ->
                        useSystemFont = value
                        scope.launch { settings.setBoolean(SettingsManager.Theme.USE_SYSTEM_FONT, value) }
                    }
                )
            }

            SettingGroup("显示") {
                SettingSwitchRow(
                    title = "OLED优化",
                    subtitle = "深色模式下使用纯黑背景",
                    leading = Icons.Filled.Contrast,
                    checked = oledEnhance,
                    onCheckedChange = { value ->
                        oledEnhance = value
                        scope.launch { settings.setBoolean(SettingsManager.Theme.OLED_ENHANCE, value) }
                    }
                )
            }

            SettingGroup("说明") {
                Text(
                    text = "外观设置即时生效；深色模式支持跟随系统",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    if (showPaletteDialog) {
        AlertDialog(
            onDismissRequest = { showPaletteDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("配色方案") },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KazumiThemeColors.ALL.forEach { (key, label) ->
                        val selected = themeColor == key
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                themeColor = key
                                scope.launch { settings.setString(SettingsManager.Theme.COLOR, key) }
                                showPaletteDialog = false
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(KazumiThemeColors.resolve(key), CircleShape)
                                    .border(
                                        BorderStroke(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                        ),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPaletteDialog = false }) { Text("取消") }
            }
        )
    }
}