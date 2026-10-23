package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

/**
 * 设置页宽屏布局（对应原版 settings_page 的 wide 分支：左侧 280dp 分类栏 + 右侧内容）
 * 只在最短边/宽度 ≥840dp（平板、横屏、桌面模式）时启用，手机不受影响。
 */
@Composable
internal fun WideSettingsLayout(navController: NavHostController) {
    var selected by remember { mutableIntStateOf(0) }
    val entries: List<Pair<String, @Composable () -> Unit>> = listOf(
        "播放设置" to { PlayerSettingsPage(navController) },
        "弹幕设置" to { DanmakuSettingsPage(navController) },
        "操作设置" to { KeyboardSettingsPage(navController) },
        "规则管理" to { PluginSettingsPage(navController) },
        "下载设置" to { DownloadSettingsPage(navController) },
        "外观设置" to { ThemeSettingsPage(navController) },
        "界面设置" to { InterfaceSettingsPage(navController) },
        "同步设置" to { SyncSettingsPage(navController) },
        "网络设置" to { ProxySettingsPage(navController) },
        "更新设置" to { UpdateSettingsPage(navController) },
        "存储与日志" to { StorageSettingsPage(navController) },
        "关于" to { AboutSettingsPage(navController) }
    )

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 12.dp)
            )
            entries.forEachIndexed { index, (title, _) ->
                val isSelected = index == selected
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .clickable { selected = index }
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }
            }
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight())
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // 右侧直接嵌入选中的设置子页（子页自带返回栏，宽屏下可忽略返回）
            entries[selected].second()
        }
    }
}