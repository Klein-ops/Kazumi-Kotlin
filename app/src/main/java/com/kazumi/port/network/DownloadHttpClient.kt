package com.kazumi.port.network

import com.kazumi.port.util.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 下载专用 HTTP 客户端
 *
 * 对应原版 lib/request/clients/download_http_client.dart +
 * lib/request/core/dio_factory.dart 的 downloadDio 配置：
 * connectTimeout 15s / receiveTimeout 30s / 默认随机 UA。
 *
 * 与原版 DownloadHttpClient 一样，仅做薄封装：
 * - [getPlain]  抓取文本（m3u8 列表）
 * - [getStream] 流式响应（直链 mp4 断点续传）
 * - [download]  下载到本地文件（分片 / KEY）
 */
object DownloadHttpClient {

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 直链大文件下载用的宽松读超时客户端（对应原版 getStream receiveTimeout 30min） */
    private val streamClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .readTimeout(30, TimeUnit.MINUTES)
            .build()
    }

    /**
     * 下载取消令牌（对应 Dio CancelToken）
     *
     * 取消后所有已注册的 OkHttp Call 会被同步 cancel()。
     */
    class CancelToken {
        @Volatile
        var cancelled: Boolean = false
            private set

        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        fun cancel(reason: String? = null) {
            if (cancelled) return
            cancelled = true
            for (listener in listeners) {
                try {
                    listener()
                } catch (_: Exception) {
                }
            }
        }

        fun onCancel(listener: () -> Unit) {
            if (cancelled) {
                listener()
            } else {
                listeners.add(listener)
            }
        }
    }

    /**
     * 抓取纯文本响应。
     *
     * @param receiveTimeoutMillis 读超时（默认 30s）
     * @param maxBytes 响应体超过该字节数时抛 [NetworkException]（对应原版 2MB 上限）
     * @param onReceiveProgress 进度回调 (received, total)
     */
    suspend fun getPlain(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cancelToken: CancelToken? = null,
        receiveTimeoutMillis: Long = 30_000,
        maxBytes: Long = 2 * 1024 * 1024,
        onReceiveProgress: ((Long, Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val response = execute(
            url = url,
            headers = headers,
            client = baseClient.newBuilder().readTimeout(receiveTimeoutMillis, TimeUnit.MILLISECONDS).build(),
            cancelToken = cancelToken
        )

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw NetworkException(
                    NetworkExceptionType.BAD_RESPONSE,
                    "HTTP ${resp.code}",
                    statusCode = resp.code
                )
            }
            val body = resp.body ?: throw NetworkException(NetworkExceptionType.BAD_RESPONSE, "空响应体")
            val total = body.contentLength()
            var received = 0L
            val builder = StringBuilder()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    received += read
                    if (received > maxBytes) {
                        throw NetworkException(
                            NetworkExceptionType.CANCEL,
                            "响应过大，非 M3U8 播放列表"
                        )
                    }
                    builder.append(String(buffer, 0, read, Charsets.UTF_8))
                    onReceiveProgress?.invoke(received, total)
                }
            }
            builder.toString()
        }
    }

    /**
     * 发起流式 GET 请求，返回完整 [Response]（调用方负责 use() 关闭）。
     * 用于直链 mp4 下载：支持 Range 头与 416 重试语义。
     */
    suspend fun getStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cancelToken: CancelToken? = null,
        receiveTimeoutMillis: Long = 30 * 60 * 1000L
    ): Response = withContext(Dispatchers.IO) {
        execute(
            url = url,
            headers = headers,
            client = streamClient.newBuilder().readTimeout(receiveTimeoutMillis, TimeUnit.MILLISECONDS).build(),
            cancelToken = cancelToken
        )
    }

    /**
     * 下载到本地文件（覆盖写），返回实际写入字节数。
     * 对应原版 DownloadHttpClient.download()。
     */
    suspend fun download(
        url: String,
        savePath: String,
        headers: Map<String, String> = emptyMap(),
        cancelToken: CancelToken? = null,
        receiveTimeoutMillis: Long = 30_000
    ): Long = withContext(Dispatchers.IO) {
        val response = execute(
            url = url,
            headers = headers,
            client = baseClient.newBuilder().readTimeout(receiveTimeoutMillis, TimeUnit.MILLISECONDS).build(),
            cancelToken = cancelToken
        )

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw NetworkException(
                    NetworkExceptionType.BAD_RESPONSE,
                    "HTTP ${resp.code}",
                    statusCode = resp.code
                )
            }
            val body = resp.body ?: throw NetworkException(NetworkExceptionType.BAD_RESPONSE, "空响应体")
            val target = File(savePath)
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, 8192)
                }
            }
            target.length()
        }
    }

    private fun execute(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        cancelToken: CancelToken?
    ): Response {
        if (cancelToken?.cancelled == true) {
            throw NetworkException(
                NetworkExceptionType.CANCEL,
                "请求已被取消，请重新请求"
            )
        }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpHeaders.getRandomUA())
        for ((k, v) in headers) {
            builder.header(k, v)
        }
        val request = builder.build()

        val call = client.newCall(request)
        if (cancelToken != null) {
            cancelToken.onCancel { call.cancel() }
        }

        return try {
            call.execute()
        } catch (e: java.io.IOException) {
            if (cancelToken?.cancelled == true) {
                throw NetworkException(
                    NetworkExceptionType.CANCEL,
                    "请求已被取消，请重新请求",
                    e
                )
            }
            throw NetworkException.wrap(e)
        }
    }
}