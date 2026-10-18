package com.kazumi.port.util

import android.net.Uri

/**
 * 媒体工具类
 *
 * 对应原版 lib/utils/media.dart 的 decodeVideoSource / extractEpisodeNumber。
 */
object MediaUtils {

    private val VIDEO_URL_REGEX = Regex(
        "(http[s]?://.*?\\.m3u8)|(http[s]?://.*?\\.mp4)",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从 iframe URL 中解码并提取真正的视频源地址。
     *
     * 行为与原版 Dart 一致：
     * 1. 整体解码 URL；
     * 2. 解析 query 参数，若某个参数值匹配 m3u8/mp4 正则，则将其作为视频源；
     * 3. 否则保持原样。
     */
    fun decodeVideoSource(iframeUrl: String): String {
        val decodedUrl = Uri.decode(iframeUrl)
        val uri = Uri.parse(decodedUrl)
        var matchedUrl = iframeUrl

        for (name in uri.queryParameterNames) {
            val value = uri.getQueryParameter(name) ?: continue
            if (VIDEO_URL_REGEX.containsMatchIn(value)) {
                matchedUrl = value
                break
            }
        }
        return Uri.encode(matchedUrl)
    }

    /** 提取集数号：匹配 "第12话" / "12集" / "12" 等。 */
    fun extractEpisodeNumber(input: String): Int {
        val regExp = Regex("第?(\\d+)[话集]?")
        val match = regExp.find(input) ?: return 0
        return match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
    }
}
