package com.kazumi.port.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kazumi.port.data.model.ApiEndpoints
import com.kazumi.port.data.model.BangumiCollection
import com.kazumi.port.data.model.BangumiItem
import com.kazumi.port.data.model.BangumiRelation
import com.kazumi.port.data.model.CharacterCommentResponse
import com.kazumi.port.data.model.CharacterFullItem
import com.kazumi.port.data.model.CharacterItem
import com.kazumi.port.data.model.CommentResponse
import com.kazumi.port.data.model.SearchDateRange
import com.kazumi.port.data.model.SearchDoubleRange
import com.kazumi.port.data.model.SearchIntRange
import com.kazumi.port.data.model.StaffResponse
import com.kazumi.port.network.BangumiClient

/**
 * Bangumi API 封装
 * 对应 Dart bangumi_api.dart
 */
class BangumiApi(
    private val client: BangumiClient
) {

    /**
     * 每日放送（一周七天）
     * 对应 getCalendar()
     */
    suspend fun getCalendar(): List<List<BangumiItem>> {
        val calendar = mutableListOf<List<BangumiItem>>()
        try {
            val jsonData = client.get(
                ApiEndpoints.BANGUMI_API_NEXT_DOMAIN + ApiEndpoints.BANGUMI_CALENDAR
            )
            if (jsonData.isJsonObject) {
                for (i in 1..7) {
                    val list = mutableListOf<BangumiItem>()
                    val jsonList = jsonData.asJsonObject.getAsJsonArray("$i")
                    if (jsonList != null) {
                        for (jsonItem in jsonList) {
                            try {
                                val subject = jsonItem.asJsonObject.getAsJsonObject("subject")
                                if (subject != null) {
                                    list.add(BangumiItem.fromJson(subject))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    calendar.add(list)
                }
            }
        } catch (e: Exception) {
            // 日历解析失败时返回空列表
        }
        return calendar
    }

    /**
     * 通过搜索构建季度日历（官方回退方案）
     * 对应 getCalendarBySearch()
     */
    suspend fun getCalendarBySearch(
        dateRange: List<String>,
        limit: Int,
        offset: Int
    ): List<List<BangumiItem>> {
        val bangumiList = mutableListOf<BangumiItem>()
        val params = JsonObject().apply {
            addProperty("keyword", "")
            addProperty("sort", "rank")
            val filter = JsonObject()
            val type = JsonArray(); type.add(2); filter.add("type", type)
            val tag = JsonArray(); tag.add("日本"); filter.add("tag", tag)
            val airDate = JsonArray()
            airDate.add(">=${dateRange[0]}")
            airDate.add("<${dateRange[1]}")
            filter.add("air_date", airDate)
            val rank = JsonArray()
            rank.add(">0"); rank.add("<=99999")
            filter.add("rank", rank)
            filter.addProperty("nsfw", true)
            add("filter", filter)
        }
        try {
            val url = ApiEndpoints.formatUrl(
                ApiEndpoints.BANGUMI_API_DOMAIN + ApiEndpoints.BANGUMI_RANK_SEARCH,
                listOf(limit, offset)
            )
            val jsonData = client.post(url, data = params)
            val jsonList = jsonData.asJsonObject.getAsJsonArray("data")
            if (jsonList != null) {
                for (jsonItem in jsonList) {
                    if (jsonItem.isJsonObject) {
                        bangumiList.add(BangumiItem.fromJson(jsonItem.asJsonObject))
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
        val calendar = mutableListOf<List<BangumiItem>>()
        for (weekday in 1..7) {
            val dayList = bangumiList.filter { it.airWeekday == weekday }
            calendar.add(dayList)
        }
        return calendar
    }

    /**
     * 获取 Bangumi 列表（排行榜）
     * 对应 getBangumiList()
     */
    suspend fun getBangumiList(rank: Int = 2, tag: String = ""): List<BangumiItem> {
        val bangumiList = mutableListOf<BangumiItem>()
        val params = JsonObject().apply {
            addProperty("keyword", "")
            addProperty("sort", "rank")
            val filter = JsonObject()
            val type = JsonArray(); type.add(2); filter.add("type", type)
            val tagArr = JsonArray(); tagArr.add(if (tag.isEmpty()) "日本" else tag); filter.add("tag", tagArr)
            val rankArr = JsonArray()
            rankArr.add(">$rank")
            rankArr.add(if (tag.isEmpty()) "<=1050" else "<=99999")
            filter.add("rank", rankArr)
            filter.addProperty("nsfw", false)
            add("filter", filter)
        }
        try {
            val jsonData = client.post(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_DOMAIN + ApiEndpoints.BANGUMI_RANK_SEARCH,
                    listOf(100, 0)
                ),
                data = params
            )
            val jsonList = jsonData.asJsonObject.getAsJsonArray("data")
            if (jsonList != null) {
                for (jsonItem in jsonList) {
                    if (jsonItem.isJsonObject) {
                        bangumiList.add(BangumiItem.fromJson(jsonItem.asJsonObject))
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
        return bangumiList
    }

    /**
     * 获取 Kazumi 镜像榜单（直连失败时回退）
     * 对应 getBangumiMirrorPopularSubjects()
     */
    suspend fun getBangumiMirrorPopularSubjects(
        tag: String = "",
        limit: Int = 24,
        offset: Int = 0
    ): List<BangumiItem> {
        val bangumiList = mutableListOf<BangumiItem>()
        val params = mutableMapOf<String, String>()
        if (tag.isNotEmpty()) params["tag"] = tag
        params["limit"] = limit.toString()
        params["offset"] = offset.toString()
        try {
            val jsonData = client.get(
                ApiEndpoints.BANGUMI_MIRROR_DOMAIN + ApiEndpoints.BANGUMI_MIRROR_POPULAR_SUBJECTS,
                queryParameters = params
            )
            val jsonList = if (jsonData.isJsonArray) jsonData.asJsonArray
            else jsonData.asJsonObject.getAsJsonArray("data")
            if (jsonList != null) {
                for (jsonItem in jsonList) {
                    if (jsonItem.isJsonObject) {
                        bangumiList.add(BangumiItem.fromJson(jsonItem.asJsonObject))
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
        return bangumiList
    }

    /**
     * 获取 Bangumi 流行/趋势列表
     * 对应 getBangumiTrendsList()
     */
    suspend fun getBangumiTrendsList(type: Int = 2, limit: Int = 24, offset: Int = 0): List<BangumiItem> {
        val bangumiList = mutableListOf<BangumiItem>()
        val params = mapOf(
            "type" to type.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString()
        )
        try {
            val jsonData = client.get(
                ApiEndpoints.BANGUMI_API_NEXT_DOMAIN + ApiEndpoints.BANGUMI_TRENDS_NEXT,
                queryParameters = params
            )
            val jsonList = jsonData.asJsonObject.getAsJsonArray("data")
            if (jsonList != null) {
                for (jsonItem in jsonList) {
                    if (jsonItem.isJsonObject) {
                        val subject = jsonItem.asJsonObject.getAsJsonObject("subject")
                        bangumiList.add(BangumiItem.fromJson(subject ?: jsonItem.asJsonObject))
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
        return bangumiList
    }

    /**
     * 搜索 Bangumi
     * 对应 bangumiSearch()（含筛选参数：tags/sort/dateRange/rankRange/scoreRange/weekdays）
     */
    suspend fun bangumiSearch(
        keyword: String,
        limit: Int = 20,
        offset: Int = 0,
        sort: String = "heat",
        tags: List<String> = emptyList(),
        dateRange: SearchDateRange? = null,
        rankRange: SearchIntRange? = null,
        scoreRange: SearchDoubleRange? = null,
        weekdays: List<Int> = emptyList()
    ): BangumiSearchPage? {
        val bangumiList = mutableListOf<BangumiItem>()
        val params = JsonObject().apply {
            addProperty("keyword", keyword)
            addProperty("sort", sort)
            val filter = JsonObject()
            val type = JsonArray(); type.add(2); filter.add("type", type)
            val tagArr = JsonArray()
            tags.forEach { tagArr.add(it) }
            filter.add("tag", tagArr)
            val rankArr = JsonArray()
            if (rankRange?.isValid == true) {
                rankRange.min?.let { rankArr.add(">=$it") }
                rankArr.add(if (rankRange.max != null) "<=${rankRange.max}" else "<=99999")
            } else if (sort == "rank") {
                rankArr.add(">0"); rankArr.add("<=99999")
            } else {
                rankArr.add(">=0"); rankArr.add("<=99999")
            }
            filter.add("rank", rankArr)
            filter.addProperty("nsfw", false)
            if (dateRange?.isValid == true) {
                val airDate = JsonArray()
                airDate.add(">=${dateRange.start}")
                airDate.add("<${dateRange.end}")
                filter.add("air_date", airDate)
            }
            if (scoreRange?.isValid == true) {
                val rating = JsonArray()
                scoreRange.min?.let { rating.add(">=$it") }
                rating.add(if (scoreRange.max != null) "<=${scoreRange.max}" else "<=10")
                filter.add("rating", rating)
            }
            if (weekdays.isNotEmpty()) {
                val airWeekday = JsonArray()
                weekdays.toSet().sorted().forEach { airWeekday.add(it) }
                filter.add("air_weekday", airWeekday)
            }
            add("filter", filter)
        }
        return try {
            val jsonData = client.post(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_DOMAIN + ApiEndpoints.BANGUMI_RANK_SEARCH,
                    listOf(limit, offset)
                ),
                data = params
            )
            val jsonList = jsonData.asJsonObject.getAsJsonArray("data")
            if (jsonList != null) {
                for (jsonItem in jsonList) {
                    if (jsonItem.isJsonObject) {
                        try {
                            val item = BangumiItem.fromJson(jsonItem.asJsonObject)
                            if (item.nameCn.isNotEmpty()) {
                                bangumiList.add(item)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            BangumiSearchPage(bangumiList, jsonList?.size() ?: 0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 按 ID 获取 Bangumi 详情
     * 对应 getBangumiInfoByID()
     */
    suspend fun getBangumiInfoByID(id: Int): BangumiItem? {
        return try {
            val jsonData = client.get(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_NEXT_DOMAIN + ApiEndpoints.BANGUMI_INFO_BY_ID_NEXT,
                    listOf(id)
                )
            )
            if (jsonData.isJsonObject) {
                BangumiItem.fromJson(jsonData.asJsonObject)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取当前用户信息
     * 对应 getCurrentUser()
     */
    suspend fun getCurrentUser(accessToken: String? = null): JsonObject? {
        return try {
            val jsonData = client.get(
                ApiEndpoints.BANGUMI_AUTH_API_MIRROR_DOMAIN + ApiEndpoints.BANGUMI_USERNAME_BY_TOKEN,
                requiresAuth = true,
                accessToken = accessToken
            )
            if (jsonData.isJsonObject && jsonData.asJsonObject.has("id")) {
                jsonData.asJsonObject
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 更新收藏状态
     * 对应 updateBangumiById()
     */
    suspend fun updateBangumiById(id: Int, data: JsonObject): Boolean {
        return try {
            client.post(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_AUTH_API_MIRROR_DOMAIN + ApiEndpoints.BANGUMI_SET_COLLECTION,
                    listOf(id)
                ),
                data = data,
                requiresAuth = true
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 按本地收藏类型更新 Bangumi 状态
     * 对应 updateBangumiByType()
     */
    suspend fun updateBangumiByType(id: Int, localType: Int): Boolean {
        val bangumiType = when (localType) {
            CollectType.WATCHING -> 3 // watching
            CollectType.WANT_TO_WATCH -> 1 // planToWatch
            CollectType.ON_HOLD -> 2 // onHold
            CollectType.COMPLETED -> 4 // watched
            CollectType.DROPPED -> 5 // abandoned
            else -> return false
        }
        val data = JsonObject().apply { addProperty("type", bangumiType) }
        return updateBangumiById(id, data)
    }

    /**
     * 拉取用户全部收藏（分页）
     * 对应 getBangumiCollectibles()
     */
    suspend fun getBangumiCollectibles(
        username: String,
        limit: Int = 50
    ): List<BangumiCollection> {
        val result = mutableListOf<BangumiCollection>()
        val includeTypes = listOf(1, 2, 3, 4, 5) // planToWatch/onHold/watching/watched/abandoned
        for (type in includeTypes) {
            var offset = 0
            while (true) {
                val page = getBangumiCollectionPage(
                    username = username,
                    type = type,
                    limit = limit,
                    offset = offset
                )
                if (page.isEmpty()) break
                result.addAll(page)
                if (page.size < limit) break
                offset += limit
            }
        }
        return result
    }

    /**
     * 单页收藏列表
     * GET /v0/users/{username}/collections?type={type}&limit={limit}&offset={offset}
     */
    private suspend fun getBangumiCollectionPage(
        username: String,
        type: Int,
        limit: Int,
        offset: Int
    ): List<BangumiCollection> {
        return try {
            val url = ApiEndpoints.formatUrl(
                ApiEndpoints.BANGUMI_API_DOMAIN + ApiEndpoints.BANGUMI_GET_ALL_COLLECTIONS,
                listOf(username, limit, offset)
            )
            val jsonData = client.get(
                url,
                queryParameters = mapOf("type" to type.toString())
            )
            val list: JsonArray = jsonData.asJsonObject.getAsJsonArray("data") ?: return emptyList()
            list.mapNotNull { e ->
                if (e.isJsonObject) {
                    try {
                        BangumiCollection.fromJson(e.asJsonObject)
                    } catch (_: Exception) {
                        null
                    }
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取番剧评论（分页）
     * 对应 getBangumiCommentsByID()
     * GET /p1/subjects/{id}/comments?limit=20&offset={offset}
     */
    suspend fun getBangumiCommentsByID(id: Int, offset: Int = 0): CommentResponse {
        return try {
            val jsonData = client.get(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_NEXT_DOMAIN + ApiEndpoints.BANGUMI_COMMENTS_BY_ID_NEXT,
                    listOf(id, 20, offset)
                )
            )
            if (jsonData.isJsonObject) {
                CommentResponse.fromJson(jsonData.asJsonObject)
            } else CommentResponse()
        } catch (e: Exception) {
            CommentResponse()
        }
    }

    /**
     * 获取角色评论
     * 对应 getCharacterCommentsByCharacterID()
     * GET /p1/characters/{id}/comments
     */
    suspend fun getCharacterCommentsByCharacterID(id: Int): CharacterCommentResponse {
        return try {
            val jsonData = client.get(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_NEXT_DOMAIN + ApiEndpoints.BANGUMI_CHARACTER_COMMENTS_BY_ID_NEXT,
                    listOf(id)
                )
            )
            CharacterCommentResponse.fromJson(jsonData)
        } catch (e: Exception) {
            CharacterCommentResponse()
        }
    }

    /**
     * 获取番剧制作人员
     * 对应 getBangumiStaffByID()
     * GET /v0/subjects/{id}/persons
     */
    suspend fun getBangumiStaffByID(id: Int): StaffResponse {
        return try {
            val jsonData = client.get(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_DOMAIN + ApiEndpoints.BANGUMI_STAFF_BY_ID,
                    listOf(id)
                )
            )
            if (jsonData.isJsonArray) {
                StaffResponse.fromJson(jsonData.asJsonArray)
            } else StaffResponse()
        } catch (e: Exception) {
            StaffResponse()
        }
    }

    /**
     * 获取番剧角色列表
     * 对应 getCharatersByBangumiID()
     * GET /v0/subjects/{id}/characters
     */
    suspend fun getCharatersByBangumiID(id: Int): List<CharacterItem> {
        val result = mutableListOf<CharacterItem>()
        return try {
            val jsonData = client.get(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_DOMAIN + ApiEndpoints.BANGUMI_CHARACTER_BY_ID,
                    listOf(id)
                )
            )
            if (jsonData.isJsonArray) {
                for (e in jsonData.asJsonArray) {
                    if (e.isJsonObject) {
                        try {
                            result.add(CharacterItem.fromJson(e.asJsonObject))
                        } catch (_: Exception) {}
                    }
                }
            }
            result
        } catch (e: Exception) {
            result
        }
    }

    /**
     * 获取角色完整信息
     * 对应 getCharacterByCharacterID()
     * GET /p1/characters/{id}
     */
    suspend fun getCharacterByCharacterID(id: Int): CharacterFullItem {
        return try {
            val jsonData = client.get(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_NEXT_DOMAIN + ApiEndpoints.BANGUMI_CHARACTER_INFO_BY_CHARACTER_ID_NEXT,
                    listOf(id)
                )
            )
            if (jsonData.isJsonObject) {
                CharacterFullItem.fromJson(jsonData.asJsonObject)
            } else {
                CharacterFullItem(id = 0)
            }
        } catch (e: Exception) {
            CharacterFullItem(id = 0)
        }
    }

    /**
     * 获取番剧关联条目
     * 对应 getBangumiRelationsByID()
     * GET /v0/subjects/{id}/subjects
     */
    suspend fun getBangumiRelationsByID(id: Int): List<BangumiRelation> {
        val relations = mutableListOf<BangumiRelation>()
        return try {
            val jsonData = client.get(
                ApiEndpoints.formatUrl(
                    ApiEndpoints.BANGUMI_API_DOMAIN + ApiEndpoints.BANGUMI_RELATIONS_BY_ID,
                    listOf(id)
                )
            )
            if (jsonData.isJsonArray) {
                for (jsonItem in jsonData.asJsonArray) {
                    if (jsonItem.isJsonObject) {
                        try {
                            relations.add(BangumiRelation.fromJson(jsonItem.asJsonObject))
                        } catch (_: Exception) {}
                    }
                }
            }
            relations
        } catch (e: Exception) {
            relations
        }
    }

    /**
     * 提交/更新番剧评分吐槽
     * 对应 addOrUpdateBangumiEvaluationBySubjectID()
     * PUT /v0/users/-/collections/{subjectID}
     */
    suspend fun addOrUpdateBangumiEvaluationBySubjectID(
        subjectID: Int,
        localType: Int,
        comment: String? = null,
        rate: Int? = null,
        tags: List<String>? = null
    ): Boolean {
        val bangumiType = when (localType) {
            CollectType.WATCHING -> 3 // watching
            CollectType.WANT_TO_WATCH -> 1 // planToWatch
            CollectType.ON_HOLD -> 2 // onHold
            CollectType.COMPLETED -> 4 // watched
            CollectType.DROPPED -> 5 // abandoned
            else -> return false
        }
        val data = JsonObject().apply { addProperty("type", bangumiType) }
        comment?.let { data.addProperty("comment", it) }
        rate?.let { data.addProperty("rate", it) }
        tags?.let { t ->
            val arr = JsonArray()
            t.forEach { arr.add(it) }
            data.add("tags", arr)
        }
        return updateBangumiById(subjectID, data)
    }
}

/**
 * Bangumi 搜索分页结果
 * 对应 Dart BangumiSearchPage
 */
data class BangumiSearchPage(
    val items: List<BangumiItem>,
    val rawCount: Int
)