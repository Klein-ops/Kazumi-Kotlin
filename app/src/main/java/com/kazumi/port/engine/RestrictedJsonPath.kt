package com.kazumi.port.engine

import com.jayway.jsonpath.JsonPath
import com.kazumi.port.data.model.ApiRuleFormatException

/**
 * 受限 JSONPath 求值器
 * 对应 Dart RestrictedJsonPath
 *
 * 仅支持：
 * - `$.` 开头
 * - `.key` 字段访问
 * - `[n]` 索引、`[*]` 通配、`['key']` / `["key"]` 引号字段
 */
object RestrictedJsonPath {

    fun validate(expression: String) {
        if (expression.isEmpty() || !expression.startsWith("$")) {
            throw ApiRuleFormatException("JSONPath 必须以 \$ 开头: $expression")
        }
        var index = 1
        while (index < expression.length) {
            val char = expression[index]
            when {
                char == '.' -> {
                    index++
                    val start = index
                    while (index < expression.length &&
                        Regex("[A-Za-z0-9_$-]").matches(expression[index].toString())
                    ) {
                        index++
                    }
                    if (index == start) {
                        throw ApiRuleFormatException("不支持的 JSONPath: $expression")
                    }
                }
                char == '[' -> {
                    val end = findBracketEnd(expression, index)
                    val content = expression.substring(index + 1, end).trim()
                    val isIndex = Regex("^\\d+$").matches(content)
                    val isWildcard = content == "*"
                    val isQuoted = content.length >= 2 &&
                        ((content.startsWith("'") && content.endsWith("'")) ||
                            (content.startsWith("\"") && content.endsWith("\"")))
                    if (!isIndex && !isWildcard && !isQuoted) {
                        throw ApiRuleFormatException("不支持的 JSONPath 片段: [$content]")
                    }
                    index = end + 1
                }
                else -> throw ApiRuleFormatException("不支持的 JSONPath: $expression")
            }
        }
    }

    private fun findBracketEnd(expression: String, start: Int): Int {
        var quote: Char? = null
        var escaped = false
        for (i in start + 1 until expression.length) {
            val char = expression[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null
                continue
            }
            if (char == '\'' || char == '"') {
                quote = char
                continue
            }
            if (char == ']') return i
        }
        throw ApiRuleFormatException("JSONPath 缺少 ]: $expression")
    }

    fun read(document: Any, expression: String): List<Any?> {
        validate(expression)
        try {
            @Suppress("UNCHECKED_CAST")
            val result = JsonPath.read<Any>(document, expression)
            return when (result) {
                is List<*> -> result as List<Any?>
                else -> listOf(result)
            }
        } catch (e: Exception) {
            // 无匹配时 jayway 抛 PathNotFoundException / 空列表
            if (e is com.jayway.jsonpath.PathNotFoundException) return emptyList()
            throw ApiRuleFormatException("JSONPath 解析失败 $expression: ${e.message}", e)
        }
    }

    fun readFirst(document: Any, expression: String): Any? {
        val values = read(document, expression)
        return values.firstOrNull()
    }
}