package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.imageLoader
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 存储与日志页（对应原版 storage_settings.dart）
 * 缓存：清除图片缓存；诊断：错误日志（进入日志页）
 */
@Composable
fun StorageSettingsPage(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf("正在统计…") }
    var clearing by remember { mutableStateOf(false) }
    var showCacheInfo by remember { mutableStateOf(false) }

    fun refreshCacheSize() {
        scope.launch {
            val size = withContext(Dispatchers.IO) {
                val cacheDir = context.cacheDir.resolve("coil_disk")
                if (cacheDir.exists()) {
                    cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else 0L
            }
            cacheSize = if (size > 0) {
                val mb = size / 1024.0 / 1024.0
                String.format("%.2f MB", mb)
            } else {
                "0.00 MB"
            }
        }
    }

    SettingsSubPageScaffold(
        title = "存储与日志",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("缓存") {
                SettingClickRow(
                    title = "清除图片缓存",
                    subtitle = if (clearing) "正在清除…" else cacheSize,
                    leading = Icons.Filled.Image,
                    onClick = {
                        clearing = true
                        cacheSize = "正在清除…"
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                context.imageLoader.diskCache?.clear()
                                context.imageLoader.memoryCache?.clear()
                            }
                            clearing = false
                            refreshCacheSize()
                        }
                    }
                )
            }

            SettingGroup("诊断") {
                SettingClickRow(
                    title = "错误日志",
                    subtitle = "查看应用错误日志",
                    leading = Icons.Filled.ReceiptLong,
                    onClick = { navController.navigate(com.kazumi.port.ui.navigation.Routes.SETTINGS_LOGS) }
                )
            }

            SettingGroup("说明") {
                SettingClickRow(
                    title = "缓存说明",
                    subtitle = "图片会在下次加载时重新下载。视频、记录和设置不会删除。",
                    leading = Icons.Filled.CleaningServices,
                    onClick = { showCacheInfo = true }
                )
            }
        }
    }

    if (showCacheInfo) {
        AlertDialog(
            onDismissRequest = { showCacheInfo = false },
            title = { Text("缓存说明") },
            text = {
                Text(
                    "• 图片缓存：封面等图片加载后自动缓存，清除后下次会重新下载\n" +
                        "• 视频缓存：下载的视频不会因清除图片缓存而删除\n" +
                        "• 观看记录与设置：不会随缓存清除而丢失"
                )
            },
            confirmButton = {
                TextButton(onClick = { showCacheInfo = false }) { Text("知道了") }
            }
        )
    }
}