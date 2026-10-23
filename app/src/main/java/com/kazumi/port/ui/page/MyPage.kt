package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SentimentSatisfiedAlt
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.ui.components.PageHeader
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.navigation.Routes

/**
 * 我的页（对应原版 my_page.dart + my_space_view.dart）
 * 观看统计 + 历史/下载入口 + 偏好与数据入口
 */
@Composable
fun MyPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }

    // 观看统计：用 observeAll 流感知数据变化（历史页删除/播放新增后返回，统计实时刷新）
    val historyFlow by app.historyRepository.observeAll()
        .collectAsState(initial = emptyList())
    val downloadFlow by app.downloadRepository.observeRecords()
        .collectAsState(initial = emptyList())

    val bangumiCount = historyFlow.size
    val episodeCount = historyFlow.sumOf { it.progresses.size.coerceAtLeast(1) }
    val downloadCount = downloadFlow.size

    MainScaffold(navController) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            PageHeader(
                title = "我的",
                trailing = {
                    FilledTonalButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("设置")
                    }
                }
            )

            // 观看统计
            WatchStatsPanel(
                bangumiCount = bangumiCount,
                episodeCount = episodeCount
            )

            Spacer(Modifier.height(12.dp))

            // 历史 / 下载 入口
            Row(modifier = Modifier.fillMaxWidth()) {
                ToolTile(
                    icon = Icons.Filled.History,
                    title = "历史记录",
                    caption = if (bangumiCount == 0) "暂无观看记录" else "查看观看记录",
                    modifier = Modifier.weight(1f),
                    onClick = { navController.navigate(Routes.HISTORY) }
                )
                Spacer(Modifier.width(12.dp))
                ToolTile(
                    icon = Icons.Filled.Download,
                    title = "离线下载",
                    caption = if (downloadCount == 0) "管理离线内容" else "$downloadCount 集下载任务",
                    modifier = Modifier.weight(1f),
                    onClick = { navController.navigate(Routes.DOWNLOAD) }
                )
            }

            Spacer(Modifier.height(24.dp))

            // 内容与偏好
            SettingGroup("内容与偏好") {
                SettingClickRow(
                    title = "规则设置",
                    subtitle = "管理番剧来源",
                    leading = Icons.Filled.Extension,
                    onClick = { navController.navigate(Routes.SETTINGS_PLUGIN) }
                )
                SettingClickRow(
                    title = "外观设置",
                    subtitle = "主题、配色与字体",
                    leading = Icons.Filled.Palette,
                    onClick = { navController.navigate(Routes.SETTINGS_THEME) }
                )
                SettingClickRow(
                    title = "播放设置",
                    subtitle = "解码、渲染与播放行为",
                    leading = Icons.Filled.PlayArrow,
                    onClick = { navController.navigate(Routes.SETTINGS_PLAYER) }
                )
                SettingClickRow(
                    title = "弹幕设置",
                    subtitle = "弹幕来源与显示效果",
                    leading = Icons.Filled.Subtitles,
                    onClick = { navController.navigate(Routes.SETTINGS_DANMAKU) }
                )
            }

            Spacer(Modifier.height(24.dp))

            // 数据与应用
            SettingGroup("数据与应用") {
                SettingClickRow(
                    title = "同步备份",
                    subtitle = "跨设备同步数据",
                    leading = Icons.Filled.CloudSync,
                    onClick = { navController.navigate(Routes.SETTINGS_SYNC) }
                )
                SettingClickRow(
                    title = "存储管理",
                    subtitle = "缓存与日志",
                    leading = Icons.Filled.CleaningServices,
                    onClick = { navController.navigate(Routes.SETTINGS_STORAGE) }
                )
                SettingClickRow(
                    title = "关于 Kazumi",
                    subtitle = "版本与开源信息",
                    leading = Icons.Filled.Info,
                    onClick = { navController.navigate(Routes.SETTINGS_ABOUT) }
                )
            }
        }
    }
}

/** 观看统计面板（对应原版 _WatchStatsPanel） */
@Composable
private fun WatchStatsPanel(
    bangumiCount: Int,
    episodeCount: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SentimentSatisfiedAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "观看足迹",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.W700,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCount(
                    value = bangumiCount,
                    label = "看过番剧",
                    modifier = Modifier.weight(1f)
                )
                StatCount(
                    value = episodeCount,
                    label = "观看集数",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCount(
    value: Int,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "$value",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.W800,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** 工具入口卡片（对应原版 _ToolTile） */
@Composable
private fun ToolTile(
    icon: ImageVector,
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp)
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}