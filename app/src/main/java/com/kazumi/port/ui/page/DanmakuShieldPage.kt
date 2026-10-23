package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.db.ShieldEntity
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.components.rememberToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 弹幕屏蔽规则页（对应原版 DanmakuShieldSettings + DanmakuShieldEditor）
 *
 * 交互与原版一致：
 * - 顶部输入「关键词或 /正则表达式/」，回车或点 + 添加；空输入不添加；
 * - 已添加列表显示条数，`/xx/` 形式标注「正则表达式」，右侧 × 删除；
 * - 无规则时显示空状态「还没有屏蔽规则」；
 * - 说明文案：包含关键词的弹幕会被隐藏。用 / / 包裹正则表达式。
 */
@Composable
fun DanmakuShieldPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val dao = remember { app.database.shieldDao() }
    val scope = rememberCoroutineScope()
    val showToast = rememberToast()
    val rules by dao.observeAll().collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }

    fun addRule() {
        val rule = input.trim()
        if (rule.isEmpty()) return
        if (rules.any { it.content == rule }) {
            showToast("该规则已存在")
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { dao.insert(ShieldEntity(content = rule)) }
            input = ""
        }
    }

    SettingsSubPageScaffold(
        title = "屏蔽规则",
        onBack = { navController.popBackStack() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text(
                    text = "添加规则",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("关键词或 /正则表达式/") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addRule() }),
                    trailingIcon = {
                        IconButton(onClick = { addRule() }) {
                            Icon(Icons.Filled.Add, contentDescription = "添加规则")
                        }
                    }
                )
                Text(
                    text = "包含关键词的弹幕会被隐藏。用 / / 包裹正则表达式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
            if (rules.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.FilterAltOff,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "还没有屏蔽规则",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item {
                    Text(
                        text = "已添加 · ${rules.size}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(rules, key = { it.content }) { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rule.content,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (rule.content.length > 2 &&
                                rule.content.startsWith("/") &&
                                rule.content.endsWith("/")
                            ) {
                                Text(
                                    text = "正则表达式",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.delete(rule.content) }
                            }
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "删除规则",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}