package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.db.DownloadStatus
import com.kazumi.port.data.repository.DownloadEpisode
import com.kazumi.port.data.repository.DownloadRecord
import com.kazumi.port.service.formatBytes
import com.kazumi.port.service.formatSpeed
import com.kazumi.port.ui.components.EmptyState
import com.kazumi.port.ui.components.PageHeader
import com.kazumi.port.ui.navigation.Routes
import kotlinx.coroutines.launch

/**
 * 下载管理页（对应原版 download_page.dart）
 * 记录卡片 + 集数状态与操作
 */
@Composable
fun DownloadPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    var records by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }

    LaunchedEffect(Unit) {
        app.downloadController.refreshRecords()
        records = app.downloadController.records
    }

    MainScaffold(navController) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(title = "下载管理")

            if (records.isEmpty()) {
                EmptyState("还没有下载记录")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(records, key = { it.key }) { record ->
                        DownloadRecordCard(
                            record = record,
                            controller = app.downloadController,
                            onPlay = { episode ->
                                navController.navigate(
                                    Routes.player(
                                        bangumiId = record.bangumiId,
                                        pluginName = record.pluginName,
                                        episode = episode.episodeNumber,
                                        road = episode.road,
                                        isLocal = true
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRecordCard(
    record: DownloadRecord,
    controller: com.kazumi.port.service.DownloadController,
    onPlay: (DownloadEpisode) -> Unit
) {
    val scope = rememberCoroutineScope()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.bangumiName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            controller.resumeAllDownloads(record.bangumiId, record.pluginName)
                        }
                    }
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "全部恢复")
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            controller.deleteRecord(record.bangumiId, record.pluginName)
                        }
                    }
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除全部")
                }
            }

            val episodes = record.episodes.values.sortedBy { it.episodeNumber }
            episodes.forEach { episode ->
                EpisodeRow(
                    episode = episode,
                    speed = controller.getSpeed(record.bangumiId, record.pluginName, episode.episodeNumber),
                    onPause = {
                        scope.launch {
                            controller.pauseDownload(record.bangumiId, record.pluginName, episode.episodeNumber)
                        }
                    },
                    onResume = {
                        scope.launch {
                            controller.retryDownload(record.bangumiId, record.pluginName, episode.episodeNumber)
                        }
                    },
                    onDelete = {
                        scope.launch {
                            controller.deleteEpisode(record.bangumiId, record.pluginName, episode.episodeNumber)
                        }
                    },
                    onPlay = { onPlay(episode) }
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: DownloadEpisode,
    speed: Double,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit
) {
    val statusText = when (episode.status) {
        DownloadStatus.COMPLETED -> "已完成 · ${formatBytes(episode.totalBytes.toLong())}"
        DownloadStatus.DOWNLOADING -> {
            val speedText = if (speed > 0) " · ${formatSpeed(speed)}" else ""
            "${(episode.progressPercent * 100).toInt()}% · " +
                "${episode.downloadedSegments}/${episode.totalSegments} 分片$speedText"
        }
        DownloadStatus.FAILED -> episode.errorMessage.ifEmpty { "下载失败" }
        DownloadStatus.PAUSED -> "已暂停 · ${(episode.progressPercent * 100).toInt()}%"
        DownloadStatus.PENDING -> "排队中"
        DownloadStatus.RESOLVING -> "正在解析视频源"
        else -> ""
    }

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = episode.episodeName.ifEmpty { "第${episode.episodeNumber}集" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            when (episode.status) {
                DownloadStatus.COMPLETED -> IconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
                }
                DownloadStatus.DOWNLOADING -> IconButton(onClick = onPause) {
                    Icon(Icons.Filled.Pause, contentDescription = "暂停")
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> IconButton(onClick = onResume) {
                    Icon(
                        if (episode.status == DownloadStatus.FAILED) Icons.Filled.Refresh else Icons.Filled.PlayArrow,
                        contentDescription = if (episode.status == DownloadStatus.FAILED) "重试" else "继续"
                    )
                }
                else -> {}
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
        if (episode.status == DownloadStatus.DOWNLOADING ||
            episode.status == DownloadStatus.PAUSED
        ) {
            LinearProgressIndicator(
                progress = { episode.progressPercent.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}