package com.kazumi.port.util

/**
 * M3U8 解析器
 *
 * 对应原版 lib/utils/m3u8_parser.dart。
 * 支持主列表（master）与媒体列表（media）解析、KEY 提取、嵌套 m3u8 展开、
 * 本地 m3u8 重建。
 */
object M3u8Parser {

    /** KEY 信息 */
    data class M3u8Key(
        val method: String,
        val uri: String,
        val iv: String?
    ) {
        override fun toString(): String {
            val sb = StringBuilder("#EXT-X-KEY:METHOD=$method,URI=\"$uri\"")
            if (iv != null) sb.append(",IV=$iv")
            return sb.toString()
        }
    }

    /** 分片 */
    data class M3u8Segment(
        val duration: Double,
        val uri: String,
        val discontinuityGroup: Int,
        val key: M3u8Key?
    )

    /** 码率变体 */
    data class M3u8Variant(
        val bandwidth: Int,
        val resolution: String?,
        val uri: String
    )

    /** 主列表 */
    data class M3u8MasterPlaylist(
        val variants: List<M3u8Variant>
    ) {
        val bestVariant: M3u8Variant
            get() = variants.reduce { a, b -> if (a.bandwidth > b.bandwidth) a else b }
    }

    /** 媒体列表 */
    data class M3u8MediaPlaylist(
        val segments: List<M3u8Segment>,
        val targetDuration: Double,
        val isVod: Boolean
    )

    enum class M3u8Type { MASTER, MEDIA }

    fun detectType(content: String): M3u8Type {
        return if (content.contains("#EXT-X-STREAM-INF")) M3u8Type.MASTER else M3u8Type.MEDIA
    }

    /** 将相对 URL 解析为绝对 URL */
    fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl
        }
        if (relativeUrl.startsWith("/")) {
            val uri = android.net.Uri.parse(baseUrl)
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            return "${uri.scheme}://${uri.host}$portPart$relativeUrl"
        }
        val basePath = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1)
        return "$basePath$relativeUrl"
    }

    fun parseMasterPlaylist(content: String, baseUrl: String): M3u8MasterPlaylist {
        val lines = content.split('\n').map { it.trim() }
        val variants = mutableListOf<M3u8Variant>()

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val attrs = line.substring("#EXT-X-STREAM-INF:".length)
                var bandwidth = 0
                var resolution: String? = null

                Regex("BANDWIDTH=(\\d+)").find(attrs)?.let { bandwidth = it.groupValues[1].toIntOrNull() ?: 0 }
                Regex("RESOLUTION=([^\\s,]+)").find(attrs)?.let { resolution = it.groupValues[1] }

                if (i + 1 < lines.size && !lines[i + 1].startsWith("#")) {
                    val uri = resolveUrl(baseUrl, lines[i + 1])
                    variants.add(M3u8Variant(bandwidth = bandwidth, resolution = resolution, uri = uri))
                }
            }
        }

        return M3u8MasterPlaylist(variants = variants)
    }

    fun parseMediaPlaylist(content: String, baseUrl: String): M3u8MediaPlaylist {
        val lines = content.split('\n').map { it.trim() }
        val segments = mutableListOf<M3u8Segment>()
        var targetDuration = 0.0
        var hasEndList = false
        var isExplicitVod = false
        var isLiveEvent = false
        var currentDiscontinuityGroup = 0
        var currentKey: M3u8Key? = null
        var currentDuration = 0.0

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substring("#EXT-X-TARGETDURATION:".length).toDoubleOrNull() ?: 0.0
                }
                line == "#EXT-X-ENDLIST" -> hasEndList = true
                line == "#EXT-X-PLAYLIST-TYPE:VOD" -> isExplicitVod = true
                line == "#EXT-X-PLAYLIST-TYPE:EVENT" -> isLiveEvent = true
                line == "#EXT-X-DISCONTINUITY" -> currentDiscontinuityGroup++
                line.startsWith("#EXT-X-KEY:") -> currentKey = parseKey(line, baseUrl)
                line.startsWith("#EXTINF:") -> {
                    currentDuration = line.substring("#EXTINF:".length).split(',')[0].toDoubleOrNull() ?: 0.0
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val uri = resolveUrl(baseUrl, line)
                    segments.add(
                        M3u8Segment(
                            duration = currentDuration,
                            uri = uri,
                            discontinuityGroup = currentDiscontinuityGroup,
                            key = currentKey
                        )
                    )
                    currentDuration = 0.0
                }
            }
        }

        // VOD 判定：
        // 1. 有 #EXT-X-ENDLIST，或
        // 2. 有 #EXT-X-PLAYLIST-TYPE:VOD，或
        // 3. 有有限分片且不是显式 EVENT 直播流。
        // 很多第三方源对 VOD 内容省略 #EXT-X-ENDLIST。
        val isVod = hasEndList || isExplicitVod || (!isLiveEvent && segments.isNotEmpty())

        return M3u8MediaPlaylist(
            segments = segments,
            targetDuration = targetDuration,
            isVod = isVod
        )
    }

    private fun parseKey(line: String, baseUrl: String): M3u8Key? {
        val attrs = line.substring("#EXT-X-KEY:".length)
        val method = Regex("METHOD=([^,]+)").find(attrs)?.groupValues?.get(1) ?: "NONE"
        if (method == "NONE") return null

        val uri = Regex("URI=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)?.let { resolveUrl(baseUrl, it) } ?: ""
        val iv = Regex("IV=(0x[0-9a-fA-F]+)").find(attrs)?.groupValues?.get(1)

        return M3u8Key(method = method, uri = uri, iv = iv)
    }

    /** 提取去重的 KEY 列表（按 uri 去重） */
    fun extractUniqueKeys(playlist: M3u8MediaPlaylist): List<M3u8Key> {
        val seen = mutableSetOf<String>()
        val keys = mutableListOf<M3u8Key>()
        for (seg in playlist.segments) {
            val key = seg.key ?: continue
            if (!seen.contains(key.uri)) {
                seen.add(key.uri)
                keys.add(key)
            }
        }
        return keys
    }

    /**
     * 重建本地 m3u8（分片按 seg_00000.ts 命名）。
     * [keyUriToLocal] 将远端 KEY 地址映射为本地文件路径。
     */
    fun buildLocalM3u8(
        segments: List<M3u8Segment>,
        targetDuration: Double,
        keyUriToLocal: Map<String, String> = emptyMap()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#EXT-X-VERSION:3")
        sb.appendLine("#EXT-X-TARGETDURATION:${kotlin.math.ceil(targetDuration).toInt()}")
        sb.appendLine("#EXT-X-MEDIA-SEQUENCE:0")

        var lastDiscontinuityGroup = 0
        var lastKey: M3u8Key? = null

        for (i in segments.indices) {
            val seg = segments[i]

            if (seg.discontinuityGroup != lastDiscontinuityGroup && i > 0) {
                sb.appendLine("#EXT-X-DISCONTINUITY")
                lastDiscontinuityGroup = seg.discontinuityGroup
            }

            if (seg.key != lastKey) {
                if (seg.key == null) {
                    sb.appendLine("#EXT-X-KEY:METHOD=NONE")
                } else {
                    val localUri = keyUriToLocal[seg.key!!.uri] ?: seg.key!!.uri
                    val keySb = StringBuilder("#EXT-X-KEY:METHOD=${seg.key!!.method},URI=\"$localUri\"")
                    if (seg.key!!.iv != null) {
                        keySb.append(",IV=${seg.key!!.iv}")
                    }
                    sb.appendLine(keySb.toString())
                }
                lastKey = seg.key
            }

            sb.appendLine("#EXTINF:${String.format("%.6f", seg.duration)},")
            sb.appendLine("seg_${i.toString().padStart(5, '0')}.ts")
        }

        sb.appendLine("#EXT-X-ENDLIST")
        return sb.toString()
    }

    private fun isM3u8Url(url: String): Boolean {
        val path = android.net.Uri.parse(url).path.orEmpty().lowercase()
        return path.endsWith(".m3u8")
    }

    /**
     * 展开嵌套 M3U8 片段。
     *
     * [fetcher] 异步回调，给定 URL 返回 M3U8 文本内容。
     * [maxDepth] 递归深度上限，防止无限嵌套。
     */
    suspend fun resolveNestedSegments(
        segments: List<M3u8Segment>,
        fetcher: suspend (String) -> String,
        maxDepth: Int = 3
    ): List<M3u8Segment> {
        if (maxDepth <= 0) return segments
        if (segments.none { isM3u8Url(it.uri) }) return segments

        val result = mutableListOf<M3u8Segment>()
        var groupOffset = 0

        for (seg in segments) {
            if (!isM3u8Url(seg.uri)) {
                result.add(
                    M3u8Segment(
                        duration = seg.duration,
                        uri = seg.uri,
                        discontinuityGroup = seg.discontinuityGroup + groupOffset,
                        key = seg.key
                    )
                )
                continue
            }

            // 该 segment 的 URI 指向嵌套 m3u8，展开
            try {
                val content = fetcher(seg.uri)
                val nested = parseMediaPlaylist(content, seg.uri)
                val resolved = resolveNestedSegments(nested.segments, fetcher, maxDepth - 1)

                if (resolved.isEmpty()) continue

                val nestedBase = seg.discontinuityGroup + groupOffset
                var maxNestedGroup = 0
                for (ns in resolved) {
                    if (ns.discontinuityGroup > maxNestedGroup) {
                        maxNestedGroup = ns.discontinuityGroup
                    }
                }

                for (ns in resolved) {
                    result.add(
                        M3u8Segment(
                            duration = ns.duration,
                            uri = ns.uri,
                            discontinuityGroup = ns.discontinuityGroup + nestedBase,
                            key = ns.key
                        )
                    )
                }

                // 后续 segment 的 group 需要额外偏移，避免碰撞
                groupOffset += maxNestedGroup
            } catch (e: Exception) {
                // 获取/解析失败，保留原始 segment
                result.add(
                    M3u8Segment(
                        duration = seg.duration,
                        uri = seg.uri,
                        discontinuityGroup = seg.discontinuityGroup + groupOffset,
                        key = seg.key
                    )
                )
            }
        }

        return result
    }
}