package com.kazumi.port.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * trace.moe 客户端
 * 对应 Dart trace_client.dart
 */
class TraceClient {

    private val client get() = HttpClientFactory.getClient()

    /**
     * POST 二进制图片数据
     */
    suspend fun post(
        url: String,
        data: ByteArray? = null,
        queryParameters: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): JsonElement {
        return withContext(Dispatchers.IO) {
            val fullUrl = buildUrl(url, queryParameters)
            val body = data?.toRequestBody("application/octet-stream".toMediaType())
            val builder = Request.Builder().url(fullUrl)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.header("User-Agent", com.kazumi.port.data.model.HttpHeaders.randomUA())
            builder.post(body ?: ByteArray(0).toRequestBody())
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw NetworkException(
                            NetworkExceptionType.BAD_RESPONSE,
                            "trace.moe HTTP ${response.code}",
                            statusCode = response.code
                        )
                    }
                    JsonParser.parseString(bodyStr)
                }
            } catch (e: NetworkException) {
                throw e
            } catch (e: Exception) {
                throw NetworkException.wrap(e)
            }
        }
    }

    private fun buildUrl(url: String, queryParameters: Map<String, String>): String {
        if (queryParameters.isEmpty()) return url
        val builder = StringBuilder(url)
        queryParameters.forEach { (k, v) ->
            builder.append(if (builder.indexOf("?") >= 0) "&" else "?")
            builder.append(k).append("=").append(URLEncoder.encode(v, "UTF-8"))
        }
        return builder.toString()
    }
}