package com.kazumi.port.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.Plugin
import com.kazumi.port.util.FileLogger
import com.kazumi.port.utils.PluginImportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 添加规则来源，对应原版 enum RuleAddSource */
enum class RuleAddSource { CATALOG, CLIPBOARD, FILE, CREATE }

data class RuleAddEntry(
    val source: RuleAddSource,
    val icon: ImageVector,
    val title: String,
    val desc: String
)

/**
 * 添加规则对话框
 * 对应原版 showRuleAddDialog：四种来源
 */
@Composable
fun RuleAddDialog(
    onResult: (RuleAddSource?) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text("添加规则") },
        text = {
            Column {
                Text("选择一种方式，添加新的番剧来源。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(20.dp))
                listOf(
                    RuleAddEntry(RuleAddSource.CATALOG, Icons.Filled.TravelExplore, "浏览规则仓库", "发现并安装社区规则"),
                    RuleAddEntry(RuleAddSource.CLIPBOARD, Icons.Filled.ContentPaste, "从剪贴板导入", "粘贴规则链接或 JSON"),
                    RuleAddEntry(RuleAddSource.FILE, Icons.Filled.FileOpen, "从文件导入", "读取本地 JSON 规则文件"),
                    RuleAddEntry(RuleAddSource.CREATE, Icons.Filled.EditNote, "新建规则", "从模板开始编写")
                ).forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResult(entry.source) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
                            Text(entry.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(null) }) { Text("取消") }
        }
    )
}

/**
 * 导入规则对话框
 * 对应原版 showRuleImportDialog：粘贴 kazumi:// 链接或 JSON
 */
@Composable
fun RuleImportDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onImported: () -> Unit
) {
    val app = remember { KazumiApp.get() }
    var text by remember { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val toast = rememberToast()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("导入规则") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "支持多条 kazumi:// 链接、单条 JSON 或 JSON 数组。同名规则会被替换。",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("规则内容") },
                    placeholder = { Text("在这里粘贴规则链接或 JSON") },
                    minLines = 4,
                    maxLines = 8,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!saving) onDismiss() }, enabled = !saving) { Text("取消") }
            Button(
                onClick = {
                    if (saving) return@Button
                    val result = PluginImportParser.parse(text)
                    if (result.plugins.isEmpty()) {
                        error = result.failures.firstOrNull() ?: "没有识别到规则"
                        return@Button
                    }
                    saving = true
                    ImportHelper.importPlugins(app, result.plugins) { ok ->
                        saving = false
                        if (ok) {
                            toast("已导入 ${result.plugins.size} 条规则，跳过重复 ${result.duplicateCount} 条，失败 ${result.failureCount} 条")
                            onImported()
                            onDismiss()
                        } else {
                            error = "保存失败，内容已保留，请重试。"
                        }
                    }
                },
                enabled = text.isNotBlank() && !saving
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (saving) "正在导入" else "导入规则")
            }
        }
    )
}

/**
 * 分享规则对话框
 * 对应原版 showRuleShareDialog：kazumi:// 链接 + 复制
 */
@Composable
fun RuleShareDialog(
    plugin: Plugin,
    onDismiss: () -> Unit
) {
    val link = remember(plugin.name) {
        com.kazumi.port.utils.KazumiRuleEncoding.jsonToKazumiBase64(
            com.google.gson.Gson().toJson(plugin.toJson())
        )
    }
    val toast = rememberToast()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享规则") },
        text = {
            Column {
                Text(plugin.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "复制规则链接，对方可在「添加规则」中从剪贴板导入。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
            Button(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(link))
                toast("规则链接已复制")
                onDismiss()
            }) {
                Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制链接")
            }
        }
    )
}

/**
 * 删除规则确认对话框
 * 对应原版 confirmRuleDeletion
 */
@Composable
fun ConfirmRuleDeletionDialog(
    names: Set<String>,
    onResult: (Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (names.size == 1) "删除这条规则？" else "删除 ${names.size} 条规则？") },
        text = {
            Column {
                Text(
                    if (names.size == 1) names.first() else names.take(5).joinToString("、") + if (names.size > 5) " 等" else "",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "删除后将不再使用这些来源搜索番剧。你可以稍后重新导入规则。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(false) }) { Text("保留") }
            Button(
                onClick = { onResult(true) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text("删除") }
        }
    )
}

/**
 * 空状态，对应原版 GeneralEmptyState
 */
@Composable
fun RuleEmptyState(
    icon: ImageVector,
    title: String,
    action: (@Composable () -> Unit)? = null
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            if (action != null) {
                Spacer(Modifier.size(12.dp))
                action()
            }
        }
    }
}

/** 协程辅助：在工作线程导入并持久化插件，回调结果 */
object ImportHelper {
    fun importPlugins(
        app: KazumiApp,
        plugins: List<Plugin>,
        onResult: (Boolean) -> Unit
    ) {
        FileLogger.i("RuleImport", "importPlugins start: 共 ${plugins.size} 条 | ${plugins.map { it.name }}")
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val ok = runCatching {
                plugins.forEach { plugin ->
                    val json = com.google.gson.Gson().toJson(plugin.toJson())
                    val saved = app.pluginRepository.importPlugin(json)
                    if (saved == null) {
                        FileLogger.e("RuleImport", "importPlugins: 第 ${plugins.indexOf(plugin) + 1} 条导入返回 null | name=${plugin.name}")
                    }
                }
            }.onFailure { e ->
                FileLogger.e("RuleImport", "importPlugins 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            }.isSuccess
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                FileLogger.i("RuleImport", "importPlugins done: ok=$ok")
                onResult(ok)
            }
        }
    }
}