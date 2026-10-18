package com.kazumi.port.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.player.PlayerSyncPlayController
import kotlinx.coroutines.launch

/**
 * 同步播放（一起看）底部弹窗（对应原版 syncplay_sheet.dart）
 *
 * 流程：主页（创建/加入/服务器）-> 创建/加入房间（填房间号+昵称）或服务器选择
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPlaySheet(
    syncplay: PlayerSyncPlayController,
    settings: SettingsManager,
    onDismiss: () -> Unit,
    toast: (String) -> Unit
) {
    var destination by remember { mutableStateOf("home") } // home/create/join/server

    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (destination) {
            "create", "join" -> RoomSheet(
                isCreate = destination == "create",
                syncplay = syncplay,
                settings = settings,
                onBack = { destination = "home" },
                onDismiss = onDismiss,
                toast = toast
            )
            "server" -> ServerSheet(
                settings = settings,
                onBack = { destination = "home" },
                onDismiss = onDismiss,
                toast = toast
            )
            else -> HomeSheet(
                syncplay = syncplay,
                settings = settings,
                onPickCreate = { destination = "create" },
                onPickJoin = { destination = "join" },
                onPickServer = { destination = "server" },
                onDismiss = onDismiss,
                toast = toast
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSheet(
    syncplay: PlayerSyncPlayController,
    settings: SettingsManager,
    onPickCreate: () -> Unit,
    onPickJoin: () -> Unit,
    onPickServer: () -> Unit,
    onDismiss: () -> Unit,
    toast: (String) -> Unit
) {
    val state by syncplay.state.collectAsState()
    var endPoint by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        endPoint = settings.getString(SettingsManager.Player.SYNC_PLAY_END_POINT, "syncplay.pl:8996")
    }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("一起看", style = MaterialTheme.typography.titleMedium)
                Text("和朋友同步看番", style = MaterialTheme.typography.bodySmall)
            }
            if (state.connected || state.connecting) {
                FilledTonalButton(
                    onClick = {
                        syncplay.exitRoom()
                        toast("已断开连接")
                    },
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (state.connecting) "取消连接" else "断开连接")
                }
            } else {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
        }

        when {
            state.connecting -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("正在连接", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            endPoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            state.connected -> {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "当前房间",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    state.room,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = TextUnit(4f, TextUnitType.Sp),
                                    modifier = Modifier.weight(1f)
                                )
                                val ctx = LocalContext.current
                                FilledTonalIconButton(onClick = {
                                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("room", state.room))
                                    toast("房间号已复制")
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                Icon(Icons.Filled.Dns, contentDescription = null, modifier = Modifier.width(22.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("网络延迟", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.weight(1f))
                                Text("${state.clientRtt} ms", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "分享房间号，好友即可加入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp,
                        modifier = Modifier.weight(1f).clickable(onClick = onPickCreate)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Icon(Icons.Filled.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(20.dp))
                            Text("创建房间", style = MaterialTheme.typography.titleMedium)
                            Text("邀请朋友加入", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp,
                        modifier = Modifier.weight(1f).clickable(onClick = onPickJoin)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Icon(Icons.Filled.Login, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(20.dp))
                            Text("加入房间", style = MaterialTheme.typography.titleMedium)
                            Text("输入房间号", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                ListItem(
                    headlineContent = { Text("同步服务器") },
                    supportingContent = { Text(endPoint) },
                    trailingContent = { Text("›") },
                    modifier = Modifier.clickable(onClick = onPickServer)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomSheet(
    isCreate: Boolean,
    syncplay: PlayerSyncPlayController,
    settings: SettingsManager,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    toast: (String) -> Unit
) {
    var room by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var roomError by remember { mutableStateOf<String?>(null) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        username = settings.getString(SettingsManager.Player.SYNC_PLAY_USER_NAME, "")
        if (username.isEmpty()) {
            username = generateUserName()
        }
        if (isCreate) {
            room = generateRoomNumber()
        }
    }

    fun submit() {
        roomError = null
        usernameError = null
        val r = room.trim()
        val u = username.trim()
        if (r.isEmpty()) { roomError = "请输入房间号"; return }
        if (!Regex("^[0-9]{6,10}$").matches(r)) { roomError = "房间号为 6-10 位数字"; return }
        if (u.isEmpty()) { usernameError = "请输入昵称"; return }
        if (!Regex("^[a-zA-Z]{4,12}$").matches(u)) { usernameError = "昵称为 4-12 位英文字母"; return }
        submitting = true
        scope.launch {
            settings.setString(SettingsManager.Player.SYNC_PLAY_USER_NAME, u)
            onDismiss()
            val ok = syncplay.createRoom(r, u)
            if (ok) {
                syncplay.setPlayingBangumi()
                toast("已加入房间 $r")
            } else {
                toast(syncplay.state.value.errorMessage ?: "连接失败")
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (isCreate) "创建房间" else "加入房间", style = MaterialTheme.typography.titleMedium)
                Text(if (isCreate) "将房间号分享给好友" else "输入好友的房间号", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            if (isCreate) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "房间号",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                room,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = TextUnit(4f, TextUnitType.Sp)
                            )
                        }
                        FilledTonalIconButton(onClick = { room = generateRoomNumber() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重新生成")
                        }
                        val ctx = LocalContext.current
                        FilledTonalIconButton(onClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("room", room))
                            toast("房间号已复制")
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it.filter { c -> c.isDigit() }.take(10); roomError = null },
                    label = { Text("房间号") },
                    placeholder = { Text("6-10 位数字") },
                    isError = roomError != null,
                    supportingText = roomError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; usernameError = null },
                label = { Text("昵称") },
                supportingText = usernameError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { submit() },
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp))
                } else {
                    Icon(if (isCreate) Icons.Filled.AddCircleOutline else Icons.Filled.Login, contentDescription = null, modifier = Modifier.width(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isCreate) "创建并加入" else "加入房间")
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheet(
    settings: SettingsManager,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    toast: (String) -> Unit
) {
    val official = listOf("syncplay.pl:8995", "syncplay.pl:8996", "syncplay.pl:8997")
    var selected by remember { mutableStateOf("syncplay.pl:8996") }
    var custom by remember { mutableStateOf("") }
    var isCustom by remember { mutableStateOf(false) }
    var customError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val saved = settings.getString(SettingsManager.Player.SYNC_PLAY_END_POINT, "syncplay.pl:8996")
        if (saved in official) {
            selected = saved
        } else {
            isCustom = true
            custom = saved
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("同步服务器", style = MaterialTheme.typography.titleMedium)
                Text("房间成员需使用同一服务器", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            official.forEach { ep ->
                val selectedThis = !isCustom && selected == ep
                ListItem(
                    headlineContent = {
                        Text(
                            ep,
                            color = if (selectedThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selectedThis) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Public, contentDescription = null, tint = if (selectedThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    trailingContent = if (selectedThis) {
                        { Icon(Icons.Filled.Login, contentDescription = "已选", tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    modifier = Modifier.clickable { isCustom = false; selected = ep; customError = null }
                )
            }
            val selectedCustom = isCustom
            ListItem(
                headlineContent = {
                    Text(
                        "自定义服务器",
                        color = if (selectedCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selectedCustom) FontWeight.Bold else FontWeight.Normal
                    )
                },
                leadingContent = {
                    Icon(Icons.Filled.Edit, contentDescription = null, tint = if (selectedCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = if (selectedCustom) {
                    { Icon(Icons.Filled.Login, contentDescription = "已选", tint = MaterialTheme.colorScheme.primary) }
                } else null,
                modifier = Modifier.clickable { isCustom = true; customError = null }
            )
            if (isCustom) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it; customError = null },
                    label = { Text("服务器地址") },
                    placeholder = { Text("example.com:8996") },
                    isError = customError != null,
                    supportingText = customError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    var endPoint = selected
                    if (isCustom) {
                        endPoint = custom.trim()
                        if (parseEndPoint(endPoint) == null) {
                            customError = "地址格式为 host:port"
                            return@Button
                        }
                    }
                    scope.launch {
                        settings.setString(SettingsManager.Player.SYNC_PLAY_END_POINT, endPoint)
                        onBack()
                        toast("同步服务器已保存")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}

private fun parseEndPoint(endPoint: String): Pair<String, Int>? {
    val input = endPoint.trim()
    if (input.isEmpty()) return null
    val idx = input.lastIndexOf(':')
    if (idx <= 0 || idx == input.length - 1) return null
    val port = input.substring(idx + 1).toIntOrNull() ?: return null
    if (port <= 0 || port > 65535) return null
    return input.substring(0, idx) to port
}

private fun generateRoomNumber(): String {
    val chars = "0123456789"
    return (1..8).map { chars.random() }.joinToString("")
}

private fun generateUserName(): String {
    val consonants = "bcdfghjklmnpqrstvwxyz"
    val vowels = "aeiou"
    val syllables = 3 + (0..1).random()
    val sb = StringBuilder()
    repeat(syllables) {
        sb.append(consonants.random())
        sb.append(vowels.random())
    }
    return sb.toString().replaceFirstChar { it.uppercase() }
}