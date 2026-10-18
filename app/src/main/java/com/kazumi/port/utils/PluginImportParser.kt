package com.kazumi.port.utils

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kazumi.port.data.model.Plugin
import com.kazumi.port.util.FileLogger

/**
 * 规则导入解析器
 * 对应原版 lib/services/plugin/plugin_import_parser.dart
 * 支持：JSON 对象 / JSON 数组 / 多条 kazumi:// 链接 / 单条 kazumi:// 链接
 */
object PluginImportParser {

    private val gson = Gson()

    data class ParseResult(
        val plugins: List<Plugin>,
        val failures: List<String>,
        val duplicateCount: Int
    ) {
        val failureCount: Int get() = failures.size
    }

    private val RULE_LINK_PAYLOAD_PREFIX = Regex("^[A-Za-z0-9+/_=%\\-\\s]+")
    private val WHITESPACE = Regex("\\s+")

    fun parse(input: String): ParseResult {
        val value = input.trim()
        if (value.isEmpty()) {
            FileLogger.w("RuleImport", "parse: 输入为空")
            return ParseResult(emptyList(), listOf("导入内容为空"), 0)
        }
        FileLogger.d("RuleImport", "parse start: len=${value.length} head=${value.take(120)}")

        val parsed = mutableListOf<Plugin>()
        val failures = mutableListOf<String>()

        // 尝试整体按 JSON 解析
        var jsonValue: Any? = null
        var decodedAsJson = false
        try {
            jsonValue = JsonParser.parseString(value)
            decodedAsJson = true
        } catch (_: Exception) {
            // 分享链接与文本走下方的 kazumi:// 解析
        }

        if (decodedAsJson && jsonValue != null) {
            if (jsonValue is com.google.gson.JsonArray) {
                for (index in 0 until jsonValue.size()) {
                    parseEntry(jsonValue[index], index + 1, parsed, failures)
                }
            } else {
                parseEntry(jsonValue, 1, parsed, failures)
            }
        } else {
            val segments = KazumiRuleEncoding.findKazumiRuleLinkSegments(value)
            if (segments.isEmpty()) {
                failures.add("未找到有效的 JSON 或 kazumi:// 规则链接")
            } else {
                segments.forEachIndexed { index, (scheme, rawPayload) ->
                    try {
                        val entry = decodeRuleEntry(scheme, rawPayload)
                        parseEntry(entry, index + 1, parsed, failures)
                    } catch (e: Exception) {
                        failures.add("第 ${index + 1} 条：${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }

        // 去重（同名保留最后一条）
        val uniquePlugins = LinkedHashMap<String, Plugin>()
        var duplicateCount = 0
        for (plugin in parsed) {
            val key = plugin.name.lowercase()
            if (uniquePlugins.containsKey(key)) duplicateCount++
            uniquePlugins[key] = plugin
        }

        return ParseResult(
            plugins = uniquePlugins.values.toList(),
            failures = failures,
            duplicateCount = duplicateCount
        ).also { result ->
            FileLogger.i(
                "RuleImport",
                "parse done: plugins=${result.plugins.size} failures=${result.failures.size} dup=${result.duplicateCount}" +
                    (if (result.failures.isNotEmpty()) " | failures=${result.failures}" else "")
            )
        }
    }

    private fun decodeRuleEntry(scheme: String, rawPayload: String): Any {
        val payload = RULE_LINK_PAYLOAD_PREFIX.find(rawPayload)?.value?.trimEnd()
            ?: throw IllegalArgumentException("缺失规则链接内容")
        if (payload.isEmpty()) throw IllegalArgumentException("缺失规则链接内容")

        // 逐步缩短 payload 以容忍链接后的杂散文本
        val candidates = mutableListOf(payload)
        val whitespaceMatches = WHITESPACE.findAll(payload).toList()
        for (match in whitespaceMatches.asReversed()) {
            val candidate = payload.substring(0, match.range.first).trimEnd()
            if (candidate.isNotEmpty() && candidate != candidates.last()) {
                candidates.add(candidate)
            }
        }

        var firstError: Exception? = null
        for (candidate in candidates) {
            try {
                val json = KazumiRuleEncoding.kazumiBase64ToJson("$scheme$candidate")
                val parsed = JsonParser.parseString(json)
                if (parsed is JsonObject) {
                    return parsed
                }
                firstError ?: IllegalArgumentException("规则链接内容必须是 JSON 对象")
            } catch (e: Exception) {
                firstError ?: e
            }
        }
        throw firstError ?: IllegalArgumentException("无效的规则链接")
    }

    private fun parseEntry(
        entry: Any?,
        index: Int,
        plugins: MutableList<Plugin>,
        failures: MutableList<String>
    ) {
        try {
            val plugin: Plugin = when (entry) {
                is JsonObject -> Plugin.fromJson(entry)
                is com.google.gson.JsonPrimitive -> {
                    // 单条 kazumi:// 链接（内嵌于 JSON 数组）
                    val json = KazumiRuleEncoding.kazumiBase64ToJson(entry.asString)
                    val decoded = JsonParser.parseString(json)
                    if (decoded !is JsonObject) {
                        throw IllegalArgumentException("规则链接内容必须是 JSON 对象")
                    }
                    Plugin.fromJson(decoded)
                }
                else -> throw IllegalArgumentException("规则必须是 JSON 对象或 kazumi:// 链接")
            }
            if (plugin.name.trim().isEmpty()) {
                throw IllegalArgumentException("规则名称不能为空")
            }
            if (plugin.requiresNewerClient) {
                throw IllegalArgumentException("规则需要更高版本客户端")
            }
            plugins.add(plugin)
        } catch (e: Exception) {
            failures.add("第 $index 条：${e.message ?: e.javaClass.simpleName}")
        }
    }
}