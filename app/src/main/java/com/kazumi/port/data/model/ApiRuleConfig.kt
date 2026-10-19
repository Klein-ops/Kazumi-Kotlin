package com.kazumi.port.data.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 规则模式：xpath / api
 * 对应 Dart RuleMode
 */
object RuleMode {
    const val XPATH = "xpath"
    const val API = "api"

    fun normalize(value: Any?): String = if (value == API) API else XPATH
}

/**
 * API 请求体类型
 * 对应 Dart ApiBodyType
 */
object ApiBodyType {
    const val NONE = "none"
    const val JSON = "json"
    const val FORM = "form"

    fun normalize(value: Any?): String = when (value) {
        JSON -> JSON
        FORM -> FORM
        else -> NONE
    }
}

/**
 * 章节格式：nested / delimited
 * 对应 Dart ApiChapterFormat
 */
object ApiChapterFormat {
    const val NESTED = "nested"
    const val DELIMITED = "delimited"

    fun normalize(value: Any?): String = if (value == DELIMITED) DELIMITED else NESTED
}

/**
 * API 请求配置
 * 对应 Dart ApiRequestConfig
 */
data class ApiRequestConfig(
    var method: String = "GET",
    var url: String = "",
    var headers: Map<String, Any?> = emptyMap(),
    var query: Map<String, Any?> = emptyMap(),
    var bodyType: String = ApiBodyType.NONE,
    var body: Any? = null
) {
    companion object {
        fun fromJson(json: JsonObject): ApiRequestConfig {
            val headers = parseStringMap(json.get("headers"))
            val query = parseStringMap(json.get("query"))
            return ApiRequestConfig(
                method = (json.get("method")?.asString ?: "GET").uppercase(),
                url = json.get("url")?.asString ?: "",
                headers = headers,
                query = query,
                bodyType = ApiBodyType.normalize(json.get("bodyType")?.asString),
                body = json.get("body")?.let { if (it.isJsonNull) null else it }
            )
        }
    }

    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("method", method.uppercase())
        obj.addProperty("url", url)
        if (headers.isNotEmpty()) obj.add("headers", Gson().toJsonTree(headers))
        if (query.isNotEmpty()) obj.add("query", Gson().toJsonTree(query))
        if (bodyType != ApiBodyType.NONE) {
            obj.addProperty("bodyType", bodyType)
            if (body != null) obj.add("body", Gson().toJsonTree(body))
        }
        return obj
    }
}

/**
 * API 搜索配置
 * 对应 Dart ApiSearchConfig
 */
data class ApiSearchConfig(
    var request: ApiRequestConfig = ApiRequestConfig(),
    var listPath: String = "$.data[*]",
    var namePath: String = "$.name",
    var sourcePath: String = "$.url",
    var sourceTemplate: String = ""
) {
    companion object {
        fun fromJson(json: JsonObject): ApiSearchConfig {
            return ApiSearchConfig(
                request = json.get("request")?.takeIf { it.isJsonObject }
                    ?.let { ApiRequestConfig.fromJson(it.asJsonObject) }
                    ?: ApiRequestConfig(),
                listPath = json.get("listPath")?.asString ?: "$.data[*]",
                namePath = json.get("namePath")?.asString ?: "$.name",
                sourcePath = json.get("sourcePath")?.asString ?: "$.url",
                sourceTemplate = (json.get("sourceTemplate")?.asString ?: "").trim()
            )
        }
    }
    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.add("request", request.toJson())
        obj.addProperty("listPath", listPath)
        obj.addProperty("namePath", namePath)
        obj.addProperty("sourcePath", sourcePath)
        if (sourceTemplate.isNotEmpty()) obj.addProperty("sourceTemplate", sourceTemplate)
        return obj
    }
}

/**
 * 播放页配置
 * 对应 Dart ApiEpisodePageConfig
 */
data class ApiEpisodePageConfig(
    var url: String = "",
    var query: Map<String, Any?> = emptyMap()
) {
    companion object {
        fun fromJson(json: JsonObject): ApiEpisodePageConfig {
            return ApiEpisodePageConfig(
                url = json.get("url")?.asString ?: "",
                query = parseStringMap(json.get("query"))
            )
        }
    }

    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("url", url)
        obj.add("query", Gson().toJsonTree(query))
        return obj
    }
}

/**
 * API 章节配置
 * 对应 Dart ApiChapterConfig
 */
data class ApiChapterConfig(
    var request: ApiRequestConfig = ApiRequestConfig(),
    var format: String = ApiChapterFormat.NESTED,
    // nested
    var roadsPath: String = "$.data.roads[*]",
    var roadNamePath: String = "$.name",
    var episodesPath: String = "$.episodes[*]",
    var episodeNamePath: String = "$.name",
    var episodeUrlPath: String = "$.url",
    // delimited
    var roadNamesPath: String = "",
    var roadEpisodesPath: String = "",
    var roadSeparator: String = "$$$",
    var episodeSeparator: String = "#",
    var fieldSeparator: String = "$",
    // variables
    var variables: Map<String, String> = emptyMap(),
    var episodePage: ApiEpisodePageConfig? = null
) {
    companion object {
        fun fromJson(json: JsonObject): ApiChapterConfig {
            val rawVariables = parseStringMap(json.get("variables"))
            return ApiChapterConfig(
                request = json.get("request")?.takeIf { it.isJsonObject }
                    ?.let { ApiRequestConfig.fromJson(it.asJsonObject) }
                    ?: ApiRequestConfig(),
                format = ApiChapterFormat.normalize(json.get("format")?.asString),
                roadsPath = json.get("roadsPath")?.asString ?: "$.data.roads[*]",
                roadNamePath = json.get("roadNamePath")?.asString ?: "$.name",
                episodesPath = json.get("episodesPath")?.asString ?: "$.episodes[*]",
                episodeNamePath = json.get("episodeNamePath")?.asString ?: "$.name",
                episodeUrlPath = json.get("episodeUrlPath")?.asString ?: "$.url",
                roadNamesPath = json.get("roadNamesPath")?.asString ?: "",
                roadEpisodesPath = json.get("roadEpisodesPath")?.asString ?: "",
                roadSeparator = json.get("roadSeparator")?.asString ?: "$$$",
                episodeSeparator = json.get("episodeSeparator")?.asString ?: "#",
                fieldSeparator = json.get("fieldSeparator")?.asString ?: "$",
                variables = rawVariables.mapValues { it.value.toString() },
                episodePage = json.get("episodePage")?.takeIf { it.isJsonObject }
                    ?.let { ApiEpisodePageConfig.fromJson(it.asJsonObject) }
            )
        }
    }

    fun toJson(): JsonObject {
        val obj = JsonObject()
        obj.add("request", request.toJson())
        obj.addProperty("format", format)
        if (format == ApiChapterFormat.NESTED || hasNestedConfig) {
            obj.addProperty("roadsPath", roadsPath)
            obj.addProperty("roadNamePath", roadNamePath)
            obj.addProperty("episodesPath", episodesPath)
            obj.addProperty("episodeNamePath", episodeNamePath)
            obj.addProperty("episodeUrlPath", episodeUrlPath)
        }
        if (format == ApiChapterFormat.DELIMITED || hasDelimitedConfig) {
            obj.addProperty("roadNamesPath", roadNamesPath)
            obj.addProperty("roadEpisodesPath", roadEpisodesPath)
            obj.addProperty("roadSeparator", roadSeparator)
            obj.addProperty("episodeSeparator", episodeSeparator)
            obj.addProperty("fieldSeparator", fieldSeparator)
        }
        if (variables.isNotEmpty()) obj.add("variables", Gson().toJsonTree(variables))
        episodePage?.let { obj.add("episodePage", it.toJson()) }
        return obj
    }

    private val hasNestedConfig: Boolean
        get() = roadsPath != "$.data.roads[*]" ||
                roadNamePath != "$.name" ||
                episodesPath != "$.episodes[*]" ||
                episodeNamePath != "$.name" ||
                episodeUrlPath != "$.url"

    private val hasDelimitedConfig: Boolean
        get() = roadNamesPath.isNotEmpty() || roadEpisodesPath.isNotEmpty()
}

/** 解析 JSON 对象为 Map<String, Any?>（headers/query/variables 用）。
 *  注意：调用点传的都是 JsonElement?（json.get(...)），只用这一个重载即可；
 *  曾有两个重载(Any?/JsonElement?)导致 Kotlin 重载解析命中 JsonElement? 并自我递归 → StackOverflowError。 */
private fun parseStringMap(value: com.google.gson.JsonElement?): Map<String, Any?> {
    if (value == null || !value.isJsonObject) return emptyMap()
    val obj = value.asJsonObject
    val result = HashMap<String, Any?>()
    obj.entrySet().forEach { (key, v) ->
        result[key] = if (v.isJsonPrimitive) {
            val p = v.asJsonPrimitive
            when {
                p.isString -> p.asString
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber
                else -> p.asString
            }
        } else {
            v
        }
    }
    return result
}
