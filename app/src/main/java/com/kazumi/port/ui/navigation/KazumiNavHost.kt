package com.kazumi.port.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kazumi.port.ui.page.PopularPage
import com.kazumi.port.ui.page.SearchPage
import com.kazumi.port.ui.page.TimelinePage
import com.kazumi.port.ui.page.CollectPage
import com.kazumi.port.ui.page.DownloadPage
import com.kazumi.port.ui.page.SettingsPage
import com.kazumi.port.ui.page.DetailPage
import com.kazumi.port.ui.page.HistoryPage
import com.kazumi.port.ui.page.PlayerPage
import com.kazumi.port.ui.page.MyPage
import com.kazumi.port.ui.page.OnboardingPage
import com.kazumi.port.ui.page.ImageSearchPage
import com.kazumi.port.ui.page.PlayerSettingsPage
import com.kazumi.port.ui.page.DanmakuSettingsPage
import com.kazumi.port.ui.page.KeyboardSettingsPage
import com.kazumi.port.ui.page.PluginSettingsPage
import com.kazumi.port.ui.page.PluginShopPage
import com.kazumi.port.ui.page.CreditsPage
import com.kazumi.port.ui.page.RouteErrorPage
import com.kazumi.port.ui.page.DownloadSettingsPage
import com.kazumi.port.ui.page.ThemeSettingsPage
import com.kazumi.port.ui.page.InterfaceSettingsPage
import com.kazumi.port.ui.page.SyncSettingsPage
import com.kazumi.port.ui.page.ProxySettingsPage
import com.kazumi.port.ui.page.ProxyEditorPage
import com.kazumi.port.ui.page.UpdateSettingsPage
import com.kazumi.port.ui.page.StorageSettingsPage
import com.kazumi.port.ui.page.LogsSettingsPage
import com.kazumi.port.ui.page.AboutSettingsPage

object Routes {
    const val POPULAR = "popular"
    const val SEARCH = "search"
    const val TIMELINE = "timeline"
    const val COLLECT = "collect"
    const val DOWNLOAD = "download"
    const val MY = "my"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val ONBOARDING = "onboarding"
    const val IMAGE_SEARCH = "search/image"
    const val DETAIL = "detail/{bangumiId}?pluginName={pluginName}&src={src}"
    const val PLAYER = "player/{bangumiId}?pluginName={pluginName}&src={src}&episode={episode}&road={road}&isLocal={isLocal}&roadsJson={roadsJson}"

    // 设置子页（对应原版 /settings/xxx 路由）
    const val SETTINGS_PLAYER = "settings/player"
    const val SETTINGS_DANMAKU = "settings/danmaku"
    const val SETTINGS_KEYBOARD = "settings/keyboard"
    const val SETTINGS_PLUGIN = "settings/plugin"
    const val SETTINGS_PLUGIN_SHOP = "settings/plugin/shop"
    const val SETTINGS_DOWNLOAD = "settings/download-settings"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_INTERFACE = "settings/interface"
    const val SETTINGS_SYNC = "settings/sync"
    const val SETTINGS_PROXY = "settings/proxy"
    const val SETTINGS_PROXY_EDITOR = "settings/proxy/editor"
    const val SETTINGS_UPDATE = "settings/update"
    const val SETTINGS_STORAGE = "settings/storage"
    const val SETTINGS_LOGS = "settings/storage/logs"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_ABOUT_CREDITS = "settings/about/credits"
    const val ROUTE_ERROR = "route_error"

    fun detail(bangumiId: Int, pluginName: String = "", src: String = ""): String =
        "detail/$bangumiId?pluginName=$pluginName&src=$src"

    fun player(
        bangumiId: Int,
        pluginName: String = "",
        src: String = "",
        episode: Int = 1,
        road: Int = 0,
        isLocal: Boolean = false,
        roadsJson: String = ""
    ): String =
        "player/$bangumiId?pluginName=$pluginName&src=$src&episode=$episode&road=$road&isLocal=$isLocal&roadsJson=${android.net.Uri.encode(roadsJson)}"

    /**
     * 把原版 defaultStartupPage 字符串（如 /tab/popular/）映射为 Kotlin 路由
     * 对应原版 Navigation 中的页面路径
     */
    fun fromStartupPage(page: String): String = when (page) {
        "/tab/timeline/" -> TIMELINE
        "/tab/collect/" -> COLLECT
        "/tab/my/" -> MY
        else -> POPULAR
    }
}

@Composable
fun KazumiNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
    startDestination: String = Routes.POPULAR
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Routes.ONBOARDING) { OnboardingPage(navController) }
        composable(Routes.IMAGE_SEARCH) { ImageSearchPage(navController) }
        composable(Routes.POPULAR) { PopularPage(navController) }
        composable(Routes.SEARCH) { SearchPage(navController) }
        composable(Routes.TIMELINE) { TimelinePage(navController) }
        composable(Routes.COLLECT) { CollectPage(navController) }
        composable(Routes.DOWNLOAD) { DownloadPage(navController) }
        composable(Routes.SETTINGS) { SettingsPage(navController) }
        composable(Routes.MY) { MyPage(navController) }
        composable(Routes.HISTORY) { HistoryPage(navController) }
        composable(Routes.SETTINGS_PLAYER) { PlayerSettingsPage(navController) }
        composable(Routes.SETTINGS_DANMAKU) { DanmakuSettingsPage(navController) }
        composable(Routes.SETTINGS_KEYBOARD) { KeyboardSettingsPage(navController) }
        composable(Routes.SETTINGS_PLUGIN) { PluginSettingsPage(navController) }
        composable(Routes.SETTINGS_PLUGIN_SHOP) { PluginShopPage(navController) }
        composable(Routes.SETTINGS_DOWNLOAD) { DownloadSettingsPage(navController) }
        composable(Routes.SETTINGS_THEME) { ThemeSettingsPage(navController) }
        composable(Routes.SETTINGS_INTERFACE) { InterfaceSettingsPage(navController) }
        composable(Routes.SETTINGS_SYNC) { SyncSettingsPage(navController) }
        composable(Routes.SETTINGS_PROXY) { ProxySettingsPage(navController) }
        composable(Routes.SETTINGS_PROXY_EDITOR) { ProxyEditorPage(navController) }
        composable(Routes.SETTINGS_UPDATE) { UpdateSettingsPage(navController) }
        composable(Routes.SETTINGS_STORAGE) { StorageSettingsPage(navController) }
        composable(Routes.SETTINGS_LOGS) { LogsSettingsPage(navController) }
        composable(Routes.SETTINGS_ABOUT) { AboutSettingsPage(navController) }
        composable(Routes.SETTINGS_ABOUT_CREDITS) { CreditsPage(navController) }
        composable(Routes.ROUTE_ERROR) { RouteErrorPage(navController) }
        composable(Routes.DETAIL) { entry ->
            DetailPage(
                navController = navController,
                bangumiId = entry.arguments?.getString("bangumiId")?.toIntOrNull() ?: 0,
                pluginName = entry.arguments?.getString("pluginName").orEmpty(),
                src = entry.arguments?.getString("src").orEmpty()
            )
        }
        composable(Routes.PLAYER) { entry ->
            PlayerPage(
                navController = navController,
                bangumiId = entry.arguments?.getString("bangumiId")?.toIntOrNull() ?: 0,
                pluginName = entry.arguments?.getString("pluginName").orEmpty(),
                src = entry.arguments?.getString("src").orEmpty(),
                episode = entry.arguments?.getString("episode")?.toIntOrNull() ?: 1,
                road = entry.arguments?.getString("road")?.toIntOrNull() ?: 0,
                isLocal = entry.arguments?.getString("isLocal")?.toBoolean() ?: false,
                roadsJson = entry.arguments?.getString("roadsJson").orEmpty()
            )
        }
    }
}