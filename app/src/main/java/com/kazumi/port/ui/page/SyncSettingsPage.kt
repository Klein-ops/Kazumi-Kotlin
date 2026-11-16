package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.KazumiApp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingSwitchRow
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.kazumi.port.ui.components.KazumiLoadingIndicator
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import com.kazumi.port.ui.components.rememberToast

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
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavEnabled by remember { mutableStateOf(false) }
    var webDavHistory by remember { mutableStateOf(false) }
    var webDavCollect by remember { mutableStateOf(false) }
    var webDavShield by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    // WebDAV 配置对话框的草稿值（避免直接改已保存值）
    var draftUrl by remember { mutableStateOf("") }
    var draftUsername by remember { mutableStateOf("") }
    var draftPassword by remember { mutableStateOf("") }
    // ---- Bangumi 账号区（2026-11-17 补全：原版 bangumi_sync_page 有 Token 输入/测试连接/立即同步）----
    var bangumiOutput by remember { mutableStateOf("") }
    var bangumiConnecting by remember { mutableStateOf(false) }
    var bangumiSyncing by remember { mutableStateOf(false) }
    var bangumiSyncProgress by remember { mutableStateOf("") }
    val bangumiService = remember { app.bangumiSyncService }
    val showBangumiToast = rememberToast()
    LaunchedEffect(Unit) {
        bangumiToken = settings.getString(SettingsManager.Bangumi.ACCESS_TOKEN, "")
        bangumiEnabled = settings.getBoolean(SettingsManager.Bangumi.SYNC_ENABLE, false)
        webDavUrl = settings.getString(SettingsManager.WebDav.URL, "")
        webDavUsername = settings.getString(SettingsManager.WebDav.USERNAME, "")
        webDavPassword = settings.getString(SettingsManager.WebDav.PASSWORD, "")
        webDavEnabled = settings.getBoolean(SettingsManager.WebDav.ENABLE, false)
        webDavHistory = settings.getBoolean(SettingsManager.WebDav.ENABLE_HISTORY, false)
        webDavCollect = settings.getBoolean(SettingsManager.WebDav.ENABLE_COLLECT, false)
        webDavShield = settings.getBoolean(SettingsManager.WebDav.ENABLE_DANMAKU_SHIELD, false)
        // 已配置且同步开启时尝试连接，显示当前用户名
        if (bangumiToken.isNotBlank() && bangumiEnabled) {
            bangumiConnecting = true
            val ok = bangumiService.connect()
            bangumiConnecting = false
            bangumiOutput = if (ok) "已连接 · ${bangumiService.currentUsername}" else "连接失败：${bangumiService.errorMessage ?: "未知错误"}"
        }
    }

    fun saveBangumiToken() {
        val token = bangumiToken.trim()
        if (token.isEmpty()) {
            showBangumiToast("请输入 Access Token")
            return
        }
        bangumiConnecting = true
        bangumiOutput = ""
        scope.launch {
            val ok = bangumiService.saveToken(token)
            bangumiConnecting = false
            if (ok) {
                bangumiOutput = "已保存并连接 · ${bangumiService.currentUsername}"
                showBangumiToast("Bangumi 账号已连接")
            } else {
                bangumiOutput = "连接失败：${bangumiService.errorMessage ?: "Token 无效"}"
            }
        }
    }

    fun testBangumi() {
        val token = bangumiToken.trim()
        if (token.isEmpty()) {
            showBangumiToast("请先输入 Access Token")
            return
        }
        bangumiConnecting = true
        bangumiOutput = ""
        scope.launch {
            val ok = bangumiService.saveToken(token)
            bangumiConnecting = false
            bangumiOutput = if (ok) "连接成功 · ${bangumiService.currentUsername}" else "连接失败：${bangumiService.errorMessage ?: "未知错误"}"
        }
    }

    fun syncBangumiNow() {
        if (bangumiSyncing) return
        bangumiSyncing = true
        bangumiSyncProgress = ""
        scope.launch {
            val ok = bangumiService.syncCollectibles { msg, done, total ->
                bangumiSyncProgress = if (total > 0) "$msg（$done/$total）" else msg
            }
            bangumiSyncing = false
            if (ok) {
                bangumiSyncProgress = "追番状态已同步"
                showBangumiToast("追番状态已同步到 Bangumi")
            } else {
                bangumiSyncProgress = "同步失败：${bangumiService.errorMessage ?: "请检查网络或配置"}"
            }
        }
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
                // Access Token 输入（对齐原版 bangumi_sync_page 的 Token 输入框）
                OutlinedTextField(
                    value = bangumiToken,
                    onValueChange = { bangumiToken = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    label = { Text("Bangumi Access Token") },
                    placeholder = { Text("在 bangumi.tv/settings/access_token 获取") },
                    singleLine = true,
                    enabled = !bangumiConnecting
                )
                // 连接状态 / 按钮行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { testBangumi() },
                        enabled = !bangumiConnecting && bangumiToken.isNotBlank()
                    ) {
                        if (bangumiConnecting) {
                            KazumiLoadingIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (hasBangumi) "测试连接" else "连接")
                    }
                    Button(
                        onClick = { saveBangumiToken() },
                        enabled = !bangumiConnecting && bangumiToken.isNotBlank()
                    ) {
                        Text("保存并连接")
                    }
                }
                // 连接结果输出
                if (bangumiOutput.isNotEmpty()) {
                    Text(
                        text = bangumiOutput,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                // 自动同步追番开关（对齐原版：修改追番状态时同步到 Bangumi）
                SettingSwitchRow(
                    title = "自动同步追番",
                    subtitle = if (hasBangumi) "修改追番状态时同步到 Bangumi" else "请先连接 Bangumi 账号",
                    leading = Icons.Filled.Bookmarks,
                    checked = bangumiEnabled,
                    enabled = hasBangumi,
                    onCheckedChange = { value ->
                        bangumiEnabled = value
                        scope.launch { settings.setBoolean(SettingsManager.Bangumi.SYNC_ENABLE, value) }
                    }
                )
                // 立即同步按钮（对齐原版 StateActionButton "立即同步追番"）
                if (hasBangumi) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { syncBangumiNow() },
                            enabled = !bangumiSyncing && bangumiEnabled
                        ) {
                            if (bangumiSyncing) {
                                KazumiLoadingIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (bangumiSyncing) "正在同步…" else "立即同步追番")
                        }
                    }
                    if (bangumiSyncProgress.isNotEmpty()) {
                        Text(
                            text = bangumiSyncProgress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            SettingGroup("多设备同步") {
                SettingClickRow(
                    title = if (hasWebDav) "WebDAV 同步服务器" else "连接你的云盘",
                    subtitle = if (hasWebDav) webDavUrl else "需要支持 WebDAV 的云盘或服务器",
                    leading = Icons.Filled.Devices,
                    trailingValue = if (hasWebDav) "已配置" else "未配置",
                    topRadius = 24.dp,
                    onClick = {
                        draftUrl = webDavUrl
                        draftUsername = webDavUsername
                        draftPassword = webDavPassword
                        showWebDavDialog = true
                    }
                )
                SettingSwitchRow(
                    title = "启用 WebDAV",
                    subtitle = if (hasWebDav) "开启后选择下方要同步的内容" else "请先配置服务器",
                    leading = Icons.Filled.Devices,
                    checked = webDavEnabled,
                    enabled = hasWebDav,
                    bottomRadius = 24.dp,
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
                    topRadius = 24.dp,
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
                    bottomRadius = 24.dp,
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

    // WebDAV 配置对话框
    if (showWebDavDialog) {
        AlertDialog(
            onDismissRequest = { showWebDavDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("WebDAV 服务器配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draftUrl,
                        onValueChange = { draftUrl = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("服务器地址") },
                        placeholder = { Text("https://dav.example.com/remote.php/dav/") }
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftUsername,
                        onValueChange = { draftUsername = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("用户名") }
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draftPassword,
                        onValueChange = { draftPassword = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = draftUrl.trim()
                        if (url.isEmpty()) {
                            showWebDavDialog = false
                            return@TextButton
                        }
                        scope.launch {
                            settings.setString(SettingsManager.WebDav.URL, url)
                            settings.setString(SettingsManager.WebDav.USERNAME, draftUsername.trim())
                            settings.setString(SettingsManager.WebDav.PASSWORD, draftPassword)
                        }
                        webDavUrl = url
                        webDavUsername = draftUsername.trim()
                        webDavPassword = draftPassword
                        showWebDavDialog = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showWebDavDialog = false }) { Text("取消") }
            }
        )
    }
}