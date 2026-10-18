package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.network.HttpClientFactory
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.components.rememberToast
import com.kazumi.port.util.ProxyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 代理配置编辑页（对应原版 proxy_editor_page.dart）
 * 输入代理地址（http://host:port）与测试地址，点击「保存并测试」：
 * 1. 解析代理 URL -> HttpClientFactory.applyProxy() 即时应用到全局网络栈
 * 2. 用带代理的全局 OkHttp 请求测试地址验证连通性
 * 3. 成功：保存并启用代理；失败：恢复直连并提示
 */
@Composable
fun ProxyEditorPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val showToast = rememberToast()

    var proxyUrl by remember { mutableStateOf("") }
    var testUrl by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        proxyUrl = settings.getString(SettingsManager.Proxy.PROXY_URL, "")
        testUrl = settings.getString(SettingsManager.Proxy.PROXY_TEST_URL, "")
    }

    SettingsSubPageScaffold(
        title = "代理配置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = { proxyUrl = it },
                label = { Text("代理地址") },
                placeholder = { Text("http://127.0.0.1:7890") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = testUrl,
                onValueChange = { testUrl = it },
                label = { Text("测试地址（可选）") },
                placeholder = { Text("https://www.google.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val url = proxyUrl.trim()
                    if (url.isEmpty()) {
                        showToast("请输入代理地址")
                        return@Button
                    }
                    if (!ProxyUtils.isValidProxyUrl(url)) {
                        showToast("格式错误，请使用 http://host:port")
                        return@Button
                    }
                    scope.launch {
                        isTesting = true
                        val parsed = ProxyUtils.parseProxyUrl(url)
                        if (parsed == null) {
                            showToast("代理地址格式错误")
                            isTesting = false
                            return@launch
                        }
                        // 先应用到全局网络栈
                        HttpClientFactory.applyProxy(parsed.first, parsed.second)
                        settings.setString(SettingsManager.Proxy.PROXY_URL, url)
                        val testTarget = testUrl.trim().ifEmpty { "https://www.google.com" }
                        settings.setString(SettingsManager.Proxy.PROXY_TEST_URL, testTarget)
                        // 用带代理的全局 OkHttp 验证连通性
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                val request = okhttp3.Request.Builder().url(testTarget).build()
                                HttpClientFactory.getClient().newCall(request).execute().use { resp ->
                                    resp.code in 200..399
                                }
                            } catch (e: Exception) {
                                false
                            }
                        }
                        if (ok) {
                            settings.setBoolean(SettingsManager.Proxy.PROXY_ENABLE, true)
                            settings.setBoolean(SettingsManager.Proxy.PROXY_CONFIGURED, true)
                            showToast("测试成功")
                        } else {
                            settings.setBoolean(SettingsManager.Proxy.PROXY_ENABLE, false)
                            settings.setBoolean(SettingsManager.Proxy.PROXY_CONFIGURED, false)
                            HttpClientFactory.applyProxy(null, null)
                            showToast("代理连接失败")
                        }
                        isTesting = false
                    }
                },
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isTesting) "测试中…" else "保存并测试")
            }
            Text(
                text = "测试通过后将自动启用代理；测试失败会自动恢复直连。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}