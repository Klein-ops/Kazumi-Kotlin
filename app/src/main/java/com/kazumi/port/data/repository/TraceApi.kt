package com.kazumi.port.data.repository

import com.google.gson.JsonObject
import com.kazumi.port.data.model.ApiEndpoints
import com.kazumi.port.network.TraceClient
import java.io.File

/**
 * trace.moe 图片搜索 API
 * 对应 Dart trace_api.dart
 */
class TraceApi(
    private val client: TraceClient = TraceClient()
) {

    /**
     * 根据图片文件搜索番剧信息
     * 对应 searchAnimeByImageFile()
     */
    suspend fun searchAnimeByImageFile(
        imageFile: File,
        anilistInfo: Int = 2
    ): ImageSearchItem {
        val bytes = imageFile.readBytes()
        val data = client.post(
            ApiEndpoints.TRACE_API,
            data = bytes,
            queryParameters = mapOf("anilistInfo" to anilistInfo.toString()),
            headers = mapOf("Content-Type" to "image/jpeg")
        )
        return ImageSearchItem.fromJson(data.asJsonObject)
    }

    /**
     * 根据图片 URL 搜索番剧信息
     * 对应 searchAnimeByImageUrl()
     */
    suspend fun searchAnimeByImageUrl(
        imageUrl: String,
        anilistInfo: Int = 2
    ): ImageSearchItem {
        val data = client.post(
            ApiEndpoints.TRACE_API,
            queryParameters = mapOf(
                "anilistInfo" to anilistInfo.toString(),
                "url" to imageUrl
            )
        )
        return ImageSearchItem.fromJson(data.asJsonObject)
    }
}

/**
 * 图片搜索响应
 * 对应 Dart ImageSearchItem（保留核心字段）
 */
data class ImageSearchItem(
    val frameCount: Int? = null,
    val error: String? = null,
    val result: List<ResultItem>? = null
) {
    companion object {
        fun fromJson(json: JsonObject): ImageSearchItem {
            val resultList = json.getAsJsonArray("result")
            val results = resultList?.mapNotNull { e ->
                if (e.isJsonObject) ResultItem.fromJson(e.asJsonObject) else null
            }
            return ImageSearchItem(
                frameCount = if (json.has("frameCount")) json.get("frameCount").asInt else null,
                error = json.get("error")?.takeIf { !it.isJsonNull }?.asString,
                result = results
            )
        }
    }
}

/**
 * 搜索结果条目
 * 对应 Dart ResultItem（保留核心字段）
 */
data class ResultItem(
    val anilist: Anilist? = null,
    val filename: String? = null,
    val episode: Any? = null,
    val from: Double? = null,
    val at: Double? = null,
    val to: Double? = null,
    val duration: Double? = null,
    val similarity: Double? = null,
    val video: String? = null,
    val image: String? = null
) {
    companion object {
        fun fromJson(json: JsonObject): ResultItem {
            val epRaw = json.get("episode")
            val ep: Any? = when {
                epRaw == null || epRaw.isJsonNull -> null
                epRaw.isJsonArray -> epRaw.asJsonArray.map { it.asInt }
                epRaw.isJsonPrimitive && epRaw.asJsonPrimitive.isNumber -> epRaw.asInt
                else -> null
            }
            return ResultItem(
                anilist = json.getAsJsonObject("anilist")?.let { Anilist.fromJson(it) },
                filename = json.get("filename")?.takeIf { !it.isJsonNull }?.asString,
                episode = ep,
                from = json.get("from")?.takeIf { !it.isJsonNull }?.asDouble,
                at = json.get("at")?.takeIf { !it.isJsonNull }?.asDouble,
                to = json.get("to")?.takeIf { !it.isJsonNull }?.asDouble,
                duration = json.get("duration")?.takeIf { !it.isJsonNull }?.asDouble,
                similarity = json.get("similarity")?.takeIf { !it.isJsonNull }?.asDouble,
                video = json.get("video")?.takeIf { !it.isJsonNull }?.asString,
                image = json.get("image")?.takeIf { !it.isJsonNull }?.asString
            )
        }
    }
}

/**
 * Anilist 信息
 * 对应 Dart Anilist（保留核心字段）
 */
data class Anilist(
    val id: Int? = null,
    val type: String? = null,
    val idMal: Int? = null,
    val title: AnilistTitle? = null,
    val format: String? = null,
    val genres: List<String>? = null,
    val season: String? = null,
    val status: String? = null,
    val isAdult: Boolean? = null,
    val siteUrl: String? = null,
    val duration: Int? = null,
    val episodes: Int? = null,
    val synonyms: List<String>? = null,
    val seasonYear: Int? = null,
    val coverImage: CoverImage? = null,
    val popularity: Int? = null,
    val bannerImage: String? = null,
    val countryOfOrigin: String? = null
) {
    companion object {
        fun fromJson(json: JsonObject): Anilist {
            return Anilist(
                id = json.get("id")?.takeIf { !it.isJsonNull }?.asInt,
                type = json.get("type")?.takeIf { !it.isJsonNull }?.asString,
                idMal = json.get("idMal")?.takeIf { !it.isJsonNull }?.asInt,
                title = json.getAsJsonObject("title")?.let { AnilistTitle.fromJson(it) },
                format = json.get("format")?.takeIf { !it.isJsonNull }?.asString,
                genres = json.getAsJsonArray("genres")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null },
                season = json.get("season")?.takeIf { !it.isJsonNull }?.asString,
                status = json.get("status")?.takeIf { !it.isJsonNull }?.asString,
                isAdult = json.get("isAdult")?.takeIf { !it.isJsonNull }?.asBoolean,
                siteUrl = json.get("siteUrl")?.takeIf { !it.isJsonNull }?.asString,
                duration = json.get("duration")?.takeIf { !it.isJsonNull }?.asInt,
                episodes = json.get("episodes")?.takeIf { !it.isJsonNull }?.asInt,
                synonyms = json.getAsJsonArray("synonyms")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null },
                seasonYear = json.get("seasonYear")?.takeIf { !it.isJsonNull }?.asInt,
                coverImage = json.getAsJsonObject("coverImage")?.let { CoverImage.fromJson(it) },
                popularity = json.get("popularity")?.takeIf { !it.isJsonNull }?.asInt,
                bannerImage = json.get("bannerImage")?.takeIf { !it.isJsonNull }?.asString,
                countryOfOrigin = json.get("countryOfOrigin")?.takeIf { !it.isJsonNull }?.asString
            )
        }
    }
}

/**
 * Anilist 标题
 * 对应 Dart AnilistTitle
 */
data class AnilistTitle(
    val native: String? = null,
    val romaji: String? = null,
    val chinese: String? = null,
    val english: String? = null
) {
    companion object {
        fun fromJson(json: JsonObject): AnilistTitle = AnilistTitle(
            native = json.get("native")?.takeIf { !it.isJsonNull }?.asString,
            romaji = json.get("romaji")?.takeIf { !it.isJsonNull }?.asString,
            chinese = json.get("chinese")?.takeIf { !it.isJsonNull }?.asString,
            english = json.get("english")?.takeIf { !it.isJsonNull }?.asString
        )
    }
}

/**
 * 封面图片
 * 对应 Dart CoverImage
 */
data class CoverImage(
    val color: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val extraLarge: String? = null
) {
    companion object {
        fun fromJson(json: JsonObject): CoverImage = CoverImage(
            color = json.get("color")?.takeIf { !it.isJsonNull }?.asString,
            large = json.get("large")?.takeIf { !it.isJsonNull }?.asString,
            medium = json.get("medium")?.takeIf { !it.isJsonNull }?.asString,
            extraLarge = json.get("extraLarge")?.takeIf { !it.isJsonNull }?.asString
        )
    }
}