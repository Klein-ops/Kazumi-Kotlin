package com.kazumi.port.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 反爬虫配置
 * 对应 Dart AntiCrawlerConfig
 */
enum class CaptchaDetectType(val value: String) {
    TEXT("text"), REGEX("regex"), XPATH("xpath");

    companion object {
        fun normalize(value: Any?): CaptchaDetectType = when (value) {
            "text" -> TEXT
            "regex" -> REGEX
            else -> XPATH
        }
    }
}

data class AntiCrawlerConfig(
    val enabled: Boolean = false,
    val captchaDetectType: CaptchaDetectType = CaptchaDetectType.XPATH,
    val captchaDetectValue: String = "",
    val captchaImage: String = "",
    val captchaButton: String = "",
    val captchaCss: String = "",
    val captchaUserAgent: String = "",
    val captchaReferer: String = ""
) {
    companion object {
        fun empty(): AntiCrawlerConfig = AntiCrawlerConfig()

        fun fromJson(json: JsonObject): AntiCrawlerConfig {
            return AntiCrawlerConfig(
                enabled = json.get("enabled")?.asBoolean ?: false,
                captchaDetectType = CaptchaDetectType.normalize(json.get("captchaDetectType")?.asString),
                captchaDetectValue = json.get("captchaDetectValue")?.asString ?: "",
                captchaImage = json.get("captchaImage")?.asString ?: "",
                captchaButton = json.get("captchaButton")?.asString ?: "",
                captchaCss = json.get("captchaCss")?.asString ?: "",
                captchaUserAgent = json.get("captchaUserAgent")?.asString ?: "",
                captchaReferer = json.get("captchaReferer")?.asString ?: ""
            )
        }
    }

    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("enabled", enabled)
        obj.addProperty("captchaDetectType", captchaDetectType.value)
        obj.addProperty("captchaDetectValue", captchaDetectValue)
        obj.addProperty("captchaImage", captchaImage)
        obj.addProperty("captchaButton", captchaButton)
        obj.addProperty("captchaCss", captchaCss)
        obj.addProperty("captchaUserAgent", captchaUserAgent)
        obj.addProperty("captchaReferer", captchaReferer)
        return obj
    }
}

/**
 * 番剧插件规则（含 XPath/API 两种模式）
 * 对应 Dart Plugin
 */
data class Plugin(
    var api: String = "1",
    var type: String = "anime",
    var name: String = "",
    var version: String = "",
    var muliSources: Boolean = true,
    var useWebview: Boolean = true,
    var useNativePlayer: Boolean = true,
    var usePost: Boolean = false,
    var useLegacyParser: Boolean = false,
    var adBlocker: Boolean = false,
    var userAgent: String = "",
    var baseUrl: String = "",
    var searchURL: String = "",
    var searchList: String = "",
    var searchName: String = "",
    var searchResult: String = "",
    var chapterRoads: String = "",
    var chapterResult: String = "",
    var referer: String = "",
    var sort: Int = 0,
    var searchMode: String = RuleMode.XPATH,
    var chapterMode: String = RuleMode.XPATH,
    var searchApiConfig: ApiSearchConfig = ApiSearchConfig(),
    var chapterApiConfig: ApiChapterConfig = ApiChapterConfig(),
    var antiCrawlerConfig: AntiCrawlerConfig = AntiCrawlerConfig.empty()
) {
    val usesApiSearch: Boolean get() = searchMode == RuleMode.API

    val requiresNewerClient: Boolean
        get() = (api.toIntOrNull() ?: 1) > ApiEndpoints.API_LEVEL

    companion object {
        fun fromJson(json: JsonObject): Plugin {
            return Plugin(
                api = json.get("api")?.asString ?: "1",
                type = json.get("type")?.asString ?: "anime",
                name = json.get("name")?.asString ?: "",
                version = json.get("version")?.asString ?: "",
                muliSources = json.get("muliSources")?.asBoolean ?: true,
                useWebview = json.get("useWebview")?.asBoolean ?: true,
                useNativePlayer = json.get("useNativePlayer")?.asBoolean ?: true,
                usePost = json.get("usePost")?.asBoolean ?: false,
                useLegacyParser = json.get("useLegacyParser")?.asBoolean ?: false,
                adBlocker = json.get("adBlocker")?.asBoolean ?: false,
                userAgent = json.get("userAgent")?.asString ?: "",
                baseUrl = json.get("baseURL")?.asString ?: "",
                searchURL = json.get("searchURL")?.asString ?: "",
                searchList = json.get("searchList")?.asString ?: "",
                searchName = json.get("searchName")?.asString ?: "",
                searchResult = json.get("searchResult")?.asString ?: "",
                chapterRoads = json.get("chapterRoads")?.asString ?: "",
                chapterResult = json.get("chapterResult")?.asString ?: "",
                referer = json.get("referer")?.asString ?: "",
                sort = json.get("sort")?.asInt ?: 0,
                searchMode = RuleMode.normalize(json.get("searchMode")?.asString),
                chapterMode = RuleMode.normalize(json.get("chapterMode")?.asString),
                searchApiConfig = json.get("searchApiConfig")?.takeIf { it.isJsonObject }
                    ?.let { ApiSearchConfig.fromJson(it.asJsonObject) }
                    ?: ApiSearchConfig(),
                chapterApiConfig = json.get("chapterApiConfig")?.takeIf { it.isJsonObject }
                    ?.let { ApiChapterConfig.fromJson(it.asJsonObject) }
                    ?: ApiChapterConfig(),
                antiCrawlerConfig = json.get("antiCrawlerConfig")?.takeIf { it.isJsonObject }
                    ?.let { AntiCrawlerConfig.fromJson(it.asJsonObject) }
                    ?: AntiCrawlerConfig.empty()
            )
        }

        fun fromTemplate(): Plugin = Plugin()
    }

    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("api", api)
        obj.addProperty("type", type)
        obj.addProperty("name", name)
        obj.addProperty("version", version)
        obj.addProperty("muliSources", muliSources)
        obj.addProperty("useWebview", useWebview)
        obj.addProperty("useNativePlayer", useNativePlayer)
        obj.addProperty("usePost", usePost)
        obj.addProperty("useLegacyParser", useLegacyParser)
        obj.addProperty("adBlocker", adBlocker)
        obj.addProperty("userAgent", userAgent)
        obj.addProperty("baseURL", baseUrl)
        obj.addProperty("searchURL", searchURL)
        obj.addProperty("searchList", searchList)
        obj.addProperty("searchName", searchName)
        obj.addProperty("searchResult", searchResult)
        obj.addProperty("chapterRoads", chapterRoads)
        obj.addProperty("chapterResult", chapterResult)
        obj.addProperty("referer", referer)
        obj.addProperty("sort", sort)
        obj.addProperty("searchMode", searchMode)
        obj.addProperty("chapterMode", chapterMode)
        if (searchMode == RuleMode.API || searchApiConfig.request.url.isNotEmpty()) {
            obj.add("searchApiConfig", searchApiConfig.toJson())
        }
        if (chapterMode == RuleMode.API || chapterApiConfig.request.url.isNotEmpty()) {
            obj.add("chapterApiConfig", chapterApiConfig.toJson())
        }
        obj.add("antiCrawlerConfig", antiCrawlerConfig.toJson())
        return obj
    }

    /**
     * 构建搜索/章节请求执行配置
     */
    fun executionConfig(): RuleExecutionConfig {
        return RuleExecutionConfig(
            pluginName = name,
            baseUrl = baseUrl,
            usePost = usePost,
            searchMode = searchMode,
            chapterMode = chapterMode,
            searchUrl = searchURL,
            searchList = searchList,
            searchName = searchName,
            searchResult = searchResult,
            chapterRoads = chapterRoads,
            chapterResult = chapterResult,
            searchApiConfig = searchApiConfig,
            chapterApiConfig = chapterApiConfig,
            antiCrawlerConfig = antiCrawlerConfig
        )
    }

    /**
     * 用于播放/下载的请求头
     */
    fun buildHttpHeaders(): Map<String, String> {
        val headers = HashMap<String, String>()
        headers["user-agent"] = if (userAgent.isEmpty()) HttpHeaders.randomUA() else userAgent
        if (referer.isNotEmpty()) headers["referer"] = referer
        return headers
    }
}