package com.kazumi.port.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.data.model.ApiEndpoints
import com.kazumi.port.ui.components.SettingsSubPageScaffold

/**
 * 致谢页（对应原版 credits_page.dart）
 * 贡献 / 服务 / 开源项目三类链接
 */
@Composable
fun CreditsPage(navController: NavHostController) {
    SettingsSubPageScaffold(
        title = "致谢",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            CreditsGroup("贡献") {
                CreditsLinkTile(
                    icon = Icons.Filled.Brush,
                    title = "图标作者",
                    subtitle = "Pixiv",
                    url = "https://www.pixiv.net/users/66219277"
                )
            }
            Spacer(Modifier.size(24.dp))
            CreditsGroup("服务") {
                CreditsLinkTile(
                    icon = Icons.Filled.MenuBook,
                    title = "Bangumi",
                    subtitle = "番剧信息",
                    url = ApiEndpoints.BANGUMI_INDEX
                )
                CreditsLinkTile(
                    icon = Icons.Filled.Subtitles,
                    title = "弹弹play",
                    subtitle = "弹幕",
                    url = ApiEndpoints.DANDAN_INDEX
                )
                CreditsLinkTile(
                    icon = Icons.Filled.ImageSearch,
                    title = "trace.moe",
                    subtitle = "以图搜番",
                    url = "https://trace.moe"
                )
            }
            Spacer(Modifier.size(24.dp))
            CreditsGroup("开源项目") {
                CreditsLinkTile(
                    icon = Icons.Filled.PlayCircle,
                    title = "ExoPlayer / Media3",
                    subtitle = "视频播放",
                    url = "https://github.com/androidx/media"
                )
                CreditsLinkTile(
                    icon = Icons.Filled.Subtitles,
                    title = "DanmakuFlameMaster",
                    subtitle = "弹幕渲染",
                    url = "https://github.com/bilibili/DanmakuFlameMaster"
                )
                CreditsLinkTile(
                    icon = Icons.Filled.AutoAwesome,
                    title = "Coil",
                    subtitle = "图片加载",
                    url = "https://github.com/coil-kt/coil"
                )
                CreditsLinkTile(
                    icon = Icons.Filled.Sync,
                    title = "Syncplay",
                    subtitle = "播放同步",
                    url = "https://github.com/Syncplay/syncplay"
                )
                CreditsLinkTile(
                    icon = Icons.Filled.AccountTree,
                    title = "OkHttp",
                    subtitle = "网络请求",
                    url = "https://github.com/square/okhttp"
                )
                CreditsLinkTile(
                    icon = Icons.Filled.VideoSettings,
                    title = "Gson / jsoup / JsonPath",
                    subtitle = "数据解析",
                    url = "https://github.com/google/gson"
                )
            }
        }
    }
}

@Composable
private fun CreditsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W600,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun CreditsLinkTile(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    url: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // 打开外部链接
                runCatching {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    )
                    com.kazumi.port.KazumiApp.get().startActivity(intent)
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}