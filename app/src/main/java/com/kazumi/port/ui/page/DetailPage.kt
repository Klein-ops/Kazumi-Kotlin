package com.kazumi.port.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.google.gson.JsonParser
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.BangumiItem
import com.kazumi.port.data.model.BangumiRelation
import com.kazumi.port.data.model.CharacterItem
import com.kazumi.port.data.model.CommentItem
import com.kazumi.port.data.model.Road
import com.kazumi.port.data.model.StaffFullItem
import com.kazumi.port.data.model.resolveRelatedAnimeChain
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.data.repository.CollectType
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.ui.components.CharacterCard
import com.kazumi.port.ui.components.CharacterInfoSheet
import com.kazumi.port.ui.components.CommentCard
import com.kazumi.port.ui.components.CommentCardBone
import com.kazumi.port.ui.components.GeneralEmptyState
import com.kazumi.port.ui.components.GeneralErrorWidget
import com.kazumi.port.ui.components.RatingReviewSheet
import com.kazumi.port.ui.components.SourceSheet
import com.kazumi.port.ui.components.RelatedBangumiCard
import com.kazumi.port.ui.components.StaffCard
import com.kazumi.port.ui.components.rememberToast
import com.kazumi.port.ui.navigation.Routes
import com.kazumi.port.util.CoverSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val INFO_TABS = listOf("概览", "吐槽", "角色", "关联", "制作人员")

/**
 * 番剧详情页（对应原版 info_page.dart + info_tabview.dart）
 * 5 个 Tab：概览 / 吐槽（评论区）/ 角色 / 关联 / 制作人员
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPage(
    navController: NavHostController,
    bangumiId: Int,
    pluginName: String,
    src: String
) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    val bangumiApi = remember { BangumiApi(BangumiClient(KazumiApp.get().settings)) }

    // 详情数据：从播放器返回时详情页组合会被销毁重建，
    // 用 rememberSaveable 序列化核心状态，避免返回后重新拉详情/线路/重置 Tab
    var bangumiJson by rememberSaveable { mutableStateOf("") }
    val bangumi = remember(bangumiJson) {
        if (bangumiJson.isEmpty()) null
        else runCatching {
            BangumiItem.fromJson(JsonParser.parseString(bangumiJson).asJsonObject)
        }.getOrNull()
    }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var roadsLoading by remember { mutableStateOf(false) }
    var roadsLoaded by rememberSaveable { mutableStateOf(false) }
    var isCollected by rememberSaveable { mutableStateOf(false) }
    var collectType by rememberSaveable { mutableIntStateOf(0) }

    // 插件 -> (src, roads)，存 JSON 以便 rememberSaveable 恢复
    var pluginRoadsJson by rememberSaveable { mutableStateOf("") }
    val pluginRoads = remember(pluginRoadsJson) {
        if (pluginRoadsJson.isEmpty()) emptyList()
        else runCatching {
            val arr = JsonParser.parseString(pluginRoadsJson).asJsonArray
            arr.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val obj = el.asJsonObject
                val name = obj.get("plugin")?.asString ?: return@mapNotNull null
                val src = obj.get("src")?.asString ?: return@mapNotNull null
                val roads = obj.getAsJsonArray("roads")?.mapNotNull { r ->
                    if (r.isJsonObject) Road.fromJson(r.asJsonObject) else null
                } ?: emptyList()
                Triple(name, src, roads)
            }
        }.getOrDefault(emptyList())
    }
    fun savePluginRoads(roads: List<Triple<String, String, List<Road>>>) {
        pluginRoadsJson = runCatching {
            val arr = com.google.gson.JsonArray()
            roads.forEach { (name, src, roadList) ->
                val obj = com.google.gson.JsonObject()
                obj.addProperty("plugin", name)
                obj.addProperty("src", src)
                val roadsArr = com.google.gson.JsonArray()
                roadList.forEach { roadsArr.add(it.toJson()) }
                obj.add("roads", roadsArr)
                arr.add(obj)
            }
            arr.toString()
        }.getOrDefault("")
    }
    var selectedPlugin by rememberSaveable { mutableIntStateOf(-1) }
    var selectedRoad by rememberSaveable { mutableIntStateOf(0) }

    // Tab 状态
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    // 评论
    var comments by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsLoaded by remember { mutableStateOf(false) }
    var commentsError by remember { mutableStateOf(false) }
    var commentsOffset by remember { mutableIntStateOf(0) }
    // 角色
    var characters by remember { mutableStateOf<List<CharacterItem>>(emptyList()) }
    var charactersLoading by remember { mutableStateOf(false) }
    var charactersError by remember { mutableStateOf(false) }
    var charactersEmpty by remember { mutableStateOf(false) }
    // 声优
    var staffs by remember { mutableStateOf<List<StaffFullItem>>(emptyList()) }
    var staffLoading by remember { mutableStateOf(false) }
    var staffError by remember { mutableStateOf(false) }
    var staffEmpty by remember { mutableStateOf(false) }
    // 关联
    var relations by remember { mutableStateOf<List<BangumiRelation>>(emptyList()) }
    var relationsLoading by remember { mutableStateOf(false) }
    var relationsError by remember { mutableStateOf(false) }
    var relationsLoaded by remember { mutableStateOf(false) }

    // 弹窗
    var showSourceSheet by remember { mutableStateOf(false) }
    var showReviewSheet by remember { mutableStateOf(false) }
    var selectedCharacter by remember { mutableStateOf<CharacterItem?>(null) }
    var reviewSubmitted by remember { mutableStateOf(false) }
    val showToast = rememberToast()

    // 详情加载：整体切到 IO 线程执行网络/DB 密集操作，避免任何主线程阻塞
    // （LaunchedEffect 默认在 Main dispatcher，插件线路查询涉及同步 SQLite/OkHttp，
    //   若在主线程执行会导致整个页面冻结——时间表页无此逻辑所以不卡）
    LaunchedEffect(bangumiId) {
        // 从播放器返回恢复的状态已含详情/线路，跳过重复加载
        if (bangumiJson.isNotEmpty() && roadsLoaded) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val item = bangumiApi.getBangumiInfoByID(bangumiId)
            withContext(Dispatchers.Main) {
                if (item != null) {
                    bangumiJson = runCatching { item.toJson().toString() }.getOrDefault(bangumiJson)
                }
                isLoading = false
            }
            if (item != null) {
                val collected = app.collectRepository.getByBangumiId(bangumiId)
                withContext(Dispatchers.Main) {
                    isCollected = collected != null
                    collectType = collected?.type ?: 0
                    roadsLoading = true
                }
                val name = item.displayName()
                // 逐个插件加载：每完成一个就把已收集的线路先显示（不等全部），
                // 用户要求"加载出一个就显示一个"；对齐原版全并发，无信号量/硬超时限流
                // （信号量 3 路 + 6s 超时叠加会让慢插件拖慢整批，原版 2-3s 出结果）
                val collectedRoads = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, List<Road>>>()
                val jobs = app.pluginRepository.allPlugins().map { plugin ->
                    async {
                        try {
                            val search = app.pluginRepository.searchBangumi(plugin.name, name)
                            val hit = search.data.firstOrNull() ?: return@async
                            if (hit.src.isEmpty()) return@async
                            val roads = app.pluginRepository.queryChapterRoads(plugin.name, hit.src)
                            if (roads.isNotEmpty()) {
                                val entry = Triple(plugin.name, hit.src, roads)
                                collectedRoads.add(entry)
                                // 立即通知 UI 刷新（主线程）
                                withContext(Dispatchers.Main) {
                                    savePluginRoads(collectedRoads.toList())
                                    roadsLoading = false
                                    roadsLoaded = true
                                    if (pluginRoads.isNotEmpty() && selectedPlugin < 0) selectedPlugin = 0
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                jobs.forEach { it.join() }
                withContext(Dispatchers.Main) {
                    savePluginRoads(collectedRoads.toList())
                    roadsLoading = false
                    roadsLoaded = true
                    if (pluginRoads.isNotEmpty() && selectedPlugin < 0) selectedPlugin = 0
                }
            }
        }
    }

    // Tab 懒加载：评论/角色/关联/制作人员在首次进入对应 Tab 时拉取
    fun loadComments(loadMore: Boolean = false) {
        if (commentsLoading) return
        val item = bangumi ?: return
        commentsLoading = true
        commentsError = false
        scope.launch {
            try {
                val offset = if (loadMore) commentsOffset else 0
                val response = bangumiApi.getBangumiCommentsByID(item.id, offset = offset)
                comments = if (loadMore) comments + response.commentList else response.commentList
                commentsOffset = if (loadMore) commentsOffset + response.commentList.size else response.commentList.size
                if (!loadMore) commentsLoaded = true
            } catch (_: Exception) {
                commentsError = true
            } finally {
                commentsLoading = false
            }
        }
    }

    fun loadCharacters() {
        if (charactersLoading || characters.isNotEmpty() || charactersEmpty || charactersError) return
        val item = bangumi ?: return
        charactersLoading = true
        charactersError = false
        scope.launch {
            try {
                val list = bangumiApi.getCharatersByBangumiID(item.id)
                // 角色排序：主角/配角/客串（对应原版 relation 优先级）
                val relationPriority = mapOf("主角" to 1, "配角" to 2, "客串" to 3)
                // distinctBy：同一角色可能出现在多个 relation 分组里，
                // 而下面的 LazyColumn 用 it.id 做 key，重复会导致 Key was already used 崩溃
                characters = list.distinctBy { it.id }.sortedBy { relationPriority[it.relation] ?: 4 }
                if (characters.isEmpty()) charactersEmpty = true
            } catch (_: Exception) {
                charactersError = true
            } finally {
                charactersLoading = false
            }
        }
    }

    fun loadStaff() {
        if (staffLoading || staffs.isNotEmpty() || staffEmpty || staffError) return
        val item = bangumi ?: return
        staffLoading = true
        staffError = false
        scope.launch {
            try {
                val response = bangumiApi.getBangumiStaffByID(item.id)
                // distinctBy：LazyColumn 用 it.staff.id 做 key，重复会让 Compose 抛重复 key
                staffs = response.data.distinctBy { it.staff.id }
                if (staffs.isEmpty()) staffEmpty = true
            } catch (_: Exception) {
                staffError = true
            } finally {
                staffLoading = false
            }
        }
    }

    fun loadRelations() {
        if (relationsLoading || relationsLoaded || relationsError) return
        val item = bangumi ?: return
        relationsLoading = true
        relationsError = false
        scope.launch {
            try {
                relations = resolveRelatedAnimeChain(
                    currentSubjectId = item.id,
                    fetchRelations = { id -> bangumiApi.getBangumiRelationsByID(id) }
                    // distinctBy：关联番剧列表用 it.bangumiItem.id 做 key，同一 id 出现两次会崩
                ).distinctBy { it.bangumiItem.id }
                relationsLoaded = true
            } catch (_: Exception) {
                relationsError = true
            } finally {
                relationsLoading = false
            }
        }
    }

    // Tab 切换懒加载
    LaunchedEffect(tabIndex) {
        when (tabIndex) {
            1 -> if (!commentsLoaded && comments.isEmpty()) loadComments()
            2 -> loadCharacters()
            3 -> loadRelations()
            4 -> loadStaff()
        }
    }

    // 评论提交成功后刷新
    LaunchedEffect(reviewSubmitted) {
        if (reviewSubmitted) {
            reviewSubmitted = false
            commentsError = false
            scope.launch {
                val item = bangumi ?: return@launch
                val response = bangumiApi.getBangumiCommentsByID(item.id, offset = 0)
                comments = response.commentList
                commentsOffset = response.commentList.size
            }
        }
    }

    fun toggleCollect() {
        val item = bangumi ?: return
        scope.launch {
            if (isCollected) {
                app.collectRepository.delete(item.id)
                isCollected = false
                collectType = 0
            } else {
                app.collectRepository.upsert(item, CollectType.WANT_TO_WATCH)
                isCollected = true
                collectType = CollectType.WANT_TO_WATCH
            }
        }
    }



    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = bangumi?.displayName() ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (bangumi != null) {
                        // 滚动后显示收藏（对齐原版 SliverAppBar actions 的 CollectButton）
                        IconButton(onClick = { toggleCollect() }) {
                            Icon(
                                if (isCollected) Icons.Filled.Star else Icons.Filled.Star,
                                contentDescription = if (isCollected) "取消收藏" else "收藏",
                                tint = if (isCollected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 更多操作（对齐原版 InfoActionsMenu：保存封面/复制标题/外部打开）
                        var menuExpanded by remember { mutableStateOf(false) }
                        var savingCover by remember { mutableStateOf(false) }
                        val context = LocalContext.current
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreHoriz, contentDescription = "更多操作")
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surface) {
                                DropdownMenuItem(
                                    text = { Text(if (savingCover) "保存中…" else "保存封面") },
                                    enabled = !savingCover,
                                    onClick = {
                                        menuExpanded = false
                                        savingCover = true
                                        scope.launch {
                                            val url = bangumi.coverUrl()
                                            if (url.isBlank()) {
                                                showToast("暂无封面")
                                            } else {
                                                val ok = CoverSaver.saveCoverFromUrl(
                                                    context,
                                                    url,
                                                    CoverSaver.fileName(bangumi.displayName(), bangumi.id)
                                                )
                                                showToast(if (ok) "已保存到相册" else "封面保存失败，请重试")
                                            }
                                            savingCover = false
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("复制标题") },
                                    onClick = {
                                        menuExpanded = false
                                        val title = bangumi.displayName()
                                        if (title.isBlank()) {
                                            showToast("复制失败，请重试")
                                        } else {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as? android.content.ClipboardManager
                                            cm?.setPrimaryClip(
                                                android.content.ClipData.newPlainText("title", title)
                                            )
                                            showToast("标题已复制")
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("外部打开") },
                                    onClick = {
                                        menuExpanded = false
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://bangumi.tv/subject/${bangumi.id}")
                                        )
                                        val ok = runCatching { context.startActivity(intent) }.isSuccess
                                        if (!ok) showToast("无法打开浏览器")
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (bangumi != null) {
                ExtendedFloatingActionButton(
                    onClick = { showSourceSheet = true },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    text = { Text("开始观看") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
                bangumi == null -> Text(
                    "加载失败",
                    modifier = Modifier.padding(32.dp).align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    val item = bangumi!!
                    val selectedTab = tabIndex
                    // 统一滚动：头部作为普通 item 参与滚动，TabRow 吸顶。
                    // 手写吸顶（不依赖 stickyHeader）：header 是 index 0，tab 是 index 1，
                    // 当 firstVisibleItemIndex >= 1 说明头部已滚出、tab 顶到顶部，就在上方盖一层固定 TabRow。
                    val listState = rememberLazyListState()
                    val showStickyTabs by remember {
                        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            item {
                                DetailHeader(item)
                            }
                            item(key = "tab_row") {
                                TabRow(selectedTabIndex = selectedTab) {
                                    INFO_TABS.forEachIndexed { index, title ->
                                        Tab(
                                            selected = selectedTab == index,
                                            onClick = { tabIndex = index },
                                            text = { Text(title) }
                                        )
                                    }
                                }
                            }
                            when (selectedTab) {
                                0 -> OverviewTabContent(item = item, screenWidthDp = LocalConfiguration.current.screenWidthDp, onOpenReview = {
                                    if (collectType == 0) {
                                        showToast("请先追番")
                                    } else {
                                        showReviewSheet = true
                                    }
                                }, onTagClick = { tagName ->
                                    navController.navigate(Routes.SEARCH)
                                    scope.launch {
                                        kotlinx.coroutines.delay(150)
                                        navController.currentBackStackEntry?.savedStateHandle
                                            ?.set("image_search_keyword", tagName)
                                    }
                                })
                                1 -> CommentsTabContent(
                                    comments = comments,
                                    loading = commentsLoading,
                                    loaded = commentsLoaded,
                                    error = commentsError,
                                    hasReview = item.interest?.hasReviewContent == true,
                                    onReviewTap = {
                                        if (collectType == 0) {
                                            showToast("请先追番")
                                        } else {
                                            showReviewSheet = true
                                        }
                                    },
                                    onRetry = { loadComments() },
                                    onLoadMore = { loadComments(loadMore = true) }
                                )
                                2 -> CharactersTabContent(
                                    characters = characters,
                                    loading = charactersLoading,
                                    error = charactersError,
                                    empty = charactersEmpty,
                                    onRetry = { loadCharacters() },
                                    onCharacterClick = { character ->
                                        selectedCharacter = character
                                    }
                                )
                                3 -> RelationsTabContent(
                                    relations = relations,
                                    loading = relationsLoading,
                                    error = relationsError,
                                    loaded = relationsLoaded,
                                    onRetry = { loadRelations() },
                                    onRelationClick = { relation ->
                                        if (relation.bangumiItem.id > 0) {
                                            navController.navigate(
                                                Routes.detail(relation.bangumiItem.id)
                                            )
                                        }
                                    }
                                )
                                4 -> StaffTabContent(
                                    staffs = staffs,
                                    loading = staffLoading,
                                    error = staffError,
                                    empty = staffEmpty,
                                    onRetry = { loadStaff() }
                                )
                            }
                        }
                        // 吸顶层：头部滚出后固定 TabRow
                        if (showStickyTabs) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                TabRow(selectedTabIndex = selectedTab) {
                                    INFO_TABS.forEachIndexed { index, title ->
                                        Tab(
                                            selected = selectedTab == index,
                                            onClick = { tabIndex = index },
                                            text = { Text(title) }
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

    // 播放来源弹窗（对齐原版 SourceSheet：点 FAB「开始观看」弹出）
    if (showSourceSheet && bangumi != null) {
        SourceSheet(
            keyword = bangumi!!.displayName(),
            aliases = bangumi!!.alias,
            onPlay = { pluginName, src, title ->
                showSourceSheet = false
                scope.launch {
                    try {
                        val roads = app.pluginRepository.queryChapterRoads(pluginName, src)
                        if (roads.isEmpty()) {
                            showToast("未能获取播放列表")
                        } else {
                            val roadsJson = runCatching {
                                val arr = com.google.gson.JsonArray()
                                roads.forEach { arr.add(it.toJson()) }
                                arr.toString()
                            }.getOrDefault("")
                            // 关键修复：播放器要解析的是"播放页 URL"，不是搜索结果的详情页 URL。
                            // 原版 source_sheet.dart 的 _openSearchItem 用 searchItem.src 查线路后，
                            // 播放的是 roads 里第一集（road.data[0]），而非详情页本身。
                            // 之前把详情页 src 传给播放器 → WebView 加载详情页（无视频）→ 3×20s 超时，
                            // 这就是"解析视频源极慢"的根因（同源同线路原版几秒出画面）。
                            val firstEpisodeUrl = roads.firstOrNull()?.data?.firstOrNull()
                            if (firstEpisodeUrl.isNullOrBlank()) {
                                showToast("未能获取播放列表")
                            } else {
                                navController.navigate(
                                    Routes.player(
                                        bangumiId = bangumi!!.id,
                                        pluginName = pluginName,
                                        src = firstEpisodeUrl,
                                        episode = 1,
                                        road = 0,
                                        isLocal = false,
                                        roadsJson = roadsJson
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        showToast("未能获取播放列表")
                    }
                }
            },
            onDismiss = { showSourceSheet = false }
        )
    }

    // 角色详情弹窗
    selectedCharacter?.let { character ->
        CharacterInfoSheet(
            characterID = character.id,
            characterName = character.name,
            characterRelation = character.relation,
            actorNames = character.actorList.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct(),
            onDismiss = { selectedCharacter = null }
        )
    }

    // 评分吐槽弹窗
    if (showReviewSheet && bangumi != null) {
        RatingReviewSheet(
            bangumiItem = bangumi!!,
            onSubmit = { score, tags, comment ->
                val token = app.settings.bangumiAccessToken().trim()
                if (token.isEmpty()) {
                    showToast("请先在同步设置中绑定 Bangumi")
                    false
                } else if (collectType == 0) {
                    showToast("请先追番")
                    false
                } else {
                    val updated = bangumiApi.addOrUpdateBangumiEvaluationBySubjectID(
                        subjectID = bangumi!!.id,
                        localType = collectType,
                        comment = comment,
                        rate = score,
                        tags = tags
                    )
                    if (updated) {
                        reviewSubmitted = true
                        showReviewSheet = false
                    } else {
                        showToast("发表失败，请检查网络或 Bangumi 授权")
                    }
                    updated
                }
            },
            onDismiss = { showReviewSheet = false }
        )
    }
}

/**
 * 详情头部（封面 + 标题 + 评分 + 首播 + 简介摘要）
 */
@Composable
private fun DetailHeader(item: BangumiItem) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        // 大标题（对齐原版 info 页 headline 标题）
        Text(
            text = item.displayName(),
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = item.coverUrl(),
                contentDescription = item.displayName(),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(100.dp)
                    .height(140.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                // 放送开始（对齐原版强调色）
                Text(
                    text = "放送开始:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.airDate.ifEmpty { "未知" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                // 评分 + 星级
                Text(
                    text = if (item.votes > 0) "${item.votes} 人评分:" else "评分:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = String.format("%.1f", item.ratingScore),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    // 五星（评分/2 = 5 星制，对齐原版 RatingBarIndicator）
                    repeat(5) { i ->
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = if (item.ratingScore / 2 > i) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Rank
                Text(
                    text = "Bangumi Ranked:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (item.rank > 0) "#${item.rank}" else "#--",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (item.summary.isNotEmpty()) {
            Text(
                text = item.summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        // 评分透视柱状图（对齐原版 fl_chart 10 档柱状，纯 Canvas 手绘零依赖）
        // 原版显示条件：showRating && 屏宽 >= 600dp（LayoutBreakpoint.compact['width']）&& !isLoading
        if (screenWidthDp >= 600 && item.votesCount.isNotEmpty() && item.votes > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "评分透视:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val maxVote = item.votesCount.maxOrNull()?.coerceAtLeast(1) ?: 1
            // Canvas drawScope 内不能调 MaterialTheme（@Composable），提前缓存
            val barColor = MaterialTheme.colorScheme.primary
            val barColorFaint = barColor.copy(alpha = 0.35f)
            var touchedIndex by remember { mutableIntStateOf(-1) }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .pointerInput(item.votesCount) {
                        detectTapGestures { offset ->
                            // pointerInput 里 size 是 IntSize，转 Float 再除，避免整数截断导致
                            // 命中边界与绘制时的浮点 barWidth（Canvas size.width 是 Float）不一致
                            val barWidth = size.width.toFloat() / item.votesCount.size
                            val idx = (offset.x / barWidth).toInt().coerceIn(0, item.votesCount.size - 1)
                            touchedIndex = if (touchedIndex == idx) -1 else idx
                        }
                    }
            ) {
                val barWidth = size.width / item.votesCount.size
                item.votesCount.forEachIndexed { index, count ->
                    val h = (count.toFloat() / maxVote) * size.height
                    val left = index * barWidth + barWidth * 0.2f
                    val right = (index + 1) * barWidth - barWidth * 0.2f
                    val isTouched = index == touchedIndex
                    drawRoundRect(
                        color = if (isTouched) barColor else barColorFaint,
                        topLeft = Offset(left, size.height - h),
                        size = Size(right - left, h),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("10", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 点击某档柱后显示占比（对齐原版 BarTouchTooltipData：xx.xx% (n人)）
            if (touchedIndex >= 0 && touchedIndex < item.votesCount.size) {
                val count = item.votesCount[touchedIndex]
                val pct = count.toFloat() / item.votes * 100f
                Text(
                    text = "${String.format("%.2f", pct)}% (${count}人)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * 概览 Tab：简介 + 标签 + 集数网格
 */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.OverviewTabContent(
    item: BangumiItem,
    screenWidthDp: Int,
    onOpenReview: () -> Unit,
    onTagClick: (String) -> Unit
) {
    // 对齐原版 infoBody：maxWidth 950 居中，简介标题 18sp，标签 ActionChip
    item {
        androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 950.dp)
                ) {
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    var fullIntro by remember { mutableStateOf(false) }
                    val summary = item.summary.ifEmpty { "暂无简介" }
                    if (summary.length > 160 && !fullIntro) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 7,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = { fullIntro = true },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("加载更多")
                        }
                    } else {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (fullIntro) {
                            TextButton(
                                onClick = { fullIntro = false },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("加载更少")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        if (item.tags.isNotEmpty()) {
            item {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .widthIn(max = 950.dp)
                    ) {
                        Text(
                            text = "标签",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        var fullTag by remember { mutableStateOf(false) }
                        val visible = if (fullTag || item.tags.size < 13) item.tags else item.tags.take(13)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            visible.forEach { tag ->
                                androidx.compose.material3.InputChip(
                                    selected = false,
                                    onClick = { onTagClick(tag.name) },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(tag.name)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = tag.count.toString(),
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                )
                            }
                            if (item.tags.size >= 13 && !fullTag) {
                                androidx.compose.material3.InputChip(
                                    selected = false,
                                    onClick = { fullTag = true },
                                    label = { Text("更多 +", color = MaterialTheme.colorScheme.primary) }
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
}
/**
 * 吐槽 Tab：评论区（自己评论置顶 + 滑动到底加载更多）
 */
private fun LazyListScope.CommentsTabContent(
    comments: List<CommentItem>,
    loading: Boolean,
    loaded: Boolean,
    error: Boolean,
    hasReview: Boolean,
    onReviewTap: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit
) {
    // 写吐槽入口
        if (!hasReview) {
            item {
                OutlinedButton(
                    onClick = onReviewTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("下面我简单喵两句", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        when {
            error && comments.isEmpty() -> {
                item {
                    GeneralErrorWidget(
                        title = "评论加载失败",
                        errMsg = "请检查网络连接后重试。",
                        onRetry = onRetry,
                        modifier = Modifier.padding(vertical = 48.dp)
                    )
                }
            }
            loading && comments.isEmpty() -> {
                items(4) {
                    CommentCardBone()
                }
            }
            comments.isEmpty() && !hasReview -> {
                item {
                    GeneralEmptyState(
                        icon = Icons.Filled.ChatBubbleOutline,
                        title = "暂无吐槽",
                        modifier = Modifier.padding(vertical = 48.dp)
                    )
                }
            }
            else -> {
                items(comments.size) { index ->
                    CommentCard(item = comments[index])
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
                // 触底加载更多
                item {
                    if (loading) {
                        CommentCardBone()
                    } else if (comments.isNotEmpty()) {
                        TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
}

/**
 * 角色 Tab
 */
private fun LazyListScope.CharactersTabContent(
    characters: List<CharacterItem>,
    loading: Boolean,
    error: Boolean,
    empty: Boolean,
    onRetry: () -> Unit,
    onCharacterClick: (CharacterItem) -> Unit
) {
    when {
        error -> item {
            GeneralErrorWidget(
                title = "角色列表加载失败",
                errMsg = "请检查网络连接后重试。",
                onRetry = onRetry,
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        empty -> item {
            GeneralEmptyState(
                icon = Icons.Filled.PeopleAlt,
                title = "暂无角色信息",
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        loading && characters.isEmpty() -> item {
            Column(modifier = Modifier.padding(16.dp)) {
                repeat(4) {
                    Row(modifier = Modifier.padding(vertical = 8.dp)) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier.size(36.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {}
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(14.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(12.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
            }
        }
        else -> {
            items(characters, key = { it.id }) { character ->
                CharacterCard(
                    item = character,
                    onClick = { onCharacterClick(character) }
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/**
 * 关联 Tab
 */
private fun LazyListScope.RelationsTabContent(
    relations: List<BangumiRelation>,
    loading: Boolean,
    error: Boolean,
    loaded: Boolean,
    onRetry: () -> Unit,
    onRelationClick: (BangumiRelation) -> Unit
) {
    when {
        error -> item {
            GeneralErrorWidget(
                title = "关联条目加载失败",
                errMsg = "请检查网络连接后重试。",
                onRetry = onRetry,
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        loaded && relations.isEmpty() -> item {
            GeneralEmptyState(
                icon = Icons.Filled.AccountTree,
                title = "暂无关联条目",
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        loading && !loaded -> item {
            Box(
                modifier = Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        else -> {
            items(relations, key = { it.bangumiItem.id }) { relation ->
                Box(modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
                    RelatedBangumiCard(
                        relation = relation,
                        onClick = { onRelationClick(relation) }
                    )
                }
            }
        }
    }
}

/**
 * 制作人员 Tab
 */
private fun LazyListScope.StaffTabContent(
    staffs: List<StaffFullItem>,
    loading: Boolean,
    error: Boolean,
    empty: Boolean,
    onRetry: () -> Unit
) {
    when {
        error -> item {
            GeneralErrorWidget(
                title = "制作人员加载失败",
                errMsg = "请检查网络连接后重试。",
                onRetry = onRetry,
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        empty -> item {
            GeneralEmptyState(
                icon = Icons.Filled.Groups,
                title = "暂无制作人员信息",
                modifier = Modifier.padding(vertical = 48.dp)
            )
        }
        loading && staffs.isEmpty() -> item {
            Column(modifier = Modifier.padding(16.dp)) {
                repeat(6) {
                    Row(modifier = Modifier.padding(vertical = 8.dp)) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier.size(36.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {}
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(14.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(12.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
            }
        }
        else -> {
            items(staffs, key = { it.staff.id }) { staff ->
                StaffCard(item = staff)
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

