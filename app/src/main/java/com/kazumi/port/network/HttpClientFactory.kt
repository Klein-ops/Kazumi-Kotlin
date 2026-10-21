package com.kazumi.port.network

import android.content.Context
import com.kazumi.port.data.model.HttpHeaders
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 全局 OkHttp 客户端工厂
 * 对应 Dart request/core/dio_factory.dart
 *
 * 对齐原版关键点：
 * 1. 所有请求使用随机浏览器 UA（原版 getRandomUA()），避免被 CDN/站点按 UA 拒绝
 * 2. 连接超时 12s / 读取超时 12s（对齐原版 NetworkConfig.fromSettings()
 *    默认 connectTimeout=12s / receiveTimeout=12s；之前 5s/8s 偏短，
 *    镜像源 gitcode 实测建连就要 5s+，短超时容易误判失败）
 * 3. 全局共用 Client（连接复用），失败重试开启
 * 4. 支持代理：原版 NetworkConfig.fromSettings() 会把 proxyEnable/proxyUrl
 *    挂到所有请求（规则仓库 / Bangumi / 图片），这里同样支持，启动时由
 *    KazumiApp 读取设置并 applyProxy() 应用。
 */
object HttpClientFactory {

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var currentProxy: Proxy? = null

    fun init(context: Context) {
        getClient()
    }

    /**
     * 应用代理设置并重建全局 Client。
     * @param proxyHost 代理主机；传 null 清除代理
     * @param proxyPort 代理端口；<=0 视为无效
     * 调用后新请求立即走新 Client；进行中的旧请求不受影响。
     */
    fun applyProxy(proxyHost: String?, proxyPort: Int?) {
        synchronized(this) {
            val proxy = if (proxyHost != null && proxyPort != null && proxyPort > 0) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
            } else {
                null
            }
            currentProxy = proxy
            client = buildClient(proxy)
        }
    }

    fun getClient(): OkHttpClient {
        return client ?: synchronized(this) {
            client ?: buildClient(currentProxy).also { client = it }
        }
    }

    @Volatile
    private var lenientClient: OkHttpClient? = null

    /**
     * 宽松 TLS 客户端：信任所有证书、不校验 hostname。
     * 仅用于播放器拉取视频流/弹幕 CDN（聚合播放器行业惯例：视频源 CDN
     * 证书链五花八门，官方 Kazumi 同样不校验证书）。
     * 绝不用于登录/凭据/收藏等全局请求（那些仍走 getClient 严格校验）。
     */
    fun getLenientClient(): OkHttpClient {
        return lenientClient ?: synchronized(this) {
            lenientClient ?: buildClient(currentProxy, lenientTls = true).also { lenientClient = it }
        }
    }

    private fun buildClient(proxy: Proxy? = null, lenientTls: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        if (proxy != null) {
            builder.proxy(proxy)
        }
        builder.addInterceptor { chain ->
            val original = chain.request()
            // 仅当请求未显式设置 UA 时才注入随机浏览器 UA；
            // BangumiClient 会给请求设置专有 UA（Predidit/Kazumi/...），不能被覆盖
            if (original.header("User-Agent").isNullOrEmpty()) {
                val request = original.newBuilder()
                    .header("User-Agent", HttpHeaders.randomUA())
                    .header("Accept", "*/*")
                    .header("Accept-Language", HttpHeaders.randomAcceptLanguage())
                    .build()
                chain.proceed(request)
            } else {
                chain.proceed(original)
            }
        }
        if (lenientTls) {
            // 信任所有证书 + 任意 hostname（仅播放器拉流用）
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        return builder.build()
    }
}

/**
 * 网络异常类型
 * 对应 Dart NetworkExceptionType
 */
enum class NetworkExceptionType {
    CONNECT, TIMEOUT, BAD_RESPONSE, CANCEL, UNKNOWN
}

/**
 * 网络异常
 * 对应 Dart NetworkException
 */
class NetworkException(
    val type: NetworkExceptionType,
    message: String,
    val rawError: Throwable? = null,
    val statusCode: Int? = null
) : Exception(message, rawError) {
    companion object {
        fun wrap(e: Throwable): NetworkException {
            return when (e) {
                is NetworkException -> e
                is java.net.SocketTimeoutException -> NetworkException(
                    NetworkExceptionType.TIMEOUT, "请求超时", e
                )
                is java.net.ConnectException -> NetworkException(
                    NetworkExceptionType.CONNECT, "连接失败: ${e.message}", e
                )
                is java.io.IOException -> NetworkException(
                    NetworkExceptionType.UNKNOWN, "IO错误: ${e.message}", e
                )
                else -> NetworkException(
                    NetworkExceptionType.UNKNOWN, "未知错误: ${e.message}", e
                )
            }
        }
    }
}