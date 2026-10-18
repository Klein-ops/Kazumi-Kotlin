package com.kazumi.port.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import okhttp3.Request

/**
 * 弹幕 API 客户端
 * 对应 Dart danmaku_client.dart
 */
class DanmakuClient {

    private val client get() = HttpClientFactory.getClient()

    suspend fun get(
        url: String,
        queryParameters: Map<String, String> = emptyMap()
    ): JsonElement {
        return withContext(Dispatchers.IO) {
            val fullUrl = buildUrl(url, queryParameters)
            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", com.kazumi.port.data.model.HttpHeaders.randomUA())
                .header("Accept-Language", com.kazumi.port.data.model.HttpHeaders.randomAcceptLanguage())
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw NetworkException(
                            NetworkExceptionType.BAD_RESPONSE,
                            "Danmaku HTTP ${response.code}",
                            statusCode = response.code
                        )
                    }
                    JsonParser.parseString(body)
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