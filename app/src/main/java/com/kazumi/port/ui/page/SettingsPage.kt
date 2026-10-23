package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pages
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.navigation.Routes

/**
 * 设置索引页（对应原版 settings_page.dart 的四组菜单：播放 / 资源 / 应用 / 其他）
 * 从"我的"页右上角设置按钮进入，不带底部导航
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(navController: NavHostController) {
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    // 平板/横屏宽屏：左侧分类栏 + 右侧内容（对应原版 settings_page 的 280dp Rail）
    if (screenWidthDp >= 840) {
        WideSettingsLayout(navController)
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("播放") {
                SettingClickRow(
                    title = "播放设置",
                    subtitle = "解码、渲染与播放行为",
                    leading = Icons.Filled.DisplaySettings,
                    onClick = { navController.navigate(Routes.SETTINGS_PLAYER) }
                )
                SettingClickRow(
                    title = "弹幕设置",
                    subtitle = "弹幕来源与显示效果",
                    leading = Icons.Filled.Subtitles,
                    onClick = { navController.navigate(Routes.SETTINGS_DANMAKU) }
                )
                SettingClickRow(
                    title = "操作设置",
                    subtitle = "播放器按键映射",
                    leading = Icons.Filled.Keyboard,
                    onClick = { navController.navigate(Routes.SETTINGS_KEYBOARD) }
                )
            }

            SettingGroup("资源") {
                SettingClickRow(
                    title = "规则管理",
                    subtitle = "番剧资源规则",
                    leading = Icons.Filled.Extension,
                    onClick = { navController.navigate(Routes.SETTINGS_PLUGIN) }
                )
                SettingClickRow(
                    title = "下载设置",
                    subtitle = "并发数与弹幕缓存",
                    leading = Icons.Filled.Downloading,
                    onClick = { navController.navigate(Routes.SETTINGS_DOWNLOAD) }
                )
            }

            SettingGroup("应用") {
                SettingClickRow(
                    title = "外观设置",
                    subtitle = "主题、配色与字体",
                    leading = Icons.Filled.Palette,
                    onClick = { navController.navigate(Routes.SETTINGS_THEME) }
                )
                SettingClickRow(
                    title = "界面设置",
                    subtitle = "启动、窗口行为与展示信息",
                    leading = Icons.Filled.Pages,
                    onClick = { navController.navigate(Routes.SETTINGS_INTERFACE) }
                )
                SettingClickRow(
                    title = "同步设置",
                    subtitle = "追番状态与多设备同步",
                    leading = Icons.Filled.CloudSync,
                    onClick = { navController.navigate(Routes.SETTINGS_SYNC) }
                )
                SettingClickRow(
                    title = "网络设置",
                    subtitle = "访问加速与代理",
                    leading = Icons.Filled.Language,
                    onClick = { navController.navigate(Routes.SETTINGS_PROXY) }
                )
            }

            SettingGroup("其他") {
                SettingClickRow(
                    title = "更新设置",
                    subtitle = "规则更新",
                    leading = Icons.Filled.Update,
                    onClick = { navController.navigate(Routes.SETTINGS_UPDATE) }
                )
                SettingClickRow(
                    title = "存储与日志",
                    subtitle = "图片缓存与错误日志",
                    leading = Icons.Filled.Storage,
                    onClick = { navController.navigate(Routes.SETTINGS_STORAGE) }
                )
                SettingClickRow(
                    title = "关于",
                    subtitle = "版本与开源信息",
                    leading = Icons.Filled.Info,
                    onClick = { navController.navigate(Routes.SETTINGS_ABOUT) }
                )
            }

            Text(
                text = "Kazumi · Kotlin 重写版",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}