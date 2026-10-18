package com.kazumi.port.engine

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.kazumi.port.data.model.*
import com.kazumi.port.util.EpisodeUrl
import java.net.URI
import java.net.URLEncoder

/**
 * API 规则策略
 * 对应 Dart ApiRuleStrategy
 */
class ApiRuleStrategy {

    private val gson = Gson()

    /**
     * 解码 JSON 响应
     */
    fun decodeResponse(raw: String): Any {
        return try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            throw ApiRuleFormatException("API 响应不是有效 JSON: ${e.message}", e)
        }
    }

    /**
     * 准备 API 请求（渲染模板）
     * 对应 prepareRequest
     */
    fun prepareRequest(
        config: ApiRequestConfig,
        variables: Map<String, Any?>
    ): PreparedRuleRequest {
        val method = config.method.uppercase()
        if (method != "GET" && method != "POST") {
            throw ApiRuleFormatException("仅支持 GET/POST，当前为 $method")
        }
        if (config.url.trim().isEmpty()) {
            throw ApiRuleFormatException("API 请求 URL 不能为空")
        }
        val url = renderTemplate(config.url.trim(), variables, encode = true)
        val uri = try { URI(url) } catch (e: Exception) { null }
        if (uri == null || uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw ApiRuleFormatException("API 请求 URL 无效: $url")
        }
        val hasBody = method == "POST" && config.bodyType != ApiBodyType.NONE
        return PreparedRuleRequest(
            method = method,
            url = url,
            headers = renderMap(config.headers, variables),
            query = renderMap(config.query, variables),
            bodyType = config.bodyType,
            body = if (hasBody) renderValue(config.body, variables) else null,
            includeCookies = true
        )
    }

    /**
     * 校验搜索配置
     */
    fun validateSearchConfig(config: ApiSearchConfig) {
        RestrictedJsonPath.validate(config.listPath)
        RestrictedJsonPath.validate(config.namePath)
        RestrictedJsonPath.validate(config.sourcePath)
    }

    /**
     * 解析搜索 JSON 响应
     * 对应 parseSearch
     */
    fun parseSearch(raw: String, config: ApiSearchConfig): RuleSearchParseResult {
        validateSearchConfig(config)
        val document = decodeResponse(raw)
        val nodes = RestrictedJsonPath.read(document, config.listPath)
        val results = ArrayList<SearchItem>()
        val fragments = ArrayList<String>()
        val diagnostics = ArrayList<String>()

        nodes.forEachIndexed { index, node ->
            try {
                if (node == null) {
                    diagnostics.add("搜索节点 $index 为空，已跳过")
                    return@forEachIndexed
                }
                val name = stringValue(
                    RestrictedJsonPath.readFirst(node, config.namePath)
                )
                val source = stringValue(
                    RestrictedJsonPath.readFirst(node, config.sourcePath)
                )
                if (name.isEmpty() || source.isEmpty()) {
                    diagnostics.add("搜索节点 $index 缺少名称或来源，已跳过")
                    return@forEachIndexed
                }
                results.add(SearchItem(name = name, src = source))
                fragments.add(gson.toJson(node))
            } catch (e: ApiRuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics.add("搜索节点 $index 解析失败: $e")
            }
        }
        return RuleSearchParseResult(results, fragments, diagnostics)
    }

    /**
     * 解析章节 JSON 响应
     * 对应 parseChapters
     */
    fun parseChapters(
        raw: String,
        config: ApiChapterConfig,
        source: String,
        baseUrl: String
    ): RuleChapterParseResult {
        validateChapterConfig(config)
        val document = decodeResponse(raw)
        val rootVariables = HashMap<String, Any?>()
        rootVariables["source"] = source

        config.variables.forEach { (key, path) ->
            val value = RestrictedJsonPath.readFirst(document, path)
            if (value == null) {
                throw ApiRuleFormatException("章节响应变量 $key 未匹配到值: $path")
            }
            rootVariables[key] = value
        }

        val diagnostics = ArrayList<String>()
        val roads = if (config.format == ApiChapterFormat.DELIMITED) {
            parseDelimited(document, config, rootVariables, baseUrl, diagnostics)
        } else {
            parseNested(document, config, rootVariables, baseUrl, diagnostics)
        }
        return RuleChapterParseResult(roads, diagnostics)
    }

    /**
     * 解析嵌套 JSON 格式
     * 对应 _parseNested
     */
    private fun parseNested(
        document: Any,
        config: ApiChapterConfig,
        rootVariables: Map<String, Any?>,
        baseUrl: String,
        diagnostics: ArrayList<String>
    ): List<Road> {
        val hasRoads = config.roadsPath.trim().isNotEmpty()
        val roadNodes = if (hasRoads) {
            RestrictedJsonPath.read(document, config.roadsPath)
        } else {
            listOf(document)
        }
        val roads = ArrayList<Road>()

        roadNodes.forEachIndexed { roadIndex, roadNode ->
            try {
                if (roadNode == null) {
                    diagnostics.add("线路节点 $roadIndex 为空，已跳过")
                    return@forEachIndexed
                }
                val roadName = if (hasRoads && config.roadNamePath.trim().isNotEmpty()) {
                    stringValue(RestrictedJsonPath.readFirst(roadNode, config.roadNamePath))
                } else ""

                val episodeNodes = RestrictedJsonPath.read(roadNode, config.episodesPath)
                val urls = ArrayList<String>()
                val names = ArrayList<String>()

                episodeNodes.forEachIndexed { episodeIndex, episodeNode ->
                    try {
                        if (episodeNode == null) {
                            diagnostics.add("线路 $roadIndex 的剧集节点 $episodeIndex 为空，已跳过")
                            return@forEachIndexed
                        }
                        val episodeName = stringValue(
                            RestrictedJsonPath.readFirst(episodeNode, config.episodeNamePath)
                        )
                        val rawUrl = if (config.episodeUrlPath.trim().isEmpty()) {
                            ""
                        } else {
                            stringValue(
                                RestrictedJsonPath.readFirst(episodeNode, config.episodeUrlPath)
                            )
                        }
                        val pageUrl = resolveEpisodeUrl(
                            config, rootVariables,
                            rawUrl = rawUrl,
                            roadIndex = roadIndex,
                            episodeIndex = episodeIndex,
                            baseUrl = baseUrl
                        )
                        if (pageUrl.isEmpty()) {
                            diagnostics.add("线路 $roadIndex 的剧集节点 $episodeIndex 缺少 URL，已跳过")
                            return@forEachIndexed
                        }
                        urls.add(pageUrl)
                        names.add(if (episodeName.isEmpty()) "第${episodeIndex + 1}集" else episodeName)
                    } catch (e: ApiRuleFormatException) {
                        throw e
                    } catch (e: Exception) {
                        diagnostics.add("线路 $roadIndex 的剧集节点 $episodeIndex 解析失败: $e")
                    }
                }

                if (urls.isEmpty()) {
                    diagnostics.add("线路节点 $roadIndex 没有有效剧集，已跳过")
                    return@forEachIndexed
                }
                roads.add(
                    Road(
                        name = if (roadName.isEmpty()) "播放线路${roads.size + 1}" else roadName,
                        data = urls,
                        identifier = names
                    )
                )
            } catch (e: ApiRuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics.add("线路节点 $roadIndex 解析失败: $e")
            }
        }
        return roads
    }

    /**
     * 解析分隔符字符串格式
     * 对应 _parseDelimited
     */
    private fun parseDelimited(
        document: Any,
        config: ApiChapterConfig,
        rootVariables: Map<String, Any?>,
        baseUrl: String,
        diagnostics: ArrayList<String>
    ): List<Road> {
        val namesValue = stringValue(
            RestrictedJsonPath.readFirst(document, config.roadNamesPath)
        )
        val episodesValue = stringValue(
            RestrictedJsonPath.readFirst(document, config.roadEpisodesPath)
        )
        if (episodesValue.isEmpty()) return emptyList()

        val roadNames = namesValue.split(config.roadSeparator)
        val roadGroups = episodesValue.split(config.roadSeparator)
        val roads = ArrayList<Road>()

        roadGroups.forEachIndexed { roadIndex, group ->
            val urls = ArrayList<String>()
            val names = ArrayList<String>()
            val entries = group.split(config.episodeSeparator)

            entries.forEachIndexed { episodeIndex, rawEntry ->
                val entry = rawEntry.trim()
                if (entry.isEmpty()) return@forEachIndexed
                val separatorIndex = entry.indexOf(config.fieldSeparator)
                if (separatorIndex < 0) {
                    diagnostics.add("线路 $roadIndex 的剧集条目 $episodeIndex 缺少字段分隔符，已跳过")
                    return@forEachIndexed
                }
                val name = entry.substring(0, separatorIndex).trim()
                val rawUrl = entry.substring(separatorIndex + config.fieldSeparator.length).trim()
                try {
                    val pageUrl = resolveEpisodeUrl(
                        config, rootVariables,
                        rawUrl = rawUrl,
                        roadIndex = roadIndex,
                        episodeIndex = episodeIndex,
                        baseUrl = baseUrl
                    )
                    if (pageUrl.isEmpty()) {
                        diagnostics.add("线路 $roadIndex 的剧集条目 $episodeIndex 缺少 URL，已跳过")
                        return@forEachIndexed
                    }
                    urls.add(pageUrl)
                    names.add(if (name.isEmpty()) "第${episodeIndex + 1}集" else name)
                } catch (e: ApiRuleFormatException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.add("线路 $roadIndex 的剧集条目 $episodeIndex 解析失败: $e")
                }
            }

            if (urls.isEmpty()) {
                diagnostics.add("线路 $roadIndex 没有有效剧集，已跳过")
                return@forEachIndexed
            }
            val configuredName = if (roadIndex < roadNames.size) roadNames[roadIndex].trim() else ""
            roads.add(
                Road(
                    name = if (configuredName.isEmpty()) "播放线路${roads.size + 1}" else configuredName,
                    data = urls,
                    identifier = names
                )
            )
        }
        return roads
    }

    /**
     * 解析剧集最终 URL（支持播放页模板）
     * 对应 _resolveEpisodeUrl
     */
    private fun resolveEpisodeUrl(
        config: ApiChapterConfig,
        rootVariables: Map<String, Any?>,
        rawUrl: String,
        roadIndex: Int,
        episodeIndex: Int,
        baseUrl: String
    ): String {
        val page = config.episodePage
        if (page == null) return EpisodeUrl.normalizeEpisodeUrl(baseUrl, rawUrl)
        if (page.url.trim().isEmpty()) {
            throw ApiRuleFormatException("播放页地址模板不能为空")
        }

        val variables = HashMap<String, Any?>(rootVariables)
        variables["episodeUrl"] = rawUrl
        variables["roadIndex"] = roadIndex
        variables["roadNumber"] = roadIndex + 1
        variables["episodeIndex"] = episodeIndex
        variables["episodeNumber"] = episodeIndex + 1

        val path = renderTemplate(page.url, variables, encode = true)
        val uri = try { URI(path) } catch (e: Exception) { null }
        if (uri == null) {
            throw ApiRuleFormatException("剧集页面 URL 无效: $path")
        }
        val renderedQuery = renderMap(page.query, variables)
            .mapValues { it.value?.toString() ?: "" }
        val mergedQuery = HashMap<String, String>()
        parseQuery(uri).forEach { (k, v) -> mergedQuery[k] = v }
        renderedQuery.forEach { (k, v) -> mergedQuery[k] = v }

        val rebuilt = URI(
            uri.scheme, null, uri.host, uri.port, uri.path,
            buildQueryString(mergedQuery), uri.fragment
        ).toString()

        return EpisodeUrl.normalizeEpisodeUrl(baseUrl, rebuilt)
    }

    /**
     * 校验章节配置
     * 对应 validateChapterConfig
     */
    fun validateChapterConfig(config: ApiChapterConfig) {
        config.variables.values.forEach { RestrictedJsonPath.validate(it) }
        if (config.format == ApiChapterFormat.DELIMITED) {
            RestrictedJsonPath.validate(config.roadNamesPath)
            RestrictedJsonPath.validate(config.roadEpisodesPath)
            if (config.roadSeparator.isEmpty() ||
                config.episodeSeparator.isEmpty() ||
                config.fieldSeparator.isEmpty()
            ) {
                throw ApiRuleFormatException("章节分隔符不能为空")
            }
            return
        }

        if (config.roadsPath.trim().isNotEmpty()) {
            RestrictedJsonPath.validate(config.roadsPath)
        }
        if (config.roadNamePath.trim().isNotEmpty()) {
            RestrictedJsonPath.validate(config.roadNamePath)
        }
        RestrictedJsonPath.validate(config.episodesPath)
        RestrictedJsonPath.validate(config.episodeNamePath)
        if (config.episodeUrlPath.trim().isNotEmpty()) {
            RestrictedJsonPath.validate(config.episodeUrlPath)
        } else if (config.episodePage == null) {
            throw ApiRuleFormatException("必须配置播放入口地址路径或播放页地址模板")
        }
        config.episodePage?.let {
            if (it.url.trim().isEmpty()) {
                throw ApiRuleFormatException("播放页地址模板不能为空")
            }
        }
    }

    private fun renderMap(
        input: Map<String, Any?>,
        variables: Map<String, Any?>
    ): Map<String, Any?> {
        return input.map { (key, value) ->
            renderTemplate(key, variables) to renderValue(value, variables)
        }.toMap()
    }

    private fun renderValue(value: Any?, variables: Map<String, Any?>): Any? {
        if (value is String) {
            val exact = Regex("^@([A-Za-z_][A-Za-z0-9_]*)$").find(value)
            if (exact != null) {
                val name = exact.groupValues[1]
                if (!variables.containsKey(name)) {
                    throw ApiRuleFormatException("缺少模板变量 @$name")
                }
                return variables[name]
            }
            return renderTemplate(value, variables)
        }
        if (value is List<*>) {
            return value.map { renderValue(it, variables) }
        }
        if (value is Map<*, *>) {
            return value.map { (k, v) -> k.toString() to renderValue(v, variables) }.toMap()
        }
        return value
    }

    private fun renderTemplate(
        template: String,
        variables: Map<String, Any?>,
        encode: Boolean = false
    ): String {
        return Regex("(?<![A-Za-z0-9_])@([A-Za-z_][A-Za-z0-9_]*)")
            .replace(template) { match ->
                val name = match.groupValues[1]
                if (!variables.containsKey(name)) {
                    throw ApiRuleFormatException("缺少模板变量 @$name")
                }
                val value = variables[name]?.toString() ?: ""
                if (encode) URLEncoder.encode(value, "UTF-8") else value
            }
    }

    private fun stringValue(value: Any?): String {
        if (value == null) return ""
        return if (value is String) value.trim() else value.toString().trim()
    }

    private fun parseQuery(uri: URI): Map<String, String> {
        val query = uri.rawQuery ?: return emptyMap()
        val result = HashMap<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                result[decode(pair.substring(0, idx))] = decode(pair.substring(idx + 1))
            } else if (pair.isNotEmpty()) {
                result[decode(pair)] = ""
            }
        }
        return result
    }

    private fun buildQueryString(query: Map<String, String>): String? {
        if (query.isEmpty()) return null
        return query.map { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.joinToString("&")
    }

    private fun decode(value: String): String {
        return try { java.net.URLDecoder.decode(value, "UTF-8") } catch (e: Exception) { value }
    }
}