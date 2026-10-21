package com.kazumi.port.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import com.kazumi.port.ui.components.RelatedBangumiCard
import com.kazumi.port.ui.components.StaffCard
import com.kazumi.port.ui.components.rememberToast
import com.kazumi.port.ui.navigation.Routes
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
                coroutineScope {
                    // 信号量限并发：全部插件同时打搜索+线路=2N 个请求并发爆发，
                    // 打爆 OkHttp 连接池+带宽，进入详情页瞬间全局卡顿。
                    // 限 3 路并发：消除风暴，且 awaitAll 不会被最慢源拖住 UI。
                    val roadSemaphore = Semaphore(3)
                    val deferred = app.pluginRepository.allPlugins().map { plugin ->
                        async {
                            roadSemaphore.withPermit {
                                try {
                                    withTimeout(6_000) {
                                        val search = app.pluginRepository.searchBangumi(plugin.name, name)
                                        val hit = search.data.firstOrNull() ?: return@withTimeout null
                                        if (hit.src.isEmpty()) return@withTimeout null
                                        val roads = app.pluginRepository.queryChapterRoads(plugin.name, hit.src)
                                        if (roads.isEmpty()) null
                                        else Triple(plugin.name, hit.src, roads)
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }
                    val loadedRoads = deferred.awaitAll().filterNotNull()
                    withContext(Dispatchers.Main) {
                        savePluginRoads(loadedRoads)
                        roadsLoading = false
                        roadsLoaded = true
                        if (pluginRoads.isNotEmpty() && selectedPlugin < 0) selectedPlugin = 0
                    }
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

    fun playEpisode(episodeIndex: Int) {
        val item = bangumi ?: return
        val plugin = pluginRoads.getOrNull(selectedPlugin) ?: return
        val road = plugin.third.getOrNull(selectedRoad) ?: return
        val url = road.data.getOrNull(episodeIndex) ?: return
        navController.navigate(
            Routes.player(
                bangumiId = item.id,
                pluginName = plugin.first,
                src = url,
                episode = episodeIndex + 1,
                road = selectedRoad,
                isLocal = false
            )
        )
    }

    fun downloadEpisode(episodeIndex: Int) {
        val item = bangumi ?: return
        val plugin = pluginRoads.getOrNull(selectedPlugin) ?: return
        val road = plugin.third.getOrNull(selectedRoad) ?: return
        val url = road.data.getOrNull(episodeIndex) ?: return
        scope.launch {
            app.downloadController.startDownload(
                bangumiId = item.id,
                bangumiName = item.displayName(),
                bangumiCover = item.coverUrl(),
                pluginName = plugin.first,
                episodeNumber = episodeIndex + 1,
                episodeName = "第${episodeIndex + 1}集",
                road = selectedRoad,
                episodePageUrl = url
            )
        }
    }

    Scaffold(
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
                        OutlinedButton(onClick = { toggleCollect() }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = if (isCollected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                if (isCollected) "已收藏" else "收藏",
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (bangumi != null && pluginRoads.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        val ep = episodesOf(pluginRoads, selectedPlugin, selectedRoad)
                        if (ep != null) playEpisode(ep)
                    }
                ) {
                    Text("开始观看")
                }
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 头部：封面 + 信息 + 线路/集数
                        DetailHeader(item)
                        SourceSelector(
                            pluginRoads = pluginRoads,
                            roadsLoading = roadsLoading,
                            selectedPlugin = selectedPlugin,
                            selectedRoad = selectedRoad,
                            onSelectPlugin = { selectedPlugin = it },
                            onSelectRoad = { selectedRoad = it }
                        )

                        TabRow(selectedTabIndex = tabIndex) {
                            INFO_TABS.forEachIndexed { index, title ->
                                Tab(
                                    selected = tabIndex == index,
                                    onClick = { tabIndex = index },
                                    text = { Text(title) }
                                )
                            }
                        }

                        when (tabIndex) {
                            0 -> OverviewTab(
                                item = item,
                                pluginRoads = pluginRoads,
                                roadsLoading = roadsLoading,
                                selectedPlugin = selectedPlugin,
                                selectedRoad = selectedRoad,
                                onPlayEpisode = { playEpisode(it) },
                                onDownloadEpisode = { downloadEpisode(it) },
                                onOpenReview = {
                                    if (collectType == 0) {
                                        showToast("请先追番")
                                    } else {
                                        showReviewSheet = true
                                    }
                                }
                            )
                            1 -> CommentsTab(
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
                            2 -> CharactersTab(
                                characters = characters,
                                loading = charactersLoading,
                                error = charactersError,
                                empty = charactersEmpty,
                                onRetry = { loadCharacters() },
                                onCharacterClick = { character ->
                                    selectedCharacter = character
                                }
                            )
                            3 -> RelationsTab(
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
                            4 -> StaffTab(
                                staffs = staffs,
                                loading = staffLoading,
                                error = staffError,
                                empty = staffEmpty,
                                onRetry = { loadStaff() }
                            )
                        }
                    }
                }
            }
        }
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
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        AsyncImage(
            model = item.coverUrl(),
            contentDescription = item.displayName(),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(100.dp)
                .height(140.dp)
                .clip(MaterialTheme.shapes.medium)
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = item.displayName(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (item.ratingScore > 0) {
                Text(
                    text = "评分 ${String.format("%.1f", item.ratingScore)} · ${item.votes} 人评分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (item.airDate.isNotEmpty()) {
                Text(
                    text = "首播 ${item.airDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (item.summary.isNotEmpty()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/**
 * 线路/集数选择区
 */
@Composable
private fun SourceSelector(
    pluginRoads: List<Triple<String, String, List<Road>>>,
    roadsLoading: Boolean,
    selectedPlugin: Int,
    selectedRoad: Int,
    onSelectPlugin: (Int) -> Unit,
    onSelectRoad: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            roadsLoading -> {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(
                        "正在加载线路…",
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            pluginRoads.isEmpty() -> {
                Text(
                    "没有可用线路",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                // 插件线路选择
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pluginRoads.forEachIndexed { index, entry ->
                        val selected = index == selectedPlugin
                        Text(
                            text = entry.first,
                            style = if (selected) MaterialTheme.typography.titleSmall
                            else MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .clickable { onSelectPlugin(index) }
                        )
                    }
                }

                // 线路（分线）选择
                val roads = pluginRoads.getOrNull(selectedPlugin)?.third
                if (roads != null && roads.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        roads.forEachIndexed { index, road ->
                            val selected = index == selectedRoad
                            Text(
                                text = road.name,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .clickable { onSelectRoad(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 概览 Tab：简介 + 标签 + 集数网格
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(
    item: BangumiItem,
    pluginRoads: List<Triple<String, String, List<Road>>>,
    roadsLoading: Boolean,
    selectedPlugin: Int,
    selectedRoad: Int,
    onPlayEpisode: (Int) -> Unit,
    onDownloadEpisode: (Int) -> Unit,
    onOpenReview: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        // 简介
        item {
            Text(
                text = "简介",
                style = MaterialTheme.typography.titleMedium
            )
        }
        item {
            Text(
                text = item.summary.ifEmpty { "暂无简介" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )
        }
        // 标签
        if (item.tags.isNotEmpty()) {
            item {
                Text(
                    text = "标签",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item.tags.take(13).forEach { tag ->
                        OutlinedButton(
                            onClick = {},
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("${tag.name} ${tag.count}")
                        }
                    }
                }
            }
        }
        // 集数
        val roads = pluginRoads.getOrNull(selectedPlugin)?.third
        val episodes = roads?.getOrNull(selectedRoad)?.data
        if (!roadsLoading && episodes != null) {
            item {
                Text(
                    "选集",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    episodes.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .size(width = 52.dp, height = 52.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { onPlayEpisode(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 吐槽 Tab：评论区（自己评论置顶 + 滑动到底加载更多）
 */
@Composable
private fun CommentsTab(
    comments: List<CommentItem>,
    loading: Boolean,
    loaded: Boolean,
    error: Boolean,
    hasReview: Boolean,
    onReviewTap: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
}

/**
 * 角色 Tab
 */
@Composable
private fun CharactersTab(
    characters: List<CharacterItem>,
    loading: Boolean,
    error: Boolean,
    empty: Boolean,
    onRetry: () -> Unit,
    onCharacterClick: (CharacterItem) -> Unit
) {
    when {
        error -> GeneralErrorWidget(
            title = "角色列表加载失败",
            errMsg = "请检查网络连接后重试。",
            onRetry = onRetry,
            modifier = Modifier.padding(vertical = 48.dp)
        )
        empty -> GeneralEmptyState(
            icon = Icons.Filled.PeopleAlt,
            title = "暂无角色信息",
            modifier = Modifier.padding(vertical = 48.dp)
        )
        loading && characters.isEmpty() -> {
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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
}

/**
 * 关联 Tab
 */
@Composable
private fun RelationsTab(
    relations: List<BangumiRelation>,
    loading: Boolean,
    error: Boolean,
    loaded: Boolean,
    onRetry: () -> Unit,
    onRelationClick: (BangumiRelation) -> Unit
) {
    when {
        error -> GeneralErrorWidget(
            title = "关联条目加载失败",
            errMsg = "请检查网络连接后重试。",
            onRetry = onRetry,
            modifier = Modifier.padding(vertical = 48.dp)
        )
        loaded && relations.isEmpty() -> GeneralEmptyState(
            icon = Icons.Filled.AccountTree,
            title = "暂无关联条目",
            modifier = Modifier.padding(vertical = 48.dp)
        )
        loading && !loaded -> Box(
            modifier = Modifier.fillMaxWidth().padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(relations, key = { it.bangumiItem.id }) { relation ->
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
@Composable
private fun StaffTab(
    staffs: List<StaffFullItem>,
    loading: Boolean,
    error: Boolean,
    empty: Boolean,
    onRetry: () -> Unit
) {
    when {
        error -> GeneralErrorWidget(
            title = "制作人员加载失败",
            errMsg = "请检查网络连接后重试。",
            onRetry = onRetry,
            modifier = Modifier.padding(vertical = 48.dp)
        )
        empty -> GeneralEmptyState(
            icon = Icons.Filled.Groups,
            title = "暂无制作人员信息",
            modifier = Modifier.padding(vertical = 48.dp)
        )
        loading && staffs.isEmpty() -> {
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(staffs, key = { it.staff.id }) { staff ->
                    StaffCard(item = staff)
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

private fun episodesOf(
    pluginRoads: List<Triple<String, String, List<Road>>>,
    pluginIndex: Int,
    roadIndex: Int
): Int? {
    val plugin = pluginRoads.getOrNull(pluginIndex) ?: return null
    val road = plugin.third.getOrNull(roadIndex) ?: return null
    return road.data.indices.firstOrNull()
}