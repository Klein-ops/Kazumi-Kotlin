package com.kazumi.port.data.model

/**
 * 规则执行配置（不可变快照）
 * 对应 Dart RuleExecutionConfig
 */
data class RuleExecutionConfig(
    val pluginName: String,
    val baseUrl: String,
    val usePost: Boolean,
    val searchMode: String,
    val chapterMode: String,
    val searchUrl: String,
    val searchList: String,
    val searchName: String,
    val searchResult: String,
    val chapterRoads: String,
    val chapterResult: String,
    val searchApiConfig: ApiSearchConfig,
    val chapterApiConfig: ApiChapterConfig,
    val antiCrawlerConfig: AntiCrawlerConfig
)

/**
 * 已准备的规则请求
 * 对应 Dart PreparedRuleRequest
 */
data class PreparedRuleRequest(
    val method: String,
    val url: String,
    val headers: Map<String, Any?> = emptyMap(),
    val query: Map<String, Any?> = emptyMap(),
    val bodyType: String = ApiBodyType.NONE,
    val body: Any? = null,
    val includeCookies: Boolean = false
)

/**
 * 搜索解析结果
 * 对应 Dart RuleSearchParseResult
 */
data class RuleSearchParseResult(
    val items: List<SearchItem>,
    val matchedFragments: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList()
)

/**
 * 章节解析结果
 * 对应 Dart RuleChapterParseResult
 */
data class RuleChapterParseResult(
    val roads: List<Road>,
    val diagnostics: List<String> = emptyList()
)

/**
 * 搜索跟踪结果
 * 对应 Dart RuleSearchTrace
 */
data class RuleSearchTrace(
    val rawResponse: String,
    val response: PluginSearchResponse,
    val matchedFragments: List<String>,
    val diagnostics: List<String>
)

/**
 * 章节跟踪结果
 * 对应 Dart RuleChapterTrace
 */
data class RuleChapterTrace(
    val rawResponse: String,
    val roads: List<Road>,
    val diagnostics: List<String>
)

/**
 * 规则异常基类
 */
open class RuleException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class CaptchaRequiredException(val pluginName: String) :
    RuleException("CaptchaRequiredException: $pluginName requires captcha verification")

class NoResultException(val pluginName: String) :
    RuleException("NoResultException: $pluginName returned no search results")

class SearchErrorException(val pluginName: String, cause: Throwable? = null) :
    RuleException("SearchErrorException: $pluginName search failed${cause?.let { " ($it)" } ?: ""}", cause)

class ChapterErrorException(val pluginName: String, cause: Throwable? = null) :
    RuleException("ChapterErrorException: $pluginName chapter query failed${cause?.let { " ($it)" } ?: ""}", cause)

class XPathRuleFormatException(
    message: String,
    cause: Throwable? = null
) : RuleException(message, cause)

class ApiRuleFormatException(
    message: String,
    cause: Throwable? = null
) : RuleException(message, cause)