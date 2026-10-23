package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.BangumiItem
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.ui.components.BangumiCard
import com.kazumi.port.ui.components.EmptyState
import com.kazumi.port.ui.components.LoadingState
import com.kazumi.port.ui.navigation.Routes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 原版 defaultAnimeTags 标签列表（热门番组 + 15 个标签） */
private val animeTags = listOf(
    "", "日常", "原创", "校园", "搞笑", "奇幻", "百合", "恋爱", "悬疑",
    "热血", "后宫", "机战", "轻改", "偶像", "治愈", "异世界"
)

private fun tagLabel(tag: String) = if (tag.isEmpty()) "热门番组" else tag

/**
 * 首页热门榜（对应原版 popular_page.dart）
 * 顶栏大标题 + 标签下拉菜单 + 搜索/历史按钮
 * 直连超时自动回退 Kazumi 镜像
 */
@Composable
fun PopularPage(navController: NavHostController) {
    var bangumiList by remember { mutableStateOf<List<BangumiItem>>(emptyList()) }
    var currentTag by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var isTimeout by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val bangumiApi = remember {
        BangumiApi(BangumiClient(KazumiApp.get().settings))
    }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    /** 读取 Bangumi 加速模式（与 BangumiClient 同源，默认 mirror） */
    suspend fun readAcceleration(): String = try {
        KazumiApp.get().settings.getString(
            com.kazumi.port.data.repository.SettingsManager.Proxy.BANGUMI_ACCELERATION,
            "mirror"
        )
    } catch (e: Exception) {
        "mirror"
    }

    /** 镜像模式是否开启（对齐原版 _bangumiMirrorEnabled：accel == mirror） */
    suspend fun mirrorEnabled(): Boolean = readAcceleration() == "mirror"

    /**
     * 拉取一页数据（对齐原版 popular_controller.queryBangumiByTrend/queryBangumiByTag）：
     * - 镜像模式（默认）：直接走 Kazumi 镜像缓存榜单 getBangumiMirrorPopularSubjects（1~2s）。
     *   注意：原版镜像模式下从不调 trending/榜单搜索（next.bgm.tv 的 /p1/trending 被镜像改写后 404），
     *   只有直连模式才走官方 trending / 榜单搜索。
     * - 非镜像模式：走官方 trending（无标签）/ 榜单搜索（有标签）。
     */
    suspend fun fetchPage(offset: Int, tag: String, limit: Int = 24): List<BangumiItem> {
        return withTimeoutOrNull(8_000) {
            if (mirrorEnabled()) {
                bangumiApi.getBangumiMirrorPopularSubjects(tag = tag, limit = limit, offset = offset)
            } else if (tag.isEmpty()) {
                bangumiApi.getBangumiTrendsList(limit = limit, offset = offset)
            } else {
                bangumiApi.getBangumiList(tag = tag)
            }
        } ?: emptyList()
    }

    suspend fun fetchFirstPage(tag: String) {
        isLoading = true
        val result = fetchPage(offset = 0, tag = tag)
        bangumiList = result
        isLoading = false
        isTimeout = result.isEmpty()
    }

    // 首次加载
    LaunchedEffect(Unit) {
        fetchFirstPage(currentTag)
    }

    fun loadMore() {
        if (isLoadingMore || isLoading) return
        isLoadingMore = true
        val nextOffset = bangumiList.size
        scope.launch {
            val result = fetchPage(offset = nextOffset, tag = currentTag)
            if (result.isNotEmpty()) {
                val existingIds = bangumiList.map { it.id }.toMutableSet()
                bangumiList = bangumiList + result.filter { existingIds.add(it.id) }
            }
            isLoadingMore = false
        }
    }

    fun selectTag(tag: String) {
        if (tag == currentTag) return
        currentTag = tag
        menuExpanded = false
        scope.launch { fetchFirstPage(tag) }
    }

    // 滚动到底部自动加载更多
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && lastVisible >= bangumiList.size - 3) {
                    loadMore()
                }
            }
    }

    // 回顶 FAB（对齐原版 PopularPage floatingActionButton: scroll to top）
    MainScaffold(navController) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxWidth = maxWidth
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏：大标题 + 标签下拉 + 搜索/历史按钮（对齐原版 SliverAppBar actions）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { menuExpanded = true }
                        .padding(end = 16.dp)
                ) {
                    Text(
                        text = tagLabel(currentTag),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "选择标签"
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { navController.navigate(Routes.SEARCH) }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
                IconButton(onClick = { navController.navigate(Routes.HISTORY) }) {
                    Icon(Icons.Filled.History, contentDescription = "历史记录")
                }
            }

            DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
                animeTags.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(tagLabel(tag)) },
                        onClick = { selectTag(tag) }
                    )
                }
            }

            // 加载更多顶部线性条（对齐原版 popular Scaffold LinearProgressIndicator）
            androidx.compose.animation.AnimatedVisibility(
                visible = isLoadingMore,
                modifier = Modifier.fillMaxWidth()
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
            }
            when {
                isLoading -> LoadingState()
                bangumiList.isEmpty() -> EmptyState(if (isTimeout) "加载超时，请检查网络或切换镜像" else "还没有数据")
                else -> {
                    // 响应式列数（对齐原版 contentGrid：<紧凑 3 列，>compact 5 列，>medium 6 列）
                    val crossCount = when {
                        maxWidth >= 900.dp -> 6
                        maxWidth >= 600.dp -> 5
                        else -> 3
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(crossCount),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bangumiList, key = { it.id }) { item ->
                            BangumiCard(
                                item = item,
                                onClick = {
                                    navController.navigate(
                                        Routes.detail(bangumiId = item.id)
                                    )
                                }
                            )
                        }
                        item {
                            if (isLoadingMore) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
            // 回顶 FAB（列表较长时显示）
            if (bangumiList.size > 6) {
                FloatingActionButton(
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "回顶")
                }
            }
        }
        }
    }
}