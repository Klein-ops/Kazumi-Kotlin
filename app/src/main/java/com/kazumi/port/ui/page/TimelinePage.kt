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
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
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
@Composable
fun TimelinePage(navController: NavHostController) {
    var calendar by remember { mutableStateOf<List<List<BangumiItem>>>(List(7) { emptyList() }) }
    var selectedDay by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var showSeasonSheet by remember { mutableStateOf(false) }
    var selectedYear by remember { mutableIntStateOf(java.time.LocalDate.now().year) }
    var selectedQuarter by remember { mutableIntStateOf(((java.time.LocalDate.now().monthValue - 1) / 3) * 3 + 1) }
    var sortMode by remember { mutableIntStateOf(0) }
    var showOptionsSheet by remember { mutableStateOf(false) }

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
            val counts = calendar.map { it.size }
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
                calendar[selectedDay].isEmpty() -> EmptyState("当天暂无放送")
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
                    items(sorted(calendar[selectedDay], sortMode), key = { it.id }) { item ->
                        BangumiCard(
                            item = item,
                            onClick = { navController.navigate(Routes.detail(item.id)) }
                        )
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
