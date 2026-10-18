package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
    val app = remember { KazumiApp.get() }
    var selectedType by remember { mutableIntStateOf(0) }
    var allItems by remember { mutableStateOf<List<CollectedBangumi>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        allItems = app.collectRepository.getAll()
        isLoading = false
    }

    val filtered = if (selectedType == 0) allItems else allItems.filter { it.type == selectedType }

    MainScaffold(navController) {
        Column(modifier = Modifier.fillMaxSize()) {
            PageHeader(title = "收藏")

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                collectTabs.forEach { (label, type) ->
                    val selected = type == selectedType
                    Text(
                        text = label,
                        style = if (selected) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .clickable { selectedType = type }
                    )
                }
            }
            HorizontalDivider()

            when {
                isLoading -> LoadingState(modifier = Modifier.padding(top = 32.dp))
                filtered.isEmpty() -> EmptyState("还没有收藏")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.bangumiItem.id }) { collected ->
                        BangumiCard(
                            item = collected.bangumiItem,
                            onClick = {
                                navController.navigate(Routes.detail(collected.bangumiItem.id))
                            }
                        )
                    }
                }
            }
        }
    }
}