package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.model.PluginHTTPItem
import com.kazumi.port.ui.components.rememberToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 引导第三步：内嵌规则仓库（对应原版 onboarding 的 PluginShopStep → PluginCatalogView.onboarding）
 *
 * 原版在这一步直接把规则仓库列表嵌进引导页，用户可以当场点「安装」，
 * 装完继续下一步；这里按同样交互实现，底部保留「打开完整规则仓库」入口。
 */
@Composable
internal fun OnboardingCatalogStep(onOpenShop: () -> Unit) {
    val app = remember { KazumiApp.get() }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var items by remember { mutableStateOf<List<PluginHTTPItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedNames by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun reload() {
        items = runCatching { app.pluginRepository.ensurePluginCatalog() }.getOrDefault(emptyList())
        installedNames = app.pluginRepository.allPlugins().map { it.name.lowercase() }.toSet()
        loading = false
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { reload() }
    }

    fun install(name: String) {
        val key = name.lowercase()
        if (key in installing) return
        installing = installing + key
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { app.pluginRepository.tryUpdatePluginByName(name) }.isSuccess
            }
            withContext(Dispatchers.IO) { reload() }
            installing = installing - key
            toast(if (ok && key in installedNames) "已添加 $name" else "添加失败，请重试")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Extension,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(10.dp))
            Column {
                Text(
                    text = "添加番剧来源",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.W700
                )
                Text(
                    text = "规则决定从哪些站点搜索和播放番剧，添加至少一条即可开始观看。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            items.isEmpty() -> Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "规则仓库暂时打不开",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "可以先进入应用，稍后在「设置 → 规则管理」里再添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Button(onClick = onOpenShop, modifier = Modifier.padding(top = 12.dp)) {
                        Text("打开规则仓库")
                    }
                }
            }

            else -> {
                items.forEach { item ->
                    val key = item.name.lowercase()
                    val installed = key in installedNames
                    val busy = key in installing
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "v${item.version}" +
                                        if (item.author.isNotBlank()) " · ${item.author}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            when {
                                installed -> Text(
                                    text = "已添加",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                busy -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )

                                else -> TextButton(onClick = { install(item.name) }) {
                                    Text("添加")
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onOpenShop, modifier = Modifier.padding(top = 4.dp)) {
                    Text("打开完整规则仓库")
                }
            }
        }
    }
}