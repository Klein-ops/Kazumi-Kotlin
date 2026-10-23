package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.SearchItem
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则测试页（对应原版 plugin_test_page 的核心能力）
 *
 * 输入关键词 → 只用这一条规则检索 → 显示结果条目（名称 + src）或错误信息。
 * 原版还有“查询播放列表/校验章节”等步骤，这里先覆盖最常用的检索验证。
 */
@Composable
fun RuleTestPage(navController: NavHostController, pluginName: String) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SearchItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var tested by remember { mutableStateOf(false) }

    fun runTest() {
        val k = keyword.trim()
        if (k.isEmpty() || loading) return
        loading = true
        error = null
        tested = false
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    app.pluginRepository.searchBangumi(pluginName, k)
                }
                results = resp.data
                tested = true
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                results = emptyList()
                tested = true
            } finally {
                loading = false
            }
        }
    }

    SettingsSubPageScaffold(
        title = "测试规则 · $pluginName",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
                label = { Text("检索关键词") },
                supportingText = { Text("只用「$pluginName」这一条规则检索") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runTest() }),
                trailingIcon = {
                    TextButton(onClick = { runTest() }, enabled = !loading) { Text("检索") }
                }
            )
            when {
                loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("检索中…", style = MaterialTheme.typography.bodyMedium)
                }

                error != null -> Text(
                    text = "检索失败：$error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp)
                )

                tested && results.isEmpty() -> Text(
                    text = "检索成功，但没有结果。可以换个关键词，或检查规则里的搜索选择器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )

                results.isNotEmpty() -> {
                    Text(
                        text = "检索到 ${results.size} 条结果",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(results) { item ->
                            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = item.src,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}