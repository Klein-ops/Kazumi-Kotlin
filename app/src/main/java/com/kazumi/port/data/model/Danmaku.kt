package com.kazumi.port.data.model

import com.google.gson.JsonObject

/**
 * 弹幕条目
 * 对应 Dart DanmakuEntry
 */
data class DanmakuEntry(
    /** 弹幕内容 */
    val message: String,
    /** 弹幕时间（秒） */
    val time: Double,
    /** 1-普通弹幕，4-底部弹幕，5-顶部弹幕 */
    val type: Int,
    /** 弹幕颜色 ARGB */
    val color: Long,
    /** 弹幕来源 ([BiliBili], [Gamer]) */
    val source: String
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("m", message)
        // 与 DanDanPlay API 格式一致，只存 RGB
        addProperty("p", "$time,$type,${color and 0xFFFFFF},$source")
    }

    companion object {
        fun fromJson(json: JsonObject): DanmakuEntry {
            val message = json.get("m")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val parts = (json.get("p")?.takeIf { !it.isJsonNull }?.asString ?: "").split(",")
            val time = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
            val type = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val color = parts.getOrNull(2)?.toLongOrNull() ?: 0xFFFFFFL
            val source = parts.getOrNull(3) ?: ""
            return DanmakuEntry(
                message = message,
                time = time,
                type = type,
                color = color,
                source = source
            )
        }
    }
}

/**
 * 弹幕简繁转换配置
 * 对应 Dart DanmakuChConvert
 */
enum class DanmakuChConvert(val value: Int) {
    NONE(0),
    SIMPLIFIED(1),
    TRADITIONAL(2);

    companion object {
        fun fromValue(value: Int): DanmakuChConvert = when (value) {
            1 -> SIMPLIFIED
            2 -> TRADITIONAL
            else -> NONE
        }
    }
}

/**
 * 弹幕工具
 * 对应 Dart utils/danmaku.dart
 */
object DanmakuUtils {

    /** 从 RGB 值生成 ARGB 颜色 */
    fun generateDanmakuColor(colorValue: Long): Long {
        val red = (colorValue shr 16) and 0xFF
        val green = (colorValue shr 8) and 0xFF
        val blue = colorValue and 0xFF
        return (0xFFL shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /**
     * 合并重复弹幕
     * 对应 mergeDuplicateDanmakus()
     */
    fun mergeDuplicateDanmakus(
        danmakus: List<DanmakuEntry>,
        timeWindowSeconds: Double = 0.0
    ): List<DanmakuEntry> {
        val grouped = LinkedHashMap<String, MutableList<DanmakuEntry>>()

        for (danmaku in danmakus) {
            var text = danmaku.message.trim().lowercase()

            val buffer = StringBuilder()
            for (ch in text) {
                val code = ch.code
                when {
                    code == 0x3000 -> buffer.append(' ')
                    code in 0xFF01..0xFF5E -> buffer.append((code - 0xFEE0).toChar())
                    else -> buffer.append(ch)
                }
            }
            text = buffer.toString()
            text = text.replace(Regex("\\s+"), "")
            text = text.replace(Regex("[^\\w\\u4e00-\\u9fff\\u3040-\\u309F\\u30A0-\\u30FF\\u31F0-\\u31FF\\uFF65-\\uFF9F]"), "")
            text = text.replace(Regex("(.)\\1{2,}")) { match ->
                val char = match.groupValues[1]
                char.repeat(3)
            }

            grouped.getOrPut(text) { mutableListOf() }.add(danmaku)
        }

        val result = mutableListOf<DanmakuEntry>()
        grouped.forEach { (_, list) ->
            if (list.isEmpty()) return@forEach
            if (timeWindowSeconds <= 0) {
                addMergedGroup(result, list)
                return@forEach
            }
            val sorted = list.sortedBy { it.time }
            var currentGroup = mutableListOf<DanmakuEntry>()
            for (item in sorted) {
                if (currentGroup.isEmpty()) {
                    currentGroup.add(item)
                    continue
                }
                val last = currentGroup.last()
                if (item.time - last.time <= timeWindowSeconds) {
                    currentGroup.add(item)
                } else {
                    addMergedGroup(result, currentGroup)
                    currentGroup = mutableListOf(item)
                }
            }
            if (currentGroup.isNotEmpty()) {
                addMergedGroup(result, currentGroup)
            }
        }
        return result
    }

    private fun addMergedGroup(
        result: MutableList<DanmakuEntry>,
        group: List<DanmakuEntry>
    ) {
        if (group.size == 1) {
            result.add(group.first())
            return
        }
        result.add(
            DanmakuEntry(
                message = "${group.first().message} x${group.size}",
                time = group.first().time,
                type = 5,
                color = 0xFFFFFFFFL,
                source = group.first().source
            )
        )
    }
}