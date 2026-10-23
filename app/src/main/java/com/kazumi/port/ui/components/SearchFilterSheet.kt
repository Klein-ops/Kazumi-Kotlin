package com.kazumi.port.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kazumi.port.data.model.SearchDateRange
import com.kazumi.port.data.model.SearchDoubleRange
import com.kazumi.port.data.model.SearchFilterState
import com.kazumi.port.data.model.SearchIntRange
import java.time.LocalDate

/**
 * 筛选结果（对应原版 _SearchFilterResult）
 */
data class SearchFilterResult(
    val filterState: SearchFilterState,
    val notShowWatched: Boolean,
    val notShowAbandoned: Boolean
)

/** 常用题材标签（对应原版 defaultAnimeTags） */
val defaultAnimeTags = listOf(
    "日常", "原创", "校园", "搞笑", "奇幻", "百合", "恋爱",
    "悬疑", "热血", "后宫", "机战", "轻改", "偶像", "治愈", "异世界"
)

/** 排序方式（对应原版 _searchSortLabels） */
val searchSortLabels = linkedMapOf(
    "heat" to "热度",
    "rank" to "排名",
    "score" to "评分",
    "match" to "匹配"
)

/** 季度选项（对应原版 _seasonOptions，近 20 年） */
private fun seasonOptions(draftSeason: String): LinkedHashMap<String, String> {
    val now = LocalDate.now()
    val values = LinkedHashMap<String, String>()
    for (year in now.year downTo now.year - 19) {
        for (quarter in 4 downTo 1) {
            val month = (quarter - 1) * 3 + 1
            val date = LocalDate.of(year, month, 1)
            if (now.isAfter(date)) {
                val label = "$year ${listOf("冬季", "春季", "夏季", "秋季")[quarter - 1]}"
                values["${year}Q$quarter"] = label
            }
        }
    }
    if (draftSeason.isNotEmpty() && !values.containsKey(draftSeason)) {
        values[draftSeason] = draftSeason
    }
    return values
}

private fun safeRangeValues(start: Float, end: Float, min: Float, max: Float): ClosedFloatingPointRange<Float> {
    val safeStart = start.coerceIn(min, max)
    val safeEnd = end.coerceIn(min, max)
    return if (safeStart <= safeEnd) safeStart..safeEnd else safeEnd..safeStart
}

/**
 * 搜索筛选底部弹窗（对应原版 search_filter_sheet.dart 的 _SearchFilterSheet）
 * 排序 / 题材标签 / 放送时间（季度+日期+周几）/ 评分与排名 / 收藏状态
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    initialState: SearchFilterState,
    initialNotShowWatched: Boolean,
    initialNotShowAbandoned: Boolean,
    onDismiss: () -> Unit,
    onApply: (SearchFilterResult) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var draft by remember { mutableStateOf(initialState) }
    var notShowWatched by remember { mutableStateOf(initialNotShowWatched) }
    var notShowAbandoned by remember { mutableStateOf(initialNotShowAbandoned) }
    var customTag by remember { mutableStateOf("") }

    val seasons = remember(draft.season) { seasonOptions(draft.season) }

    fun addTag(value: String) {
        val tag = value.trim()
        if (tag.isEmpty() || draft.tags.contains(tag)) return
        draft = draft.copy(tags = draft.tags + tag)
        customTag = ""
    }

    fun reset() {
        draft = SearchFilterState(id = draft.id, keyword = draft.keyword)
        notShowWatched = false
        notShowAbandoned = false
        customTag = ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "筛选番剧",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (draft.isIdSearch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前按编号定位番剧", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "清除编号后，可以组合其他条件查找。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { draft = draft.copy(id = "") }) { Text("清除编号") }
                }
            }

            // 排序方式
            SortDropdown(
                value = draft.sort,
                onChanged = { draft = draft.copy(sort = it) }
            )
            Spacer(Modifier.height(16.dp))

            // 题材标签
            FilterGroup(
                title = "题材标签",
                summary = if (draft.tags.isEmpty()) "不限题材" else draft.tags.joinToString("、"),
                initiallyExpanded = draft.tags.isNotEmpty()
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (defaultAnimeTags + draft.tags).distinct().forEach { tag ->
                        FilterChip(
                            label = { Text(tag) },
                            selected = draft.tags.contains(tag),
                            onClick = {
                                draft = draft.copy(
                                    tags = if (draft.tags.contains(tag)) draft.tags - tag else draft.tags + tag
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = customTag,
                    onValueChange = { customTag = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义标签") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { addTag(customTag) }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加标签")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTag(customTag) })
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 放送时间
            val dateSummary = when {
                draft.season.isNotEmpty() -> draft.season
                draft.dateRange == null -> "不限日期"
                else -> "${draft.dateRange!!.start} 至 ${draft.dateRange!!.end}"
            }
            val weekSummary = if (draft.weekdays.isNotEmpty()) " · 已选 ${draft.weekdays.size} 天" else ""
            FilterGroup(
                title = "放送时间",
                summary = "$dateSummary$weekSummary",
                initiallyExpanded = draft.season.isNotEmpty() || draft.dateRange != null || draft.weekdays.isNotEmpty()
            ) {
                SeasonDropdown(
                    season = draft.season,
                    options = seasons,
                    onChanged = { draft = draft.copy(season = it, dateRange = null) }
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    (1..7).forEach { day ->
                        FilterChip(
                            label = { Text("周" + "一二三四五六日"[day - 1]) },
                            selected = draft.weekdays.contains(day),
                            onClick = {
                                val days = draft.weekdays.toMutableSet()
                                if (day in days) days.remove(day) else days.add(day)
                                draft = draft.copy(weekdays = days.toList().sorted())
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    DashDateField(
                        label = "起",
                        value = draft.dateRange?.start ?: "",
                        onValue = {
                            draft = draft.copy(
                                dateRange = SearchDateRange(it, draft.dateRange?.end ?: ""),
                                season = ""
                            )
                        }
                    )
                    Text("至", modifier = Modifier.padding(horizontal = 6.dp))
                    DashDateField(
                        label = "止",
                        value = draft.dateRange?.end ?: "",
                        onValue = {
                            draft = draft.copy(
                                dateRange = SearchDateRange(draft.dateRange?.start ?: "", it),
                                season = ""
                            )
                        }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 评分与排名
            val scoreSummary = buildList {
                if (draft.scoreRange?.isValid == true) add("评分 ${draft.scoreRange!!.toToken()}")
                if (draft.rankRange?.isValid == true) add("排名 ${draft.rankRange!!.toToken()}")
                if (draft.scoreRange == null && draft.rankRange == null) add("不限")
            }.joinToString(" · ")
            FilterGroup(
                title = "评分与排名",
                summary = scoreSummary,
                initiallyExpanded = draft.scoreRange != null || draft.rankRange != null
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("限定评分", modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.scoreRange?.isValid == true,
                        onCheckedChange = {
                            draft = draft.copy(
                                scoreRange = if (it) SearchDoubleRange(min = 7.0, max = 10.0) else null
                            )
                        }
                    )
                }
                if (draft.scoreRange?.isValid == true) {
                    val r = draft.scoreRange!!
                    val values = safeRangeValues((r.min ?: 0.0).toFloat(), (r.max ?: 10.0).toFloat(), 0f, 10f)
                    RangeSlider(
                        value = values,
                        onValueChange = {
                            draft = draft.copy(
                                scoreRange = SearchDoubleRange(
                                    min = it.start.toDouble(),
                                    max = it.endInclusive.toDouble()
                                )
                            )
                        },
                        valueRange = 0f..10f,
                        steps = 19
                    )
                    Text(
                        "评分 ${draft.scoreRange!!.toToken()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("限定排名", modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.rankRange?.isValid == true,
                        onCheckedChange = {
                            draft = draft.copy(
                                rankRange = if (it) SearchIntRange(min = 1, max = 5000) else null
                            )
                        }
                    )
                }
                if (draft.rankRange?.isValid == true) {
                    val r = draft.rankRange!!
                    val values = safeRangeValues((r.min ?: 1).toFloat(), (r.max ?: 10000).toFloat(), 1f, 10000f)
                    RangeSlider(
                        value = values,
                        onValueChange = {
                            draft = draft.copy(
                                rankRange = SearchIntRange(
                                    min = it.start.toInt(),
                                    max = it.endInclusive.toInt()
                                )
                            )
                        },
                        valueRange = 1f..10000f,
                        steps = 99
                    )
                    Text(
                        "排名 ${draft.rankRange!!.toToken()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 收藏状态
            val collectSummary = buildList {
                if (notShowWatched) add("隐藏已看")
                if (notShowAbandoned) add("隐藏已弃")
                if (!notShowWatched && !notShowAbandoned) add("显示全部")
            }.joinToString(" · ")
            FilterGroup(
                title = "收藏状态",
                summary = collectSummary,
                initiallyExpanded = notShowWatched || notShowAbandoned
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("隐藏看过的番剧", modifier = Modifier.weight(1f))
                    Switch(checked = notShowWatched, onCheckedChange = { notShowWatched = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("隐藏抛弃的番剧", modifier = Modifier.weight(1f))
                    Switch(checked = notShowAbandoned, onCheckedChange = { notShowAbandoned = it })
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { reset() }) { Text("重置") }
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = {
                        onApply(
                            SearchFilterResult(
                                filterState = draft,
                                notShowWatched = notShowWatched,
                                notShowAbandoned = notShowAbandoned
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("查看结果")
                }
            }
        }
    }
}

/** 排序下拉（对应原版 DropdownButtonFormField 排序方式） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(value: String, onChanged: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = "排序方式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "按${searchSortLabels[value] ?: "热度"}排序",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start
            )
            Text("▾", style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface) {
            searchSortLabels.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text("按${label}排序") },
                    onClick = {
                        onChanged(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 季度下拉（对应原版 DropdownButtonFormField 放送季度） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonDropdown(
    season: String,
    options: LinkedHashMap<String, String>,
    onChanged: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(
            text = "放送季度",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = options[season] ?: "不限季度",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start
            )
            Text("▾", style = MaterialTheme.typography.bodyMedium)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.colorScheme.surface) {
            DropdownMenuItem(text = { Text("不限季度") }, onClick = {
                onChanged("")
                expanded = false
            })
            options.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onChanged(key)
                    expanded = false
                })
            }
        }
    }
}

/** 折叠分组（对应原版 _SearchFilterGroup ExpansionTile） */
@Composable
private fun FilterGroup(
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开")
            }
        }
        if (expanded) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 日期输入框（对齐原版自定义日期：手工输入 YYYY-MM-DD） */
@Composable
private fun RowScope.DashDateField(
    label: String,
    value: String,
    onValue: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new ->
            // 仅允许数字与短横线，长度限制 10（YYYY-MM-DD）
            val filtered = new.filter { it.isDigit() || it == '-' }.take(10)
            onValue(filtered)
        },
        modifier = Modifier.weight(1f),
        label = { Text(label) },
        placeholder = { Text("2026-01-01") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall
    )
}