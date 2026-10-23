package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.CollectType
import com.kazumi.port.data.repository.CollectedBangumi
import com.kazumi.port.ui.components.BangumiCard
import com.kazumi.port.ui.components.EmptyState
import com.kazumi.port.ui.components.LoadingState
import com.kazumi.port.ui.components.PageHeader
import com.kazumi.port.ui.navigation.Routes

private val collectTabs = listOf(
    "全部" to 0,
    "在看" to CollectType.WATCHING,
    "想看" to CollectType.WANT_TO_WATCH,
    "搁置" to CollectType.ON_HOLD,
    "看过" to CollectType.COMPLETED,
    "抛弃" to CollectType.DROPPED
)

/**
 * 收藏页（对应原版 collect_page.dart）
 * 从本地收藏仓库读取，按收藏状态分类展示
 */
@Composable
fun CollectPage(navController: NavHostController) {
    val showRating = com.kazumi.port.ui.components.rememberShowRating()
    val app = remember { KazumiApp.get() }
    var selectedType by remember { mutableIntStateOf(0) }
    var allItems by remember { mutableStateOf<List<CollectedBangumi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var listLayout by remember { mutableStateOf(false) } // false=卡片  true=列表
    var sort by remember { mutableIntStateOf(0) }
    var syncing by remember { mutableStateOf(false) }
    var syncTrigger by remember { mutableIntStateOf(0) }
    val toast = com.kazumi.port.ui.components.rememberToast()
    // 布局默认值取设置 defaultCollectLayout（原版默认 'list'）
    LaunchedEffect(Unit) {
        val layout = app.settings.getString(
            com.kazumi.port.data.repository.SettingsManager.Interface.DEFAULT_COLLECT_LAYOUT,
            "list"
        )
        listLayout = layout == "list"
    }

    LaunchedEffect(Unit) {
        isLoading = true
        allItems = app.collectRepository.getAll()
        isLoading = false
    }
    // 同步收藏：WebDAV 优先（同步收藏数据），否则 Bangumi 追番同步；完成刷新列表
    LaunchedEffect(syncTrigger) {
        if (syncTrigger == 0) return@LaunchedEffect
        syncing = true
        try {
            val webEnabled = app.settings.getBoolean(
                com.kazumi.port.data.repository.SettingsManager.WebDav.ENABLE, false
            ) && app.settings.getBoolean(
                com.kazumi.port.data.repository.SettingsManager.WebDav.ENABLE_COLLECT, false
            )
            val bangumiEnabled = app.settings.getBoolean(
                com.kazumi.port.data.repository.SettingsManager.Bangumi.SYNC_ENABLE, false
            )
            val done = if (webEnabled) {
                // 本地收藏序列化为 JSON（merge 必须返回非空 String）
                val localJson = runCatching {
                    val arr = com.google.gson.JsonArray()
                    allItems.forEach { c ->
                        val obj = com.google.gson.JsonObject()
                        obj.add("bangumiItem", c.bangumiItem.toJson())
                        obj.addProperty("time", c.time)
                        obj.addProperty("type", c.type)
                        arr.add(obj)
                    }
                    arr.toString()
                }.getOrDefault("[]")
                app.webDavSyncService.syncCollectibles { remote ->
                    // merge：远端无数据→传本地；本地无数据→用远端；都有→保留本地（简单策略）
                    when {
                        remote == null -> localJson
                        allItems.isEmpty() -> remote
                        else -> localJson
                    }
                } != null
            } else if (bangumiEnabled) {
                app.bangumiSyncService.syncCollectibles()
            } else false
            if (done) {
                allItems = app.collectRepository.getAll()
            } else {
                toast("未配置同步，请到设置→同步 开启")
            }
        } catch (e: Exception) {
            toast("同步失败：${e.message}")
        } finally {
            syncing = false
        }
    }

    // 类型数量（原版标签形如「全部，12 部」）
    fun countOf(type: Int): Int =
        if (type == 0) allItems.size else allItems.count { it.type == type }

    // 排序（原版 CollectSort：最近变更/番剧名称/评分最高/开播时间，默认最近变更）
    val sorted = allItems.sortedWith { a, b ->
        when (sort) {
            1 -> a.bangumiItem.displayName().compareTo(b.bangumiItem.displayName())
            2 -> b.bangumiItem.ratingScore.compareTo(a.bangumiItem.ratingScore)
            3 -> b.bangumiItem.airDate.compareTo(a.bangumiItem.airDate)
            else -> b.time.compareTo(a.time)
        }
    }
    val filtered = if (selectedType == 0) sorted else sorted.filter { it.type == selectedType }

    MainScaffold(navController) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(
                title = "追番",
                trailing = {
                    // 同步收藏（对齐原版 collect_page actions 的 StateActionButton.tonal 同步）
                    TextButton(onClick = { syncTrigger++ }) {
                        if (syncing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "同步收藏",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("同步")
                    }
                    // 排序（对齐原版 CollectSort 菜单）
                    var sortMenu by remember { mutableStateOf(false) }
                    val sortLabels = listOf("最近变更", "番剧名称", "评分最高", "开播时间")
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Text(sortLabels[sort])
                        }
                        DropdownMenu(
                            expanded = sortMenu,
                            onDismissRequest = { sortMenu = false },
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            sortLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        sort = index
                                        sortMenu = false
                                        toast("排序：$label")
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { listLayout = !listLayout }) {
                        Icon(
                            imageVector = if (listLayout) Icons.Filled.GridView else Icons.Filled.ViewList,
                            contentDescription = if (listLayout) "卡片布局" else "列表布局"
                        )
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                collectTabs.forEach { (label, type) ->
                    val selected = type == selectedType
                    // 原版标签形如「全部，12 部」
                    val text = "$label，${countOf(type)} 部"
                    Text(
                        text = text,
                        style = if (selected) MaterialTheme.typography.titleSmall
                        else MaterialTheme.typography.bodySmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .clickable { selectedType = type }
                    )
                }
            }
            HorizontalDivider()

            when {
                isLoading -> LoadingState(modifier = Modifier.padding(top = 32.dp))
                filtered.isEmpty() -> EmptyState("还没有收藏")
                else -> if (!listLayout) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.bangumiItem.id }) { collected ->
                            BangumiCard(
                                showRating = showRating,
                                item = collected.bangumiItem,
                                onClick = {
                                    navController.navigate(Routes.detail(collected.bangumiItem.id))
                                }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(filtered) { _, collected ->
                            val item = collected.bangumiItem
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        navController.navigate(Routes.detail(item.id))
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = item.coverUrl(),
                                    contentDescription = item.displayName(),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(
                                        text = item.displayName(),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    if (showRating && item.ratingScore > 0) {
                                        Text(
                                            text = "评分 ${String.format("%.1f", item.ratingScore)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    Text(
                                        text = item.airDate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}