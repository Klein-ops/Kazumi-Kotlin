package com.kazumi.port.engine

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 轻量 XPath 求值器（基于 Jsoup）
 *
 * 对应 Dart xpath_selector 库。Kazumi 规则仅支持以 `//` 开头的 XPath 子集：
 * - `//div` 任意层级匹配标签
 * - `//div[2]` 第 n 个匹配（1-based）
 * - `/div` 直接子元素
 * - `/text()` 文本节点
 * - `//div/a[2]` 组合
 * - `@href` 属性（通过 NodeXPath 属性获取）
 */
object XPathEvaluator {

    /**
     * 从根元素查询 XPath，返回匹配节点列表
     */
    fun queryNodes(root: Element, expression: String): List<Node> {
        if (expression.isBlank()) return emptyList()
        val tokens = parseExpression(expression)
        if (tokens.isEmpty()) return emptyList()

        var current: List<Node> = listOf(root)
        for (token in tokens) {
            current = evalToken(current, token)
            if (current.isEmpty()) break
        }
        return current
    }

    /**
     * 查询单个节点
     */
    fun queryNode(root: Element, expression: String): Node? {
        val nodes = queryNodes(root, expression)
        return nodes.firstOrNull()
    }

    /**
     * 从单个节点查询 XPath
     */
    fun queryNodesFromNode(node: Node, expression: String): List<Node> {
        if (expression.isBlank()) return emptyList()
        // 相对表达式以 // 开头时，从 node 后代中查找；否则作为整体从 node 求值
        val tokens = parseExpression(expression)
        if (tokens.isEmpty()) return emptyList()

        var current: List<Node> = listOf(node)
        for (token in tokens) {
            current = evalToken(current, token)
            if (current.isEmpty()) break
        }
        return current
    }

    private fun evalToken(nodes: List<Node>, token: XPathToken): List<Node> {
        val result = ArrayList<Node>()
        for (node in nodes) {
            when (token.type) {
                TokenType.DESCENDANT -> {
                    // //tag 任意层级
                    if (node is Element) {
                        result.addAll(
                            node.select(token.tagName ?: "*")
                                .filter { matchesIndex(it, token) }
                        )
                    }
                }
                TokenType.CHILD -> {
                    // /tag 直接子元素
                    if (node is Element) {
                        val children = node.children().filter { child ->
                            token.tagName == null || child.tagName() == token.tagName
                        }
                        result.addAll(children.filterIndexed { index, _ ->
                            token.index == null || index + 1 == token.index
                        })
                    }
                }
                TokenType.TEXT -> {
                    // /text()
                    if (node is Element) {
                        val texts = node.childNodes().filter { it is TextNode }
                        result.addAll(texts)
                    }
                }
                TokenType.SELF -> {
                    result.add(node)
                }
            }
        }
        return result
    }

    private fun matchesIndex(element: Element, token: XPathToken): Boolean {
        if (token.index == null) return true
        // 对于 //tag[n]，取兄弟节点中同标签的第 n 个
        val siblings = element.parent()?.children()?.filter {
            it.tagName() == element.tagName()
        } ?: return false
        val position = siblings.indexOf(element) + 1
        return position == token.index
    }

    /**
     * 解析 XPath 表达式为 token 序列
     */
    fun parseExpression(expression: String): List<XPathToken> {
        if (expression.isBlank()) return emptyList()
        val tokens = ArrayList<XPathToken>()

        var i = 0
        // 跳过开头的 //
        var isDescendant = false
        while (i < expression.length && expression[i] == '/') {
            if (i + 1 < expression.length && expression[i + 1] == '/') {
                isDescendant = true
                i += 2
            } else {
                i += 1
            }
        }

        if (i == 0 && expression.startsWith("//")) {
            isDescendant = true
            i = 2
        }

        // 第一个 token 总是从根开始
        if (isDescendant) {
            tokens.add(XPathToken(TokenType.DESCENDANT, null, null))
        }

        while (i < expression.length) {
            val c = expression[i]
            when {
                c == '/' && i + 1 < expression.length && expression[i + 1] == '/' -> {
                    // // 后代
                    i += 2
                    val (name, index) = parseTag(expression, i)
                    val next = advanceAfterTag(expression, i, name, index)
                    i = if (next > i) next else i + 1  // 强制前进，防死循环
                    tokens.add(XPathToken(TokenType.DESCENDANT, name, index))
                }
                c == '/' -> {
                    // / 子元素
                    i += 1
                    if (i < expression.length && expression.startsWith("text()", i)) {
                        i += "text()".length
                        tokens.add(XPathToken(TokenType.TEXT, null, null))
                    } else {
                        val (name, index) = parseTag(expression, i)
                        val next = advanceAfterTag(expression, i, name, index)
                        i = if (next > i) next else i + 1  // 强制前进，防死循环
                        tokens.add(XPathToken(TokenType.CHILD, name, index))
                    }
                }
                else -> {
                    // 无分隔符的标签（第一个 token）
                    val (name, index) = parseTag(expression, i)
                    val next = advanceAfterTag(expression, i, name, index)
                    if (next > i) {
                        i = next
                        tokens.add(XPathToken(TokenType.DESCENDANT, name, index))
                    } else {
                        // 遇到不认识的字符（@、.、*、[、]、空格等）：强制前进，杜绝死循环
                        // （否则 name==null && index==null 时 i 永不增长，主线程无限自旋 → ANR）
                        i += 1
                    }
                }
            }
        }

        return tokens
    }

    private fun parseTag(expr: String, start: Int): Pair<String?, Int?> {
        var i = start
        while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_' || expr[i] == '-')) {
            i++
        }
        val name = if (i > start) expr.substring(start, i) else null

        var index: Int? = null
        if (i < expr.length && expr[i] == '[') {
            val end = expr.indexOf(']', i)
            if (end > i) {
                val content = expr.substring(i + 1, end).trim()
                index = content.toIntOrNull()
            }
        }
        return Pair(name, index)
    }

    private fun advanceAfterTag(expr: String, start: Int, name: String?, index: Int?): Int {
        var i = start
        if (name != null) i += name.length
        if (index != null) {
            val bracket = expr.indexOf('[', i)
            if (bracket >= 0) {
                val end = expr.indexOf(']', bracket)
                if (end >= 0) i = end + 1
            }
        }
        return i
    }

    /**
     * 获取节点的文本内容
     */
    fun nodeText(node: Node): String {
        return when (node) {
            is TextNode -> node.text()
            is Element -> node.text()
            else -> node.toString()
        }
    }

    /**
     * 获取节点属性（href 等）
     */
    fun nodeAttribute(node: Node, attr: String): String? {
        return when (node) {
            is Element -> node.attr(attr).ifEmpty { null }
            else -> null
        }
    }

    /**
     * 节点 HTML 片段
     */
    fun nodeFragment(node: Node): String {
        return when (node) {
            is Element -> node.outerHtml()
            else -> nodeText(node)
        }
    }
}

enum class TokenType {
    DESCENDANT, CHILD, TEXT, SELF
}

data class XPathToken(
    val type: TokenType,
    val tagName: String?,
    val index: Int?
)