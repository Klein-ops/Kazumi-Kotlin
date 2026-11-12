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

    // ---- 搜索/线路内存缓存（TTL 10 分钟，对齐原版页内共享查询结果）----
    // 原版从搜索/推荐等入口点进详情页时，搜索结果的 src 一路传下来直接查线路；
    // 我们从详情页按番剧名反搜所有插件，每次进页都重新"搜索+查线路"两次网络请求，
    // 无缓存 → 线路加载慢（10s+）。这里缓存 插件名+关键词 的搜索命中 和 src 的线路，
    // 重复进入同一详情页秒出线路（TTL 内不重新请求）。
    private data class CacheEntry<T>(val value: T, val expiredAt: Long)

    @Volatile
    private var searchCache: Map<String, CacheEntry<List<SearchItem>>> = emptyMap()

    @Volatile
    private var roadsCache: Map<String, CacheEntry<List<Road>>> = emptyMap()

    private val cacheTtlMs = 10 * 60 * 1000L

    private fun <T> isCacheFresh(entry: CacheEntry<T>?): Boolean =
        entry != null && System.currentTimeMillis() < entry.expiredAt

    // ---- 规则仓库缓存（对应原版 _pluginCatalogByName / pluginHTTPList / _pluginCatalogRefreshedAt）----
    @Volatile
    private var catalogByName: Map<String, PluginHTTPItem> = emptyMap()

    @Volatile
    private var catalogList: List<PluginHTTPItem> = emptyList()

    @Volatile
    private var catalogRefreshedAt: Long = 0L

    /** 规则仓库缓存有效期：5 分钟（对应原版 _pluginCatalogMaxAge） */
    private val catalogMaxAge = 5 * 60 * 1000L

    /**
     * 规则仓库缓存写入锁：只保护 catalogList/catalogByName/catalogRefreshedAt
     * 三个 volatile 字段的"打包写入"一致性（微秒级），绝不在锁内执行网络。
     *
     * 血泪教训（2026-11-06 两连击）：
     * 1. @Synchronized 实例锁包网络 → 主线程碰锁被阻塞 → ANR（Input dispatching timed out 9.3s）
     * 2. 上一步改成 refreshLock 包网络后：DNS 卡死（实测 Linux.poll 151s 不返回）时，
     *    启动检查持锁不释放，页面 ensurePluginCatalog() 排队等锁 151s+ →
     *    页面"加载不出来"（UI 不卡但内容永远转圈）。
     * 因此：网络一律锁外执行，配合 HttpClientFactory.callTimeout(20s) 兜底 DNS/连接卡死；
     * 此锁只保证多字段缓存赋值原子可见（volatile 单字段可见，多字段一致性靠这个短锁）。
     */
    private val refreshLock = Any()

    /** 是否使用规则仓库镜像（对应原版 enableGitProxy，默认 true 对齐原版） */
    private var gitProxyEnabled: Boolean = true

    /**
     * 自定义规则仓库地址（空 = 官方仓库；对应原版 controllers.ruleRepository）。
     * 仅内存态：持久化由调用方（PluginShopPage）写入 SettingsManager.Proxy.RULE_REPOSITORY_URL，
     * 与 gitProxyEnabled 同一模式（KazumiApp 启动时从 settings 恢复一次）。
     */
    @Volatile
    var ruleRepository: String = ""
        private set

    /**
     * 已添加的规则仓库列表（首项固定为官方仓库 ""，其后为自定义仓库地址）。
     * 空列表默认仅官方仓库；用于仓库管理弹窗的展示与「只看特定仓库的源」切换。
     * 与 ruleRepository 同一持久化模式（KazumiApp 启动时恢复）。
     */
    @Volatile
    var ruleRepositories: List<String> = listOf("")
        private set

    private val catalogFresh: Boolean
        get() = catalogRefreshedAt > 0 &&
            System.currentTimeMillis() - catalogRefreshedAt < catalogMaxAge

    /**
     * 内置默认插件：原版 2.4.1 **不预装任何规则**（2.3.x 曾随包带 7sefun/DM84，2.4 起移除，
     * 引导页改为从规则仓库在线挑选）。这里保持空列表以免与用户在原版里的规则数量不一致。
     */
    private val defaultPlugins = emptyList<String>()

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
            importDefaultPlugins(onlyMissing = false)
        }
        return plugins.isNotEmpty()
    }

    /**
     * 补齐内置规则（原版安装后规则与仓库一致；移植版早期只预装 2 条，需要为老用户补一次）
     * @param onlyMissing true 时只补缺失的，不动用户已有/改过的规则
     * @return 本次新导入的条数
     */
    @Synchronized
    fun importBundledPlugins(onlyMissing: Boolean = true): Int {
        var added = 0
        defaultPlugins.forEach { name ->
            if (onlyMissing && plugins.containsKey(name.lowercase())) return@forEach
            try {
                val json = context.assets.open("plugins/$name.json")
                    .bufferedReader().use { it.readText() }
                val plugin = parsePluginJson(json) ?: return@forEach
                plugins[plugin.name.lowercase()] = plugin
                savePlugin(plugin)
                added++
            } catch (e: Exception) {
                // 内置规则缺失时忽略
            }
        }
        return added
    }

    /**
     * 从 assets 导入内置插件
     */
    private fun importDefaultPlugins(onlyMissing: Boolean = true) {
        defaultPlugins.forEach { name ->
            if (onlyMissing && plugins.containsKey(name.lowercase())) return@forEach
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
        // 按文件名排序保证加载顺序稳定（listFiles() 本身无序）
        dir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
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
    fun allPlugins(): List<Plugin> = plugins.values
        // 按持久化 sort 字段排序（对齐原版 pluginList 配置顺序；sort 相等时保持插入顺序）
        .sortedWith(compareBy({ it.sort }, { it.name }))

    @Synchronized
    fun getPlugin(name: String): Plugin? = plugins[name.lowercase()]

    @Synchronized
    fun addPlugin(plugin: Plugin) {
        plugins[plugin.name.lowercase()] = plugin
        savePlugin(plugin)
        invalidateQueryCaches()
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
        invalidateQueryCaches()
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
        invalidateQueryCaches()
        return plugin
    }

    /**
     * 搜索番剧
     * 对应 Plugin.queryBangumi
     * 带 TTL 内存缓存：同一插件+关键词在 10 分钟内不重复请求网络
     * @param forceRefresh true 时忽略缓存强制重新请求（播放来源弹窗的「重新检索」用）
     */
    suspend fun searchBangumi(
        pluginName: String,
        keyword: String,
        forceRefresh: Boolean = false
    ): PluginSearchResponse {
        val plugin = getPlugin(pluginName)
            ?: return PluginSearchResponse(pluginName, emptyList())
        val cacheKey = "${plugin.name.lowercase()}::${keyword.trim()}"
        val cached = searchCache[cacheKey]
        if (!forceRefresh && isCacheFresh(cached)) {
            return PluginSearchResponse(pluginName, cached!!.value)
        }
        return try {
            val trace = ruleEngine.search(plugin.executionConfig(), keyword)
            val items = trace.response.data
            searchCache = searchCache + (cacheKey to CacheEntry(items, System.currentTimeMillis() + cacheTtlMs))
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
     * 带 TTL 内存缓存：同一 src 在 10 分钟内不重复请求网络
     */
    suspend fun queryChapterRoads(
        pluginName: String,
        source: String
    ): List<Road> {
        val plugin = getPlugin(pluginName)
            ?: return emptyList()
        val cacheKey = "${plugin.name.lowercase()}::${source.trim()}"
        val cached = roadsCache[cacheKey]
        if (isCacheFresh(cached)) {
            return cached!!.value
        }
        val trace = ruleEngine.queryChapters(plugin.executionConfig(), source)
        val roads = trace.roads
        roadsCache = roadsCache + (cacheKey to CacheEntry(roads, System.currentTimeMillis() + cacheTtlMs))
        return roads
    }

    /** 清除搜索/线路缓存（插件改动后调用，避免旧数据残留） */
    @Synchronized
    fun invalidateQueryCaches() {
        searchCache = emptyMap()
        roadsCache = emptyMap()
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

    /**
     * 设置自定义规则仓库地址（空 = 官方仓库；对应原版 setRuleRepository）。
     * 返回是否发生变化；变化时清空 catalog 缓存（下次刷新走新仓库）。
     * 非法地址抛出 [IllegalArgumentException]（原版 FormatException 语义），
     * 由 UI 层捕获并展示错误信息，不污染当前状态。
     */
    fun setRuleRepository(input: String): Boolean {
        val normalized = RulesRepositoryConfig.normalizeForStorage(input)
        if (normalized == ruleRepository) return false
        ruleRepository = normalized
        // 仓库切换后旧缓存作废，避免合并到新仓库的请求
        catalogByName = emptyMap()
        catalogList = emptyList()
        catalogRefreshedAt = 0L
        return true
    }

    // ---- 多仓库管理（移植增强：原版仅单一 ruleRepository）----

    /** 官方仓库标识（空串） */
    val officialRepository: String get() = ""

    /** 当前是否有自定义仓库（区别于仅官方） */
    fun hasCustomRepositories(): Boolean = ruleRepositories.any { it.isNotEmpty() }

    /** 恢复已保存的仓库列表（KazumiApp 启动时调用；保持官方仓库在首位） */
    fun restoreRuleRepositories(saved: List<String>) {
        val cleaned = saved.mapNotNull { runCatching { RulesRepositoryConfig.normalizeForStorage(it) }.getOrNull() }
            .distinct()
        val list = buildList {
            add("")
            addAll(cleaned.filter { it.isNotEmpty() })
        }
        ruleRepositories = list.distinct()
    }

    /**
     * 添加自定义规则仓库（官方仓库除外）。
     * @return 添加结果：ALREADY 已存在 / ADDED 已添加 / INVALID 地址非法
     */
    fun addRuleRepository(input: String): RepositoryAddResult {
        if (input.trim().isEmpty()) return RepositoryAddResult.INVALID
        val normalized = runCatching { RulesRepositoryConfig.normalizeForStorage(input) }
            .getOrElse { return RepositoryAddResult.INVALID }
        if (normalized.isEmpty()) return RepositoryAddResult.INVALID
        if (ruleRepositories.contains(normalized)) return RepositoryAddResult.ALREADY
        ruleRepositories = ruleRepositories + normalized
        return RepositoryAddResult.ADDED
    }

    /**
     * 移除自定义规则仓库。若移除的是当前激活仓库，自动回退官方仓库。
     * @return 是否移除成功（官方仓库不可移除）
     */
    fun removeRuleRepository(repository: String): Boolean {
        val normalized = runCatching { RulesRepositoryConfig.normalizeForStorage(repository) }
            .getOrElse { return false }
        if (normalized.isEmpty()) return false // 官方仓库不可移除
        if (!ruleRepositories.contains(normalized)) return false
        ruleRepositories = ruleRepositories - normalized
        if (ruleRepository == normalized) {
            ruleRepository = ""
            catalogByName = emptyMap()
            catalogList = emptyList()
            catalogRefreshedAt = 0L
        }
        return true
    }

    /** 切换到指定仓库（必须是已添加的仓库或官方） */
    fun switchRuleRepository(repository: String): Boolean {
        val normalized = runCatching { RulesRepositoryConfig.normalizeForStorage(repository) }
            .getOrElse { return false }
        if (!ruleRepositories.contains(normalized)) return false
        if (normalized == ruleRepository) return false
        ruleRepository = normalized
        catalogByName = emptyMap()
        catalogList = emptyList()
        catalogRefreshedAt = 0L
        return true
    }

    /** 仓库添加结果 */
    enum class RepositoryAddResult { ADDED, ALREADY, INVALID }

    /** 规则仓库条目列表（缓存） */
    fun catalogItems(): List<PluginHTTPItem> = catalogList

    /** 读取规则的原始 JSON 文本（供「编辑规则」页直接改 JSON） */
    fun readPluginJson(name: String): String? = runCatching {
        val file = File(pluginsDir, "${name.lowercase()}.json")
        if (file.exists()) file.readText() else null
    }.getOrNull()

    /**
     * 用 JSON 文本替换一条规则：解析成功才写入，失败返回 false（原版编辑页保存前的校验语义）。
     */
    fun savePluginJsonText(name: String, json: String): Boolean = runCatching {
        val plugin = parsePluginJson(json) ?: return false
        // 允许改内容但保持原文件名，避免出现重复规则
        val key = name.lowercase()
        plugins[key] = plugin
        val target = File(pluginsDir, "$key.json")
        target.writeText(json)
        invalidateQueryCaches()
        true
    }.getOrDefault(false)

    /** 规则仓库是否已刷新 */
    fun isCatalogFresh(): Boolean = catalogFresh

    /** 规则仓库是否可用（缓存非空） */
    fun hasCatalog(): Boolean = catalogList.isNotEmpty()

    /**
     * 刷新规则仓库（强制）
     * 网络在锁外执行（配合 HttpClientFactory.callTimeout(20s) 兜底 DNS/连接卡死），
     * 每个调用方自己发请求、互不阻塞；写缓存才拿 refreshLock（微秒级）。
     */
    fun refreshPluginCatalog(): List<PluginHTTPItem> {
        val items = fetchCatalog()
        synchronized(refreshLock) {
            catalogList = items
            catalogByName = items.associateBy { it.name.lowercase() }
            catalogRefreshedAt = System.currentTimeMillis()
        }
        return items
    }

    /**
     * 使用缓存（5 分钟内）否则刷新；快速路径无锁，主线程可安全调用。
     * 刷新路径网络同样在锁外：不阻塞、不排队（最多等自己的请求，callTimeout 兜底）。
     */
    fun ensurePluginCatalog(): List<PluginHTTPItem> {
        if (catalogFresh && catalogList.isNotEmpty()) return catalogList
        val items = fetchCatalog()
        synchronized(refreshLock) {
            // 双检：等待写锁期间可能已被其他线程刷新（保留更新的数据）
            if (catalogFresh && catalogList.isNotEmpty()) return catalogList
            catalogList = items
            catalogByName = items.associateBy { it.name.lowercase() }
            catalogRefreshedAt = System.currentTimeMillis()
        }
        return catalogList
    }

    /** 拉取仓库列表（纯网络+解析，无锁编排；最多 callTimeout=20s 兜底） */
    private fun fetchCatalog(): List<PluginHTTPItem> =
        PluginCatalogApi.getPluginList(gitProxyEnabled, ruleRepository)

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
        FileLogger.i("RuleRepo", "tryUpdatePluginByName start: name=$name catalogName=$catalogName mirror=$gitProxyEnabled repo=${ruleRepository.ifEmpty { "官方" }}")
        val remote = runCatching { PluginCatalogApi.getPlugin(catalogName, gitProxyEnabled, ruleRepository) }
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

    /** 检查启动时规则更新（对应原版 checkPluginUpdatesOnStartup）
     * 与原版语义一致：启动检测到可更新规则时直接全部自动更新（tryUpdateAllPlugin），
     * 而不是只统计数量静默返回——否则设置里的"规则更新"开关形同虚设。
     * @return 本次实际更新的规则条数
     */
    suspend fun checkPluginUpdatesOnStartup(enabled: Boolean): Int {
        if (!enabled) return 0
        return try {
            refreshPluginCatalog()
            val updatable = allPlugins().filter {
                pluginUpdateStatus(it) == PluginUpdateAvailability.UPDATABLE
            }
            if (updatable.isEmpty()) {
                FileLogger.d("RuleRepo", "启动规则检查：无可更新规则")
                0
            } else {
                FileLogger.i("RuleRepo", "启动规则检查：发现 ${updatable.size} 条可更新规则，开始自动更新")
                val result = tryUpdateAllPlugin()
                FileLogger.i(
                    "RuleRepo",
                    "启动规则自动更新完成: updated=${result.updated} failed=${result.failed} " +
                        "requiresNewer=${result.requiresNewerClient} notNewer=${result.notNewer}"
                )
                result.updated
            }
        } catch (_: Exception) {
            0
        }
    }
}