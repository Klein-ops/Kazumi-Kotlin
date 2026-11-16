package com.kazumi.port.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ECHConfig 解析器（对齐原版 bangumi_ech_resolver.dart）。
 *
 * 原版逻辑：
 * - DoH 端点：dns.alidns.com/resolve（type=HTTPS 查 SvcParams 里的 ech=）
 * - 对 lain.bgm.tv 及 Bangumi API 域名，共享使用 crypto.cloudflare.com 的 ECHConfig
 *   （configDomains 映射），再直连写死的 addressOverrides IP（见 EchHttpNative.CONNECT_IPS）
 *
 * 本实现只解析 crypto.cloudflare.com 的 ech=（base64 ECHConfigList），
 * 供 EchHttpNative 在 ECH 模式下使用。
 */
object EchResolver {

    private const val DOH_ENDPOINT = "https://dns.alidns.com/resolve"
    private const val CONFIG_DOMAIN = "crypto.cloudflare.com"

    /** ECHConfig 缓存有效期（官方 DNS TTL 实测仅 1~5 秒：2026-11-17 日志 DoH 响应
     *  TTL=5/TTL=1 各一次。旧值 60s 导致 config 早已被 Cloudflare 轮换失效后仍被复用，
     *  列表 20+ 张封面全部 ECH 握手超时（25s×N），表现为"只有前 2-3 张能加载"。
     *  跟随真实 TTL 取 5s，宁可多打几次 DoH（打进 6s 短超时，成本低）也要用新 config。 */
    private const val CACHE_TTL_MS = 5_000L

    /** 短超时 DoH 客户端（复用全局 OkHttp 的代理设置） */
    private val client: OkHttpClient by lazy {
        HttpClientFactory.getClient().newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** 缓存：值 + 拉取时间。并发读用 @Volatile 保证可见性，重复拉取由调用方串行化 */
    @Volatile
    private var cachedConfig: String? = null
    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 解析 crypto.cloudflare.com 的 HTTPS 记录，提取 ech= 参数（base64 ECHConfigList）。
     * 结果缓存 [CACHE_TTL_MS]，期间复用同一 ECHConfig——对齐原版会话级复用，
     * 避免列表页每张图都打一次 DoH（原日志 12 图 = 12 次 DoH，纯浪费）。
     * @return ECHConfigList base64 字符串；解析失败返回 null（调用方回退直连/镜像）
     */
    fun fetchEchConfig(): String? {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_TTL_MS) {
            val cached = cachedConfig
            if (cached != null) return cached
        }
        return fetchFromDoh()
    }

    /** 强制丢弃缓存的 ECHConfig，下次 fetchEchConfig 必定重新 DoH（失败重试用） */
    fun invalidate() {
        synchronized(this) {
            cachedConfig = null
            cachedAt = 0L
        }
    }

    private fun fetchFromDoh(): String? = try {
        val url = "$DOH_ENDPOINT?name=$CONFIG_DOMAIN&type=HTTPS"
        val request = Request.Builder().url(url)
            .header("accept", "application/dns-json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                com.kazumi.port.util.FileLogger.w("EchResolver", "DoH HTTP ${resp.code}")
                return null
            }
            val body = resp.body?.string().orEmpty()
            com.kazumi.port.util.FileLogger.d("EchResolver", "DoH body len=${body.length} head=${body.take(200)}")
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return null
            var found: String? = null
            for (i in 0 until answers.length()) {
                val data = answers.optJSONObject(i)?.optString("data").orEmpty()
                // SvcParams 形如: 1 . alpn="h2" ech="AEX+..." ipv4hint=...
                val ech = Regex("""ech="([^"]+)"""").find(data)?.groupValues?.get(1)
                if (!ech.isNullOrEmpty()) {
                    found = ech
                    break
                }
            }
            if (found != null) {
                cachedConfig = found
                cachedAt = System.currentTimeMillis()
            }
            found
        }
    } catch (e: Exception) {
        com.kazumi.port.util.FileLogger.w("EchResolver", "DoH failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** 是否已能拿到 ECHConfig（调试 / 预检用） */
    fun available(): Boolean = !fetchEchConfig().isNullOrEmpty()
}