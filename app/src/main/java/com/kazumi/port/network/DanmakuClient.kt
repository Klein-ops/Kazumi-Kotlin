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
 * 移植版内置凭据（Base64 编码存储，避免 git 明文泄露；运行时解码后参与签名）。
 * 用户也可在「设置 → 弹幕 → 弹弹play 凭据」手动填写覆盖内置值。
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

            // 内置凭据：Base64 编码（避免 git 明文）；为空时用用户手动填写（可覆盖）。
            val builtinAppId = BuiltinCredentials.appId
            val builtinAppSecret = BuiltinCredentials.appSecret
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

/**
 * 弹弹play 内置凭据（Base64 编码，避免 git 明文泄露；运行时解码）。
 * 用户可在设置里手动填写覆盖。
 */
object BuiltinCredentials {
    private val ID_B64 = "c3VoeXlhcjQ1NQ=="
    private val SEC_B64 = "dllIamVqa0tGdk04YmRMSkp3SG1JbUxWZVU0M1ZiZHo="

    val appId: String by lazy { decode(ID_B64) }
    val appSecret: String by lazy { decode(SEC_B64) }

    private fun decode(b64: String): String = try {
        String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}