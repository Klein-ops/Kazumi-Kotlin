package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Bookmarks
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
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import kotlinx.coroutines.launch

/**
 * 同步设置页（对应原版 sync_settings_page.dart + webdav_sync_page.dart）
 * 追番同步（Bangumi）与多设备同步（WebDAV）两个服务入口
 */
@Composable
fun SyncSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()

    var bangumiToken by remember { mutableStateOf("") }
    var bangumiEnabled by remember { mutableStateOf(false) }
    var webDavUrl by remember { mutableStateOf("") }
    var webDavEnabled by remember { mutableStateOf(false) }
    var webDavHistory by remember { mutableStateOf(false) }
    var webDavCollect by remember { mutableStateOf(false) }
    var webDavShield by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bangumiToken = settings.getString(SettingsManager.Bangumi.ACCESS_TOKEN, "")
        bangumiEnabled = settings.getBoolean(SettingsManager.Bangumi.SYNC_ENABLE, false)
        webDavUrl = settings.getString(SettingsManager.WebDav.URL, "")
        webDavEnabled = settings.getBoolean(SettingsManager.WebDav.ENABLE, false)
        webDavHistory = settings.getBoolean(SettingsManager.WebDav.ENABLE_HISTORY, false)
        webDavCollect = settings.getBoolean(SettingsManager.WebDav.ENABLE_COLLECT, false)
        webDavShield = settings.getBoolean(SettingsManager.WebDav.ENABLE_DANMAKU_SHIELD, false)
    }

    val hasBangumi = bangumiToken.isNotBlank()
    val hasWebDav = webDavUrl.isNotBlank()

    SettingsSubPageScaffold(
        title = "同步设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("追番同步") {
                SettingClickRow(
                    title = "Bangumi 追番同步",
                    subtitle = if (hasBangumi)
                        (if (bangumiEnabled) "已开启 · 与 Bangumi 保持相同的追番状态" else "已连接 · 未开启同步")
                    else "未连接 · 登录 Bangumi 账号以同步追番状态",
                    leading = Icons.Filled.Bookmarks,
                    trailingValue = if (hasBangumi) (if (bangumiEnabled) "已开启" else "已关闭") else "未连接",
                    onClick = {
                        bangumiEnabled = !bangumiEnabled
                        scope.launch { settings.setBoolean(SettingsManager.Bangumi.SYNC_ENABLE, bangumiEnabled) }
                    }
                )
            }

            SettingGroup("多设备同步") {
                SettingClickRow(
                    title = if (hasWebDav) "WebDAV 同步服务器" else "连接你的云盘",
                    subtitle = if (hasWebDav) webDavUrl else "需要支持 WebDAV 的云盘或服务器",
                    leading = Icons.Filled.Devices,
                    trailingValue = if (hasWebDav) "已配置" else "未配置",
                    onClick = {}
                )
                SettingSwitchRow(
                    title = "启用 WebDAV",
                    subtitle = if (hasWebDav) "开启后选择下方要同步的内容" else "请先配置服务器",
                    leading = Icons.Filled.Devices,
                    checked = webDavEnabled,
                    enabled = hasWebDav,
                    onCheckedChange = { value ->
                        webDavEnabled = value
                        scope.launch { settings.setBoolean(SettingsManager.WebDav.ENABLE, value) }
                    }
                )
            }

            SettingGroup("同步内容") {
                SettingSwitchRow(
                    title = "观看记录",
                    subtitle = "自动同步播放进度与历史记录",
                    leading = Icons.Filled.History,
                    checked = webDavHistory,
                    enabled = webDavEnabled,
                    onCheckedChange = { value ->
                        webDavHistory = value
                        scope.launch { settings.setBoolean(SettingsManager.WebDav.ENABLE_HISTORY, value) }
                    }
                )
                SettingSwitchRow(
                    title = "收藏",
                    subtitle = "在收藏页同步所有追番分类",
                    leading = Icons.Filled.Favorite,
                    checked = webDavCollect,
                    enabled = webDavEnabled,
                    onCheckedChange = { value ->
                        webDavCollect = value
                        scope.launch { settings.setBoolean(SettingsManager.WebDav.ENABLE_COLLECT, value) }
                    }
                )
                SettingSwitchRow(
                    title = "弹幕屏蔽词",
                    subtitle = "启动和修改规则后自动同步，包含关键词与正则表达式",
                    leading = Icons.Filled.FilterAlt,
                    checked = webDavShield,
                    enabled = webDavEnabled,
                    onCheckedChange = { value ->
                        webDavShield = value
                        scope.launch { settings.setBoolean(SettingsManager.WebDav.ENABLE_DANMAKU_SHIELD, value) }
                    }
                )
            }

            SettingGroup("说明") {
                Text(
                    text = "WebDAV 可同时与 Bangumi 同步使用，互不影响",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}