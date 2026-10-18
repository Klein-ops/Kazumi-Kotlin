package com.kazumi.port.util

import java.net.URI

/**
 * 集数源站 URL 归一化
 * 对应 Dart utils/episode_url.dart
 *
 * 把插件抓取到的原始 href（相对路径、缺协议、带尾斜杠等）转换为稳定一致的绝对 URL。
 */
object EpisodeUrl {

    fun normalizeEpisodeUrl(baseUrl: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val rawUri = try { URI(trimmed) } catch (e: Exception) { null }
        val baseUri = try { URI(baseUrl.trim()) } catch (e: Exception) { null }
        val hasValidBase = baseUri != null &&
                (baseUri.scheme == "http" || baseUri.scheme == "https") &&
                !baseUri.host.isNullOrEmpty()

        var resolved: URI? = null
        if (rawUri != null &&
            (rawUri.scheme == "http" || rawUri.scheme == "https") &&
            !rawUri.host.isNullOrEmpty()
        ) {
            resolved = rawUri
        } else if (hasValidBase && baseUri != null) {
            resolved = try {
                baseUri.resolve(trimmed)
            } catch (e: Exception) {
                null
            }
        }

        if (resolved == null || resolved.host.isNullOrEmpty()) {
            return trimmed
        }

        // 同站 URL 的协议统一到 baseUrl 声明的协议
        if (hasValidBase && baseUri != null &&
            isHttpScheme(baseUri.scheme) && isHttpScheme(resolved.scheme) &&
            resolved.scheme != baseUri.scheme &&
            resolved.host == baseUri.host &&
            resolved.port == baseUri.port
        ) {
            resolved = URI(
                baseUri.scheme, null, resolved.host, resolved.port,
                resolved.path, resolved.query, resolved.fragment
            )
        }

        // 去除 path 尾斜杠（根路径除外）
        var path = resolved.path ?: ""
        while (path.length > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length - 1)
        }

        val hasQuery = !resolved.query.isNullOrEmpty()
        val hasFragment = !resolved.fragment.isNullOrEmpty()

        val normalized = URI(
            resolved.scheme, null, resolved.host, resolved.port,
            path,
            if (hasQuery) resolved.query else null,
            if (hasFragment) resolved.fragment else null
        )

        return normalized.toString()
    }

    private fun isHttpScheme(scheme: String?): Boolean =
        scheme == "http" || scheme == "https"
}