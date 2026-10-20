package com.kazumi.port.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 委托必须定义在文件顶层（官方推荐）：
 *  若放在类内部，每 new 一个 SettingsManager 都会创建指向同一文件的新 DataStore 实例，
 *  并发读写会抛 "There are multiple DataStores active for the same file"。
 *  移到顶层后所有 SettingsManager 实例共享同一个 DataStore。 */
private val Context.kazumiDataStore by preferencesDataStore(name = "kazumi_settings")

/**
 * 设置管理器
 *
 * 对应 Dart GStorage + SettingsKeys
 * 使用 DataStore Preferences 持久化，键名与原版 Hive 键完全一致，
 * 保证规则文件/设置语义兼容。
 */
class SettingsManager(private val context: Context) {


    /** 播放器设置 */
    object Player {
        const val HA_ENABLE = "hAenable"
        const val HARDWARE_DECODER = "hardwareDecoder"
        const val DEFAULT_PLAY_SPEED = "defaultPlaySpeed"
        const val DEFAULT_SHORTCUT_FORWARD_SPEED = "defaultShortcutForwardPlaySpeed"
        const val DEFAULT_ASPECT_RATIO_TYPE = "defaultAspectRatioType"
        const val BUTTON_SKIP_TIME = "buttonSkipTime"
        const val ARROW_KEY_SKIP_TIME = "arrowKeySkipTime"
        const val PRIVATE_MODE = "privateMode"
        const val AUTO_PLAY = "autoPlay"
        const val AUTO_PLAY_NEXT = "autoPlayNext"
        const val PLAY_RESUME = "playResume"
        const val SHOW_PLAYER_ERROR = "showPlayerError"
        const val LOW_MEMORY_MODE = "lowMemoryMode"
        const val LOW_MEMORY_POLICY = "lowMemoryPolicy"
        const val PLAYER_DEBUG_MODE = "playerDebugMode"
        const val SYNC_PLAY_END_POINT = "syncPlayEndPoint"
        const val SYNC_PLAY_USER_NAME = "syncPlayUserName"
        const val ANDROID_ENABLE_OPENSLES = "androidEnableOpenSLES"
        const val ANDROID_VIDEO_RENDERER = "androidVideoRenderer"
        const val ANDROID_AUTO_ENTER_PIP = "androidAutoEnterPIP"
        const val DEFAULT_SUPER_RESOLUTION_MODE = "defaultSuperResolutionType"
        const val DISABLE_SUPER_RESOLUTION_WARNING = "superResolutionWarn"
        const val PLAYER_DISABLE_ANIMATIONS = "playerDisableAnimations"
        const val PLAYER_LOG_LEVEL = "playerLogLevel"
        const val FORCE_AD_BLOCKER = "forceAdBlocker"
        const val BACKGROUND_PLAYBACK = "backgroundPlayback"
        const val BRIGHTNESS_VOLUME_GESTURE = "brightnessVolumeGesture"
        const val PLAYER_CONTROLLER_LAYER_DISAPPEAR_TIME = "playerControllerLayerDisappearTime"
        const val DEFAULT_VOLUME = "defaultVolume"
        const val PLAYER_MUTED = "playerMuted"
    }

    /** 弹幕设置 */
    object Danmaku {
        const val ENHANCE = "danmakuEnhance"
        const val BORDER = "danmakuBorder"
        const val BORDER_SIZE = "danmakuBorderSize"
        const val OPACITY = "danmakuOpacity"
        const val FONT_SIZE = "danmakuFontSize"
        const val TOP = "danmakuTop"
        const val SCROLL = "danmakuScroll"
        const val BOTTOM = "danmakuBottom"
        const val MASSIVE = "danmakuMassive"
        const val DEDUPLICATION = "danmakuDeduplication"
        const val CH_CONVERT = "danmakuChConvert"
        const val AREA = "danmakuArea"
        const val COLOR = "danmakuColor"
        const val DURATION = "danmakuDuration"
        const val LINE_HEIGHT = "danmakuLineHeight"
        const val TIME_OFFSET = "danmakuTimeOffset"
        const val ENABLED_BY_DEFAULT = "danmakuEnabledByDefault"
        const val BILI_SOURCE = "danmakuBiliBiliSource"
        const val GAMER_SOURCE = "danmakuGamerSource"
        const val DAN_DAN_SOURCE = "danmakuDanDanSource"

        /** 弹弹play 开放弹幕网络凭据（用户自填，对应上游 CI 的 DANDANAPI_APPID / DANDANAPI_KEY） */
        const val DAN_DAN_APP_ID = "danmakuDanDanAppId"
        const val DAN_DAN_APP_SECRET = "danmakuDanDanAppSecret"
        const val FONT_WEIGHT = "danmakuFontWeight"
        const val FOLLOW_SPEED = "danmakuFollowSpeed"
    }

    /** 主题设置 */
    object Theme {
        const val MODE = "themeMode"
        const val COLOR = "themeColor"
        const val OLED_ENHANCE = "oledEnhance"
        const val SHOW_WINDOW_BUTTON = "showWindowButton"
        const val USE_DYNAMIC_COLOR = "useDynamicColor"
        const val USE_SYSTEM_FONT = "useSystemFont"
    }

    /** 界面设置 */
    object Interface {
        const val DISPLAY_MODE = "displayMode"
        const val DEFAULT_STARTUP_PAGE = "defaultStartupPage"
        const val EXIT_BEHAVIOR = "exitBehavior"
        const val SHOW_RATING = "showRating"
        const val DEFAULT_COLLECT_LAYOUT = "defaultCollectLayout"

        /** 首次引导是否已完成（Kotlin 侧新增，用于启动分流；原版按 pluginList 是否为空判断） */
        const val ONBOARDING_DONE = "onboardingDone"
    }

    /** 代理设置 */
    object Proxy {
        const val ENABLE_GIT_PROXY = "enableGitProxy"
        const val ENABLE_BANGUMI_PROXY = "enableBangumiProxy"
        const val IMAGE_ACCELERATION = "imageAcceleration"
        const val BANGUMI_ACCELERATION = "bangumiAcceleration"
        const val ENABLE_SYSTEM_PROXY = "enableSystemProxy"
        const val PROXY_ENABLE = "proxyEnable"
        const val PROXY_CONFIGURED = "proxyConfigured"
        const val PROXY_URL = "proxyUrl"
        const val PROXY_TEST_URL = "proxyTestUrl"
    }

    /** WebDAV 设置 */
    object WebDav {
        const val ENABLE = "webDavEnable"
        const val ENABLE_HISTORY = "webDavEnableHistory"
        const val ENABLE_COLLECT = "webDavEnableCollect"
        const val ENABLE_DANMAKU_SHIELD = "webDavEnableDanmakuShield"
        const val URL = "webDavURL"
        const val USERNAME = "webDavUsername"
        const val PASSWORD = "webDavPasswd"
    }

    /** 下载设置 */
    object Download {
        const val PARALLEL_EPISODES = "downloadParallelEpisodes"
        const val PARALLEL_SEGMENTS = "downloadParallelSegments"
        const val DANMAKU = "downloadDanmaku"
        const val DIRECTORY = "downloadDirectory"
        const val DIRECTORY_BOOKMARK = "downloadDirectoryBookmark"
    }

    /** Bangumi 同步设置 */
    object Bangumi {
        const val SYNC_ENABLE = "bangumiSyncEnable"
        const val ACCESS_TOKEN = "bangumiAccessToken"
        const val SYNC_PRIORITY = "bangumiSyncPriority"
        const val IMMEDIATE_SYNC_TOAST_ENABLE = "bangumiImmediateSyncToastEnable"
    }

    /** 收藏相关设置 */
    object Collect {
        const val TIMELINE_NOT_SHOW_ABANDONED = "timelineNotShowAbandonedBangumis"
        const val TIMELINE_NOT_SHOW_WATCHED = "timelineNotShowWatchedBangumis"
        const val TIMELINE_ONLY_SHOW_WATCHING = "timelineOnlyShowWatchingBangumis"
    }

    /** 同步设置 */
    object Sync {
        const val HISTORY_DEVICE_ID = "historySyncDeviceId"
        const val HISTORY_SEQUENCE = "historySyncSequence"
        const val HISTORY_SNAPSHOT_INITIALIZED = "historySyncSnapshotInitialized"
        const val DANMAKU_SHIELD_DEVICE_ID = "danmakuShieldSyncDeviceId"
        const val DANMAKU_SHIELD_STATE = "danmakuShieldSyncState"
        const val DANMAKU_SHIELD_CORRUPT_STATE = "danmakuShieldSyncCorruptState"
    }

    /** 更新设置 */
    object Update {
        const val CHECK_PLUGIN_UPDATE_ON_STARTUP = "checkPluginUpdateOnStartup"
    }

    /** 其他设置 */
    object Misc {
        const val SEARCH_ENHANCE_ENABLE = "searchEnhanceEnable"
        const val ALWAYS_ON_TOP = "alwaysOntop"
        const val SHORTCUT_DIALOG_SHOWN = "shortcutDialogShown"
    }

    // ---- 类型化读写 API ----

    suspend fun getBoolean(key: String, default: Boolean = false): Boolean {
        val k = booleanPreferencesKey(key)
        return context.kazumiDataStore.data.first()[k] ?: default
    }

    fun booleanFlow(key: String, default: Boolean = false): Flow<Boolean> {
        val k = booleanPreferencesKey(key)
        return context.kazumiDataStore.data.map { it[k] ?: default }
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        val k = booleanPreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    suspend fun getInt(key: String, default: Int = 0): Int {
        val k = intPreferencesKey(key)
        return context.kazumiDataStore.data.first()[k] ?: default
    }

    fun intFlow(key: String, default: Int = 0): Flow<Int> {
        val k = intPreferencesKey(key)
        return context.kazumiDataStore.data.map { it[k] ?: default }
    }

    suspend fun setInt(key: String, value: Int) {
        val k = intPreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    suspend fun getDouble(key: String, default: Double = 0.0): Double {
        val k = doublePreferencesKey(key)
        return context.kazumiDataStore.data.first()[k] ?: default
    }

    fun doubleFlow(key: String, default: Double = 0.0): Flow<Double> {
        val k = doublePreferencesKey(key)
        return context.kazumiDataStore.data.map { it[k] ?: default }
    }

    suspend fun setDouble(key: String, value: Double) {
        val k = doublePreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    suspend fun getString(key: String, default: String = ""): String {
        val k = stringPreferencesKey(key)
        return context.kazumiDataStore.data.first()[k] ?: default
    }

    fun stringFlow(key: String, default: String = ""): Flow<String> {
        val k = stringPreferencesKey(key)
        return context.kazumiDataStore.data.map { it[k] ?: default }
    }

    suspend fun setString(key: String, value: String) {
        val k = stringPreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    suspend fun getStringList(key: String, default: List<String> = emptyList()): List<String> {
        val k = stringSetPreferencesKey(key)
        return context.kazumiDataStore.data.first()[k]?.toList() ?: default
    }

    suspend fun setStringList(key: String, value: List<String>) {
        val k = stringSetPreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value.toSet() }
    }

    suspend fun remove(key: String) {
        val k = stringPreferencesKey(key)
        context.kazumiDataStore.edit { it.remove(k) }
    }

    // ---- 常用设置便捷访问 ----

    suspend fun defaultPlaySpeed(): Double =
        getDouble(Player.DEFAULT_PLAY_SPEED, 1.0)

    suspend fun isPrivateMode(): Boolean =
        getBoolean(Player.PRIVATE_MODE, false)

    suspend fun autoPlay(): Boolean =
        getBoolean(Player.AUTO_PLAY, true)

    suspend fun autoPlayNext(): Boolean =
        getBoolean(Player.AUTO_PLAY_NEXT, true)

    suspend fun playResume(): Boolean =
        getBoolean(Player.PLAY_RESUME, true)

    suspend fun themeMode(): String =
        getString(Theme.MODE, "system")

    suspend fun themeColor(): String =
        getString(Theme.COLOR, "default")

    suspend fun useDynamicColor(): Boolean =
        getBoolean(Theme.USE_DYNAMIC_COLOR, false)

    suspend fun downloadDirectory(): String =
        getString(Download.DIRECTORY, "")

    suspend fun webDavUrl(): String =
        getString(WebDav.URL, "")

    suspend fun webDavUsername(): String =
        getString(WebDav.USERNAME, "")

    suspend fun webDavPassword(): String =
        getString(WebDav.PASSWORD, "")

    suspend fun webDavEnabled(): Boolean =
        getBoolean(WebDav.ENABLE, false)

    suspend fun bangumiAccessToken(): String =
        getString(Bangumi.ACCESS_TOKEN, "")

    suspend fun bangumiSyncEnabled(): Boolean =
        getBoolean(Bangumi.SYNC_ENABLE, false)

    companion object {
        const val DEFAULT_SYNC_PLAY_ENDPOINT = "https://api.syncplay.cc"
        const val DEFAULT_STARTUP_PAGE = "/tab/popular/"
    }
}