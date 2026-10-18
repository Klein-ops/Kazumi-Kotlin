package com.kazumi.port.util

/**
 * 代理工具类
 *
 * 对应原版 lib/services/network/proxy_utils.dart。
 * 支持格式：
 * - http://127.0.0.1:7890
 * - 127.0.0.1:7890
 */
object ProxyUtils {

    /** 解析代理 URL，返回 (主机, 端口)；格式非法时返回 null。 */
    fun parseProxyUrl(url: String): Pair<String, Int>? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        var hostPort = trimmed
        when {
            trimmed.lowercase().startsWith("http://") -> hostPort = trimmed.substring(7)
            trimmed.lowercase().startsWith("https://") -> hostPort = trimmed.substring(8)
        }

        val parts = hostPort.split(':')
        if (parts.size != 2) return null
        val host = parts[0]
        val port = parts[1].toIntOrNull()
        if (host.isEmpty() || port == null) return null
        return host to port
    }

    /** 获取规范化代理 URL（http://host:port），供 WebView 代理覆盖使用。 */
    fun getFormattedProxyUrl(url: String): String? {
        val parsed = parseProxyUrl(url) ?: return null
        return "http://${parsed.first}:${parsed.second}"
    }

    /** 校验代理 URL 是否合法。 */
    fun isValidProxyUrl(url: String): Boolean = parseProxyUrl(url) != null
}
