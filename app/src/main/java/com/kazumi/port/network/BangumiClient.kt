package com.kazumi.port.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kazumi.port.data.model.ApiEndpoints
import com.kazumi.port.data.model.HttpHeaders
import com.kazumi.port.data.repository.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Bangumi API 客户端
 * 对应 Dart bangumi_client.dart
 */
class BangumiClient(
    private val settings: SettingsManager
) {
    private val client get() = HttpClientFactory.getClient()

    /** Bangumi 公共 API 主机（可被镜像加速） */
    private val bangumiPublicHosts = setOf("api.bgm.tv", "next.bgm.tv")

    suspend fun get(
        url: String,
        queryParameters: Map<String, String> = emptyMap(),
        requiresAuth: Boolean = false,
        accessToken: String? = null
    ): JsonElement {
        val accel = readAcceleration()
        return withContext(Dispatchers.IO) {
            val fullUrl = buildUrl(url, queryParameters)
            val request = Request.Builder()
                .url(fullUrl)
                .headers(buildHeaders(
                    requiresAuth = requiresAuth,
                    accessToken = accessToken,
                    url = fullUrl,
                    method = "GET",
                    accel = accel
                ).toHeaders())
                .get()
                .build()
            executeWithFallback(request, accel)
        }
    }

    suspend fun post(
        url: String,
        data: Any? = null,
        queryParameters: Map<String, String> = emptyMap(),
        requiresAuth: Boolean = false
    ): JsonElement {
        val accel = readAcceleration()
        return withContext(Dispatchers.IO) {
            val fullUrl = buildUrl(url, queryParameters)
            val bodyText = if (data != null) {
                when (data) {
                    is String -> data
                    is JsonObject -> data.toString()
                    else -> com.google.gson.Gson().toJson(data)
                }
            } else {
                ""
            }
            val body = bodyText.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(fullUrl)
                .headers(buildHeaders(
                    requiresAuth = requiresAuth,
                    accessToken = null,
                    url = fullUrl,
                    method = "POST",
                    accel = accel,
                    bodyText = bodyText
                ).toHeaders())
                .post(body)
                .build()
            // 搜索/评论等受保护接口：镜像模式下由 buildHeaders 附加
            // X-AppId/X-Timestamp/X-Signature（内置官方凭据），不再分流到其它域名。
            executeWithFallback(request, accel)
        }
    }

    /**
     * Bangumi 加速执行（对齐原版 BangumiAccelerationInterceptor）：
     * - 设置 mirror：改写为 BANGUMI_MIRROR_DOMAIN 镜像请求
     * - 设置 direct/ech：直连
     * 只对 api.bgm.tv / next.bgm.tv 公共主机做改写，其它（鉴权/镜像域名）原样单发。
     *
     * 与串行回退不同，这里改成【并发 race】：镜像与直连同时发起，
     * 谁先成功用谁（不再串行等 12s+12s，冷启动不再被慢通道拖到超时返回空）。
     * 全部失败才抛异常。
     */
    private suspend fun executeWithFallback(request: Request, accel: String, allowMirror: Boolean = true): JsonElement {
        val url = request.url.toString()
        val isPublic = bangumiPublicHosts.any { url.startsWith("https://$it/") || url.startsWith("http://$it/") }
        if (!isPublic || !allowMirror) return execute(request)

        val mirror = mirrorOf(url)
        if (mirror == url) return execute(request)

        val mirrorFirst = accel == "mirror"
        val mirrorRequest = request.newBuilder().url(mirror).build()
        val directRequest = request

        return coroutineScope {
            val mirrorDeferred = async(Dispatchers.IO) {
                try {
                    Result.success(execute(mirrorRequest))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val directDeferred = async(Dispatchers.IO) {
                try {
                    Result.success(execute(directRequest))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            val ordered = if (mirrorFirst) listOf(mirrorDeferred, directDeferred)
            else listOf(directDeferred, mirrorDeferred)
            try {
                awaitFirstSuccess(ordered)
            } finally {
                mirrorDeferred.cancel()
                directDeferred.cancel()
            }
        }
    }

    /**
     * 并发 race 取第一个成功结果；全部失败时抛最后一个异常。
     */
    private suspend fun awaitFirstSuccess(jobs: List<Deferred<Result<JsonElement>>>): JsonElement {
        var lastError: Exception? = null
        while (true) {
            val done = jobs.filter { it.isCompleted }
            if (done.isNotEmpty()) {
                for (job in done) {
                    val r = job.await()
                    if (r.isSuccess) return r.getOrThrow()
                    if (lastError == null) lastError = r.exceptionOrNull() as? Exception
                }
                if (jobs.all { it.isCompleted }) break
            }
            delay(5)
        }
        throw lastError ?: NetworkException(NetworkExceptionType.UNKNOWN, "Bangumi 请求失败")
    }

    private fun mirrorOf(url: String): String {
        var out = url
        // 评论(/p1/.../comments)与搜索(/v0/search/subjects)在 kazumi.fyi 需签名；
        // 移植版已内置官方凭据并在 buildHeaders 中按需附加签名头（见 BangumiMirrorCredentials），
        // 因此与其它接口一样统一走 BANGUMI_MIRROR_DOMAIN（原版语义，不再分流到 bgmapi.com）。
        for (host in bangumiPublicHosts) {
            out = out.replace("https://$host", ApiEndpoints.BANGUMI_MIRROR_DOMAIN)
                .replace("http://$host", ApiEndpoints.BANGUMI_MIRROR_DOMAIN)
        }
        return out
    }

    private suspend fun readAcceleration(): String = try {
        // 对齐原版 BangumiAcceleration.current：未设置时回落到 enableBangumiProxy（默认镜像）
        settings.bangumiAcceleration()
    } catch (e: Exception) {
        "mirror"
    }

    private fun execute(request: Request): JsonElement {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw NetworkException(
                        NetworkExceptionType.BAD_RESPONSE,
                        "Bangumi HTTP ${response.code}",
                        statusCode = response.code
                    )
                }
                return JsonParser.parseString(body)
            }
        } catch (e: NetworkException) {
            throw e
        } catch (e: Exception) {
            throw NetworkException.wrap(e)
        }
    }

    private fun buildUrl(url: String, queryParameters: Map<String, String>): String {
        if (queryParameters.isEmpty()) return url
        val builder = StringBuilder(url)
        val hasQuery = url.contains("?")
        queryParameters.forEach { (k, v) ->
            builder.append(if (hasQuery || !url.endsWith("?")) {
                if (builder.indexOf("?") >= 0) "&" else "?"
            } else "")
            builder.append(k).append("=").append(URLEncoder.encode(v, "UTF-8"))
        }
        return builder.toString()
    }

    private fun buildHeaders(
        requiresAuth: Boolean,
        accessToken: String?,
        url: String,
        method: String,
        accel: String = "direct",
        bodyText: String = ""
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        HttpHeaders.bangumiHTTPHeader().forEach { (k, v) -> headers[k] = v }

        val token = (accessToken ?: runBlockingGetAccessToken()).trim()
        val bangumiSyncEnabled = runBlockingGetBangumiSync()
        if ((requiresAuth || bangumiSyncEnabled) && token.isNotEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }
        // 镜像模式下的受保护接口（搜索/评论）附加签名头，对齐原版
        // _shouldSignProtectedMirrorRequest + generateBangumiMirrorSearchSignature。
        if (accel == "mirror" && isPublicHost(url)) {
            val path = runCatching { java.net.URI(url).rawPath }.getOrNull().orEmpty()
            if (path.isNotEmpty() && BangumiMirrorCredentials.requiresSignature(method, path)) {
                val timestamp = System.currentTimeMillis() / 1000
                headers["X-AppId"] = BangumiMirrorCredentials.APP_ID
                headers["X-Timestamp"] = timestamp.toString()
                headers["X-Signature"] =
                    BangumiMirrorCredentials.signature(method, path, bodyText, timestamp)
            }
        }
        return headers
    }

    private fun isPublicHost(url: String): Boolean =
        bangumiPublicHosts.any { url.startsWith("https://$it/") || url.startsWith("http://$it/") }

    private fun runBlockingGetAccessToken(): String {
        return try {
            kotlinx.coroutines.runBlocking { settings.bangumiAccessToken() }
        } catch (e: Exception) {
            ""
        }
    }

    private fun runBlockingGetBangumiSync(): Boolean {
        return try {
            kotlinx.coroutines.runBlocking { settings.bangumiSyncEnabled() }
        } catch (e: Exception) {
            false
        }
    }
}