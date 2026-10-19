package com.kazumi.port.data.repository

import com.google.gson.JsonParser
import com.kazumi.port.data.model.DanmakuEntry
import com.kazumi.port.network.HttpClientFactory
import com.kazumi.port.network.NetworkException
import com.kazumi.port.network.NetworkExceptionType
import com.kazumi.port.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Bilibili 弹幕源（免鉴权）
 *
 * 为什么需要它：
 * 弹弹play 开放弹幕网络 API v2 已强制 AppId/AppSecret 鉴权（无头 403
 * `Missing Authentication Headers`、AppId 无效 403 `Invalid AppId`），
 * 第三方客户端没有凭据就无法取弹幕。而 Bilibili 的这几条公开接口
 * 实测（2026-10-19，用户网络）**无需 cookie、无需登录、无需任何 AppId**：
 *
 *  1) 按番剧名搜索：
 *     GET https://api.bilibili.com/x/web-interface/search/type
 *         ?search_type=media_bangumi&keyword={番剧名}
 *     → data.result[].season_id / media_id / title（title 内含 <em> 高亮标签）
 *
 *  2) 取分集列表（拿每集 cid）：
 *     GET https://api.bilibili.com/pgc/view/web/season?season_id={seasonId}
 *     → result.episodes[].cid / title / id(ep_id) / bvid
 *
 *  3) 按 cid 取弹幕 XML：
 *     GET https://comment.bilibili.com/{cid}.xml        （等价 api.bilibili.com/x/v1/dm/list.so?oid=）
 *     → 响应体是 **raw deflate 压缩**（Content-Encoding: deflate，
 *       注意 OkHttp 只自动解 gzip，所以这里必须用 Inflater 手工解），
 *       解压后为 <i><d p="时间,模式,字号,颜色,时间戳,池,用户哈希,弹幕id,权重">文本</d>...</i>
 *
 * 字段映射到本项目 DanmakuEntry：
 *  - message ← <d> 文本（需 HTML 反转义）
 *  - time    ← p[0]（秒，浮点）
 *  - type    ← p[1] 的 B 站 mode：1/2/3/6 → 1(滚动)，4 → 4(底部)，5 → 5(顶部)，
 *              7(高级)/8(代码弹幕) 不参与渲染，直接丢弃
 *  - color   ← p[3]（十进制 RGB）
 *  - source  ← "BiliBili"（与设置页 BiliBili 开关、原版 DanmakuEntry.source 对齐）
 */
class BilibiliDanmakuApi {

    private val client get() = HttpClientFactory.getClient()

    /** 搜索到的番剧季 */
    data class BiliSeason(
        val seasonId: Long,
        val mediaId: Long,
        val title: String
    )

    /** 分集（cid 用于取弹幕） */
    data class BiliEpisode(
        val cid: Long,
        val epId: Long,
        val title: String
    )

    /**
     * 按番剧名搜索 B 站番剧季。
     * @return 按相关度排序（B 站接口本身已排序），已剔除无 seasonId 的结果
     */
    suspend fun searchSeasons(title: String): List<BiliSeason> {
        val keyword = title.trim()
        if (keyword.isEmpty()) return emptyList()
        val url = "https://api.bilibili.com/x/web-interface/search/type" +
            "?search_type=media_bangumi&keyword=" + urlEncode(keyword)
        val json = getJson(url)
        val code = json.get("code")?.asInt ?: -1
        if (code != 0) {
            FileLogger.w("BiliDanmaku", "搜索失败: code=$code msg=${json.get("message")}")
            return emptyList()
        }
        val result = json.getAsJsonObject("data")
            ?.getAsJsonArray("result")
            ?: return emptyList()
        val out = ArrayList<BiliSeason>()
        for (e in result) {
            if (!e.isJsonObject) continue
            val obj = e.asJsonObject
            val seasonId = obj.get("season_id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            if (seasonId == 0L) continue
            out.add(
                BiliSeason(
                    seasonId = seasonId,
                    mediaId = obj.get("media_id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    title = stripHtml(obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: "")
                )
            )
        }
        FileLogger.i("BiliDanmaku", "搜索 \"$keyword\" → ${out.size} 个结果")
        return out
    }

    /** 取某季的分集列表（含 cid） */
    suspend fun getEpisodes(seasonId: Long): List<BiliEpisode> {
        if (seasonId <= 0L) return emptyList()
        val url = "https://api.bilibili.com/pgc/view/web/season?season_id=$seasonId"
        val json = getJson(url)
        val code = json.get("code")?.asInt ?: -1
        if (code != 0) {
            FileLogger.w("BiliDanmaku", "取分集失败: code=$code msg=${json.get("message")}")
            return emptyList()
        }
        val eps = json.getAsJsonObject("result")
            ?.getAsJsonArray("episodes")
            ?: return emptyList()
        val out = ArrayList<BiliEpisode>()
        for (e in eps) {
            if (!e.isJsonObject) continue
            val obj = e.asJsonObject
            val cid = obj.get("cid")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            if (cid == 0L) continue
            out.add(
                BiliEpisode(
                    cid = cid,
                    epId = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                    title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                )
            )
        }
        return out
    }

    /** 按 cid 取弹幕，解析为 DanmakuEntry 列表 */
    suspend fun getDanmaku(cid: Long): List<DanmakuEntry> {
        if (cid <= 0L) return emptyList()
        val bytes = getBytes("https://comment.bilibili.com/$cid.xml")
        val xml = decodeBody(bytes)
        if (xml.isEmpty()) {
            FileLogger.w("BiliDanmaku", "cid=$cid 弹幕响应为空")
            return emptyList()
        }
        val matches = DM_REGEX.findAll(xml)
        val out = ArrayList<DanmakuEntry>(matches.count().coerceAtMost(8000))
        for (m in matches) {
            val p = m.groupValues[1]
            val text = unescapeHtml(m.groupValues[2])
            if (text.isBlank()) continue
            val parts = p.split(",")
            val time = parts.getOrNull(0)?.toDoubleOrNull() ?: continue
            val mode = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val color = parts.getOrNull(3)?.toLongOrNull() ?: 0xFFFFFFL
            val type = when (mode) {
                4 -> 4      // 底部
                5 -> 5      // 顶部
                7, 8 -> continue // 高级/代码弹幕：本渲染层不支持，丢弃
                else -> 1   // 1/2/3/6 一律按滚动处理
            }
            out.add(
                DanmakuEntry(
                    message = text,
                    time = time,
                    type = type,
                    color = color,
                    source = "BiliBili"
                )
            )
        }
        FileLogger.i("BiliDanmaku", "cid=$cid 解析到 ${out.size} 条弹幕")
        return out
    }

    /**
     * 一站式：番剧名 + 集号 → 弹幕列表。
     * 会先选最匹配的一季（标题完全相等 > 互相包含 > 取首个），再按集号取 cid。
     */
    suspend fun fetchDanmakuByTitle(title: String, episode: Int): List<DanmakuEntry> {
        val kw = title.trim()
        if (kw.isEmpty() || episode <= 0) return emptyList()
        val seasons = searchSeasons(kw)
        if (seasons.isEmpty()) return emptyList()
        val season = pickBestSeason(kw, seasons)
        FileLogger.i("BiliDanmaku", "选中季: ${season.title} seasonId=${season.seasonId}")
        val eps = getEpisodes(season.seasonId)
        if (eps.isEmpty()) return emptyList()
        val ep = eps.getOrNull(episode - 1)
            ?: eps.firstOrNull { Regex("\\d+").find(it.title)?.value?.toIntOrNull() == episode }
            ?: return emptyList()
        FileLogger.i("BiliDanmaku", "选中集: ${ep.title} cid=${ep.cid}")
        return getDanmaku(ep.cid)
    }

    /** 标题匹配：完全相等 > 去掉季/字幕组后缀后相等 > 包含 > 首个 */
    private fun pickBestSeason(keyword: String, seasons: List<BiliSeason>): BiliSeason {
        val key = normalizeTitle(keyword)
        seasons.firstOrNull { normalizeTitle(it.title) == key }?.let { return it }
        seasons.firstOrNull {
            val t = normalizeTitle(it.title)
            t.contains(key) || key.contains(t)
        }?.let { return it }
        return seasons.first()
    }

    private fun normalizeTitle(s: String): String =
        s.replace(Regex("[\\s　!！?？·・:：\\-—_~、,，.。'\"“”‘’()（）\\[\\]【】]"), "")
            .lowercase()

    // ---------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------

    private suspend fun getJson(url: String) = withContext(Dispatchers.IO) {
        val text = decodeBody(getBytes(url))
        if (text.isEmpty()) {
            throw NetworkException(NetworkExceptionType.BAD_RESPONSE, "B站响应为空: $url")
        }
        try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw NetworkException(
                NetworkExceptionType.BAD_RESPONSE,
                "B站响应不是合法 JSON: ${text.take(80)}",
                rawError = e
            )
        }
    }

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://www.bilibili.com/")
            .header("Accept", "*/*")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    throw NetworkException(
                        NetworkExceptionType.BAD_RESPONSE,
                        "B站 HTTP ${response.code}: ${String(body).take(100)}",
                        statusCode = response.code
                    )
                }
                body
            }
        } catch (e: NetworkException) {
            throw e
        } catch (e: Exception) {
            throw NetworkException.wrap(e)
        }
    }

    /**
     * 解码响应体：B 站 comment 接口返回 raw deflate，OkHttp 不会自动解；
     * 其他接口多数返回明文或 gzip（gzip 由 OkHttp 自动解）。
     */
    private fun decodeBody(bytes: ByteArray, encoding: String? = null): String {
        if (bytes.isEmpty()) return ""
        // 明文（JSON / XML）
        if (bytes[0] == '<'.code.toByte() || bytes[0] == '{'.code.toByte() || bytes[0] == '['.code.toByte()) {
            return String(bytes, Charsets.UTF_8)
        }
        // raw deflate
        try {
            val inflater = Inflater(true)
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 3)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
            inflater.end()
            if (out.size() > 0) return String(out.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            // 落到下面的 zlib 尝试
        }
        // zlib 头（带 header 的 deflate）
        try {
            val inflater = Inflater(false)
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 3)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
            inflater.end()
            if (out.size() > 0) return String(out.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").trim()

    private fun unescapeHtml(s: String): String = s
        .replace("\u0026lt;", "<")
        .replace("\u0026gt;", ">")
        .replace("\u0026quot;", "\u0022")
        .replace("\u0026apos;", "\u0027")
        .replace("\u0026#39;", "\u0027")
        .replace("\u0026amp;", "\u0026")

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val DM_REGEX = Regex("<d p=\"([^\"]+)\">(.*?)</d>", RegexOption.DOT_MATCHES_ALL)
    }
}