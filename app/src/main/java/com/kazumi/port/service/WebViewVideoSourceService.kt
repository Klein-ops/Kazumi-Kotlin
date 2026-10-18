package com.kazumi.port.service

import android.content.Context
import com.kazumi.port.util.FileLogger

/**
 * WebView 视频源解析服务
 *
 * 对应原版 webview_video_source_service.dart。
 *
 * 使用隐藏 WebView 加载集数页面，注入 JS 解析脚本收割视频源。
 * WebView 实例在服务生命周期内复用；单个服务实例持有一个 WebView，
 * 因此解析任务按实例串行执行，下载并行通过多个服务实例实现。
 */
class WebViewVideoSourceService(
    private val context: Context
) : IVideoSourceService {

    private var controller: VideoWebviewController? = null
    private var isDisposed = false

    /** 日志回调（供 UI 展示解析日志） */
    var onLog: ((String) -> Unit)? = null

    private suspend fun getController(): VideoWebviewController {
        var c = controller
        if (c == null) {
            c = VideoWebviewController(context)
            c.onLog = { log ->
                onLog?.invoke(log)
                // 同步写入文件日志，便于排查解析失败
                FileLogger.d("WebViewResolve", log)
            }
            c.init()
            controller = c
        }
        return c
    }

    /**
     * 解析视频源 URL。
     * 串行执行（同一实例同时只处理一个请求）；切换集数时内部会先卸载旧页面。
     *
     * 超时由 [VideoWebviewController] 内部统一处理（抛出 [VideoSourceTimeoutException]），
     * 取消通过 [cancel] 以 [VideoSourceCancelledException] 完成等待。
     */
    override suspend fun resolve(
        episodeUrl: String,
        useLegacyParser: Boolean,
        offset: Int,
        timeoutMillis: Long
    ): VideoSource {
        if (isDisposed) throw VideoSourceCancelledException()
        FileLogger.i("WebViewResolve", "resolve start: url=$episodeUrl legacy=$useLegacyParser timeout=$timeoutMillis")

        val c = getController()
        val event = try {
            c.resolveVideoSource(
                url = episodeUrl,
                useLegacyParser = useLegacyParser,
                offset = offset,
                timeoutMillis = timeoutMillis
            )
        } catch (e: Exception) {
            FileLogger.w("WebViewResolve", "resolveVideoSource threw: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }

        FileLogger.i("WebViewResolve", "resolve done: url=${event.url} format=${event.format} offset=${event.offset}")
        return VideoSource(
            url = event.url,
            offset = event.offset,
            type = VideoSourceType.ONLINE,
            format = event.format
        )
    }

    /** 取消当前正在进行的解析 */
    override fun cancel() {
        controller?.cancel()
    }

    /** 释放 WebView 资源 */
    override suspend fun dispose() {
        if (isDisposed) return
        isDisposed = true
        controller?.dispose()
        controller = null
    }
}