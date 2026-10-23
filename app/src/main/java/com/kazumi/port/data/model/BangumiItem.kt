package com.kazumi.port.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField

/**
 * 番剧标签
 * 对应 Dart BangumiTag
 */
data class BangumiTag(
    val name: String = "",
    val count: Int = 0,
    val totalCount: Int = 0
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("count", count)
        addProperty("total_cont", totalCount)
    }

    companion object {
        fun fromJson(json: JsonObject): BangumiTag {
            return BangumiTag(
                name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                count = json.get("count")?.asInt ?: 0,
                totalCount = json.get("total_cont")?.asInt ?: 0
            )
        }
    }
}

/**
 * 番剧兴趣（我的评分/状态）
 * 对应 Dart BangumiInterest
 */
data class BangumiInterest(
    val id: Int = 0,
    val rate: Int = 0,
    val type: Int = 0,
    val comment: String = "",
    val tags: List<String> = emptyList(),
    val epStatus: Int = 0,
    val volStatus: Int = 0,
    val updatedAt: Int = 0,
    val user: User? = null
) {
    val hasUserProfile: Boolean get() = user != null

    val hasReviewContent: Boolean get() = rate > 0 || comment.trim().isNotEmpty()

    fun copyWithUser(user: User?): BangumiInterest = copy(user = user ?: this.user)

    companion object {
        fun fromJson(json: JsonObject): BangumiInterest {
            val tagsRaw = json.get("tags")
            val tagList = mutableListOf<String>()
            if (tagsRaw != null && tagsRaw.isJsonArray) {
                for (e in tagsRaw.asJsonArray) {
                    when {
                        e.isJsonPrimitive && e.asJsonPrimitive.isString -> tagList.add(e.asString)
                        e.isJsonObject && e.asJsonObject.has("name") ->
                            tagList.add(e.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "")
                    }
                }
            }
            return BangumiInterest(
                id = json.get("id")?.asInt ?: 0,
                rate = json.get("rate")?.asInt ?: 0,
                type = json.get("type")?.asInt ?: 0,
                comment = json.get("comment")?.takeIf { !it.isJsonNull }?.asString ?: "",
                tags = tagList,
                epStatus = json.get("epStatus")?.asInt ?: 0,
                volStatus = json.get("volStatus")?.asInt ?: 0,
                updatedAt = json.get("updatedAt")?.asInt ?: 0,
                user = json.getAsJsonObject("user")?.let { User.fromJson(it) }
            )
        }
    }
}

/**
 * 番剧条目
 * 对应 Dart BangumiItem
 */
data class BangumiItem(
    val id: Int = 0,
    val type: Int = 2,
    val name: String = "",
    val nameCn: String = "",
    val summary: String = "",
    val airDate: String = "",
    val airWeekday: Int = 0,
    val rank: Int = 0,
    val images: Map<String, String> = emptyMap(),
    val tags: List<BangumiTag> = emptyList(),
    val alias: List<String> = emptyList(),
    val ratingScore: Double = 0.0,
    val votes: Int = 0,
    val votesCount: List<Int> = emptyList(),
    val info: String = "",
    val metaTags: List<String> = emptyList(),
    val interest: BangumiInterest? = null
) {

    fun coverUrl(): String = images["large"] ?: images["common"] ?: ""

    fun displayName(): String = if (nameCn.isNotEmpty()) nameCn else name

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("type", type)
        addProperty("name", name)
        addProperty("name_cn", nameCn)
        addProperty("summary", summary)
        addProperty("air_date", airDate)
        addProperty("air_weekday", airWeekday)
        addProperty("rank", rank)
        val img = JsonObject()
        images.forEach { (k, v) -> img.addProperty(k, v) }
        add("images", img)
        val tagArr = JsonArray()
        tags.forEach { tagArr.add(it.toJson()) }
        add("tags", tagArr)
        val aliasArr = JsonArray()
        alias.forEach { aliasArr.add(it) }
        add("alias", aliasArr)
        addProperty("rating_score", ratingScore)
        addProperty("votes", votes)
        val votesArr = JsonArray()
        votesCount.forEach { votesArr.add(it) }
        add("votes_count", votesArr)
        addProperty("info", info)
        val metaArr = JsonArray()
        metaTags.forEach { metaArr.add(it) }
        add("meta_tags", metaArr)
        interest?.let { interest ->
            val interestObj = JsonObject()
            interestObj.addProperty("id", interest.id)
            interestObj.addProperty("rate", interest.rate)
            interestObj.addProperty("type", interest.type)
            interestObj.addProperty("comment", interest.comment)
            val itags = JsonArray()
            interest.tags.forEach { itags.add(it) }
            interestObj.add("tags", itags)
            interestObj.addProperty("epStatus", interest.epStatus)
            interestObj.addProperty("volStatus", interest.volStatus)
            interestObj.addProperty("updatedAt", interest.updatedAt)
            add("interest", interestObj)
        }
    }

    companion object {
        /**
         * 从 Bangumi API JSON 解析
         * 对应 Dart BangumiItem.fromJson
         */
        fun fromJson(json: JsonObject): BangumiItem {
            // rating 可能是嵌套对象（API：rating.score/total/rank/count），
            // 也可能是 toJson 扁平序列化（rating_score/votes/rank 顶层键）。两者都要兼容。
            val rating = json.getAsJsonObject("rating") ?: JsonObject()
            val flatRatingScore = json.get("rating_score")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
            val flatVotes = json.get("votes")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val flatRank = json.get("rank")?.takeIf { !it.isJsonNull }?.asInt ?: 0

            val nameCn = (json.get("name_cn")?.takeIf { !it.isJsonNull }?.asString ?: "")
                    .ifEmpty { (json.get("nameCN")?.takeIf { !it.isJsonNull }?.asString ?: "").ifEmpty { json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "" } }

            val airDateStr = resolveAirDateString(json)

            val imagesObj = json.getAsJsonObject("images") ?: JsonObject().apply {
                addProperty("large", json.get("image")?.takeIf { !it.isJsonNull }?.asString ?: "")
                addProperty("common", "")
                addProperty("medium", "")
                addProperty("small", "")
                addProperty("grid", "")
            }
            val images = mutableMapOf<String, String>()
            imagesObj.entrySet().forEach { (k, v) ->
                images[k] = if (v.isJsonNull) "" else v.asString
            }

            val tagList = mutableListOf<BangumiTag>()
            val rawTags = json.getAsJsonArray("tags")
            if (rawTags != null) {
                for (e in rawTags) {
                    if (e.isJsonObject) tagList.add(BangumiTag.fromJson(e.asJsonObject))
                }
            }

            val alias = parseBangumiAliases(json)

            val interest = json.getAsJsonObject("interest")?.let { BangumiInterest.fromJson(it) }

            val metaTags = mutableListOf<String>()
            val rawMeta = json.getAsJsonArray("metaTags")
            if (rawMeta != null) {
                val seen = LinkedHashSet<String>()
                for (e in rawMeta) {
                    if (e.isJsonPrimitive && e.asJsonPrimitive.isString) {
                        val t = e.asString.trim()
                        if (t.isNotEmpty()) seen.add(t)
                    }
                }
                metaTags.addAll(seen)
            }

            return BangumiItem(
                id = json.get("id")?.asInt ?: 0,
                type = if (json.has("type")) json.get("type")?.asInt ?: 2 else 2,
                name = json.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                nameCn = nameCn,
                summary = json.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: "",
                airDate = airDateStr,
                airWeekday = dateStringToWeekday(airDateStr.ifEmpty { "2000-11-11" }),
                rank = if (rating.has("rank")) rating.get("rank")?.asInt ?: 0
                else flatRank,
                images = images,
                tags = tagList,
                alias = alias,
                ratingScore = runCatching {
                    val score = if (rating.has("score")) rating.get("score").asDouble
                    else if (flatRatingScore > 0.0) flatRatingScore
                    else 0.0
                    Math.round(score * 10) / 10.0
                }.getOrDefault(if (flatRatingScore > 0.0) flatRatingScore else 0.0),
                votes = if (rating.has("total")) rating.get("total")?.asInt ?: 0
                else flatVotes,
                votesCount = parseBangumiVoteCount(rating, json),
                info = json.get("info")?.takeIf { !it.isJsonNull }?.asString ?: "",
                metaTags = metaTags,
                interest = interest
            )
        }

        private fun resolveAirDateString(json: JsonObject): String {
            fun nonEmpty(v: JsonElement?): String? {
                if (v == null || v.isJsonNull) return null
                val s = v.asString.trim()
                return s.ifEmpty { null }
            }
            nonEmpty(json.get("date"))?.let { return it }
            val airtime = json.getAsJsonObject("airtime")
            if (airtime != null) {
                nonEmpty(airtime.get("date"))?.let { return it }
            }
            // 兼容 toJson 扁平序列化（air_date）
            nonEmpty(json.get("air_date"))?.let { return it }
            return ""
        }

        private fun parseBangumiVoteCount(rating: JsonObject, json: JsonObject = rating): List<Int> {
            val count = rating.get("count") ?: json.get("votes_count") ?: return emptyList()
            val result = mutableListOf<Int>()
            if (count.isJsonObject) {
                val obj = count.asJsonObject
                for (i in 1..10) {
                    result.add(obj.get(i.toString())?.takeIf { !it.isJsonNull }?.asInt ?: 0)
                }
            } else if (count.isJsonArray) {
                for (e in count.asJsonArray) {
                    result.add(e.asInt)
                }
            }
            return result
        }

        private fun parseBangumiAliases(json: JsonObject): List<String> {
            val infobox = json.getAsJsonArray("infobox") ?: return emptyList()
            for (item in infobox) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                if (obj.get("key")?.takeIf { !it.isJsonNull }?.asString != "别名") continue
                // api.bgm.tv /v0 使用 value；next.bgm.tv /p1 使用 values
                val raw = obj.get("values") ?: obj.get("value") ?: return emptyList()
                val aliases = mutableListOf<String>()
                if (raw.isJsonArray) {
                    for (e in raw.asJsonArray) {
                        val text = if (e.isJsonObject && e.asJsonObject.has("v")) {
                            e.asJsonObject.get("v")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        } else {
                            e.asString
                        }.trim()
                        if (text.isNotEmpty()) aliases.add(text)
                    }
                } else {
                    val text = raw.asString.trim()
                    if (text.isNotEmpty()) aliases.add(text)
                }
                return aliases
            }
            return emptyList()
        }

        /**
         * 日期字符串转星期
         * 对应 Dart dateStringToWeekday
         */
        private fun dateStringToWeekday(date: String): Int {
            return try {
                val localDate = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
                // 周一=1 ... 周日=7
                localDate.get(ChronoField.DAY_OF_WEEK)
            } catch (e: Exception) {
                0
            }
        }
    }
}