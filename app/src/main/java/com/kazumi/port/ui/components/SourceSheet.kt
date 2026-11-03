package com.kazumi.port.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.PluginSearchStatus
import com.kazumi.port.data.model.SearchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单个插件的检索状态 */
private class SourceGroup(
    val name: String
) {
    /** 该组当前检索词（原版 per-group keyword；null=跟随全局 query） */
    var keyword by mutableStateOf<String?>(null)
    /**
     * 检索状态：必须用 Compose state（mutableStateOf），不能是普通 var！
     * 否则协程里修改 status 不触发重组 → 界面一直显示"正在检索…"转圈，
     * 尽管结果已进 results 列表（mutableStateListOf 会触发重组，但 when 第一个
     * 匹配的 PENDING 分支永远优先），滑动一下重新组合后才显示正常。
     */
    var status by mutableStateOf(PluginSearchStatus.PENDING)
    /** 结果列表（Compose state list，添加即触发重组） */
    val results = mutableStateListOf<SearchItem>()
    /** 失败原因（如验证码挑战），null 时用默认文案 */
    var message by mutableStateOf<String?>(null)

    /** 是否有可用结果（决定排序时是否靠前） */
    val hasResults: Boolean get() = status == PluginSearchStatus.SUCCESS && results.isNotEmpty()

    /** 当前生效检索词（跟随全局 or 组内覆盖） */
    fun effectiveKeyword(defaultKeyword: String): String = keyword ?: defaultKeyword
}

/** 分组显示三态（对应原版 _SourceDisplay） */
private enum class SourceDisplay { COLLAPSED, PREVIEW, EXPANDED }

/** 检索动作（对应原版 _querySource / _retry） */
private enum class SearchAction { QUERY, RETRY }

/**
 * 播放来源弹窗（对齐原版 source_sheet.dart / source_sheet_view.dart）
 * 顶部标题「播放来源」+ 检索进度；下方按插件分组展示搜索结果；
 * 点击某条结果 → 查询播放列表 → 进入播放页。
 * - 分组支持三态折叠：收起（仅组头）/ 预览（1 条 + 展开全部）/ 展开全部
 * - 组头点击切换展开收起；箭头旋转 + 内容高度动画
 * - 三点菜单：修改检索词 / 使用别名 / 重新检索 / 在浏览器中打开
 * - 有结果的源排在前面，无结果的排后面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSheet(
    keyword: String,
    onPlay: (pluginName: String, src: String, title: String) -> Unit,
    onDismiss: () -> Unit,
    /** 番剧别名（原版「使用别名」用它换检索词） */
    aliases: List<String> = emptyList()
) {
    val app = remember { KazumiApp.get() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugins = remember { app.pluginRepository.allPlugins() }
    // 保持插件配置顺序
    val groups = remember { plugins.map { SourceGroup(it.name) } }
    val total = groups.size
    var pendingCount by remember { mutableStateOf(total) }
    var resultCount by remember { mutableStateOf(0) }
    // 全局检索词（原版「使用别名」会把检索词换成番剧别名后重新检索）
    var query by remember { mutableStateOf(keyword) }
    // 折叠状态：默认全部收起；首个出现结果的组自动展开为 PREVIEW（对齐原版 _autoExpandFirstResult）
    val displayStates = remember { mutableStateListOf<Pair<String, SourceDisplay>>() }
    var autoExpansionHandled by remember { mutableStateOf(false) }
    // 菜单/弹窗状态
    var menuGroup by remember { mutableStateOf<String?>(null) }
    var showAliasDialog by remember { mutableStateOf(false) }
    var aliasTarget by remember { mutableStateOf<String?>(null) }
    var showKeywordDialog by remember { mutableStateOf(false) }
    var keywordTarget by remember { mutableStateOf<String?>(null) }
    var keywordInput by remember { mutableStateOf("") }

    fun displayOf(name: String): SourceDisplay =
        displayStates.firstOrNull { it.first == name }?.second ?: SourceDisplay.COLLAPSED

    fun setDisplay(name: String, display: SourceDisplay) {
        autoExpansionHandled = true
        val idx = displayStates.indexOfFirst { it.first == name }
        if (idx >= 0) displayStates[idx] = name to display else displayStates.add(name to display)
    }

    fun toggle(name: String) {
        setDisplay(name, if (displayOf(name) == SourceDisplay.COLLAPSED) SourceDisplay.PREVIEW else SourceDisplay.COLLAPSED)
    }

    fun updateCount() {
        pendingCount = groups.count { it.status == PluginSearchStatus.PENDING }
        resultCount = groups.sumOf { it.results.size }
    }

    /** 单个组检索（原版 querySource） */
    fun searchGroup(group: SourceGroup, action: SearchAction) {
        val activeKeyword = group.effectiveKeyword(query)
        scope.launch {
            group.status = PluginSearchStatus.PENDING
            group.results.clear()
            updateCount()
            try {
                // RETRY 忽略缓存强制重新检索（原版 _retry）
                val resp = app.pluginRepository.searchBangumi(
                    pluginName = group.name,
                    keyword = activeKeyword,
                    forceRefresh = action == SearchAction.RETRY
                )
                if (resp.data.isEmpty()) {
                    group.status = PluginSearchStatus.NO_RESULT
                } else {
                    group.status = PluginSearchStatus.SUCCESS
                    // 对齐原版按 src 去重（seenBySource：同一插件内重复 src 只显示一次）
                    group.results.addAll(resp.data.distinctBy { it.src })
                }
            } catch (e: Exception) {
                group.status = PluginSearchStatus.ERROR
                group.message = if (e is com.kazumi.port.data.model.CaptchaRequiredException) {
                    "需要验证码验证：请在浏览器打开该站点完成验证后重试"
                } else null
            }
            withContext(Dispatchers.Main.immediate) {
                updateCount()
                // 首个出现结果的组自动展开为预览（只触发一次）
                if (!autoExpansionHandled && group.hasResults) {
                    setDisplay(group.name, SourceDisplay.PREVIEW)
                }
            }
        }
    }

    /** 全体检索（换全局检索词时调用，对齐原版 queryAllSource） */
    fun searchAll() {
        groups.forEach { it.status = PluginSearchStatus.PENDING; it.results.clear() }
        pendingCount = total
        resultCount = 0
        plugins.forEach { plugin ->
            val group = groups.firstOrNull { it.name == plugin.name } ?: return@forEach
            searchGroup(group, SearchAction.QUERY)
        }
    }

    LaunchedEffect(query) {
        searchAll()
    }

    fun openInBrowser(group: SourceGroup) {
        val plugin = plugins.firstOrNull { it.name == group.name } ?: return
        val activeKeyword = group.effectiveKeyword(query)
        // 对齐原版 _openInBrowser：API 搜索直接用 baseUrl，否则替换 searchURL 的 @keyword
        val targetUrl = if (plugin.usesApiSearch) {
            plugin.baseUrl
        } else {
            plugin.searchURL.replace("@keyword", java.net.URLEncoder.encode(activeKeyword, "UTF-8"))
        }
        if (targetUrl.isBlank()) return
        val ok = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
        }.isSuccess
        if (!ok) {
            // 复用同包 Toast 工具
            android.widget.Toast.makeText(context, "无法打开浏览器，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 按"有结果优先"排序（稳定排序：同档保持插件配置顺序）
    val sortedGroups = remember(groups, resultCount, pendingCount) {
        groups.sortedWith(compareByDescending<SourceGroup> { it.hasResults }.thenBy { groups.indexOf(it) })
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 显式不透明背景（对齐原版 showModalBottomSheet 的 surface 背景；老版本 material3 默认容器可能透明）
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题 + 进度（对齐原版 MaterialBottomSheetHeader）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "播放来源",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (pendingCount > 0)
                            "检索中 ${total - pendingCount}/$total · $resultCount 个结果"
                        else
                            "$total 个来源 · $resultCount 个结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = "收起",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            // 分组列表（有结果的在前）
            LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                items(sortedGroups, key = { it.name }) { group ->
                    SourceGroupRow(
                        group = group,
                        display = displayOf(group.name),
                        keywordOverride = group.effectiveKeyword(query) != query,
                        menuExpanded = menuGroup == group.name,
                        onMenuDismiss = { menuGroup = null },
                        onToggle = { toggle(group.name) },
                        onSetDisplay = { setDisplay(group.name, it) },
                        onPlayItem = { item ->
                            onPlay(group.name, item.src, item.name)
                        },
                        onMoreClick = { menuGroup = group.name },
                        onUseAlias = {
                            aliasTarget = group.name
                            showAliasDialog = true
                        },
                        onCustomKeyword = {
                            keywordTarget = group.name
                            keywordInput = group.effectiveKeyword(query)
                            showKeywordDialog = true
                        },
                        onRetry = { searchGroup(group, SearchAction.RETRY) },
                        onOpenBrowser = { openInBrowser(group) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            // 修改检索词弹窗（对应原版 _showCustomKeywordDialog）
            if (showKeywordDialog) {
                val target = keywordTarget
                AlertDialog(
                    onDismissRequest = { showKeywordDialog = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = { Text("修改检索词") },
                    text = {
                        Column {
                            Text(
                                text = target?.let { "仅对「$it」生效，其他来源仍用原关键词。" } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.OutlinedTextField(
                                value = keywordInput,
                                onValueChange = { keywordInput = it },
                                singleLine = true,
                                placeholder = { Text("输入关键词") }
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val g = groups.firstOrNull { it.name == target }
                            if (g != null && keywordInput.isNotBlank()) {
                                g.keyword = keywordInput.trim()
                                setDisplay(g.name, SourceDisplay.PREVIEW)
                                searchGroup(g, SearchAction.QUERY)
                            }
                            showKeywordDialog = false
                            keywordTarget = null
                        }) { Text("确定") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showKeywordDialog = false
                            keywordTarget = null
                        }) { Text("取消") }
                    }
                )
            }
            // 使用别名重新检索（对应原版 _showAliasPickerDialog）
            if (showAliasDialog) {
                val target = aliasTarget
                AlertDialog(
                    onDismissRequest = { showAliasDialog = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = { Text("选择别名") },
                    text = {
                        if (aliases.isEmpty()) {
                            Text("这部番没有别名，可以换个关键词再试。")
                        } else {
                            Column {
                                aliases.forEach { alias ->
                                    Text(
                                        text = alias,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                val g = groups.firstOrNull { it.name == target }
                                                if (g != null) {
                                                    g.keyword = alias
                                                    setDisplay(g.name, SourceDisplay.PREVIEW)
                                                    searchGroup(g, SearchAction.QUERY)
                                                }
                                                showAliasDialog = false
                                                aliasTarget = null
                                            }
                                            .padding(vertical = 12.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showAliasDialog = false
                            aliasTarget = null
                        }) { Text("取消") }
                    }
                )
            }
        }
    }
}

/** 单个插件的分组行（组头 + 折叠内容） */
@Composable
private fun SourceGroupRow(
    group: SourceGroup,
    display: SourceDisplay,
    /** 该组是否使用了独立检索词（显示提示行） */
    keywordOverride: Boolean,
    /** 该组的三点菜单是否展开 */
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onToggle: () -> Unit,
    onSetDisplay: (SourceDisplay) -> Unit,
    onPlayItem: (SearchItem) -> Unit,
    onMoreClick: () -> Unit,
    onUseAlias: () -> Unit,
    onCustomKeyword: () -> Unit,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // 组头：插件名 + 状态 + 展开箭头（对齐原版 _buildSourceGroup 头部）
        val collapsed = display == SourceDisplay.COLLAPSED
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (group.status) {
                    PluginSearchStatus.PENDING -> "检索中…"
                    PluginSearchStatus.SUCCESS ->
                        if (group.results.isEmpty()) "无结果" else "${group.results.size} 个结果"
                    PluginSearchStatus.NO_RESULT -> "无结果"
                    PluginSearchStatus.ERROR -> "检索失败"
                    PluginSearchStatus.CAPTCHA -> "需要验证"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            // 展开箭头：收起朝下，展开朝上（对齐原版 AnimatedRotation）
            val rotation by animateFloatAsState(
                targetValue = if (collapsed) 0f else 180f,
                animationSpec = tween(200),
                label = "expandRotation"
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(rotation)
            )
            // 分组更多操作（对齐原版 _buildSourceMenu 四项）
            Box {
                IconButton(onClick = onMoreClick) {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = onMenuDismiss,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    DropdownMenuItem(
                        text = { Text("修改此来源的检索词") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { onMenuDismiss(); onCustomKeyword() }
                    )
                    DropdownMenuItem(
                        text = { Text("使用别名检索此来源") },
                        leadingIcon = { Icon(Icons.Filled.Tag, contentDescription = null) },
                        onClick = { onMenuDismiss(); onUseAlias() }
                    )
                    DropdownMenuItem(
                        text = { Text("重新检索此来源") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = { onMenuDismiss(); onRetry() }
                    )
                    DropdownMenuItem(
                        text = { Text("在浏览器中打开") },
                        leadingIcon = { Icon(Icons.Filled.OpenInBrowser, contentDescription = null) },
                        onClick = { onMenuDismiss(); onOpenBrowser() }
                    )
                }
            }
        }
        // 独立检索词提示（对齐原版「检索词：xxx」）
        if (!collapsed && keywordOverride) {
            Text(
                text = "检索词：${group.keyword ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            )
        }
        // 折叠内容：AnimatedVisibility 高度动画
        AnimatedVisibility(
            visible = !collapsed,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 4 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 4 }
        ) {
            SourceGroupBody(
                group = group,
                display = display,
                onSetDisplay = onSetDisplay,
                onPlayItem = onPlayItem,
                onUseAlias = onUseAlias,
                onCustomKeyword = onCustomKeyword
            )
        }
    }
}

/** 组内容：检索中 / 空态 / 错误 / 结果列表（preview 只显示 1 条 + 展开按钮） */
@Composable
private fun SourceGroupBody(
    group: SourceGroup,
    display: SourceDisplay,
    onSetDisplay: (SourceDisplay) -> Unit,
    onPlayItem: (SearchItem) -> Unit,
    onUseAlias: () -> Unit,
    onCustomKeyword: () -> Unit
) {
    when {
        group.status == PluginSearchStatus.PENDING -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "正在检索…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        group.status == PluginSearchStatus.SUCCESS && group.results.isEmpty() -> {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "没有找到匹配结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                // 对齐原版空态操作：修改检索词 / 使用别名（分两行，避免挤占）
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCustomKeyword) { Text("修改检索词") }
                    TextButton(onClick = onUseAlias) { Text("使用别名") }
                }
            }
        }
        group.status == PluginSearchStatus.ERROR -> {
            Text(
                group.message ?: "检索失败，请重试或打开网站检查是否可用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
            )
        }
        group.results.isNotEmpty() -> {
            val showAll = display == SourceDisplay.EXPANDED
            val visibleResults = if (showAll) group.results else group.results.take(1)
            // 结果行（对齐原版 _buildResult SplitListRow）
            visibleResults.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayItem(item) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(16.dp))
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            // 展开/收起全部条目按钮（对齐原版展开全部 N 个结果）
            if (group.results.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onSetDisplay(if (showAll) SourceDisplay.PREVIEW else SourceDisplay.EXPANDED)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showAll) "收起全部条目" else "展开全部 ${group.results.size} 个结果",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (showAll) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}