package com.kazumi.port.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.rememberCoroutineScope
import com.kazumi.port.data.repository.CollectType
import com.kazumi.port.data.repository.SettingsManager
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.BangumiItem
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.ui.components.BangumiCard
import com.kazumi.port.ui.components.EmptyState
import com.kazumi.port.ui.components.LoadingState
import com.kazumi.port.ui.components.PageHeader
import com.kazumi.port.ui.navigation.Routes
import java.time.LocalDate
import java.time.temporal.ChronoField

private val weekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 时间表页（对应原版 timeline_page.dart）
 * 周选择器对齐 _TimelineWeekSelector：胶囊容器 + 选中高亮 + 今天/周X + 当日计数
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelinePage(navController: NavHostController) {
    val showRating = com.kazumi.port.ui.components.rememberShowRating()
    val scope = rememberCoroutineScope()
    var calendar by remember { mutableStateOf<List<List<BangumiItem>>>(List(7) { emptyList() }) }
    var selectedDay by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var showSeasonSheet by remember { mutableStateOf(false) }
    var selectedYear by remember { mutableIntStateOf(java.time.LocalDate.now().year) }
    var selectedQuarter by remember { mutableIntStateOf(((java.time.LocalDate.now().monthValue - 1) / 3) * 3 + 1) }
    var sortMode by remember { mutableIntStateOf(0) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    // 显示范围筛选（原版 timeline_options：隐藏看过的 / 隐藏抛弃的 / 只看正在追的）
    var notShowWatched by remember { mutableStateOf(false) }
    var notShowAbandoned by remember { mutableStateOf(false) }
    var onlyWatching by remember { mutableStateOf(false) }
    var watchedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var droppedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var watchingIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val app = remember { KazumiApp.get() }
    suspend fun reloadFilters() {
        notShowWatched = app.settings.getBoolean(
            com.kazumi.port.data.repository.SettingsManager.Collect.TIMELINE_NOT_SHOW_WATCHED, false
        )
        notShowAbandoned = app.settings.getBoolean(
            com.kazumi.port.data.repository.SettingsManager.Collect.TIMELINE_NOT_SHOW_ABANDONED, false
        )
        onlyWatching = app.settings.getBoolean(
            com.kazumi.port.data.repository.SettingsManager.Collect.TIMELINE_ONLY_SHOW_WATCHING, false
        )
        val all = app.collectRepository.getAll()
        watchedIds = all.filter { it.type == CollectType.COMPLETED }.map { it.bangumiItem.id }.toSet()
        droppedIds = all.filter { it.type == CollectType.DROPPED }.map { it.bangumiItem.id }.toSet()
        watchingIds = all.filter { it.type == CollectType.WATCHING }.map { it.bangumiItem.id }.toSet()
    }
    LaunchedEffect(Unit) { reloadFilters() }

    val bangumiApi = remember { BangumiApi(BangumiClient(KazumiApp.get().settings)) }

    // 屏幕宽度（响应式列数，对齐原版 _buildDay minCardWidth 逻辑）
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // 今天（周一=1..周日=7，容器下标 0..6）
    val todayIndex = runCatching {
        LocalDate.now().get(ChronoField.DAY_OF_WEEK) - 1
    }.getOrDefault(0)

    LaunchedEffect(Unit) {
        isLoading = true
        val result = bangumiApi.getCalendar()
        if (result.size == 7) {
            calendar = result
        } else {
            // 回退：用搜索构建季度日历
            val fallback = bangumiApi.getCalendarBySearch(
                dateRange = listOf("2026-04-01", "2026-07-01"),
                limit = 100,
                offset = 0
            )
            if (fallback.size == 7) calendar = fallback
        }
        selectedDay = todayIndex
        isLoading = false
    }

    MainScaffold(navController) {
        // 按显示范围筛选（对应原版 filterCalendar）
        fun visible(list: List<BangumiItem>): List<BangumiItem> = list.filter { item ->
            if (notShowWatched && watchedIds.contains(item.id)) return@filter false
            if (notShowAbandoned && droppedIds.contains(item.id)) return@filter false
            if (onlyWatching && !watchingIds.contains(item.id)) return@filter false
            true
        }
        val dayItems = sorted(visible(calendar[selectedDay]), sortMode)
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(title = "时间表")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { showSeasonSheet = true }) {
                    Text("${selectedYear}年${quarterLabel(selectedQuarter)}季")
                }
                IconButton(onClick = { showOptionsSheet = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "排序与筛选")
                }
            }

            // 星期选择器（对齐原版 _TimelineWeekSelector）
            val counts = calendar.map { visible(it).size }
            TimelineWeekSelector(
                counts = counts,
                selectedDay = selectedDay,
                todayIndex = todayIndex,
                isLoading = isLoading,
                onSelect = { selectedDay = it }
            )
            HorizontalDivider()

            when {
                isLoading -> LoadingState(modifier = Modifier.padding(top = 32.dp))
                dayItems.isEmpty() -> EmptyState("当天暂无放送")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(
                        when {
                            screenWidthDp >= 880 -> 3
                            screenWidthDp >= 600 -> 2
                            else -> 1
                        }
                    ),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(dayItems, key = { it.id }) { item ->
                        BangumiCard(
                            showRating = showRating,
                            item = item,
                            onClick = { navController.navigate(Routes.detail(item.id)) }
                        )
                    }
                }
            }
        }

        // 排序与筛选面板（对应原版 _TimelineOptionsButton + timeline_options）
        if (showOptionsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showOptionsSheet = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "排序与筛选",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    val sortLabels = listOf("热度优先", "评分优先", "默认顺序")
                    sortLabels.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sortMode = index }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (sortMode == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (sortMode == index) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "显示范围",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    listOf(
                        Triple("隐藏看过的番剧", notShowWatched, SettingsManager.Collect.TIMELINE_NOT_SHOW_WATCHED),
                        Triple("隐藏抛弃的番剧", notShowAbandoned, SettingsManager.Collect.TIMELINE_NOT_SHOW_ABANDONED),
                        Triple("只看正在追的番剧", onlyWatching, SettingsManager.Collect.TIMELINE_ONLY_SHOW_WATCHING)
                    ).forEach { (label, checked, key) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = checked,
                                onCheckedChange = { value ->
                                    when (key) {
                                        SettingsManager.Collect.TIMELINE_NOT_SHOW_WATCHED -> notShowWatched = value
                                        SettingsManager.Collect.TIMELINE_NOT_SHOW_ABANDONED -> notShowAbandoned = value
                                        else -> onlyWatching = value
                                    }
                                    scope.launch { app.settings.setBoolean(key, value) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 周选择器（对应原版 timeline_week_selector.dart 的 _TimelineWeekSelector）
 * surfaceContainerLow 圆角容器 + 选中项 primary 胶囊高亮 + 今天/周X + 计数
 */
@Composable
private fun TimelineWeekSelector(
    counts: List<Int>,
    selectedDay: Int,
    todayIndex: Int,
    isLoading: Boolean,
    onSelect: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(colors.surfaceContainerLow, RoundedCornerShape(28.dp))
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        weekdayNames.forEachIndexed { index, name ->
            val selected = index == selectedDay
            val label = if (index == todayIndex) "今天" else "周$name"
            Box(
                modifier = Modifier
                    .clickable { onSelect(index) }
                    .background(
                        color = if (selected) colors.primary
                        else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.W700,
                        color = if (selected) colors.onPrimary else colors.onSurfaceVariant
                    )
                    Text(
                        text = if (isLoading) "—" else counts.getOrElse(index) { 0 }.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.W500,
                        color = if (selected) colors.onPrimary.copy(alpha = 0.9f)
                        else colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}
/** 季度中文标签 */
private fun quarterLabel(q: Int): String = when (q) {
    1 -> "1"; 4 -> "4"; 7 -> "7"; 10 -> "10"; else -> "1"
}

/** 排序中文标签（原版 TimelineSort） */
private fun sortLabel(mode: Int): String = when (mode) {
    1 -> "评分优先"; 2 -> "默认顺序"; else -> "热度优先"
}

/** 按排序模式对某天剧集排序 */
private fun sorted(items: List<BangumiItem>, mode: Int): List<BangumiItem> = when (mode) {
    1 -> items.sortedByDescending { it.ratingScore }
    2 -> items
    else -> items.sortedByDescending { it.votes }
}
