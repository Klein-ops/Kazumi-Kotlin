package com.kazumi.port.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.History
import com.kazumi.port.ui.components.EmptyState
import com.kazumi.port.ui.components.LoadingState
import com.kazumi.port.ui.navigation.Routes
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 历史记录页（对应原版 pages/history）
 *
 * 与原版一致的能力：
 * - 顶部搜索框（按中文名/原名/别名匹配），带清除按钮
 * - 按日期分组：今天 / 昨天 / 更早
 * - 每条显示封面、标题、第几集·来源、上次观看进度（mm:ss）
 * - 点击条目继续播放；右侧菜单可「播放 / 详情 / 删除」
 * - 右上角清空全部（带确认对话框，文案对齐原版）
 */
@Composable
fun HistoryPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    var histories by remember { mutableStateOf<List<History>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }

    suspend fun refresh() {
        histories = app.historyRepository.getAll().sortedByDescending { it.lastWatchTime }
        isLoading = false
    }

    LaunchedEffect(Unit) { refresh() }

    // 搜索：中文名 / 原名 / 别名
    val keyword = query.trim().lowercase()
    val matched = if (keyword.isEmpty()) histories else histories.filter { h ->
        val item = h.bangumiItem
        val haystack = (listOf(item.nameCn, item.name) + item.alias).joinToString("\n").lowercase()
        haystack.contains(keyword)
    }

    // 分组：今天 / 昨天 / 更早
    val today = LocalDate.now()
    fun groupLabel(ms: Long): String {
        val date = runCatching {
            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrDefault(today)
        return when {
            date == today -> "今天"
            date == today.minusDays(1) -> "昨天"
            else -> "更早"
        }
    }
    val groups = listOf("今天", "昨天", "更早").map { label ->
        label to matched.filter { groupLabel(it.lastWatchTime) == label }
    }.filter { it.second.isNotEmpty() }

    fun openPlayer(h: History) {
        navController.navigate(
            Routes.player(
                bangumiId = h.bangumiItem.id,
                pluginName = h.adapterName,
                src = h.lastSrc,
                episode = h.lastWatchEpisode
            )
        )
    }

    // 独立页（不嵌 MainScaffold）：明确返回箭头 + 无底部导航，退出逻辑清晰
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 顶栏：返回按钮 + 标题 + 清空（对齐原版 SysAppBar）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "历史记录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (histories.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清空全部历史记录")
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            placeholder = { Text("搜索历史记录") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                    }
                }
            }
        )
        HorizontalDivider()
        when {
            isLoading -> LoadingState(modifier = Modifier.padding(top = 32.dp))
            matched.isEmpty() && keyword.isNotEmpty() -> EmptyState("没有匹配的记录")
            matched.isEmpty() -> EmptyState("还没有观看记录")
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                groups.forEach { (label, items) ->
                    item(key = "group-$label") {
                        Text(
                            text = "$label · ${items.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                        )
                    }
                    items(items, key = { it.key }) { history ->
                        HistoryRow(
                            history = history,
                            onClick = { openPlayer(history) },
                            onPlay = { openPlayer(history) },
                            onDetail = {
                                navController.navigate(
                                    Routes.detail(
                                        bangumiId = history.bangumiItem.id,
                                        pluginName = history.adapterName,
                                        src = history.lastSrc
                                    )
                                )
                            },
                            onDelete = {
                                scope.launch {
                                    app.historyRepository.delete(history.key)
                                    refresh()
                                }
                            }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 88.dp))
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("清空历史记录？") },
            text = { Text("清空后无法恢复，观看进度也会一起清除。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        app.historyRepository.clear()
                        refresh()
                    }
                }) { Text("清空全部") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}

/** 单条历史（封面 + 标题 + 集数/来源 + 进度 + 更多菜单） */
@Composable
private fun HistoryRow(
    history: History,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDetail: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cover = history.bangumiItem.coverUrl()
    val progressMs = history.progresses[history.lastWatchEpisode]?.progressMs ?: 0L
    val positionText = if (progressMs > 0) {
        val totalSeconds = progressMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    } else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (cover.isNotEmpty()) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(60.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(84.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = history.bangumiItem.displayName(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "第 ${history.lastWatchEpisode} 集 · ${history.adapterName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (positionText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "上次看到 $positionText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                DropdownMenuItem(text = { Text("播放") }, onClick = {
                    menuOpen = false
                    onPlay()
                })
                DropdownMenuItem(text = { Text("详情") }, onClick = {
                    menuOpen = false
                    onDetail()
                })
                DropdownMenuItem(text = { Text("删除") }, onClick = {
                    menuOpen = false
                    onDelete()
                })
            }
        }
    }
}