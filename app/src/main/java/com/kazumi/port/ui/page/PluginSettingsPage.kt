package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.Plugin
import com.kazumi.port.data.model.PluginUpdateAvailability
import com.kazumi.port.data.model.PluginUpdateResult
import com.kazumi.port.ui.components.ConfirmRuleDeletionDialog
import com.kazumi.port.ui.components.RuleAddDialog
import com.kazumi.port.ui.components.RuleAddSource
import com.kazumi.port.ui.components.RuleCard
import com.kazumi.port.ui.components.RuleEmptyState
import com.kazumi.port.ui.components.RuleImportDialog
import com.kazumi.port.ui.components.RulePageIntro
import com.kazumi.port.ui.components.RuleShareDialog
import com.kazumi.port.ui.components.RuleTag
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.components.rememberToast
import com.kazumi.port.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则管理页（对应原版 plugin_view_page.dart）
 * 对齐：RulePageIntro / 搜索 / FilterChip(全部·可更新) / 更新全部 / 多选删除 / 更多菜单(编辑·测试·检查更新·分享·上移·下移·删除) / 空态
 * 差异：拖动排序由菜单"上移/下移"替代；编辑/测试页入口保留待接入
 */
@Composable
fun PluginSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val repo = app.pluginRepository
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val focusManager = LocalFocusManager.current

    var plugins by remember { mutableStateOf<List<Plugin>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var updatesOnly by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var updatingNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var updating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var catalogFailed by remember { mutableStateOf(false) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var sharePlugin by remember { mutableStateOf<Plugin?>(null) }

    val clipboard = LocalClipboardManager.current

    fun refreshPlugins() { plugins = repo.allPlugins() }

    LaunchedEffect(Unit) {
        refreshPlugins()
        catalogFailed = try {
            withContext(Dispatchers.IO) { repo.ensurePluginCatalog() }
            false
        } catch (_: Exception) { true }
        refreshPlugins()
    }

    fun updateOne(plugin: Plugin) {
        if (updating || plugin.name in updatingNames) return
        updatingNames = updatingNames + plugin.name
        scope.launch(Dispatchers.IO) {
            try {
                repo.ensurePluginCatalog()
                when (repo.pluginUpdateStatus(plugin)) {
                    PluginUpdateAvailability.UPDATABLE -> when (repo.tryUpdatePluginByName(plugin.name)) {
                        PluginUpdateResult.UPDATED -> toast("更新成功")
                        PluginUpdateResult.REQUIRES_NEWER_CLIENT -> toast("规则需要更高版本客户端")
                        else -> toast("更新规则失败")
                    }
                    PluginUpdateAvailability.NOT_IN_CATALOG -> toast("规则仓库中没有当前规则")
                    else -> toast("规则已是最新")
                }
            } catch (_: Exception) { toast("检查规则更新失败") }
            updatingNames = updatingNames - plugin.name
            refreshPlugins()
        }
    }

    fun updateAll() {
        if (updating) return
        updating = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = repo.tryUpdateAllPlugin()
                val msg = if (result.hasNoCandidates) "没有可更新的规则" else {
                    val parts = mutableListOf("成功 ${result.updated} 条")
                    if (result.requiresNewerClient > 0) parts.add("不兼容 ${result.requiresNewerClient} 条")
                    if (result.notNewer > 0) parts.add("已跳过 ${result.notNewer} 条")
                    if (result.failed > 0) parts.add("失败 ${result.failed} 条")
                    "更新完成：${parts.joinToString("，")}"
                }
                toast(msg)
            } catch (_: Exception) { toast("更新规则失败") }
            updating = false
            catalogFailed = !repo.isCatalogFresh()
            refreshPlugins()
        }
    }

    fun deletePlugins(names: Set<String>) {
        if (deleting || names.isEmpty()) return
        deleting = true
        scope.launch(Dispatchers.IO) {
            try {
                repo.removePlugins(names)
                toast("已删除 ${names.size} 条规则")
                selectedNames = emptySet(); selecting = false
            } catch (_: Exception) { toast("删除规则失败，请重试") }
            deleting = false
            refreshPlugins()
        }
    }

    fun movePlugin(plugin: Plugin, direction: Int) {
        scope.launch(Dispatchers.IO) {
            try { repo.movePlugin(plugin.name, direction); refreshPlugins() }
            catch (_: Exception) { toast("保存规则顺序失败") }
        }
    }

    val queryLower = query.trim().lowercase()
    val updatesCount = plugins.count { repo.pluginUpdateStatus(it) == PluginUpdateAvailability.UPDATABLE }
    val visible = plugins.filter { p ->
        val qOk = queryLower.isEmpty() || p.name.lowercase().contains(queryLower) || p.baseUrl.lowercase().contains(queryLower)
        val uOk = !updatesOnly || repo.pluginUpdateStatus(p) == PluginUpdateAvailability.UPDATABLE
        qOk && uOk
    }
    val allSelected = visible.isNotEmpty() && visible.all { it.name in selectedNames }

    SettingsSubPageScaffold(
        title = if (selecting) "已选择 ${selectedNames.size} 条" else "规则管理",
        onBack = {
            if (selecting) { selecting = false; selectedNames = emptySet() }
            else navController.popBackStack()
        },
        actions = {
            if (selecting) {
                IconButton(
                    onClick = { if (selectedNames.isNotEmpty()) showDeleteDialog = true },
                    enabled = selectedNames.isNotEmpty() && !deleting
                ) { Icon(Icons.Filled.Delete, contentDescription = "删除所选规则") }
            } else {
                IconButton(onClick = { selecting = true; selectedNames = emptySet() }) {
                    Icon(Icons.Filled.Checklist, contentDescription = "批量选择")
                }
            }
        }
    ) {
        when {
            plugins.isEmpty() -> RuleEmptyState(Icons.Filled.Extension, "还没有安装规则")
            visible.isEmpty() -> RuleEmptyState(
                Icons.Filled.Search,
                if (updatesOnly && query.isEmpty()) "没有可更新的规则" else "没有符合条件的规则",
                { TextButton(onClick = { query = ""; updatesOnly = false }) { Text("显示全部规则") } }
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    RulePageIntro("我的规则", "管理你的番剧来源。拖动调整搜索顺序，点按规则进行编辑。", Icons.Filled.Extension) {
                        Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showAddDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("添加规则")
                            }
                            FilledTonalButton(onClick = { navController.navigate(Routes.SETTINGS_PLUGIN_SHOP) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("规则仓库")
                            }
                        }
                    }
                    Spacer(Modifier.size(20.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("搜索名称或站点") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "清除搜索") } } } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !updatesOnly, onClick = { updatesOnly = false }, label = { Text("全部 ${plugins.size}") })
                        FilterChip(selected = updatesOnly, onClick = { updatesOnly = true }, label = { Text("可更新 $updatesCount") })
                        TextButton(onClick = { updateAll() }, enabled = !updating && updatingNames.isEmpty()) {
                            if (updating) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                            else { Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                            Text(if (updating) "正在更新" else "更新全部")
                        }
                        if (selecting) {
                            TextButton(onClick = {
                                selectedNames = if (allSelected) selectedNames - visible.map { it.name }.toSet()
                                else selectedNames + visible.map { it.name }
                            }) { Text(if (allSelected) "取消全选" else "全选当前列表") }
                        }
                    }
                    if (catalogFailed) {
                        Text("暂时无法检查更新，已安装的规则仍可使用。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                items(visible, key = { it.name }) { plugin ->
                    val updatable = repo.pluginUpdateStatus(plugin) == PluginUpdateAvailability.UPDATABLE
                    val selected = plugin.name in selectedNames
                    RuleCard(
                        title = plugin.name,
                        subtitle = runCatching { java.net.URI(plugin.baseUrl).host }.getOrDefault(plugin.baseUrl),
                        selected = selected,
                        tags = listOfNotNull<@Composable () -> Unit>(
                            { RuleTag(plugin.version.ifEmpty { "v?" }, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant) },
                            if (updatable) { { RuleTag("可更新", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) } } else null
                        ),
                        onClick = {
                            if (selecting) {
                                selectedNames = if (selected) selectedNames - plugin.name else selectedNames + plugin.name
                            } else toast("规则编辑页待接入")
                        },
                        onLongPress = { selecting = true; selectedNames = selectedNames + plugin.name },
                        trailing = {
                            if (selecting) {
                                Checkbox(checked = selected, onCheckedChange = {
                                    selectedNames = if (selected) selectedNames - plugin.name else selectedNames + plugin.name
                                })
                            } else if (plugin.name in updatingNames) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                var menuOpen by remember(plugin.name) { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreHoriz, contentDescription = "更多操作") }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                                        containerColor = MaterialTheme.colorScheme.surface) {
                                        DropdownMenuItem(text = { Text("编辑规则") }, onClick = { menuOpen = false; navController.navigate(Routes.pluginEdit(plugin.name)) })
                                        DropdownMenuItem(text = { Text("测试规则") }, onClick = { menuOpen = false; navController.navigate(Routes.pluginTest(plugin.name)) })
                                        DropdownMenuItem(text = { Text("检查更新") }, enabled = !updating, onClick = { menuOpen = false; updateOne(plugin) })
                                        DropdownMenuItem(text = { Text("分享规则") }, onClick = { menuOpen = false; sharePlugin = plugin })
                                        HorizontalDivider()
                                        val index = plugins.indexOfFirst { it.name == plugin.name }
                                        DropdownMenuItem(text = { Text("上移") }, enabled = index > 0, onClick = { menuOpen = false; movePlugin(plugin, -1) })
                                        DropdownMenuItem(text = { Text("下移") }, enabled = index >= 0 && index < plugins.size - 1, onClick = { menuOpen = false; movePlugin(plugin, 1) })
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("删除规则") }, enabled = !deleting, onClick = { menuOpen = false; showDeleteDialog = true; selectedNames = setOf(plugin.name) })
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        RuleAddDialog { source ->
            showAddDialog = false
            when (source) {
                RuleAddSource.CATALOG -> navController.navigate(Routes.SETTINGS_PLUGIN_SHOP)
                RuleAddSource.CLIPBOARD -> {
                    val clip = clipboard.getText()?.text ?: ""
                    if (clip.isNotBlank()) showImportDialog = true else toast("剪贴板为空")
                }
                RuleAddSource.FILE -> toast("从文件导入暂不支持（请使用剪贴板导入）")
                RuleAddSource.CREATE -> toast("规则编辑器待接入")
                null -> {}
            }
        }
    }
    if (showImportDialog) {
        RuleImportDialog(
            initialValue = clipboard.getText()?.text ?: "",
            onDismiss = { showImportDialog = false },
            onImported = { refreshPlugins() }
        )
    }
    if (showDeleteDialog) {
        ConfirmRuleDeletionDialog(names = selectedNames) { confirmed ->
            showDeleteDialog = false
            if (confirmed) deletePlugins(selectedNames) else selectedNames = emptySet()
        }
    }
    sharePlugin?.let { RuleShareDialog(plugin = it, onDismiss = { sharePlugin = null }) }
}