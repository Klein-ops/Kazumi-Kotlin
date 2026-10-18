package com.kazumi.port.data.model

import com.google.gson.JsonObject

/**
 * Bangumi 收藏条目（仅用于解析 Bangumi API 返回的收藏数据，不适用本地收藏）
 * 对应 Dart BangumiCollection
 */
data class BangumiCollection(
    val bangumiId: Int,
    val date: String?,
    val updatedAt: String,
    val type: Int,
    val name: String,
    val nameCn: String,
    val shortSummary: String,
    val score: Double,
    val eps: Int,
    val rank: Int,
    val images: Map<String, String>,
    val tags: List<BangumiTag>
) {
    /** 转换为本地收藏用的 BangumiItem */
    fun toBangumiItem(): BangumiItem = BangumiItem(
        id = bangumiId,
        type = 2,
        name = name,
        nameCn = nameCn,
        summary = shortSummary,
        airDate = date ?: "",
        images = images,
        tags = tags,
        rank = rank,
        ratingScore = score
    )

    companion object {
        /**
         * 从 Bangumi API 收藏 JSON 解析
         * 对应 BangumiCollection.fromJson
         */
        fun fromJson(json: JsonObject): BangumiCollection {
            val subject = json.getAsJsonObject("subject") ?: JsonObject()
            val bangumiId = if (subject.has("id")) subject.get("id")?.asInt ?: 0
            else json.get("subject_id")?.asInt ?: 0
            if (bangumiId <= 0) {
                throw IllegalArgumentException("BangumiCollection: invalid or missing bangumi id")
            }

            val imagesObj = subject.getAsJsonObject("images") ?: JsonObject()
            val images = mutableMapOf<String, String>()
            imagesObj.entrySet().forEach { (k, v) ->
                images[k] = if (v.isJsonNull) "" else v.asString
            }

            val tags = mutableListOf<BangumiTag>()
            subject.getAsJsonArray("tags")?.forEach { e ->
                if (e.isJsonObject) {
                    try {
                        tags.add(BangumiTag.fromJson(e.asJsonObject))
                    } catch (_: Exception) {}
                }
            }

            val updatedAt = json.get("updated_at")?.takeIf { !it.isJsonNull }?.asString ?: ""
            if (updatedAt.isEmpty()) {
                throw IllegalArgumentException("BangumiCollection: missing or empty updated_at")
            }

            return BangumiCollection(
                bangumiId = bangumiId,
                date = if (subject.has("date") && !subject.get("date").isJsonNull) {
                    subject.get("date").asString
                } else null,
                updatedAt = updatedAt,
                type = if (json.has("type")) json.get("type")?.asInt ?: 0 else 0,
                name = subject.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                nameCn = subject.get("name_cn")?.takeIf { !it.isJsonNull }?.asString ?: "",
                shortSummary = subject.get("short_summary")?.takeIf { !it.isJsonNull }?.asString ?: "",
                score = if (subject.has("score")) subject.get("score").asDouble else 0.0,
                eps = if (subject.has("eps")) subject.get("eps")?.asInt ?: 0 else 0,
                rank = if (subject.has("rank")) subject.get("rank")?.asInt ?: 0 else 0,
                images = images,
                tags = tags
            )
        }
    }
}