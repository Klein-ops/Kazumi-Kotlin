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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingsSubPageScaffold

/**
 * 存储与日志页（对应原版 storage_settings.dart）
 * 缓存：清除图片缓存；诊断：错误日志（进入日志页）
 */
@Composable
fun StorageSettingsPage(navController: NavHostController) {
    var cacheSize by remember { mutableStateOf("正在统计…") }
    var clearing by remember { mutableStateOf(false) }

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
                        // TODO: 接入图片缓存服务后替换
                        clearing = false
                        cacheSize = "0.00 MB"
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
                    onClick = {}
                )
            }
        }
    }
}