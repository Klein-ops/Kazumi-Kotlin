package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.PluginCatalogItemStatus
import com.kazumi.port.data.model.PluginHTTPItem
import com.kazumi.port.data.model.PluginUpdateResult
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.ui.components.RuleCard
import com.kazumi.port.ui.components.RuleEmptyState
import com.kazumi.port.ui.components.RulePageIntro
import com.kazumi.port.ui.components.RuleTag
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.components.rememberToast
import com.kazumi.port.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则仓库页（对应原版 plugin_shop_page.dart + plugin_catalog_view.dart）
 * - 顶部 RulePageIntro 引导卡
 * - 搜索（规则名/作者）+ 排序（最近更新/名称）+ 刷新 + 全选筛选（全部/已安装/可更新）
 * - 每条：RuleCard + 安装/更新按钮；安装中显示进度
 * - 失败态：无法访问规则仓库（重试 + 切换镜像）
 */
@Composable
fun PluginShopPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val repo = app.pluginRepository
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val focusManager = LocalFocusManager.current

    var items by remember { mutableStateOf<List<PluginHTTPItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    // loading 首帧即为 true（直接显示加载态，避免闪一下"仓库暂无规则"）；
    // 重入保护单独用 inFlight，不能拿 loading 当锁——否则首次进入页面时
    // loading 已是 true，loadCatalog 首行守卫会把 LaunchedEffect 发起的
    // 首次加载直接 return 掉，loading 永远回不到 false，页面永久转圈。
    var loading by remember { mutableStateOf(true) }
    var inFlight by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortByName by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) } // 0=全部 1=已安装 2=可更新
    var showSortMenu by remember { mutableStateOf(false) }

    fun loadCatalog(forceRefresh: Boolean) {
        if (inFlight) return
        inFlight = true
        loading = true
        loadFailed = false
        scope.launch(Dispatchers.IO) {
            try {
                if (forceRefresh) repo.refreshPluginCatalog() else repo.ensurePluginCatalog()
                items = repo.catalogItems()
                loading = false
            } catch (_: Exception) {
                items = repo.catalogItems()
                loading = false
                loadFailed = true
            } finally {
                inFlight = false
            }
        }
    }

    LaunchedEffect(Unit) { loadCatalog(forceRefresh = false) }

    fun toggleGitProxyAndRefresh() {
        scope.launch(Dispatchers.IO) {
            try {
                val next = !repo.isGitProxyEnabled()
                repo.setGitProxyEnabled(next)
                // 持久化开关，避免重启后丢失（与设置页同一 key）
                app.settings.setBoolean(SettingsManager.Proxy.ENABLE_GIT_PROXY, next)
                withContext(Dispatchers.Main) { loadCatalog(forceRefresh = true) }
            } catch (_: Exception) {
                toast("切换规则镜像失败，请重试")
            }
        }
    }

    fun install(item: PluginHTTPItem, status: PluginCatalogItemStatus) {
        if (item.name in installing) return
        installing = installing + item.name
        FileLogger.i("RuleRepo", "install 点击: name=${item.name} status=$status")
        scope.launch(Dispatchers.IO) {
            try {
                val result = repo.tryUpdatePluginByName(item.name)
                FileLogger.i("RuleRepo", "install 结果: name=${item.name} result=$result")
                when (result) {
                    PluginUpdateResult.UPDATED -> toast(
                        if (status == PluginCatalogItemStatus.INSTALL) "导入成功" else "更新成功"
                    )
                    PluginUpdateResult.REQUIRES_NEWER_CLIENT -> toast("规则需要更高版本客户端")
                    PluginUpdateResult.NOT_NEWER -> toast("远程规则版本不高于本地，已跳过更新")
                    else -> toast(if (status == PluginCatalogItemStatus.INSTALL) "导入规则失败" else "更新规则失败")
                }
            } catch (e: Exception) {
                FileLogger.e("RuleRepo", "install 异常: ${e.javaClass.simpleName}: ${e.message} | name=${item.name}", e)
                toast("保存规则失败")
            }
            installing = installing - item.name
        }
    }

    // 过滤 + 排序
    val queryLower = query.trim().lowercase()
    val visible = items.filter { item ->
        val qOk = queryLower.isEmpty() ||
            item.name.lowercase().contains(queryLower) ||
            item.author.lowercase().contains(queryLower)
        val sOk = when (filter) {
            0 -> true
            1 -> repo.pluginStatus(item) != PluginCatalogItemStatus.INSTALL
            else -> repo.pluginStatus(item) == PluginCatalogItemStatus.UPDATE
        }
        qOk && sOk
    }.sortedWith { a, b ->
        if (sortByName) a.name.lowercase().compareTo(b.name.lowercase())
        else b.lastUpdate.compareTo(a.lastUpdate)
    }
    val installedCount = items.count { repo.pluginStatus(it) != PluginCatalogItemStatus.INSTALL }
    val updatesCount = items.count { repo.pluginStatus(it) == PluginCatalogItemStatus.UPDATE }

    SettingsSubPageScaffold(
        title = "规则仓库",
        onBack = { navController.popBackStack() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (loading && items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (items.isEmpty() && loadFailed) {
                RuleEmptyState(
                    Icons.Filled.CloudOff,
                    "无法访问规则仓库",
                    {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "请检查网络连接，或切换规则仓库镜像后重试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { loadCatalog(forceRefresh = true) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp)); Text("重新加载")
                                }
                                TextButton(onClick = { toggleGitProxyAndRefresh() }) {
                                    Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (repo.isGitProxyEnabled()) "关闭规则镜像" else "启用规则镜像")
                                }
                            }
                        }
                    }
                )
            } else if (visible.isEmpty()) {
                RuleEmptyState(
                    if (items.isEmpty()) Icons.Filled.Extension else Icons.Filled.Search,
                    if (items.isEmpty()) "仓库暂无规则" else "没有符合条件的规则"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        RulePageIntro(
                            "发现更多来源",
                            "浏览社区规则，为你的番剧搜索添加更多选择。",
                            Icons.Filled.TravelExplore
                        )
                        Spacer(Modifier.size(20.dp))
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索规则或作者") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = if (query.isNotEmpty()) {
                                { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "清除搜索") } }
                            } else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.size(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf(
                                Pair(0, "全部 ${items.size}"),
                                Pair(1, "已安装 $installedCount"),
                                Pair(2, "可更新 $updatesCount")
                            ).forEach { (idx, label) ->
                                FilterChip(
                                    selected = filter == idx,
                                    onClick = { filter = idx },
                                    label = { Text(label) },
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                TextButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (sortByName) "名称排序" else "最近更新")
                                }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false },
                                    containerColor = MaterialTheme.colorScheme.surface) {
                                    DropdownMenuItem(
                                        text = { Text("按更新时间排序") },
                                        onClick = { sortByName = false; showSortMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("按名称排序") },
                                        onClick = { sortByName = true; showSortMenu = false }
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { loadCatalog(forceRefresh = true) }, enabled = !loading) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新规则列表")
                            }
                        }
                        if (loading && items.isNotEmpty()) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        }
                        if (loadFailed && items.isNotEmpty()) {
                            Text(
                                "刷新失败，正在显示上次获取的规则。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    items(visible, key = { it.name }) { item ->
                        val status = repo.pluginStatus(item)
                        val busy = item.name in installing
                        val installed = status == PluginCatalogItemStatus.INSTALLED
                        RuleCard(
                            title = item.name,
                            subtitle = if (item.author.isEmpty()) null else "作者 · ${item.author}",
                            installed = installed,
                            tags = listOfNotNull<@Composable () -> Unit>(
                                {
                                    RuleTag(
                                        item.version.ifEmpty { "v?" },
                                        MaterialTheme.colorScheme.surfaceContainerHighest,
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                if (item.antiCrawlerEnabled) {
                                    {
                                        RuleTag(
                                            "含验证支持",
                                            MaterialTheme.colorScheme.tertiaryContainer,
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                } else null
                            ),
                            caption = if (item.lastUpdate > 0) {
                                "更新于 ${
                                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                        .format(Date(item.lastUpdate))
                                }"
                            } else null,
                            trailing = {
                                if (!busy && installed) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("已安装", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else {
                                    val isInstall = status == PluginCatalogItemStatus.INSTALL
                                    FilledTonalButton(
                                        onClick = { install(item, status) },
                                        enabled = !busy
                                    ) {
                                        if (busy) {
                                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Spacer(Modifier.width(6.dp)); Text(if (isInstall) "安装中" else "更新中")
                                        } else {
                                            Icon(if (isInstall) Icons.Filled.Add else Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp)); Text(if (isInstall) "安装" else "更新")
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}