package com.kazumi.port.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 播放线路（包含多个剧集 URL）
 * 对应 Dart Road
 */
data class Road(
    var name: String,
    var data: List<String>,
    var identifier: List<String>
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        add("data", JsonArray().apply { data.forEach { add(it) } })
        add("identifier", JsonArray().apply { identifier.forEach { add(it) } })
    }

    companion object {
        fun fromJson(json: JsonObject): Road = Road(
            name = json.get("name")?.asString ?: "",
            data = json.getAsJsonArray("data")?.map { it.asString } ?: emptyList(),
            identifier = json.getAsJsonArray("identifier")?.map { it.asString } ?: emptyList()
        )
    }
}