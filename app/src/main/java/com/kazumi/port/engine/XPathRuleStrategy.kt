package com.kazumi.port.engine

import com.kazumi.port.data.model.*
import com.kazumi.port.util.EpisodeUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI
import java.net.URLEncoder

/**
 * XPath 规则策略
 * 对应 Dart XPathRuleStrategy
 */
class XPathRuleStrategy {

    /**
     * 准备搜索请求
     * 对应 prepareSearchRequest
     */
    fun prepareSearchRequest(config: RuleExecutionConfig, keyword: String): PreparedRuleRequest {
        val queryUrl = config.searchUrl.replace(
            "@keyword", URLEncoder.encode(keyword, "UTF-8")
        )
        val uri = try { URI(queryUrl) } catch (e: Exception) { null }
        if (uri == null || uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw XPathRuleFormatException("搜索 URL 无效: $queryUrl")
        }
        if (!config.usePost) {
            return PreparedRuleRequest(
                method = "GET",
                url = queryUrl,
                includeCookies = true
            )
        }
        // POST 模式：把 query 参数作为表单体
        val queryParams = parseQuery(uri)
        val baseUrl = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        return PreparedRuleRequest(
            method = "POST",
            url = baseUrl,
            bodyType = ApiBodyType.FORM,
            body = queryParams,
            includeCookies = true
        )
    }

    /**
     * 准备章节请求
     * 对应 prepareChapterRequest
     */
    fun prepareChapterRequest(config: RuleExecutionConfig, source: String): PreparedRuleRequest {
        val url = EpisodeUrl.normalizeEpisodeUrl(config.baseUrl, source)
        val uri = try { URI(url) } catch (e: Exception) { null }
        if (uri == null || uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw XPathRuleFormatException("章节 URL 无效: $url")
        }
        return PreparedRuleRequest(method = "GET", url = url)
    }

    /**
     * 解析搜索结果
     * 对应 parseSearch
     */
    fun parseSearch(raw: String, config: RuleExecutionConfig): RuleSearchParseResult {
        val root = documentElement(raw)
        if (detectsCaptchaChallenge(raw, config.antiCrawlerConfig, root)) {
            throw CaptchaRequiredException(config.pluginName)
        }

        val items = ArrayList<SearchItem>()
        val fragments = ArrayList<String>()
        val diagnostics = ArrayList<String>()

        val nodes = runSelector(config.searchList) {
            XPathEvaluator.queryNodes(root, config.searchList)
        }

        nodes.forEachIndexed { index, node ->
            try {
                val nameNode = runSelector(config.searchName) {
                    XPathEvaluator.queryNodesFromNode(node, config.searchName).firstOrNull()
                }
                val name = nameNode?.let { XPathEvaluator.nodeText(it) }?.trim() ?: ""
                val srcNode = runSelector(config.searchResult) {
                    XPathEvaluator.queryNodesFromNode(node, config.searchResult).firstOrNull()
                }
                val src = XPathEvaluator.nodeAttribute(srcNode ?: node, "href")?.trim() ?: ""

                if (name.isEmpty() || src.isEmpty()) {
                    diagnostics.add("搜索节点 $index 缺少名称或来源，已跳过")
                    return@forEachIndexed
                }
                items.add(SearchItem(name = name, src = src))
                fragments.add(XPathEvaluator.nodeFragment(node))
            } catch (e: XPathRuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics.add("搜索节点 $index 解析失败: $e")
            }
        }

        return RuleSearchParseResult(items, fragments, diagnostics)
    }

    /**
     * 解析章节（播放线路）
     * 对应 parseChapters
     */
    fun parseChapters(raw: String, config: RuleExecutionConfig): RuleChapterParseResult {
        val root = documentElement(raw)
        val roads = ArrayList<Road>()
        val diagnostics = ArrayList<String>()

        val roadNodes = runSelector(config.chapterRoads) {
            XPathEvaluator.queryNodes(root, config.chapterRoads)
        }

        roadNodes.forEachIndexed { roadIndex, roadNode ->
            try {
                val urls = ArrayList<String>()
                val names = ArrayList<String>()

                val episodeNodes = runSelector(config.chapterResult) {
                    XPathEvaluator.queryNodesFromNode(roadNode, config.chapterResult)
                }

                episodeNodes.forEachIndexed { episodeIndex, episodeNode ->
                    try {
                        val source = XPathEvaluator.nodeAttribute(episodeNode, "href")?.trim() ?: ""
                        if (source.isEmpty()) {
                            diagnostics.add("线路 $roadIndex 的剧集节点 $episodeIndex 缺少 URL，已跳过")
                            return@forEachIndexed
                        }
                        val text = XPathEvaluator.nodeText(episodeNode)
                        val name = text.replace(Regex("\\s+"), "")
                        urls.add(EpisodeUrl.normalizeEpisodeUrl(config.baseUrl, source))
                        names.add(if (name.isEmpty()) "第${episodeIndex + 1}集" else name)
                    } catch (e: Exception) {
                        diagnostics.add("线路 $roadIndex 的剧集节点 $episodeIndex 解析失败: $e")
                    }
                }

                if (urls.isEmpty()) {
                    diagnostics.add("线路 $roadIndex 没有有效剧集，已跳过")
                    return@forEachIndexed
                }
                roads.add(
                    Road(
                        name = "播放线路${roads.size + 1}",
                        data = urls,
                        identifier = names
                    )
                )
            } catch (e: XPathRuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics.add("线路节点 $roadIndex 解析失败: $e")
            }
        }

        return RuleChapterParseResult(roads, diagnostics)
    }

    /**
     * 检测验证码挑战页
     * 对应 detectsCaptchaChallenge
     */
    fun detectsCaptchaChallenge(
        raw: String,
        config: AntiCrawlerConfig,
        htmlElement: Element? = null
    ): Boolean {
        if (!config.enabled) return false
        val detectValue = config.captchaDetectValue.trim()
        if (detectValue.isNotEmpty()) {
            when (config.captchaDetectType) {
                CaptchaDetectType.TEXT -> return raw.contains(detectValue)
                CaptchaDetectType.REGEX -> {
                    return try {
                        Regex(detectValue, RegexOption.IGNORE_CASE)
                            .containsMatchIn(raw)
                    } catch (e: Exception) {
                        false
                    }
                }
                CaptchaDetectType.XPATH -> {
                    val root = htmlElement ?: documentElement(raw)
                    return runSelector(detectValue) {
                        XPathEvaluator.queryNodes(root, detectValue).firstOrNull()
                    } != null
                }
            }
        }

        val root = htmlElement ?: documentElement(raw)
        val fallbackSelectors = listOf(
            config.captchaImage,
            config.captchaButton
        )
        for (expression in fallbackSelectors) {
            if (expression.trim().isEmpty()) continue
            val node = runSelector(expression) {
                XPathEvaluator.queryNodes(root, expression).firstOrNull()
            }
            if (node != null) return true
        }
        return false
    }

    private fun documentElement(raw: String): Element {
        try {
            return Jsoup.parse(raw)
        } catch (e: Exception) {
            throw XPathRuleFormatException("HTML 响应解析失败", e)
        }
    }

    private fun <T> runSelector(expression: String, query: () -> T): T {
        if (expression.trim().isEmpty()) {
            throw XPathRuleFormatException("XPath 不能为空")
        }
        try {
            return query()
        } catch (e: XPathRuleFormatException) {
            throw e
        } catch (e: Exception) {
            throw XPathRuleFormatException("XPath 无效: $expression", e)
        }
    }

    private fun parseQuery(uri: URI): Map<String, Any?> {
        val query = uri.rawQuery ?: return emptyMap()
        val result = HashMap<String, Any?>()
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

    private fun decode(value: String): String {
        return try { java.net.URLDecoder.decode(value, "UTF-8") } catch (e: Exception) { value }
    }
}