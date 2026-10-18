package com.kazumi.port.network

import java.net.URI

/**
 * 图片加速模式
 * 对应原版 lib/services/network/image_acceleration.dart
 */
enum class ImageAcceleration(val label: String, val description: String) {
    DIRECT("直连", "加载原始图片"),
    ECH("ECH", "加密握手（推荐）"),
    MIRROR("镜像", "使用图片镜像");

    companion object {
        /**
         * 默认 ECH（对齐原版 SettingsKeys.imageAcceleration 默认值）。
         *
         * 背景：ECH native 桥 v3 已修复两大缺陷（ack 回调 + 302 重定向跟随），
         * 实测 lain.bgm.tv 直链 ECH 下载 200 + JPEG。mirror 模式在用户网络下
         * 镜像站（corsproxy/wsrv/weserv）全部不可达，反而卡死，因此 ECH 是
         * 唯一可用通路，恢复为默认。
         */
        fun fromSetting(value: String?): ImageAcceleration {
            val raw = value?.trim().orEmpty()
            return when (raw.lowercase()) {
                "direct" -> DIRECT
                "mirror" -> MIRROR
                // 默认走 ECH（含存量 "ech" 与空值）
                else -> ECH
            }
        }
    }
}

/**
 * Bangumi 图片 URL 重写器
 * 对应原版 lib/services/network/bangumi_image_url_rewriter.dart
 *
 * 将 lain.bgm.tv / api.bgm.tv/v0/.../image 的图片 URL 重写为 wsrv.nl 镜像，
 * 解决部分网络环境下 Bangumi 图床直连失败的问题。
 */
object BangumiImageUrlRewriter {

    private val API_IMAGE_KINDS = setOf("subjects", "characters", "persons")

    /** 图片代理候选（依次尝试，全部失败才报错） */
    private val IMAGE_PROXIES = listOf(
        // corsproxy.io 实测可达；wsrv.nl / images.weserv.nl 在部分网络下连接被重置，放后面兜底
        "https://corsproxy.io/?url=",
        "https://wsrv.nl/?url=",
        "https://images.weserv.nl/?url=",
    )

    /** 是否为 Bangumi 图片（http/https + lain.bgm.tv 或 api.bgm.tv/v0/.../image） */
    fun isBangumiImage(url: String): Boolean = runCatching {
        val uri = URI(url)
        val isHttp = uri.scheme == "http" || uri.scheme == "https"
        isHttp && (uri.host == "lain.bgm.tv" || isApiImage(uri))
    }.getOrDefault(false)

    /** 返回该图片的全部候选地址（原始 + 各代理改写），供拦截器依次尝试 */
    fun rewriteCandidates(url: String): List<String> {
        if (!isBangumiImage(url)) return listOf(url)
        val result = mutableListOf(url)
        runCatching {
            val uri = URI(url)
            val sourceUrl = uri.host + uri.path + (if (uri.query != null) "?${uri.query}" else "")
            val path = uri.path.lowercase()
            val suffix = if (path.endsWith(".gif")) "&n=-1" else ""
            IMAGE_PROXIES.forEach { proxy ->
                result.add(proxy + sourceUrl + suffix)
            }
        }
        return result
    }

    /** 重写为 wsrv.nl 镜像；非 Bangumi 图片或改写失败时原样返回（兼容旧调用） */
    fun rewrite(url: String): String {
        val candidates = rewriteCandidates(url)
        return if (candidates.size > 1) candidates[1] else url
    }

    private fun isApiImage(uri: URI): Boolean {
        if (uri.host != "api.bgm.tv") return false
        val segments = uri.path.split("/").filter { it.isNotEmpty() }
        if (segments.size != 4 || segments[0] != "v0" || segments[3] != "image") return false
        val id = segments[2].toIntOrNull() ?: return false
        return segments[1] in API_IMAGE_KINDS && id > 0
    }
}
