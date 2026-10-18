package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.History
import com.kazumi.port.ui.components.EmptyState
import com.kazumi.port.ui.components.LoadingState
import com.kazumi.port.ui.navigation.Routes
import kotlinx.coroutines.launch

/**
 * 历史记录页（对应原版 settings/history_page.dart）
 * 展示观看历史，支持单条删除与清空
 */
@Composable
fun HistoryPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    var histories by remember { mutableStateOf<List<History>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    suspend fun refresh() {
        histories = app.historyRepository.getAll().sortedByDescending { it.lastWatchTime }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    MainScaffold(navController) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "历史记录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (histories.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            app.historyRepository.clear()
                            refresh()
                        }
                    }) {
                        Text("清空")
                    }
                }
            }
            HorizontalDivider()

            when {
                isLoading -> LoadingState(modifier = Modifier.padding(top = 32.dp))
                histories.isEmpty() -> EmptyState("还没有观看记录")
                else -> LazyColumn {
                    items(histories, key = { it.key }) { history ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate(
                                        Routes.detail(
                                            bangumiId = history.bangumiItem.id,
                                            pluginName = history.adapterName,
                                            src = history.lastSrc
                                        )
                                    )
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = history.bangumiItem.displayName(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "第 ${history.lastWatchEpisode} 集 · ${history.adapterName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    app.historyRepository.delete(history.key)
                                    refresh()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "删除这条记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 48.dp))
                    }
                }
            }
        }
    }
}