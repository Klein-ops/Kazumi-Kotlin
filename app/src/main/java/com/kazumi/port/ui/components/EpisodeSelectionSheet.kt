package com.kazumi.port.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kazumi.port.data.model.Road

/**
 * 选集切换面板（对齐原版 episode_selection_panel.dart）
 * 顶部剧名标题 + 线路横向 FilterChip + 剧集网格（5 列）
 * @param selectedEpisode 当前集（1-based）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSelectionSheet(
    title: String,
    roads: List<Road>,
    selectedRoad: Int,
    selectedEpisode: Int,
    onSelect: (episodeIndex: Int, roadIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var visibleRoad by remember {
        mutableIntStateOf(selectedRoad.coerceIn(0, (roads.size - 1).coerceAtLeast(0)))
    }
    val road = roads.getOrNull(visibleRoad)
    val count = road?.data?.size ?: 0

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            // 标题
            Text(
                text = title.ifBlank { "剧集列表" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            // 线路选择（横向滚动 chips）
            if (roads.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    roads.forEachIndexed { index, r ->
                        val name = r.name.trim().ifEmpty { "播放线路 ${index + 1}" }
                        FilterChip(
                            selected = index == visibleRoad,
                            onClick = { visibleRoad = index },
                            label = { Text(name) }
                        )
                    }
                }
            }
            // 计数
            Text(
                text = "全部剧集 · $count 集",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (count == 0) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("这条线路暂无剧集", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            // 剧集网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
            ) {
                items(count) { idx ->
                    val ep = idx + 1
                    val name = road.identifier.getOrNull(idx)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "第${ep}集"
                    val selected = visibleRoad == selectedRoad && ep == selectedEpisode
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .clickable { onSelect(idx, visibleRoad) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}