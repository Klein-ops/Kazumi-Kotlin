package com.kazumi.port.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 时间格式化
 * 对应 Dart utils/date_time.dart
 */

/**
 * 评论时间显示（对应 Dart dateFormat()）
 * <=60s 刚刚；<=1h X分钟前；<=12h X小时前；
 * 同年 MM月DD日 hh:mm；跨年 YY年MM月DD日 hh:mm
 */
fun dateFormat(timestamp: Int, formatType: String = "list"): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val distance = nowSeconds - timestamp

    if (formatType == "detail") {
        return customTimestampString(timestamp = timestamp, date = "YY-MM-DD hh:mm", formatType = formatType)
    }
    if (distance <= 60) {
        return "刚刚"
    } else if (distance <= 3600) {
        return "${(distance / 60).toInt()}分钟前"
    } else if (distance <= 43200) {
        return "${(distance / 60 / 60).toInt()}小时前"
    }
    val now = Instant.ofEpochSecond(nowSeconds).atZone(ZoneId.systemDefault())
    val then = Instant.ofEpochSecond(timestamp.toLong()).atZone(ZoneId.systemDefault())
    return if (now.year == then.year) {
        customTimestampString(timestamp = timestamp, date = "MM月DD日 hh:mm", formatType = formatType)
    } else {
        customTimestampString(timestamp = timestamp, date = "YY年MM月DD日 hh:mm", formatType = formatType)
    }
}

private fun customTimestampString(
    timestamp: Int,
    date: String,
    formatType: String = "list"
): String {
    val time = Instant.ofEpochSecond(timestamp.toLong()).atZone(ZoneId.systemDefault())
    val yy = time.year.toString()
    val mm = if (formatType == "detail") time.monthValue.toString().padStart(2, '0') else time.monthValue.toString()
    val dd = if (formatType == "detail") time.dayOfMonth.toString().padStart(2, '0') else time.dayOfMonth.toString()
    val hh = if (formatType == "detail") time.hour.toString().padStart(2, '0') else time.hour.toString()
    val minute = if (formatType == "detail") time.minute.toString().padStart(2, '0') else time.minute.toString()

    val formatted = date
        .replace("YY", yy)
        .replace("MM", mm)
        .replace("DD", dd)
        .replace("hh", hh)
        .replace("mm", minute)

    val now = LocalDateTime.now()
    if (time.year == now.year && time.monthValue == now.monthValue && time.dayOfMonth == now.dayOfMonth) {
        return "今天"
    }
    return formatted
}

/**
 * 相对时间（对应 Dart formatTimestampToRelativeTime()）
 * 用于较长跨度：X年前 / X个月前 / X天前 / X小时前 / X分钟前 / 刚刚
 */
fun formatTimestampToRelativeTime(timestamp: Int): String {
    val diff = System.currentTimeMillis() - timestamp * 1000L
    val days = diff / 86_400_000L
    return when {
        days > 365 -> "${days / 365}年前"
        days > 30 -> "${days / 30}个月前"
        days > 0 -> "${days}天前"
        diff > 3_600_000L -> "${diff / 3_600_000L}小时前"
        diff > 60_000L -> "${diff / 60_000L}分钟前"
        else -> "刚刚"
    }
}

/**
 * 日期字符串转星期（对应 Dart dateStringToWeekday）
 * 周一=1 ... 周日=7
 */
fun dateStringToWeekday(dateString: String): Int {
    return try {
        java.time.LocalDate.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE).dayOfWeek.value
    } catch (e: Exception) {
        1
    }
}