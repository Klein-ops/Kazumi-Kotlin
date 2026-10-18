package com.kazumi.port.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
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
 * 界面设置页（对应原版 interface_settings.dart）
 * 启动界面 / 追番默认布局 / 显示评分
 */
@Composable
fun InterfaceSettingsPage(navController: NavHostController) {
    val app = remember { KazumiApp.get() }
    val settings = app.settings
    val scope = rememberCoroutineScope()

    var showRating by remember { mutableStateOf(false) }
    var defaultPage by remember { mutableStateOf("") }
    var collectLayout by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        showRating = settings.getBoolean(SettingsManager.Interface.SHOW_RATING, true)
        defaultPage = settings.getString(SettingsManager.Interface.DEFAULT_STARTUP_PAGE, "/tab/popular/")
        collectLayout = settings.getString(SettingsManager.Interface.DEFAULT_COLLECT_LAYOUT, "grid")
    }

    val pageLabels = mapOf(
        "/tab/popular/" to "推荐",
        "/tab/timeline/" to "时间表",
        "/tab/collect/" to "追番",
        "/tab/my/" to "我的"
    )
    val pageOrder = listOf("/tab/popular/", "/tab/timeline/", "/tab/collect/", "/tab/my/")

    SettingsSubPageScaffold(
        title = "界面设置",
        onBack = { navController.popBackStack() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingGroup("启动") {
                SettingClickRow(
                    title = "启动界面设置",
                    subtitle = "设置应用开启时的默认页面",
                    leading = Icons.Filled.Home,
                    trailingValue = pageLabels[defaultPage] ?: "推荐",
                    onClick = {
                        val idx = pageOrder.indexOf(defaultPage)
                        defaultPage = pageOrder[(idx + 1) % pageOrder.size]
                        scope.launch { settings.setString(SettingsManager.Interface.DEFAULT_STARTUP_PAGE, defaultPage) }
                    }
                )
            }

            SettingGroup("展示信息") {
                SettingClickRow(
                    title = "追番默认布局",
                    subtitle = "下次打开追番页时使用，页面内切换不会改变此设置",
                    leading = Icons.Filled.ViewAgenda,
                    trailingValue = if (collectLayout == "list") "列表" else "网格",
                    onClick = {
                        collectLayout = if (collectLayout == "list") "grid" else "list"
                        scope.launch { settings.setString(SettingsManager.Interface.DEFAULT_COLLECT_LAYOUT, collectLayout) }
                    }
                )
                SettingSwitchRow(
                    title = "显示评分",
                    subtitle = "关闭后隐藏概览和番剧列表中的评分信息",
                    leading = Icons.Filled.Star,
                    checked = showRating,
                    onCheckedChange = { value ->
                        showRating = value
                        scope.launch { settings.setBoolean(SettingsManager.Interface.SHOW_RATING, value) }
                    }
                )
            }
        }
    }
}