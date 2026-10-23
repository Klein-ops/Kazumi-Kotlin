package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kazumi.port.ui.navigation.Routes

private data class MainTab(
    val route: String,
    val label: String,
    val icon: @Composable () -> androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: @Composable () -> androidx.compose.ui.graphics.vector.ImageVector
)

private val mainTabs = listOf(
    MainTab(Routes.POPULAR, "推荐", { Icons.Outlined.Home }, { Icons.Filled.Home }),
    MainTab(Routes.TIMELINE, "时间表", { Icons.Outlined.Timeline }, { Icons.Filled.Timeline }),
    MainTab(Routes.COLLECT, "追番", { Icons.Outlined.FavoriteBorder }, { Icons.Filled.Favorite }),
    MainTab(Routes.MY, "我的", { Icons.Outlined.Settings }, { Icons.Filled.Settings })
)

/**
 * 主界面底部导航容器（对应原版 index_page 的底部导航）
 */
@Composable
fun MainScaffold(
    navController: NavHostController,
    content: @Composable () -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            NavigationBar {
                mainTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(Routes.POPULAR) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(if (currentRoute == tab.route) tab.selectedIcon() else tab.icon(), contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            content()
        }
    }
}