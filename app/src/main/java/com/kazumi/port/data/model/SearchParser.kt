package com.kazumi.port.data.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 搜索日期范围（对应 Dart SearchDateRange）
 */
data class SearchDateRange(
    val start: String,
    val end: String
) {
    val isValid: Boolean get() = start.isNotEmpty() && end.isNotEmpty()
}

/**
 * 搜索整数范围（对应 Dart SearchIntRange）
 */
data class SearchIntRange(
    val min: Int? = null,
    val max: Int? = null
) {
    val isValid: Boolean get() = min != null || max != null
    fun toToken(): String = "${min ?: ""}..${max ?: ""}"
}

/**
 * 搜索浮点范围（对应 Dart SearchDoubleRange）
 */
data class SearchDoubleRange(
    val min: Double? = null,
    val max: Double? = null
) {
    val isValid: Boolean get() = min != null || max != null
    fun toToken(): String = "${formatDouble(min)}..${formatDouble(max)}"

    companion object {
        private fun formatDouble(value: Double?): String {
            if (value == null) return ""
            val fixed = String.format("%.1f", value)
            return if (fixed.endsWith(".0")) fixed.substring(0, fixed.length - 2) else fixed
        }
    }
}

/**
 * 搜索筛选状态（对应 Dart SearchFilterState）
 */
data class SearchFilterState(
    val id: String = "",
    val keyword: String = "",
    val tags: List<String> = emptyList(),
    val sort: String = "heat",
    val season: String = "",
    val dateRange: SearchDateRange? = null,
    val rankRange: SearchIntRange? = null,
    val scoreRange: SearchDoubleRange? = null,
    val weekdays: List<Int> = emptyList()
) {
    val isIdSearch: Boolean get() = id.isNotEmpty()

    val hasAdvancedFilters: Boolean
        get() = tags.isNotEmpty() ||
            sort != "heat" ||
            season.isNotEmpty() ||
            dateRange != null ||
            rankRange?.isValid == true ||
            scoreRange?.isValid == true ||
            weekdays.isNotEmpty()

    val effectiveDateRange: SearchDateRange?
        get() {
            if (dateRange != null) return dateRange
            if (season.isEmpty()) return null
            return SearchParser.seasonToDateRange(season)
        }
}

/**
 * 搜索关键词解析器（对应 Dart SearchParser）
 * 解析/序列化形如 `关键词 tag:日常 sort:score season:2026Q1 date:.. rank:1..5000 score:7..10 weekday:1,3` 的查询串
 */
class SearchParser(private val query: String) {

    private val idRegExp = Regex("""id:(\d+)""", RegexOption.IGNORE_CASE)
    private val tagRegExp = Regex(
        """(?:^|\s)tag:([^\s]+?)(?=(?:id|tag|sort|season|date|rank|score|weekday|nsfw):|\s|$)""",
        RegexOption.IGNORE_CASE
    )
    private val sortRegExp = Regex("""sort:([\w\-]+)""", RegexOption.IGNORE_CASE)
    private val seasonRegExp = Regex("""season:(\d{4}Q[1-4])""", RegexOption.IGNORE_CASE)
    private val dateRegExp = Regex(
        """date:(\d{4}-\d{2}-\d{2})\.\.(\d{4}-\d{2}-\d{2})""",
        RegexOption.IGNORE_CASE
    )
    private val rankRegExp = Regex("""rank:(\d*)\.\.(\d*)""", RegexOption.IGNORE_CASE)
    private val scoreRegExp = Regex(
        """score:(\d+(?:\.\d+)?)?\.\.(\d+(?:\.\d+)?)?""",
        RegexOption.IGNORE_CASE
    )
    private val weekdayRegExp = Regex("""weekday:([1-7](?:,[1-7])*)""", RegexOption.IGNORE_CASE)

    fun parseId(): String? = idRegExp.find(query)?.groupValues?.get(1)

    fun parseTags(): List<String> =
        tagRegExp.findAll(query)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .toList()

    fun parseSort(): String? = sortRegExp.find(query)?.groupValues?.get(1)

    fun parseSeason(): String? =
        seasonRegExp.find(query)?.groupValues?.get(1)?.uppercase()

    fun parseDateRange(): SearchDateRange? {
        val m = dateRegExp.find(query) ?: return null
        return SearchDateRange(start = m.groupValues[1], end = m.groupValues[2])
    }

    fun parseRankRange(): SearchIntRange? {
        val m = rankRegExp.find(query) ?: return null
        val min = m.groupValues[1].toIntOrNull()
        val max = m.groupValues[2].toIntOrNull()
        val range = SearchIntRange(min = min, max = max)
        return if (range.isValid) range else null
    }

    fun parseScoreRange(): SearchDoubleRange? {
        val m = scoreRegExp.find(query) ?: return null
        val min = m.groupValues[1].toDoubleOrNull()
        val max = m.groupValues[2].toDoubleOrNull()
        val range = SearchDoubleRange(min = min, max = max)
        return if (range.isValid) range else null
    }

    fun parseWeekdays(): List<Int> {
        val m = weekdayRegExp.find(query) ?: return emptyList()
        val value = m.groupValues[1]
        if (value.isEmpty()) return emptyList()
        return value.split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()
            .toList()
            .sorted()
    }

    fun parseKeywords(): String =
        query.replace(tokenRegExp(), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun toFilterState(): SearchFilterState = SearchFilterState(
        id = parseId() ?: "",
        keyword = parseKeywords(),
        tags = parseTags(),
        sort = parseSort() ?: "heat",
        season = parseSeason() ?: "",
        dateRange = parseDateRange(),
        rankRange = parseRankRange(),
        scoreRange = parseScoreRange(),
        weekdays = parseWeekdays()
    )

    companion object {
        private const val FIELD_NAMES = "id|tag|sort|season|date|rank|score|weekday|nsfw"

        /** 从筛选状态序列化为查询串（对应 Dart fromFilterState） */
        fun fromFilterState(state: SearchFilterState): String {
            if (state.id.isNotEmpty()) return "id:${state.id}"
            val tokens = mutableListOf<String>()
            val keyword = state.keyword.trim()
            if (keyword.isNotEmpty()) tokens.add(keyword)
            state.tags.forEach { tag ->
                val normalized = tag.trim()
                if (normalized.isNotEmpty()) tokens.add("tag:$normalized")
            }
            if (state.sort.isNotEmpty() && state.sort != "heat") tokens.add("sort:${state.sort}")
            if (state.season.isNotEmpty()) {
                tokens.add("season:${state.season}")
            } else if (state.dateRange?.isValid == true) {
                tokens.add("date:${state.dateRange!!.start}..${state.dateRange!!.end}")
            }
            if (state.rankRange?.isValid == true) tokens.add("rank:${state.rankRange!!.toToken()}")
            if (state.scoreRange?.isValid == true) tokens.add("score:${state.scoreRange!!.toToken()}")
            if (state.weekdays.isNotEmpty()) {
                val weekdays = state.weekdays.toSet().toList().sorted()
                tokens.add("weekday:${weekdays.joinToString(",")}")
            }
            return tokens.joinToString(" ").trim()
        }

        /** 季度 -> 日期范围（对应 Dart seasonToDateRange） */
        fun seasonToDateRange(season: String): SearchDateRange? {
            val m = Regex("""^(\d{4})Q([1-4])$""", RegexOption.IGNORE_CASE).find(season) ?: return null
            val year = m.groupValues[1].toInt()
            val quarter = m.groupValues[2].toInt()
            val startMonth = (quarter - 1) * 3 + 1
            val start = LocalDate.of(year, startMonth, 1)
            val end = LocalDate.of(year, startMonth + 2, 1)
            return SearchDateRange(
                start = start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                end = end.format(DateTimeFormatter.ISO_LOCAL_DATE)
            )
        }

        private fun tokenRegExp(): Regex = Regex(
            """(?:^|\s)?(?:(?:$FIELD_NAMES)):[^\s]*?(?=(?:\s|$)|(?:(?:$FIELD_NAMES)):)""",
            RegexOption.IGNORE_CASE
        )
    }
}