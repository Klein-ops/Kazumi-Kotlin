package com.kazumi.port.network

import com.kazumi.port.data.model.ApiBodyType
import com.kazumi.port.data.model.HttpHeaders
import com.kazumi.port.data.model.PreparedRuleRequest
import com.kazumi.port.data.model.RuleExecutionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则站点请求客户端（携带插件 Cookie）
 * 对应 Dart PluginSiteClient
 */
object PluginSiteClient {

    /** 插件名 -> Cookie 字符串 */
    private val cookieStore = HashMap<String, String>()

    @Synchronized
    fun saveCookies(pluginName: String, cookies: String) {
        if (cookies.isNotEmpty()) {
            cookieStore[pluginName] = cookies
        }
    }

    @Synchronized
    fun loadCookies(pluginName: String): String = cookieStore[pluginName] ?: ""

    @Synchronized
    fun clearCookies(pluginName: String) {
        cookieStore.remove(pluginName)
    }

    /**
     * 发送请求并返回响应文本（非 2xx 时抛 BadResponseException 保留响应体）
     */
    suspend fun requestText(
        url: String,
        method: String = "GET",
        headers: Map<String, Any?> = emptyMap(),
        queryParameters: Map<String, Any?> = emptyMap(),
        data: Any? = null
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val fullUrl = buildUrlWithQuery(url, queryParameters)
                val requestBuilder = Request.Builder()
                    .url(fullUrl)
                    .method(method.uppercase(), buildBody(method, data, headers))

                headers.forEach { (key, value) ->
                    if (value != null) requestBuilder.header(key, value.toString())
                }
                if (requestBuilder.build().header("user-agent") == null) {
                    requestBuilder.header("user-agent", HttpHeaders.randomUA())
                }
                requestBuilder.header("accept", "*/*")
                requestBuilder.header("accept-language", HttpHeaders.randomAcceptLanguage())

                val call = HttpClientFactory.getClient().newCall(requestBuilder.build())
                val response = call.execute()
                response.use {
                    if (!it.isSuccessful) {
                        val body = it.body?.string() ?: ""
                        throw BadResponseException(it.code, body, it.headers.toMultimap())
                    }
                    it.body?.string() ?: ""
                }
            } catch (e: BadResponseException) {
                throw e
            } catch (e: Exception) {
                throw NetworkException.wrap(e)
            }
        }
    }

    /**
     * 默认规则请求执行器
     * 对应 Dart _DefaultRuleRequestExecutor
     */
    suspend fun executeRuleRequest(
        request: PreparedRuleRequest,
        config: RuleExecutionConfig
    ): String {
        val cookieHeader = if (request.includeCookies) {
            loadCookies(config.pluginName)
        } else ""

        val headers = HashMap<String, Any?>()
        headers["referer"] = "${config.baseUrl}/"
        if (cookieHeader.isNotEmpty()) headers["cookie"] = cookieHeader
        request.headers.forEach { (key, value) ->
            headers[key.lowercase()] = value
        }

        if (request.method == "POST") {
            when (request.bodyType) {
                ApiBodyType.JSON -> headers.putIfAbsent("content-type", "application/json")
                ApiBodyType.FORM -> headers.putIfAbsent(
                    "content-type", "application/x-www-form-urlencoded"
                )
            }
        }

        return requestText(
            request.url,
            method = request.method,
            headers = headers,
            queryParameters = request.query,
            data = if (request.method == "POST") request.body else null
        )
    }

    private fun buildUrlWithQuery(url: String, query: Map<String, Any?>): String {
        if (query.isEmpty()) return url
        val base = url.toHttpUrlOrNull() ?: return url
        val builder = base.newBuilder()
        query.forEach { (key, value) ->
            if (value != null) builder.addQueryParameter(key, value.toString())
        }
        return builder.build().toString()
    }

    private fun buildBody(
        method: String,
        data: Any?,
        headers: Map<String, Any?>
    ): okhttp3.RequestBody? {
        if (!method.equals("POST", ignoreCase = true) || data == null) return null
        val contentType = headers["content-type"]?.toString()
            ?: headers["Content-Type"]?.toString()

        return when {
            contentType?.contains("application/json") == true -> {
                val json = when (data) {
                    is String -> data
                    is JSONObject -> data.toString()
                    is JSONArray -> data.toString()
                    is Map<*, *> -> JSONObject().apply {
                        data.forEach { (k, v) -> put(k.toString(), v) }
                    }.toString()
                    else -> data.toString()
                }
                json.toRequestBody("application/json; charset=utf-8".toMediaType())
            }
            contentType?.contains("application/x-www-form-urlencoded") == true -> {
                val builder = FormBody.Builder()
                when (data) {
                    is Map<*, *> -> data.forEach { (k, v) ->
                        builder.add(k.toString(), v?.toString() ?: "")
                    }
                    else -> {}
                }
                builder.build()
            }
            else -> {
                data.toString().toRequestBody("text/plain; charset=utf-8".toMediaType())
            }
        }
    }
}

/**
 * 非 2xx 响应异常（保留响应体供验证码检测）
 */
class BadResponseException(
    val code: Int,
    val responseBody: String,
    val headers: Map<String, List<String>>
) : Exception("HTTP $code")