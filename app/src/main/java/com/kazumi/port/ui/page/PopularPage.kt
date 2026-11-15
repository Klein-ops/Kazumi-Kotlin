package com.kazumi.port.ui.page

import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
import com.kazumi.port.ui.components.rememberPressScale
import com.kazumi.port.ui.navigation.Routes
import com.kazumi.port.util.FileLogger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.rememberSharedContentState

/** 原版 defaultAnimeTags 标签列表（热门番组 + 15 个标签） */
private val animeTags = listOf(
    "", "日常", "原创", "校园", "搞笑", "奇幻", "百合", "恋爱", "悬疑",
    "热血", "后宫", "机战", "轻改", "偶像", "治愈", "异世界"
)

private fun tagLabel(tag: String) = if (tag.isEmpty()) "热门番组" else tag

/** 列表序列化（用 BangumiItem.toJson 的扁平格式，兼容 fromJson 反序列化） */
private fun List<BangumiItem>.toJsonArrayString(): String {
    val arr = com.google.gson.JsonArray()
    forEach { arr.add(it.toJson()) }
    return arr.toString()
}

private fun parseBangumiList(json: String): List<BangumiItem> = try {
    com.google.gson.JsonParser.parseString(json).asJsonArray.mapNotNull { el ->
        if (el.isJsonObject) BangumiItem.fromJson(el.asJsonObject) else null
    }
} catch (e: Exception) {
    emptyList()
}

/**
 * 首页热门榜（对应原版 popular_page.dart）
 * 顶栏大标题 + 标签下拉菜单 + 搜索/历史按钮
 * 直连超时自动回退 Kazumi 镜像
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun PopularPage(
    navController: NavHostController,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    with(sharedTransitionScope) {
    val showRating = com.kazumi.port.ui.components.rememberShowRating()
    // 用 rememberSaveable 保存列表数据：主 tab 切走（推荐→设置→推荐）时，
    // NavHost restoreState 恢复页面，数据/标签不丢，不再重新 fetch + 重载图片。
    var bangumiListJson by rememberSaveable { mutableStateOf("") }
    var currentTag by rememberSaveable { mutableStateOf("") }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var isLoadingMore by rememberSaveable { mutableStateOf(false) }
    var isTimeout by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    // 下拉刷新中：与 isLoading 区分（刷新保留旧列表 + 顶部指示器，不整页白屏）
    var isRefreshing by remember { mutableStateOf(false) }

    val bangumiList = parseBangumiList(bangumiListJson)

    val bangumiApi = remember {
        BangumiApi(BangumiClient(KazumiApp.get().settings))
    }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    /** 读取 Bangumi 加速模式（与 BangumiClient 同源：未设置时回落到 enableBangumiProxy → 镜像） */
    suspend fun readAcceleration(): String = try {
        KazumiApp.get().settings.bangumiAcceleration()
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
        // 不设外层 withTimeoutOrNull：底层 OkHttp 已配 connect/read 12s + callTimeout 20s +
        // retryOnConnectionFailure，BangumiClient 内部还会镜像/直连并发 race（谁先成功用谁）。
        // 之前加的 8s 硬超时会在镜像/直连都在慢（>8s）时提前掐断返回空 → 首页"慢时直接提示刷新"，
        // 原版没有这层限制；去掉后最坏 20s 由底层兜底必返回，慢只会多等几秒而非直接失败。
        return if (mirrorEnabled()) {
            bangumiApi.getBangumiMirrorPopularSubjects(tag = tag, limit = limit, offset = offset)
        } else if (tag.isEmpty()) {
            bangumiApi.getBangumiTrendsList(limit = limit, offset = offset)
        } else {
            bangumiApi.getBangumiList(tag = tag)
        }
    }

    suspend fun fetchFirstPage(tag: String) {
        isLoading = true
        val result = try {
            fetchPage(offset = 0, tag = tag)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 必须兜底：底层全部通路失败会抛 NetworkException，若不捕获则协程异常逃逸，
            // isLoading 永远停在 true，整个首页卡死在 Loading 态（用户反馈"切走再切回加载失败、
            // 刷新无效、只能大退"的根因）。失败落 isTimeout 走"加载超时"空态，可下拉/再进重试。
            FileLogger.w("PopularPage", "首次加载失败: ${e.message}")
            emptyList()
        }
        bangumiListJson = result.toJsonArrayString()
        isLoading = false
        isTimeout = result.isEmpty()
    }

    // 下拉刷新：重新拉第一页，保留旧列表直到新数据就绪（避免整页白屏）
    suspend fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        try {
            val result = fetchPage(offset = 0, tag = currentTag)
            bangumiListJson = result.toJsonArrayString()
            isTimeout = result.isEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w("PopularPage", "下拉刷新失败: ${e.message}")
            isTimeout = true
        } finally {
            isRefreshing = false
        }
    }

    // 首次加载：仅当恢复状态里没有数据时才 fetch（切 tab 回来记住状态，不再重载）
    LaunchedEffect(Unit) {
        if (bangumiList.isEmpty() && isLoading) {
            fetchFirstPage(currentTag)
        } else {
            isLoading = false
        }
    }

    fun loadMore() {
        if (isLoadingMore || isLoading) return
        isLoadingMore = true
        val nextOffset = bangumiList.size
        scope.launch {
            try {
                val result = fetchPage(offset = nextOffset, tag = currentTag)
                if (result.isNotEmpty()) {
                    val existingIds = bangumiList.map { it.id }.toMutableSet()
                    bangumiListJson = (bangumiList + result.filter { existingIds.add(it.id) }).toJsonArrayString()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 加载更多失败不打断已有列表，仅记录（isTimeout 保持原状，避免误伤首屏空态判断）
                FileLogger.w("PopularPage", "加载更多失败: ${e.message}")
            } finally {
                isLoadingMore = false
            }
        }
    }

    fun selectTag(tag: String) {
        if (tag == currentTag) return
        currentTag = tag
        menuExpanded = false
        scope.launch {
            try {
                fetchFirstPage(tag)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // fetchFirstPage 内部已兜底，此处再包一层防止 selectTag 协程异常逃逸
                FileLogger.w("PopularPage", "切换标签失败: ${e.message}")
            }
        }
    }

    // 滚动到底部自动加载更多（对齐原版 scrollListener：pixels >= maxScrollExtent - 200 才触发）。
    // 之前用「最后可见项 index >= size-3」判定，3 列网格下一屏只差 1 行就会触发，
    // 表现为"随便往下划两下加载条就亮一次"——现在改成真正触底（距内容底部 ≤200px）才加载。
    LaunchedEffect(gridState, bangumiList.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow false
            // 距底部剩余高度 = 最后可见项底部到 viewport 底部的距离 + 其后的项数 × 项高（网格近似等高）
            val remaining = info.viewportEndOffset +
                (info.totalItemsCount - last.index - 1).coerceAtLeast(0) * last.size
            remaining <= 200f
        }
            .distinctUntilChanged()
            .collect { atBottom -> if (atBottom) loadMore() }
    }

    // 回顶 FAB（对齐原版 PopularPage floatingActionButton: scroll to top）
    MainScaffold(navController) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxWidth = maxWidth
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏大标题 + 标签下拉 + 搜索/历史按钮（对齐原版 SliverAppBar actions）
            val tagPress = rememberPressScale()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = tagPress.scale
                                scaleY = tagPress.scale
                            }
                            .clickable(
                                interactionSource = tagPress.interactionSource,
                                indication = LocalIndication.current,
                                onClick = { menuExpanded = true }
                            )
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
                    // 锚定在标题下方（对齐原版 KazumiMenuButton：
                    // MenuAnchor 挂在标题 InkWell 上，maximumSize = Size(240, 350)）
                    // 标签下拉菜单：对齐原版 KazumiMenuButton maximumSize Size(240,350)。
                    // 必须同时限制高度（heightIn max 350），否则 16 个标签全部平铺、
                    // 菜单溢出到屏幕上下边缘且无法滚动（用户反馈"全铺开扑到屏幕顶上和最底下"）。
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier
                            .widthIn(max = 240.dp)
                            .heightIn(max = 350.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        animeTags.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(tagLabel(tag)) },
                                onClick = { selectTag(tag) }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { navController.navigate(Routes.SEARCH) }) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
                IconButton(onClick = { navController.navigate(Routes.HISTORY) }) {
                    Icon(Icons.Filled.History, contentDescription = "历史记录")
                }
            }

            // 加载更多顶部线性条（对齐原版 popular Scaffold LinearProgressIndicator：
            // 固定 4dp 高度 + AnimatedOpacity 淡入淡出，出现/消失不顶动布局。
            // 之前用 AnimatedVisibility（visible=false 直接移除），每次加载条出现都会把
            // 下方列表往下挤 4dp、消失又弹回，配合"滑两下就触发"表现为加载条一直跳。）
            Box(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isLoadingMore,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(300))
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
            // 下拉刷新：对列表整体生效（空态/加载态包可滚动容器，保证手势可用）
            // 显式指定 indicator（containerColor/color 用主题色而非默认透明），
            // 避免 MD3 默认指示器在部分主题下透明不可见
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { scope.launch { refresh() } },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                when {
                    isLoading -> {
                        // 可滚动容器：空内容也能触发下拉手势
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            LoadingState(modifier = Modifier.padding(top = 64.dp))
                        }
                    }
                    bangumiList.isEmpty() -> {
                        // 可滚动容器：空态/超时也能下拉重试
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            EmptyState(
                                text = if (isTimeout) "加载超时，请检查网络或切换镜像" else "还没有数据"
                            )
                        }
                    }
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
                                showRating = showRating,
                                item = item,
                                modifier = Modifier.animateItem(),
                                // 封面共享元素动画（对齐原版 Hero tag=bangumiItem.id）：
                                // key = "cover_{id}"，详情页封面用同一 key 即可跨页平移动画
                                coverModifier = Modifier.sharedElement(
                                    state = rememberSharedContentState(key = "cover_${item.id}"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                ),
                                onClick = {
                                    navController.navigate(
                                        Routes.detail(bangumiId = item.id)
                                    )
                                }
                            )
                        }
                    }
                    }
                }
            }
        }
            // 回顶 FAB（对齐原版 PopularPage 无条件显示 floatingActionButton）
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