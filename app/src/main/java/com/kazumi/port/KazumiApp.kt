package com.kazumi.port

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kazumi.port.data.db.KazumiDatabase
import com.kazumi.port.data.repository.BangumiApi
import com.kazumi.port.data.repository.CollectRepository
import com.kazumi.port.data.repository.DownloadRepository
import com.kazumi.port.data.repository.HistoryRepository
import com.kazumi.port.data.repository.PluginRepository
import com.kazumi.port.data.repository.SearchHistoryRepository
import com.kazumi.port.data.repository.SettingsManager
import com.kazumi.port.network.BangumiClient
import com.kazumi.port.network.HttpClientFactory
import com.kazumi.port.network.ImageAcceleration
import com.kazumi.port.network.ImageAccelerationInterceptor
import com.kazumi.port.service.DownloadController
import com.kazumi.port.service.DownloadDirectoryService
import com.kazumi.port.service.DownloadManager
import com.kazumi.port.sync.BangumiSyncService
import com.kazumi.port.sync.WebDavSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Kazumi Application - Kotlin 原生重写版
 *
 * 对应原 Flutter 版 main.dart / core_module.dart
 */
class KazumiApp : Application(), ImageLoaderFactory {

    lateinit var settings: SettingsManager
        private set
    lateinit var pluginRepository: PluginRepository
        private set
    lateinit var database: KazumiDatabase
        private set
    lateinit var historyRepository: HistoryRepository
        private set
    lateinit var collectRepository: CollectRepository
        private set
    lateinit var searchHistoryRepository: SearchHistoryRepository
        private set
    lateinit var downloadRepository: DownloadRepository
        private set
    lateinit var downloadDirectoryService: DownloadDirectoryService
        private set
    lateinit var downloadManager: DownloadManager
        private set
    lateinit var downloadController: DownloadController
        private set
    lateinit var webDavSyncService: WebDavSyncService
        private set
    lateinit var bangumiSyncService: BangumiSyncService
        private set

    /** 当前图片加速模式（内存缓存，供 Coil 拦截器零延迟读取） */
    @Volatile
    var currentImageAcceleration: ImageAcceleration = ImageAcceleration.ECH
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private lateinit var instance: KazumiApp

        fun get(): KazumiApp = instance

        fun appContext(): Context = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化文件日志（写入 Android/data/com.kazumi.port/files/logs/）
        com.kazumi.port.util.FileLogger.init(this)
        settings = SettingsManager(this)
        database = KazumiDatabase.getInstance(this)
        pluginRepository = PluginRepository(this)
        historyRepository = HistoryRepository(database.historyDao())
        collectRepository = CollectRepository(database.collectDao())
        searchHistoryRepository = SearchHistoryRepository(database.searchHistoryDao())
        downloadRepository = DownloadRepository(database.downloadDao(), settings)
        downloadDirectoryService = DownloadDirectoryService(this, settings)
        downloadManager = DownloadManager(
            settings = settings,
            downloadDao = database.downloadDao(),
            directoryService = downloadDirectoryService
        )
        downloadController = DownloadController(
            context = this,
            repository = downloadRepository,
            downloadManager = downloadManager,
            plugins = pluginRepository.allPlugins()
        )
        webDavSyncService = WebDavSyncService(this, settings)
        bangumiSyncService = BangumiSyncService(
            settings = settings,
            collectRepository = collectRepository,
            bangumiApi = BangumiApi(BangumiClient(settings))
        )
        // 预构建全局 OkHttp 客户端
        HttpClientFactory.init(this)
        // 应用代理设置（对应原版 NetworkConfig.fromSettings()：proxyEnable/proxyUrl
        // 会作用于规则仓库 / Bangumi / 图片等所有走全局 OkHttp 的请求）
        appScope.launch {
            val proxyEnable = settings.getBoolean(SettingsManager.Proxy.PROXY_ENABLE, false)
            if (proxyEnable) {
                val proxyUrl = settings.getString(SettingsManager.Proxy.PROXY_URL, "")
                val parsed = com.kazumi.port.util.ProxyUtils.parseProxyUrl(proxyUrl)
                if (parsed != null) {
                    HttpClientFactory.applyProxy(parsed.first, parsed.second)
                }
            }
        }
        // 同步规则仓库镜像开关到 PluginRepository
        appScope.launch {
            // 规则仓库镜像默认开启（对齐原版 enableGitProxy 默认 true）
            val gitProxy = settings.getBoolean(SettingsManager.Proxy.ENABLE_GIT_PROXY, true)
            pluginRepository.setGitProxyEnabled(gitProxy)
        }
        // 读取图片加速模式（默认 ech，与原版一致；ECH native 桥 v3 已修 ack+重定向，
        // 实测 lain.bgm.tv 直链 200，mirror 在你网络下镜像站全挂反而卡死）
        appScope.launch {
            currentImageAcceleration =
                ImageAcceleration.fromSetting(settings.getString(SettingsManager.Proxy.IMAGE_ACCELERATION, "ech"))
        }
    }

    /** 更新图片加速模式（设置页切换时调用） */
    fun updateImageAcceleration(mode: ImageAcceleration) {
        currentImageAcceleration = mode
    }

    /** Coil 全局 ImageLoader：注入图片加速拦截器（direct/ech/mirror）+ 复用全局 OkHttp 客户端 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // 复用全局 OkHttp（随机浏览器 UA + 5s/8s 短超时 + 代理）：
            // 通过 okhttp3.Call.Factory 接口每次发请求取当前 client，
            // 保证代理切换（HttpClientFactory.applyProxy）后图片立即走新网络栈。
            .callFactory { request -> HttpClientFactory.getClient().newCall(request) }
            // 磁盘缓存：图片首次加载后落盘，二次进入秒开（原版 Kazumi 有图片缓存设置，
            // 移植版必须开，否则每次进列表都重新下载 → 慢）
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_disk"))
                    .maxSizeBytes(512L * 1024 * 1024) // 512MB，可存大量封面
                    .build()
            }
            // 内存缓存默认开启（Coil 默认 memoryCache 已启用，这里显式保障）
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .components {
                add(ImageAccelerationInterceptor { currentImageAcceleration })
            }
            .crossfade(true)
            .build()
}