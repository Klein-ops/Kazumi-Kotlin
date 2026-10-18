package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.MaterialTheme
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
import com.kazumi.port.network.ImageAcceleration
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.components.rememberToast
import com.kazumi.port.ui.navigation.Routes
import com.kazumi.port.util.ProxyUtils
import kotlinx.coroutines.launch

/**
 * 网络设置页（对应原版 proxy_settings_page.dart + network_mirror_settings.dart）
 * 访问加速（番剧条目加速 / 规则仓库镜像 / 图片加速）与代理（启用代理 / 代理配置）
 */
@Composable
fun ProxySettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val showToast = rememberToast()

    var bangumiAcceleration by remember { mutableStateOf("mirror") }
    var imageAcceleration by remember { mutableStateOf("ech") }
    var enableGitProxy by remember { mutableStateOf(true) }
    var proxyEnable by remember { mutableStateOf(false) }
    var proxyUrl by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        bangumiAcceleration = settings.getString(SettingsManager.Proxy.BANGUMI_ACCELERATION, "mirror")
        imageAcceleration = settings.getString(SettingsManager.Proxy.IMAGE_ACCELERATION, "ech")
        enableGitProxy = settings.getBoolean(SettingsManager.Proxy.ENABLE_GIT_PROXY, true)
        proxyEnable = settings.getBoolean(SettingsManager.Proxy.PROXY_ENABLE, false)
        proxyUrl = settings.getString(SettingsManager.Proxy.PROXY_URL, "")
    }

    val bangumiLabels = mapOf("direct" to "直连", "ech" to "加密握手", "mirror" to "镜像")
    val bangumiOrder = listOf("direct", "ech", "mirror")
    val imageLabels = mapOf("direct" to "直连", "ech" to "ECH", "mirror" to "镜像")
    val imageOrder = listOf("direct", "ech", "mirror")

    SettingsSubPageScaffold(
        title = "网络设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("访问加速") {
                SettingClickRow(
                    title = "番剧条目加速",
                    subtitle = "番剧信息与评论",
                    leading = Icons.Filled.TravelExplore,
                    trailingValue = bangumiLabels[bangumiAcceleration] ?: "镜像",
                    onClick = {
                        val idx = bangumiOrder.indexOf(bangumiAcceleration)
                        bangumiAcceleration = bangumiOrder[(idx + 1) % bangumiOrder.size]
                        scope.launch { settings.setString(SettingsManager.Proxy.BANGUMI_ACCELERATION, bangumiAcceleration) }
                    }
                )
                SettingSwitchRow(
                    title = "规则仓库镜像",
                    subtitle = "加速规则的下载与更新",
                    leading = Icons.Filled.Extension,
                    checked = enableGitProxy,
                    onCheckedChange = { value ->
                        enableGitProxy = value
                        scope.launch {
                            settings.setBoolean(SettingsManager.Proxy.ENABLE_GIT_PROXY, value)
                            app.pluginRepository.setGitProxyEnabled(value)
                        }
                    }
                )
                SettingClickRow(
                    title = "图片加速",
                    subtitle = "封面与头像",
                    leading = Icons.Filled.Image,
                    trailingValue = imageLabels[imageAcceleration] ?: "ECH",
                    onClick = {
                        val idx = imageOrder.indexOf(imageAcceleration)
                        val next = imageOrder[(idx + 1) % imageOrder.size]
                        imageAcceleration = next
                        scope.launch {
                            settings.setString(SettingsManager.Proxy.IMAGE_ACCELERATION, next)
                            app.updateImageAcceleration(ImageAcceleration.fromSetting(next))
                        }
                    }
                )
            }

            SettingGroup("代理") {
                SettingSwitchRow(
                    title = "启用代理",
                    subtitle = "启用后网络请求将通过代理服务器",
                    leading = Icons.Filled.VpnKey,
                    checked = proxyEnable,
                    onCheckedChange = { value ->
                        proxyEnable = value
                        scope.launch {
                            settings.setBoolean(SettingsManager.Proxy.PROXY_ENABLE, value)
                            if (value) {
                                // 即时应用：解析已保存的代理地址并挂到全局 OkHttp
                                val url = settings.getString(SettingsManager.Proxy.PROXY_URL, "")
                                val parsed = ProxyUtils.parseProxyUrl(url)
                                if (parsed != null) {
                                    HttpClientFactory.applyProxy(parsed.first, parsed.second)
                                    showToast("代理已启用")
                                } else {
                                    // 地址无效则回弹开关
                                    settings.setBoolean(SettingsManager.Proxy.PROXY_ENABLE, false)
                                    showToast("请先配置有效的代理地址")
                                }
                            } else {
                                // 关闭即时清除
                                HttpClientFactory.applyProxy(null, null)
                                showToast("代理已关闭")
                            }
                        }
                    }
                )
                SettingClickRow(
                    title = "代理配置",
                    subtitle = if (proxyUrl.isEmpty()) "配置代理服务器地址和认证信息" else proxyUrl,
                    leading = Icons.Filled.Tune,
                    onClick = { navController.navigate(Routes.SETTINGS_PROXY_EDITOR) }
                )
            }

            SettingGroup("说明") {
                Text(
                    text = "镜像加速选项：direct=直连 · ech=加密握手 · mirror=镜像接口（默认）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}