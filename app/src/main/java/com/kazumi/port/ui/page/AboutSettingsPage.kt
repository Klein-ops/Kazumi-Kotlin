package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.ui.components.SettingClickRow
import com.kazumi.port.ui.components.SettingGroup
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.navigation.Routes

/**
 * 关于页（对应原版 about_page.dart）
 * 项目信息 + 项目/开源链接
 */
@Composable
fun AboutSettingsPage(navController: NavHostController) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            context.startActivity(intent)
        } catch (e: Exception) {
            // 无可用浏览器时不崩溃
        }
    }

    // 运行时读取 APK 实际版本号（不依赖 BuildConfig，避免 CodeAssist 环境不生成该类）
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    SettingsSubPageScaffold(
        title = "关于",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Kazumi",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "版本 $versionName (Kotlin 重写版)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "基于自定义规则的开源番剧应用",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            SettingGroup("项目") {
                SettingClickRow(
                    title = "源代码",
                    subtitle = "GitHub 仓库",
                    leading = Icons.Filled.Code,
                    onClick = { openUrl("https://github.com/Klein-ops/Kazumi-Kotlin") }
                )
                SettingClickRow(
                    title = "项目主页",
                    subtitle = "kazumi.app",
                    leading = Icons.Filled.Language,
                    onClick = { openUrl("https://kazumi.app/") }
                )
                SettingClickRow(
                    title = "问题反馈",
                    subtitle = "在 GitHub 提交 Issue",
                    leading = Icons.Filled.BugReport,
                    onClick = { openUrl("https://github.com/Klein-ops/Kazumi-Kotlin/issues") }
                )
            }

            SettingGroup("开源") {
                SettingClickRow(
                    title = "贡献指南",
                    subtitle = "参与 Kazumi 开发",
                    leading = Icons.Filled.Handshake,
                    onClick = { openUrl("https://github.com/Klein-ops/Kazumi-Kotlin/blob/main/README.md") }
                )
                SettingClickRow(
                    title = "致谢",
                    subtitle = "贡献者、服务与开源项目",
                    leading = Icons.Filled.Favorite,
                    onClick = { navController.navigate(Routes.SETTINGS_ABOUT_CREDITS) }
                )
                SettingClickRow(
                    title = "GPL-3.0 许可证",
                    subtitle = "查看开源许可证",
                    leading = Icons.Filled.Balance,
                    onClick = { openUrl("https://github.com/Klein-ops/Kazumi-Kotlin/blob/main/LICENSE") }
                )
                SettingClickRow(
                    title = "更新日志",
                    subtitle = "查看版本发布记录",
                    leading = Icons.Filled.Update,
                    onClick = { openUrl("https://github.com/Klein-ops/Kazumi-Kotlin/releases") }
                )
            }
        }
    }
}