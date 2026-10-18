package com.kazumi.port.data.model

import com.google.gson.JsonObject

/**
 * 番剧搜索条目
 * 对应 Dart SearchItem
 */
data class SearchItem(
    var name: String,
    var src: String
) {
    companion object {
        fun fromJson(json: JsonObject): SearchItem {
            return SearchItem(
                name = json.get("name")?.asString ?: "",
                src = json.get("src")?.asString ?: ""
            )
        }
    }
}

/**
 * 插件搜索结果
 * 对应 Dart PluginSearchResponse
 */
data class PluginSearchResponse(
    val pluginName: String,
    val data: List<SearchItem>
) {
    companion object {
        fun fromJson(json: JsonObject): PluginSearchResponse {
            val list = json.getAsJsonArray("data")?.map {
                SearchItem.fromJson(it.asJsonObject)
            } ?: emptyList()
            return PluginSearchResponse(
                pluginName = json.get("pluginName")?.asString ?: "",
                data = list
            )
        }
    }
}

/**
 * 搜索状态
 * 对应 Dart PluginSearchStatus
 */
enum class PluginSearchStatus {
    PENDING, SUCCESS, ERROR, NO_RESULT, CAPTCHA
}