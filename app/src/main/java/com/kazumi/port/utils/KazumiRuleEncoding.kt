package com.kazumi.port.utils

import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * kazumi:// 规则链接编解码
 * 对应原版 lib/utils/encoding.dart：
 * - jsonToKazumiBase64：JSON → "kazumi://" + base64
 * - kazumiBase64ToJson：kazumi:// 链接 → JSON（兼容 URL-safe Base64 / 无 padding / 百分号编码）
 */
object KazumiRuleEncoding {

    private val SCHEME_REGEX = Regex("kazumi:(?://)?", RegexOption.IGNORE_CASE)

    /** 从输入中切出所有 kazumi:// 链接段（scheme + rawPayload） */
    fun findKazumiRuleLinkSegments(input: String): List<Pair<String, String>> {
        val matches = SCHEME_REGEX.findAll(input).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else input.length
            val scheme = match.value
            val rawPayload = input.substring(match.range.last + 1, end)
            scheme to rawPayload
        }
    }

    fun jsonToKazumiBase64(json: String): String {
        val bytes = Base64.encodeToString(
            json.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        return "kazumi://$bytes"
    }

    fun kazumiBase64ToJson(kazumiBase64Str: String): String {
        val input = kazumiBase64Str.trim()
        val schemeMatch = SCHEME_REGEX.find(input)
            ?: throw IllegalArgumentException("Invalid Kazumi rule link")
        var payload = input.substring(schemeMatch.range.last + 1)
        // 百分号解码（兼容 URI handler 传入）。
        // 注意：不能用 URLDecoder，它会将合法 base64 字符 '+' 错解为空格；
        // 原版 Dart 用 Uri.decodeComponent，仅解码 %XX、保留 '+'
        payload = percentDecode(payload)
        payload = payload.replace(Regex("\\s"), "")
        if (payload.isEmpty()) throw IllegalArgumentException("Kazumi rule link is empty")

        // 兼容标准与 URL-safe Base64，且可无 padding
        val normalized = payload
            .replace('-', '+')
            .replace('_', '/')
            .padEnd((payload.length + 3) / 4 * 4, '=')
        try {
            val decoded = Base64.decode(normalized, Base64.DEFAULT)
            return String(decoded, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid Kazumi rule link payload")
        }
    }

    /** 仅解码 %XX 序列（不处理 +），保留其他字符原样 */
    private fun percentDecode(input: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    bytes.add(v.toByte())
                    i += 3
                    continue
                }
            }
            // 非 %XX：按 UTF-8 编码追加（规则链接为 ASCII，安全）
            c.toString().toByteArray(StandardCharsets.UTF_8).forEach { bytes.add(it) }
            i++
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }
}