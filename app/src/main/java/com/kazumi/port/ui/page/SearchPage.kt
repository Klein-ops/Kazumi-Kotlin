package com.kazumi.port.ui.page

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.BangumiItem
import com.kazumi.port.data.model.SearchFilterState
import com.kazumi.port.data.model.SearchParser
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.data.repository.SearchHistory
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.ui.components.BangumiCard
import com.kazumi.port.ui.components.EmptyState
import com.kazumi.port.ui.components.LoadingState
import com.kazumi.port.ui.components.SearchFilterSheet
import com.kazumi.port.ui.navigation.Routes
import com.google.gson.JsonParser
import kotlinx.coroutines.launch

/**
 * 搜索页（对应原版 search_page.dart）
 * 搜索框 + 历史记录（管理/清空/单条删除）+ 以图搜番 + 条件筛选入口
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchPage(navController: NavHostController) {
    var keyword by rememberSaveable { mutableStateOf("") }
    // 搜索结果必须在导航到详情页后保留（rememberSaveable），否则从详情页返回时
    // SearchPage 重组后状态全丢 → 回到初始搜索框而非搜索结果（原版 Flutter 页面栈状态是保留的）
    var searchResultJson by rememberSaveable { mutableStateOf("") }
    val bangumiResults: List<BangumiItem> = remember(searchResultJson) {
        if (searchResultJson.isEmpty()) emptyList()
        else runCatching {
            val arr = JsonParser.parseString(searchResultJson).asJsonArray
            arr.mapNotNull { if (it.isJsonObject) BangumiItem.fromJson(it.asJsonObject) else null }
        }.getOrDefault(emptyList())
    }
    var isLoading by remember { mutableStateOf(false) }
    var searched by rememberSaveable { mutableStateOf(false) }
    var searchTrigger by remember { mutableStateOf(0) }
    var histories by remember { mutableStateOf<List<SearchHistory>>(emptyList()) }
    var managingHistory by remember { mutableStateOf(false) }
    var imageSearching by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var notShowWatched by remember { mutableStateOf(false) }
    var notShowAbandoned by remember { mutableStateOf(false) }

    val app = remember { KazumiApp.get() }
    val bangumiApi = remember { BangumiApi(BangumiClient(app.settings)) }
    val scope = rememberCoroutineScope()

    suspend fun refreshHistories() {
        histories = app.searchHistoryRepository.getRecent(limit = 10)
    }

    LaunchedEffect(Unit) {
        refreshHistories()
    }

    fun doSearch() {
        val k = keyword.trim()
        if (k.isEmpty()) return
        searchTrigger++
    }

    // 图片选择器（以图搜番）
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            imageSearching = true
            val file = app.cacheDir.resolve("trace_upload_${System.currentTimeMillis()}.jpg")
            scope.launch {
                try {
                    // 通过内容 Uri 读取到临时文件后调用 trace.moe
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (file.exists()) {
                        val result = com.kazumi.port.data.repository.TraceApi()
                            .searchAnimeByImageFile(file)
                        val title = result.result?.firstOrNull()?.anilist?.title?.chinese
                            ?: result.result?.firstOrNull()?.anilist?.title?.romaji
                        if (title != null) {
                            keyword = title
                            doSearch()
                        } else {
                            keyword = result.error ?: "未找到匹配结果"
                        }
                    }
                } catch (e: Exception) {
                    keyword = "图片搜索失败"
                } finally {
                    imageSearching = false
                    file.delete()
                }
            }
        }
    }

    // 接收以图搜番页返回的关键词（对应原版 pushNamed('/search/image') 等待 result）
    LaunchedEffect(Unit) {
        navController.currentBackStackEntry?.savedStateHandle?.getStateFlow<String?>("image_search_keyword", null)
            ?.collect { keywordResult ->
                if (keywordResult != null) {
                    keyword = keywordResult
                    doSearch()
                }
            }
    }

    // 搜索执行：由 searchTrigger 状态驱动
    LaunchedEffect(searchTrigger) {
        if (searchTrigger == 0) return@LaunchedEffect
        val query = keyword.trim()
        if (query.isEmpty()) return@LaunchedEffect
        isLoading = true
        searched = true
        // 解析查询串（支持 id:xx / tag:xx / sort:xx / season:xx / date:.. / rank:.. / score:.. / weekday:x）
        val filters = SearchParser(query).toFilterState()
        // 记录搜索历史（对齐原版：搜索成功后写入）
        scope.launch {
            app.searchHistoryRepository.record(query)
            refreshHistories()
        }
        val page = bangumiApi.bangumiSearch(
            keyword = filters.keyword,
            sort = filters.sort,
            tags = filters.tags,
            dateRange = filters.dateRange,
            rankRange = filters.rankRange,
            scoreRange = filters.scoreRange,
            weekdays = filters.weekdays
        )
        val items = page?.items ?: emptyList()
        // 存 JSON 数组串（rememberSaveable 只能存可打包类型），返回时反序列化恢复
        searchResultJson = runCatching {
            val arr = com.google.gson.JsonArray()
            items.forEach { arr.add(it.toJson()) }
            arr.toString()
        }.getOrDefault("")
        isLoading = false
    }

    // 返回键：搜索结果 / 历史管理态优先退回上一个状态，而不是直接退出搜索页
    BackHandler(enabled = searched || managingHistory) {
        if (managingHistory) {
            managingHistory = false
        } else if (searched) {
            searched = false
            searchResultJson = ""
        }
    }

    MainScaffold(navController) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            // 搜索框 + 以图搜番
            // M3 SearchBar（对齐原版 search_page.dart SearchBar：圆角、surfaceContainerHigh、minHeight 64）
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        placeholder = { Text("搜索番剧名称") },
                        leadingIcon = {
                            IconButton(onClick = { doSearch() }) {
                                Icon(Icons.Filled.Search, contentDescription = "搜索")
                            }
                        },
                        trailingIcon = {
                            Row {
                                if (keyword.isNotEmpty()) {
                                    IconButton(onClick = { keyword = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清空")
                                    }
                                }
                                IconButton(onClick = { navController.navigate(Routes.IMAGE_SEARCH) }) {
                                    Icon(Icons.Filled.ImageSearch, contentDescription = "以图搜番")
                                }
                            }
                        },
                        keyboardActions = KeyboardActions(
                            onSearch = { doSearch() },
                            onDone = { doSearch() }
                        ),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        )
                    )
                },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth()
            )

            // 按条件查找入口（对齐原版 search_page.dart 的"按条件查找"ListTile）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showFilterSheet = true }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "按条件查找",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "题材、放送时间与评分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()

            when {
                // 加载中
                isLoading -> LoadingState(modifier = Modifier.padding(top = 24.dp))
                // 已搜索且有结果
                searched && bangumiResults.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    items(bangumiResults, key = { it.id }) { item ->
                        BangumiCard(
                            item = item,
                            onClick = { navController.navigate(Routes.detail(item.id)) },
                            showRating = true
                        )
                    }
                }
                // 已搜索但无结果
                searched && bangumiResults.isEmpty() -> EmptyState("没有找到相关番剧")
                // 未搜索：展示历史
                else -> {
                    if (imageSearching) {
                        LoadingState(text = "图片识别中…")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (histories.isEmpty()) {
                                item { EmptyState("输入关键词开始搜索") }
                            } else {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 4.dp, end = 4.dp, top = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "最近搜索",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (managingHistory) {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    app.searchHistoryRepository.clear()
                                                    refreshHistories()
                                                    managingHistory = false
                                                }
                                            }) { Text("清空") }
                                        }
                                        TextButton(onClick = {
                                            managingHistory = !managingHistory
                                        }) { Text(if (managingHistory) "完成" else "管理") }
                                    }
                                }
                                // 用 timestamp（表主键，天然唯一）做 key：
                                // 历史版本里同一关键词可能存了多行，用 keyword 当 key 会重复
                                items(histories, key = { it.timestamp }) { history ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                keyword = history.keyword
                                                doSearch()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                        Text(
                                            text = history.keyword,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (managingHistory) {
                                            IconButton(onClick = {
                                                scope.launch {
                                                    app.searchHistoryRepository.delete(history.keyword)
                                                    refreshHistories()
                                                }
                                            }) {
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "删除这条记录",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(2.dp)
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(start = 40.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 筛选弹窗（对齐原版 _SearchFilterSheet 的 showAdaptiveBottomSheet）
    if (showFilterSheet) {
        SearchFilterSheet(
            initialState = SearchParser(keyword).toFilterState(),
            initialNotShowWatched = notShowWatched,
            initialNotShowAbandoned = notShowAbandoned,
            onDismiss = { showFilterSheet = false },
            onApply = { result ->
                showFilterSheet = false
                notShowWatched = result.notShowWatched
                notShowAbandoned = result.notShowAbandoned
                keyword = SearchParser.fromFilterState(result.filterState)
                searchTrigger++
            }
        )
    }
}