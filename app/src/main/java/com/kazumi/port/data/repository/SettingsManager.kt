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
        /** 编辑弹幕时自动暂停播放（原版 pauseOnDanmakuEdit，移动端默认开） */
        const val PAUSE_ON_EDIT = "pauseOnDanmakuEdit"
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

        /** 引导完成标记（原版键名 onboardingCompleted；从原版迁移的用户不会再次看到引导） */
        const val ONBOARDING_COMPLETED = "onboardingCompleted"
        /** 旧版本本移植版使用过的键名，仅用于兼容读取 */
        const val LEGACY_ONBOARDING_DONE = "onboardingDone"
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
        /** 应用本体自动更新（原版 autoUpdate，默认开） */
        const val AUTO_UPDATE = "autoUpdate"
    }

    /** 其他设置 */
    object Misc {
        const val SEARCH_ENHANCE_ENABLE = "searchEnhanceEnable"
        const val ALWAYS_ON_TOP = "alwaysOntop"
        const val SHORTCUT_DIALOG_SHOWN = "shortcutDialogShown"
    }

    // ---- 类型化读写 API ----

    /**
     * 与原版 lib/services/storage/settings_keys.dart 一一对应的默认值表（104 项，全量抄录）。
     *
     * 为什么要有这张表：原版的默认值定义在 SettingKey 上，读设置时不需要调用方再传默认值；
     * 移植版早期把默认值散落在各页面的 getXxx(key, default) 里，导致同一个开关在不同页面
     * 默认值不一致（有的开、有的关），从原版迁移过来的用户会看到"行为不一样"。
     * 现在统一由这张表兜底：调用方传的 default 只在表里没有该键时才生效。
     */
    object Defaults {

        val boolean: Map<String, Boolean> = mapOf(
            // 默认开
            "hAenable" to true,
            "searchEnhanceEnable" to true,
            "autoUpdate" to true,
            "checkPluginUpdateOnStartup" to true,
            "danmakuEnhance" to true,
            "pauseOnDanmakuEdit" to true,
            "danmakuBorder" to true,
            "danmakuTop" to true,
            "danmakuScroll" to true,
            "danmakuColor" to true,
            "danmakuBiliBiliSource" to true,
            "danmakuGamerSource" to true,
            "danmakuDanDanSource" to true,
            "danmakuFollowSpeed" to true,
            "autoPlay" to true,
            "autoPlayNext" to true,
            "playResume" to true,
            "enableGitProxy" to true,
            "enableBangumiProxy" to true,
            "androidEnableOpenSLES" to true,
            "showRating" to true,
            "downloadDanmaku" to true,
            "bangumiImmediateSyncToastEnable" to true,
            "brightnessVolumeGesture" to true,
            // 默认关
            "alwaysOntop" to false,
            "danmakuBottom" to false,
            "danmakuMassive" to false,
            "danmakuDeduplication" to false,
            "danmakuEnabledByDefault" to false,
            "privateMode" to false,
            "showPlayerError" to false,
            "oledEnhance" to false,
            "enableSystemProxy" to false,
            "webDavEnable" to false,
            "webDavEnableHistory" to false,
            "webDavEnableCollect" to false,
            "webDavEnableDanmakuShield" to false,
            "lowMemoryMode" to false,
            "showWindowButton" to false,
            "useDynamicColor" to false,
            "playerDebugMode" to false,
            "androidAutoEnterPIP" to false,
            "superResolutionWarn" to false,
            "playerDisableAnimations" to false,
            "timelineNotShowAbandonedBangumis" to false,
            "timelineNotShowWatchedBangumis" to false,
            "timelineOnlyShowWatchingBangumis" to false,
            "useSystemFont" to false,
            "forceAdBlocker" to false,
            "backgroundPlayback" to false,
            "proxyEnable" to false,
            "proxyConfigured" to false,
            "shortcutDialogShown" to false,
            "onboardingCompleted" to false,
            "bangumiSyncEnable" to false,
            "historySyncSnapshotInitialized" to false,
            "playerMuted" to false
        )

        val int: Map<String, Int> = mapOf(
            "defaultAspectRatioType" to 1,
            "buttonSkipTime" to 80,
            "arrowKeySkipTime" to 10,
            "danmakuChConvert" to 0,
            "danmakuFontWeight" to 4,
            "exitBehavior" to 2,
            "defaultSuperResolutionType" to 1,
            "playerLogLevel" to 2,
            "downloadParallelEpisodes" to 2,
            "downloadParallelSegments" to 3,
            "bangumiSyncPriority" to 0,
            "historySyncSequence" to 0,
            "playerControllerLayerDisappearTime" to 4000
        )

        val double: Map<String, Double> = mapOf(
            "defaultPlaySpeed" to 1.0,
            "defaultShortcutForwardPlaySpeed" to 2.0,
            "danmakuBorderSize" to 1.5,
            "danmakuOpacity" to 1.0,
            "danmakuFontSize" to 25.0,
            "danmakuArea" to 1.0,
            "danmakuDuration" to 8.0,
            "danmakuLineHeight" to 1.6,
            "danmakuTimeOffset" to 0.0,
            "defaultVolume" to 100.0
        )

        val string: Map<String, String> = mapOf(
            "hardwareDecoder" to "auto-safe",
            "themeMode" to "system",
            "themeColor" to "default",
            "imageAcceleration" to "ech",
            "bangumiAcceleration" to "",
            "defaultStartupPage" to "/tab/popular/",
            "webDavURL" to "",
            "webDavUsername" to "",
            "webDavPasswd" to "",
            "syncPlayEndPoint" to "syncplay.pl:8996",
            "syncPlayUserName" to "",
            "androidVideoRenderer" to "auto",
            "proxyUrl" to "",
            "proxyTestUrl" to "",
            "defaultCollectLayout" to "list",
            "downloadDirectory" to "",
            "downloadDirectoryBookmark" to "",
            "bangumiAccessToken" to "",
            "danmakuShieldSyncDeviceId" to "",
            "danmakuShieldSyncState" to "",
            "danmakuShieldSyncCorruptState" to "",
            "historySyncDeviceId" to ""
        )

        /** 原版 displayMode / lowMemoryPolicy 默认是 null（未选择），用空串表示"未设置" */
        fun stringOf(key: String): String = string[key] ?: ""
    }

    /** 原版 compactLayout = shortestSide < 600dp：手机上弹幕默认字号是 16 而不是 25 */
    private fun isCompactLayout(): Boolean {
        val c = context.resources.configuration
        return minOf(c.screenWidthDp, c.screenHeightDp) < 600
    }

    suspend fun getBoolean(key: String, default: Boolean = false): Boolean {
        val k = booleanPreferencesKey(key)
        return context.kazumiDataStore.data.first()[k] ?: Defaults.boolean[key] ?: default
    }

    fun booleanFlow(key: String, default: Boolean = false): Flow<Boolean> {
        val k = booleanPreferencesKey(key)
        val fallback = Defaults.boolean[key] ?: default
        return context.kazumiDataStore.data.map { it[k] ?: fallback }
    }

    suspend fun setBoolean(key: String, value: Boolean) {
        val k = booleanPreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    suspend fun getInt(key: String, default: Int = 0): Int {
        val k = intPreferencesKey(key)
        return context.kazumiDataStore.data.first()[k] ?: Defaults.int[key] ?: default
    }

    fun intFlow(key: String, default: Int = 0): Flow<Int> {
        val k = intPreferencesKey(key)
        val fallback = Defaults.int[key] ?: default
        return context.kazumiDataStore.data.map { it[k] ?: fallback }
    }

    suspend fun setInt(key: String, value: Int) {
        val k = intPreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    /** 默认值解析：原版 danmakuFontSize 带 defaultResolver（compactLayout 时 16），其余直接查表 */
    private fun doubleDefault(key: String, default: Double): Double =
        if (key == Danmaku.FONT_SIZE) {
            if (isCompactLayout()) 16.0 else 25.0
        } else {
            Defaults.double[key] ?: default
        }

    suspend fun getDouble(key: String, default: Double = 0.0): Double {
        val k = doublePreferencesKey(key)
        val value = context.kazumiDataStore.data.first()[k]
        if (value != null) return value
        // 兼容：旧版本 PlayerPage 曾用 getInt 读/写过字号，若 DataStore 里残留同名的 Int 值则一并认
        if (key == Danmaku.FONT_SIZE) {
            val legacy = context.kazumiDataStore.data.first()[intPreferencesKey(key)]
            if (legacy != null) return legacy.toDouble()
        }
        return doubleDefault(key, default)
    }

    fun doubleFlow(key: String, default: Double = 0.0): Flow<Double> {
        val k = doublePreferencesKey(key)
        val fallback = doubleDefault(key, default)
        return context.kazumiDataStore.data.map { it[k] ?: fallback }
    }

    suspend fun setDouble(key: String, value: Double) {
        val k = doublePreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    suspend fun getString(key: String, default: String = ""): String {
        val k = stringPreferencesKey(key)
        return context.kazumiDataStore.data.first()[k] ?: Defaults.string[key] ?: default
    }

    fun stringFlow(key: String, default: String = ""): Flow<String> {
        val k = stringPreferencesKey(key)
        val fallback = Defaults.string[key] ?: default
        return context.kazumiDataStore.data.map { it[k] ?: fallback }
    }

    suspend fun setString(key: String, value: String) {
        val k = stringPreferencesKey(key)
        context.kazumiDataStore.edit { it[k] = value }
    }

    /**
     * Bangumi 加速模式解析（对齐原版 BangumiAcceleration.current）：
     * 存的是 direct/ech/mirror 就用它；否则沿用旧开关 enableBangumiProxy（默认开 → 镜像）。
     *
     * 注意：不能直接 getString(BANGUMI_ACCELERATION, "mirror") —— Defaults 表里该键的默认值
     * 是空串（原版 SettingsKey 默认值就是 ''，语义是“未设置”），空串会被当作最终值返回，
     * 从而把镜像模式误判成关闭，热门页改走 next.bgm.tv 直连导致加载失败。
     */
    suspend fun bangumiAcceleration(): String {
        val raw = getString(Proxy.BANGUMI_ACCELERATION, "")
        return when (raw) {
            "direct", "ech", "mirror" -> raw
            else -> if (getBoolean(Proxy.ENABLE_BANGUMI_PROXY, true)) "mirror" else "direct"
        }
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
        // 同一键名可能以不同类型存在（历史遗留），逐个类型清一遍
        context.kazumiDataStore.edit {
            it.remove(booleanPreferencesKey(key))
            it.remove(intPreferencesKey(key))
            it.remove(doublePreferencesKey(key))
            it.remove(stringPreferencesKey(key))
            it.remove(stringSetPreferencesKey(key))
        }
    }

    /**
     * 批量清空设置键，使其回落到 Defaults 表里的默认值。
     * 对应原版 GStorage.resetSettings(keys)：原版"恢复默认"就是删键，而不是写死一份值。
     */
    suspend fun resetKeys(keys: List<String>) {
        context.kazumiDataStore.edit {
            keys.forEach { key ->
                it.remove(booleanPreferencesKey(key))
                it.remove(intPreferencesKey(key))
                it.remove(doublePreferencesKey(key))
                it.remove(stringPreferencesKey(key))
                it.remove(stringSetPreferencesKey(key))
            }
        }
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
        /** 原版默认 SyncPlay 服务器（lib/services/player/syncplay_endpoint.dart） */
        const val DEFAULT_SYNC_PLAY_ENDPOINT = "syncplay.pl:8996"
        const val DEFAULT_STARTUP_PAGE = "/tab/popular/"
    }
}