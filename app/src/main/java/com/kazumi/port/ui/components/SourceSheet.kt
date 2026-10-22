package com.kazumi.port.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.PluginSearchStatus
import com.kazumi.port.data.model.SearchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** 单个插件的检索状态 */
private data class SourceGroup(
    val name: String,
    var status: PluginSearchStatus = PluginSearchStatus.PENDING,
    val results: MutableList<SearchItem> = mutableStateListOf()
)

/**
 * 播放来源弹窗（对齐原版 source_sheet.dart / source_sheet_view.dart）
 * 顶部标题「播放来源」+ 检索进度；下方按插件分组展示搜索结果；
 * 点击某条结果 → 查询播放列表 → 进入播放页。
 * 检索结果按完成顺序逐个填入（对齐原版「检索中 x/y · n 个结果」），不等全部完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSheet(
    keyword: String,
    onPlay: (pluginName: String, src: String, title: String) -> Unit,
    onDismiss: () -> Unit
) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    val plugins = remember { app.pluginRepository.allPlugins() }
    // 保持插件配置顺序
    val groups = remember { plugins.map { SourceGroup(it.name) } }
    val total = groups.size
    var pendingCount by remember { mutableStateOf(total) }
    var resultCount by remember { mutableStateOf(0) }

    fun updateCount() {
        pendingCount = groups.count { it.status == PluginSearchStatus.PENDING }
        resultCount = groups.sumOf { it.results.size }
    }

    LaunchedEffect(keyword) {
        // 并发检索所有插件（对齐原版 queryAllSource：Future.wait 全并发，无信号量限流）
        plugins.forEach { plugin ->
            scope.launch {
                val group = groups.firstOrNull { it.name == plugin.name } ?: return@launch
                try {
                    // 无 per-plugin 硬超时（对齐原版）：依赖 OkHttp 12s 超时兜底，
                    // 信号量 + 超时叠加会让慢插件拖慢整批（原版 2-3s 出结果）
                    val resp = app.pluginRepository.searchBangumi(plugin.name, keyword)
                    if (resp.data.isEmpty()) {
                        group.status = PluginSearchStatus.NO_RESULT
                    } else {
                        group.status = PluginSearchStatus.SUCCESS
                        // 对齐原版按 src 去重（seenBySource：同一插件内重复 src 只显示一次）
                        group.results.addAll(resp.data.distinctBy { it.src })
                    }
                } catch (e: Exception) {
                    group.status = PluginSearchStatus.ERROR
                }
                withContext(Dispatchers.Main.immediate) { updateCount() }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 显式不透明背景（对齐原版 showModalBottomSheet 的 surface 背景；老版本 material3 默认容器可能透明）
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题 + 进度（对齐原版 MaterialBottomSheetHeader）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "播放来源",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (pendingCount > 0)
                            "检索中 ${total - pendingCount}/$total · $resultCount 个结果"
                        else
                            "$total 个来源 · $resultCount 个结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "收起",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            // 分组列表
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                items(groups, key = { it.name }) { group ->
                    SourceGroupRow(
                        group = group,
                        onPlayItem = { item ->
                            onPlay(group.name, item.src, item.name)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** 单个插件的分组行（标题 + 状态 + 结果列表/空态/错误） */
@Composable
private fun SourceGroupRow(
    group: SourceGroup,
    onPlayItem: (SearchItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // 组头：插件名 + 状态 + 展开箭头（对齐原版 _buildSourceGroup 头部）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { /* 展开/收起（默认展开，简化处理） */ }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (group.status) {
                    PluginSearchStatus.PENDING -> "检索中…"
                    PluginSearchStatus.SUCCESS ->
                        if (group.results.isEmpty()) "无结果" else "${group.results.size} 个结果"
                    PluginSearchStatus.NO_RESULT -> "无结果"
                    PluginSearchStatus.ERROR -> "检索失败"
                    PluginSearchStatus.CAPTCHA -> "需要验证"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            // 分组更多操作（对齐原版 _buildSourceMenu）
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        // 结果列表 / 空态 / 错误
        when {
            group.status == PluginSearchStatus.PENDING -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "正在检索…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            group.status == PluginSearchStatus.SUCCESS &&
                group.results.isEmpty() -> {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "没有找到匹配结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {}) { Text("使用别名") }
                }
            }
            group.status == PluginSearchStatus.ERROR -> {
                Text(
                    "检索失败，请重试或打开网站检查是否可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                )
            }
            group.results.isNotEmpty() -> {
                // 结果行（对齐原版 _buildResult SplitListRow）
                group.results.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPlayItem(item) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(16.dp))
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
