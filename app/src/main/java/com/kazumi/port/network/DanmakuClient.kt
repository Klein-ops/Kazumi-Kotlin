package com.kazumi.port.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.kazumi.port.data.repository.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * 弹幕 API 客户端
 * 对应 Dart danmaku_client.dart
 *
 * 2026-10 起弹弹play 开放弹幕网络 v2 接口强制鉴权，缺头一律返回
 * 403 + `X-Error-Message: Missing Authentication Headers`。
 * 官方（上游 Predidit/Kazumi）的做法是：
 *  - 请求头带 X-Auth: 1、X-AppId、X-Timestamp、X-Signature；
 *  - X-Signature = base64(sha256(AppId + Timestamp + Path + AppSecret))，
 *    其中 Path 为 URL 路径（以 / 开头、不含域名与查询参数）；
 *  - AppId / AppSecret 由构建时注入（--dart-define），仓库内不含明文。
 *
 * 移植版这里同样不硬编码密钥：凭据由用户在「设置 → 弹幕 → 弹弹play 凭据」里填写，
 * 存本机 DataStore，运行时按官方算法签名。
 */
class DanmakuClient(
    private val settings: SettingsManager? = null
) {

    private val client get() = HttpClientFactory.getClient()

    suspend fun get(
        url: String,
        queryParameters: Map<String, String> = emptyMap()
    ): JsonElement {
        return withContext(Dispatchers.IO) {
            val fullUrl = buildUrl(url, queryParameters)
            val httpUrl = fullUrl.toHttpUrlOrNull()
                ?: throw NetworkException(
                    NetworkExceptionType.UNKNOWN,
                    "弹幕请求 URL 非法: $fullUrl"
                )

            val builtinAppId = com.kazumi.port.BuildConfig.DANDAN_APP_ID.trim()
            val builtinAppSecret = com.kazumi.port.BuildConfig.DANDAN_APP_SECRET.trim()
            // 优先用构建时注入的凭据（local.properties，git 排除不进仓库）；
            // 未内置时用用户手动填写的设置（可覆盖）。
            val appId = builtinAppId.ifEmpty {
                settings?.getString(SettingsManager.Danmaku.DAN_DAN_APP_ID, "")?.trim().orEmpty()
            }
            val appSecret = builtinAppSecret.ifEmpty {
                settings?.getString(SettingsManager.Danmaku.DAN_DAN_APP_SECRET, "")?.trim().orEmpty()
            }
            val hasCredentials = appId.isNotEmpty() && appSecret.isNotEmpty()

            val builder = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", com.kazumi.port.data.model.HttpHeaders.randomUA())
                .header("Accept-Language", com.kazumi.port.data.model.HttpHeaders.randomAcceptLanguage())

            if (hasCredentials) {
                val timestamp = System.currentTimeMillis() / 1000
                builder
                    .header("X-Auth", "1")
                    .header("X-AppId", appId)
                    .header("X-Timestamp", timestamp.toString())
                    .header("X-Signature", sign(appId, timestamp, httpUrl.encodedPath, appSecret))
            }

            try {
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val serverMessage = response.header("X-Error-Message").orEmpty()
                        val detail = when {
                            serverMessage.isNotEmpty() -> serverMessage
                            response.code == 403 && !hasCredentials -> "未配置弹弹play凭据"
                            else -> "HTTP ${response.code}"
                        }
                        throw NetworkException(
                            NetworkExceptionType.BAD_RESPONSE,
                            "Danmaku HTTP ${response.code}: $detail",
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

    companion object {
        /**
         * 官方签名算法：base64(sha256(AppId + Timestamp + Path + AppSecret))
         * 与上游 utils/crypto.dart 的 generateDandanSignature() 等价。
         */
        fun sign(appId: String, timestamp: Long, path: String, appSecret: String): String {
            val data = appId + timestamp.toString() + path + appSecret
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(data.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(digest)
        }
    }
}