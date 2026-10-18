package com.kazumi.port.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kazumi.port.data.model.Plugin
import com.kazumi.port.data.model.PluginCatalogItemStatus
import com.kazumi.port.data.model.PluginHTTPItem
import com.kazumi.port.data.model.PluginUpdateAvailability
import com.kazumi.port.data.model.PluginUpdateResult
import com.kazumi.port.data.model.SearchItem
import com.kazumi.port.engine.RuleEngine
import com.kazumi.port.data.model.SearchErrorException
import com.kazumi.port.data.model.NoResultException
import com.kazumi.port.data.model.CaptchaRequiredException
import com.kazumi.port.data.model.PluginSearchResponse
import com.kazumi.port.data.model.Road
import com.kazumi.port.data.model.RuleChapterTrace
import com.kazumi.port.data.model.RuleSearchTrace
import com.kazumi.port.network.PluginSiteClient
import com.kazumi.port.util.FileLogger
import com.kazumi.port.utils.VersionUtil
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件（规则）管理仓库
 *
 * 对应 Dart plugins_controller.dart + Plugin 业务方法
 */
class PluginRepository(
    private val context: android.content.Context
) {
    private val gson = Gson()
    private val ruleEngine = RuleEngine()

    /** 插件名(小写) -> 插件 */
    private val plugins = LinkedHashMap<String, Plugin>()

    // ---- 规则仓库缓存（对应原版 _pluginCatalogByName / pluginHTTPList / _pluginCatalogRefreshedAt）----
    @Volatile
    private var catalogByName: Map<String, PluginHTTPItem> = emptyMap()

    @Volatile
    private var catalogList: List<PluginHTTPItem> = emptyList()

    @Volatile
    private var catalogRefreshedAt: Long = 0L

    /** 规则仓库缓存有效期：5 分钟（对应原版 _pluginCatalogMaxAge） */
    private val catalogMaxAge = 5 * 60 * 1000L

    /** 是否使用规则仓库镜像（对应原版 enableGitProxy，默认 true 对齐原版） */
    private var gitProxyEnabled: Boolean = true

    private val catalogFresh: Boolean
        get() = catalogRefreshedAt > 0 &&
            System.currentTimeMillis() - catalogRefreshedAt < catalogMaxAge

    /** 内置默认插件 */
    private val defaultPlugins = listOf(
        "7sefun",
        "DM84"
    )

    private val pluginsDir: File
        get() = File(context.filesDir, "plugins").apply { mkdirs() }

    init {
        loadPlugins()
        if (plugins.isEmpty()) {
            importDefaultPlugins()
        }
    }

    /**
     * 确保内置规则已安装（对应原版 copyPluginsToExternalDirectory）
     * 供首次引导（onboarding）等场景调用
     */
    @Synchronized
    fun ensureBundledPluginsInstalled(): Boolean {
        if (plugins.isEmpty()) {
            importDefaultPlugins()
        }
        return plugins.isNotEmpty()
    }

    /**
     * 从 assets 导入内置插件
     */
    private fun importDefaultPlugins() {
        defaultPlugins.forEach { name ->
            try {
                val json = context.assets.open("plugins/$name.json")
                    .bufferedReader().use { it.readText() }
                val plugin = parsePluginJson(json)
                if (plugin != null) {
                    plugins[plugin.name.lowercase()] = plugin
                    savePlugin(plugin)
                }
            } catch (e: Exception) {
                // 内置插件缺失时忽略
            }
        }
    }

    private fun loadPlugins() {
        plugins.clear()
        val dir = pluginsDir
        if (!dir.exists()) return
        dir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val plugin = parsePluginJson(file.readText())
                if (plugin != null && plugin.name.isNotEmpty()) {
                    plugins[plugin.name.lowercase()] = plugin
                }
            } catch (e: Exception) {
                // 跳过损坏的插件文件
            }
        }
    }

    private fun parsePluginJson(json: String): Plugin? {
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            Plugin.fromJson(obj)
        } catch (e: Exception) {
            FileLogger.w("RuleImport", "parsePluginJson FAILED: ${e.javaClass.simpleName}: ${e.message} | head=${json.take(200)}")
            null
        }
    }

    @Synchronized
    fun allPlugins(): List<Plugin> = plugins.values.toList()

    @Synchronized
    fun getPlugin(name: String): Plugin? = plugins[name.lowercase()]

    @Synchronized
    fun addPlugin(plugin: Plugin) {
        plugins[plugin.name.lowercase()] = plugin
        savePlugin(plugin)
    }

    @Synchronized
    fun updatePlugin(plugin: Plugin) {
        addPlugin(plugin)
    }

    @Synchronized
    fun removePlugin(name: String) {
        val key = name.lowercase()
        plugins.remove(key)
        File(pluginsDir, "$key.json").delete()
        PluginSiteClient.clearCookies(key)
    }

    @Synchronized
    fun savePlugin(plugin: Plugin) {
        val fileName = "${plugin.name.lowercase()}.json"
        File(pluginsDir, fileName).writeText(
            gson.toJson(plugin.toJson())
        )
    }

    /**
     * 导入插件 JSON 字符串（规则分享）
     */
    @Synchronized
    fun importPlugin(json: String): Plugin? {
        val plugin = parsePluginJson(json) ?: run {
            FileLogger.e("RuleImport", "importPlugin: parsePluginJson 返回 null，JSON 无效 | head=${json.take(200)}")
            return null
        }
        if (plugin.name.isEmpty()) {
            FileLogger.e("RuleImport", "importPlugin: 规则名称为空")
            return null
        }
        plugins[plugin.name.lowercase()] = plugin
        try {
            savePlugin(plugin)
            FileLogger.i("RuleImport", "importPlugin OK: name=${plugin.name} version=${plugin.version}")
        } catch (e: Exception) {
            FileLogger.e("RuleImport", "importPlugin savePlugin FAILED: ${e.javaClass.simpleName}: ${e.message} | name=${plugin.name}")
        }
        return plugin
    }

    /**
     * 搜索番剧
     * 对应 Plugin.queryBangumi
     */
    suspend fun searchBangumi(
        pluginName: String,
        keyword: String
    ): PluginSearchResponse {
        val plugin = getPlugin(pluginName)
            ?: return PluginSearchResponse(pluginName, emptyList())
        return try {
            val trace = ruleEngine.search(plugin.executionConfig(), keyword)
            trace.response
        } catch (e: CaptchaRequiredException) {
            throw e
        } catch (e: NoResultException) {
            throw e
        } catch (e: SearchErrorException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 查询播放线路
     * 对应 Plugin.queryChapterRoads
     */
    suspend fun queryChapterRoads(
        pluginName: String,
        source: String
    ): List<Road> {
        val plugin = getPlugin(pluginName)
            ?: return emptyList()
        val trace = ruleEngine.queryChapters(plugin.executionConfig(), source)
        return trace.roads
    }

    /**
     * 获取插件请求头（播放/下载用）
     */
    fun buildHttpHeaders(pluginName: String): Map<String, String> {
        val plugin = getPlugin(pluginName)
        return plugin?.buildHttpHeaders() ?: emptyMap()
    }

    /**
     * 获取插件执行配置（供视频源解析使用）
     */
    fun executionConfig(pluginName: String): com.kazumi.port.data.model.RuleExecutionConfig? {
        return getPlugin(pluginName)?.executionConfig()
    }

    // ---- 规则仓库 API ----//

    /** 设置是否使用规则仓库镜像（对应原版 enableGitProxy） */
    fun setGitProxyEnabled(enabled: Boolean) {
        gitProxyEnabled = enabled
    }

    fun isGitProxyEnabled(): Boolean = gitProxyEnabled

    /** 规则仓库条目列表（缓存） */
    fun catalogItems(): List<PluginHTTPItem> = catalogList

    /** 规则仓库是否已刷新 */
    fun isCatalogFresh(): Boolean = catalogFresh

    /** 规则仓库是否可用（缓存非空） */
    fun hasCatalog(): Boolean = catalogList.isNotEmpty()

    /** 刷新规则仓库（强制） */
    @Synchronized
    fun refreshPluginCatalog(): List<PluginHTTPItem> {
        val items = PluginCatalogApi.getPluginList(gitProxyEnabled)
        catalogList = items
        catalogByName = items.associateBy { it.name.lowercase() }
        catalogRefreshedAt = System.currentTimeMillis()
        return items
    }

    /** 使用缓存（5 分钟内）否则刷新 */
    @Synchronized
    fun ensurePluginCatalog(): List<PluginHTTPItem> {
        if (catalogFresh && catalogList.isNotEmpty()) return catalogList
        return refreshPluginCatalog()
    }

    /** 远程版本是否更新（对应原版 _remoteIsNewer） */
    private fun remoteIsNewer(localVersion: String, remoteVersion: String): Boolean =
        VersionUtil.needUpdate(localVersion, remoteVersion)

    /** 单条规则更新状态（对应原版 pluginUpdateStatus） */
    fun pluginUpdateStatus(plugin: Plugin): PluginUpdateAvailability {
        if (catalogRefreshedAt == 0L) return PluginUpdateAvailability.UNKNOWN
        val remote = catalogByName[plugin.name.lowercase()] ?: return PluginUpdateAvailability.NOT_IN_CATALOG
        return if (remoteIsNewer(plugin.version, remote.version)) {
            PluginUpdateAvailability.UPDATABLE
        } else {
            PluginUpdateAvailability.LATEST
        }
    }

    /** 仓库条目相对本地的状态（对应原版 pluginStatus） */
    fun pluginStatus(item: PluginHTTPItem): PluginCatalogItemStatus {
        val local = getPlugin(item.name) ?: return PluginCatalogItemStatus.INSTALL
        return if (remoteIsNewer(local.version, item.version)) {
            PluginCatalogItemStatus.UPDATE
        } else {
            PluginCatalogItemStatus.INSTALLED
        }
    }

    /** 更新单个规则（对应原版 tryUpdatePluginByName 的下载+替换逻辑) */
    suspend fun tryUpdatePluginByName(name: String): PluginUpdateResult {
        val catalogName = catalogByName[name.lowercase()]?.name ?: name
        FileLogger.i("RuleRepo", "tryUpdatePluginByName start: name=$name catalogName=$catalogName mirror=$gitProxyEnabled")
        val remote = runCatching { PluginCatalogApi.getPlugin(catalogName, gitProxyEnabled) }
            .onFailure { e ->
                FileLogger.e("RuleRepo", "tryUpdatePluginByName getPlugin 失败: ${e.javaClass.simpleName}: ${e.message} | name=$name", e)
            }
            .getOrNull() ?: return PluginUpdateResult.FAILED
        if (remote.name.isEmpty() || remote.name.lowercase() != name.lowercase()) {
            FileLogger.w("RuleRepo", "tryUpdatePluginByName: 规则名不匹配 remote=${remote.name} 期望=$name")
            return PluginUpdateResult.FAILED
        }
        remote.name = name
        if (remote.requiresNewerClient) {
            FileLogger.w("RuleRepo", "tryUpdatePluginByName: $name 需要更高版本客户端")
            return PluginUpdateResult.REQUIRES_NEWER_CLIENT
        }
        val local = getPlugin(name)
        if (local != null && !remoteIsNewer(local.version, remote.version)) {
            FileLogger.d("RuleRepo", "tryUpdatePluginByName: $name 远程版本不高于本地 local=${local.version} remote=${remote.version}")
            return PluginUpdateResult.NOT_NEWER
        }
        addPlugin(remote)
        FileLogger.i("RuleRepo", "tryUpdatePluginByName OK: $name v${remote.version}")
        return PluginUpdateResult.UPDATED
    }

    /** 更新全部可更新规则（对应原版 tryUpdateAllPlugin） */
    suspend fun tryUpdateAllPlugin(): PluginBatchUpdateResult {
        val candidates = allPlugins().filter {
            pluginUpdateStatus(it) == PluginUpdateAvailability.UPDATABLE
        }
        if (candidates.isEmpty()) return PluginBatchUpdateResult(updated = 0)
        var updated = 0
        var failed = 0
        var requiresNewer = 0
        var notNewer = 0
        candidates.forEach { plugin ->
            when (tryUpdatePluginByName(plugin.name)) {
                PluginUpdateResult.UPDATED -> updated++
                PluginUpdateResult.REQUIRES_NEWER_CLIENT -> requiresNewer++
                PluginUpdateResult.NOT_NEWER -> notNewer++
                PluginUpdateResult.FAILED -> failed++
            }
        }
        return PluginBatchUpdateResult(updated, failed, requiresNewer, notNewer)
    }

    /** 排序：上移/下移（对应原版 onReorder，持久化顺序） */
    @Synchronized
    fun movePlugin(name: String, direction: Int) {
        val keys = plugins.keys.toList()
        val idx = keys.indexOf(name.lowercase())
        if (idx < 0) return
        val newIdx = idx + direction
        if (newIdx < 0 || newIdx >= keys.size) return
        val entry = plugins.entries.elementAt(idx)
        val list = plugins.entries.toMutableList()
        list.removeAt(idx)
        list.add(newIdx, entry)
        plugins.clear()
        list.forEach { plugins[it.key] = it.value }
        // 持久化顺序（重写 plugins.json）
        persistOrder()
    }

    /** 将当前顺序持久化到单一 plugins.json（对应原版 _writePluginsJson） */
    @Synchronized
    fun persistOrder() {
        val json = gson.toJson(plugins.values.map { it.toJson() })
        File(pluginsDir, "plugins.json").writeText(json)
    }

    /** 删除多条规则（对应原版 removePlugins） */
    @Synchronized
    fun removePlugins(names: Set<String>) {
        names.forEach { removePlugin(it) }
    }

    /** 批量更新结果 */
    data class PluginBatchUpdateResult(
        val updated: Int = 0,
        val failed: Int = 0,
        val requiresNewerClient: Int = 0,
        val notNewer: Int = 0
    ) {
        val hasNoCandidates: Boolean get() = updated == 0 && failed == 0 && requiresNewerClient == 0 && notNewer == 0
    }

    /** 检查启动时规则更新（对应原版 checkPluginUpdatesOnStartup） */
    fun checkPluginUpdatesOnStartup(enabled: Boolean): Int {
        if (!enabled) return 0
        return try {
            refreshPluginCatalog()
            allPlugins().count { pluginUpdateStatus(it) == PluginUpdateAvailability.UPDATABLE }
        } catch (_: Exception) {
            0
        }
    }
}