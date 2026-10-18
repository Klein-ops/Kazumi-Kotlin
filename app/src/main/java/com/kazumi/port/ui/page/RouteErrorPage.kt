package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.kazumi.port.ui.components.SettingsSubPageScaffold
import com.kazumi.port.ui.navigation.Routes

/**
 * 路由错误页（对应原版 route_error_page.dart）
 * 无法打开的页面时展示错误信息 + 返回首页按钮
 */
@Composable
fun RouteErrorPage(
    navController: NavHostController,
    message: String = "请检查链接是否正确后重试。"
) {
    SettingsSubPageScaffold(
        title = "Kazumi",
        onBack = { navController.popBackStack() }
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "无法打开页面",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Button(
                    onClick = {
                        navController.navigate(Routes.POPULAR) {
                            popUpTo(Routes.POPULAR) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("返回首页", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}