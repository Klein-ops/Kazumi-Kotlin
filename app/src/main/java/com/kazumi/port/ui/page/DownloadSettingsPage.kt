package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.service.DownloadDirectoryService
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.components.rememberToast
import kotlinx.coroutines.launch

/**
 * 下载设置页（对应原版 download_settings.dart）
 * 并发设置（同时下载集数 / 分片并发数）、缓存设置（下载位置 / 缓存弹幕）、说明
 */
@Composable
fun DownloadSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val showToast = rememberToast()
    val context = LocalContext.current
    val dirService = remember { DownloadDirectoryService(context, settings) }
    var parallelEpisodes by remember { mutableIntStateOf(1) }
    var parallelSegments by remember { mutableIntStateOf(5) }
    var downloadDanmaku by remember { mutableStateOf(false) }
    var downloadDirectory by remember { mutableStateOf("") }
    var showConcurrentInfo by remember { mutableStateOf(false) }
    // SAF 目录选择器：选完用 resolveTreeUri 解析为真实路径并保存
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val resolved = dirService.resolveTreeUri(uri)
                    if (resolved.isNullOrBlank()) {
                        showToast("无法解析所选目录（云盘/虚拟卷暂不支持）")
                    } else {
                        dirService.setDirectory(resolved)
                        downloadDirectory = resolved
                        showToast("下载目录已设为 $resolved")
                    }
                } catch (e: Exception) {
                    showToast("设置下载目录失败：${e.message}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        parallelEpisodes = settings.getInt(SettingsManager.Download.PARALLEL_EPISODES, 1)
        parallelSegments = settings.getInt(SettingsManager.Download.PARALLEL_SEGMENTS, 5)
        downloadDanmaku = settings.getBoolean(SettingsManager.Download.DANMAKU, false)
        downloadDirectory = settings.getString(SettingsManager.Download.DIRECTORY, "")
    }

    SettingsSubPageScaffold(
        title = "下载设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("并发设置") {
                Text(
                    text = "同时下载集数：$parallelEpisodes 集",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = parallelEpisodes.toFloat(),
                    onValueChange = { parallelEpisodes = it.toInt() },
                    onValueChangeFinished = {
                        scope.launch { settings.setInt(SettingsManager.Download.PARALLEL_EPISODES, parallelEpisodes) }
                    },
                    valueRange = 1f..5f,
                    steps = 3
                )
                Text(
                    text = "分片并发数：$parallelSegments 个",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = parallelSegments.toFloat(),
                    onValueChange = { parallelSegments = it.toInt() },
                    onValueChangeFinished = {
                        scope.launch { settings.setInt(SettingsManager.Download.PARALLEL_SEGMENTS, parallelSegments) }
                    },
                    valueRange = 1f..10f,
                    steps = 8
                )
            }

            SettingGroup("缓存设置") {
                SettingClickRow(
                    title = "下载位置",
                    subtitle = if (downloadDirectory.isEmpty()) "默认下载位置（未自定义）" else downloadDirectory,
                    leading = Icons.Filled.Folder,
                    onClick = { directoryPicker.launch(null) }
                )
                SettingSwitchRow(
                    title = "缓存弹幕",
                    subtitle = "下载视频时同时缓存弹幕数据",
                    leading = Icons.Filled.Subtitles,
                    checked = downloadDanmaku,
                    onCheckedChange = { value ->
                        downloadDanmaku = value
                        scope.launch { settings.setBoolean(SettingsManager.Download.DANMAKU, value) }
                    }
                )
            }

            SettingGroup("说明") {
                SettingClickRow(
                    title = "关于并发设置",
                    subtitle = "• 集数并发：同时下载多少集视频\n• 分片并发：每集内同时下载多少个视频片段\n• 较高的并发可提升速度，但可能被服务器限制\n• 修改后对新开始的下载生效",
                    leading = Icons.Filled.Info,
                    onClick = { showConcurrentInfo = true }
                )
            }

            SettingGroup {
                SettingClickRow(
                    title = "恢复默认设置",
                    subtitle = "将下载相关设置恢复为默认值",
                    leading = Icons.Filled.Restore,
                    onClick = {
                        scope.launch {
                            settings.setInt(SettingsManager.Download.PARALLEL_EPISODES, 1)
                            settings.setInt(SettingsManager.Download.PARALLEL_SEGMENTS, 5)
                            settings.setBoolean(SettingsManager.Download.DANMAKU, false)
                        }
                        parallelEpisodes = 1
                        parallelSegments = 5
                        downloadDanmaku = false
                    }
                )
            }
        }
    }

    if (showConcurrentInfo) {
        AlertDialog(
            onDismissRequest = { showConcurrentInfo = false },
            title = { Text("关于并发设置") },
            text = {
                Text(
                    "• 集数并发：同时下载多少集视频\n" +
                        "• 分片并发：每集内同时下载多少个视频片段\n" +
                        "• 较高的并发可提升速度，但可能被服务器限制\n" +
                        "• 修改后对新开始的下载生效"
                )
            },
            confirmButton = {
                TextButton(onClick = { showConcurrentInfo = false }) { Text("知道了") }
            }
        )
    }
}