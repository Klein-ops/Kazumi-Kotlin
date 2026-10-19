package com.kazumi.port.data.repository

import com.google.gson.JsonObject
import com.kazumi.port.data.model.ApiEndpoints
import com.kazumi.port.data.model.DanmakuChConvert
import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.data.model.DanmakuUtils
import com.kazumi.port.network.DanmakuClient

/**
 * 弹幕（DanDanPlay）API 封装
 * 对应 Dart danmaku_api.dart
 */
class DanmakuApi(
    private val settings: SettingsManager? = null,
    private val client: DanmakuClient = DanmakuClient(settings)
) {

    /**
     * 通过 Bangumi ID 获取 DanDanPlay 番剧 ID
     * 对应 getDanDanBangumiIDByBgmBangumiID()
     */
    suspend fun getDanDanBangumiIDByBgmBangumiID(bgmBangumiID: Int): Int {
        val path = ApiEndpoints.formatUrl(
            ApiEndpoints.DANDAN_API_INFO_BY_BGM_BANGUMI_ID,
            listOf(bgmBangumiID)
        )
        val endPoint = ApiEndpoints.DANDAN_API_DOMAIN + path
        val jsonData = client.get(endPoint)
        return DanmakuEpisodeResponse.fromJson(jsonData.asJsonObject).bangumiId
    }

    /**
     * 通过 DanDanPlay 番剧 ID 获取分集列表
     * 对应 getDanDanEpisodesByDanDanBangumiID()
     */
    suspend fun getDanDanEpisodesByDanDanBangumiID(bangumiID: Int): DanmakuEpisodeResponse {
        val endPoint = ApiEndpoints.DANDAN_API_DOMAIN + ApiEndpoints.DANDAN_API_INFO + bangumiID.toString()
        val jsonData = client.get(endPoint)
        return DanmakuEpisodeResponse.fromJson(jsonData.asJsonObject)
    }

    /**
     * v2 分集搜索（避免 anime 搜索的 25 条上限）
     * 对应 searchAnimes()
     */
    suspend fun searchAnimes(title: String): DanmakuSearchResponse {
        val endPoint = ApiEndpoints.DANDAN_API_DOMAIN + ApiEndpoints.DANDAN_API_SEARCH_EPISODES
        val jsonData = client.get(
            endPoint,
            queryParameters = mapOf("anime" to title, "v2" to "true")
        )
        return DanmakuSearchResponse.fromJson(jsonData.asJsonObject)
    }

    /**
     * 获取弹幕（按番剧 ID + 集数）
     * 对应 getDanDanmaku()
     */
    suspend fun getDanDanmaku(bangumiID: Int, episode: Int): List<DanmakuEntry> {
        if (bangumiID == 0) return emptyList()
        // 依赖未公开的集数 ID 约定
        return getComments("$bangumiID${episode.toString().padStart(4, '0')}")
    }

    /**
     * 按分集 ID 获取弹幕
     * 对应 getDanDanmakuByEpisodeID()
     */
    suspend fun getDanDanmakuByEpisodeID(episodeID: Int): List<DanmakuEntry> =
        getComments(episodeID.toString())

    private suspend fun getComments(episodeID: String): List<DanmakuEntry> {
        val endPoint = ApiEndpoints.DANDAN_API_DOMAIN + ApiEndpoints.DANDAN_API_COMMENT + episodeID
        val chConvert = settings?.let {
            DanmakuChConvert.fromValue(
                it.getInt(SettingsManager.Danmaku.CH_CONVERT, 0)
            )
        } ?: DanmakuChConvert.NONE

        val jsonData = client.get(
            endPoint,
            queryParameters = mapOf(
                "withRelated" to "true",
                "chConvert" to chConvert.value.toString()
            )
        )
        val comments = jsonData.asJsonObject.getAsJsonArray("comments") ?: return emptyList()
        return comments.mapNotNull { c ->
            if (c.isJsonObject) {
                DanmakuEntry.fromJson(c.asJsonObject)
            } else null
        }
    }
}

/**
 * 弹幕分集
 * 对应 Dart DanmakuEpisode
 */
data class DanmakuEpisode(
    val episodeId: Int,
    val episodeTitle: String
)

/**
 * 弹幕分集响应
 * 对应 Dart DanmakuEpisodeResponse
 */
data class DanmakuEpisodeResponse(
    val bangumiId: Int,
    val episodes: List<DanmakuEpisode>,
    val errorCode: Int,
    val success: Boolean,
    val errorMessage: String
) {
    companion object {
        fun fromJson(json: JsonObject): DanmakuEpisodeResponse {
            val bangumi = json.getAsJsonObject("bangumi") ?: JsonObject()
            val episodesList = bangumi.getAsJsonArray("episodes") ?: return fromTemplate()
            val episodeList = episodesList.mapNotNull { e ->
                if (e.isJsonObject) {
                    DanmakuEpisode(
                        episodeId = e.asJsonObject.get("episodeId")?.asInt ?: 0,
                        episodeTitle = e.asJsonObject.get("episodeTitle")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    )
                } else null
            }
            return DanmakuEpisodeResponse(
                bangumiId = bangumi.get("animeId")?.asInt ?: 0,
                episodes = episodeList,
                errorCode = json.get("errorCode")?.asInt ?: 0,
                success = json.get("success")?.asBoolean ?: false,
                errorMessage = json.get("errorMessage")?.takeIf { !it.isJsonNull }?.asString ?: ""
            )
        }

        fun fromTemplate(): DanmakuEpisodeResponse =
            DanmakuEpisodeResponse(0, emptyList(), 0, false, "")
    }
}

/**
 * 弹幕搜索番剧
 * 对应 Dart DanmakuSearchAnime
 */
data class DanmakuSearchAnime(
    val animeId: Int,
    val animeTitle: String,
    val typeDescription: String = ""
)

/**
 * 弹幕搜索响应
 * 对应 Dart DanmakuSearchResponse
 */
data class DanmakuSearchResponse(
    val animes: List<DanmakuSearchAnime>,
    val hasMore: Boolean
) {
    companion object {
        fun fromJson(json: JsonObject): DanmakuSearchResponse {
            val list = json.getAsJsonArray("animes") ?: return DanmakuSearchResponse(emptyList(), false)
            val animes = list.mapNotNull { e ->
                if (e.isJsonObject) {
                    val obj = e.asJsonObject
                    DanmakuSearchAnime(
                        animeId = obj.get("animeId")?.asInt ?: 0,
                        animeTitle = obj.get("animeTitle")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        typeDescription = obj.get("typeDescription")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    )
                } else null
            }
            return DanmakuSearchResponse(
                animes = animes,
                hasMore = if (json.has("hasMore")) json.get("hasMore")?.asBoolean ?: false else false
            )
        }
    }
}