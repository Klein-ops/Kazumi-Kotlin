package com.kazumi.port.service

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kazumi.port.data.model.HttpHeaders
import com.kazumi.port.util.MediaUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebView 视频源解析控制器
 *
 * 对应原版 video_webview_controller.dart + video_webview_android_impl.dart。
 *
 * 使用隐藏 WebView（无界面实例）加载集数页面，注入解析脚本：
 * - 新版解析器：拦截 Response.prototype.text / XHR 的 m3u8 响应 + 扫描 <video> 标签
 * - 旧版解析器：MutationObserver 监听 iframe src 变化，经 MediaUtils.decodeVideoSource 提取
 */
class VideoWebviewController(
    private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 隐藏 WebView 实例，整个服务生命周期内复用 */
    private var webView: WebView? = null

    /** 注入脚本是否已完成（只注入一次） */
    private var hasInjectedScripts = false

    /** 当前注入的脚本内容（页面每次导航时在 onPageStarted/onPageFinished 重新注入） */
    private var currentScript: String? = null

    /** 判断是否为媒体直链（m3u8/mp4/mkv/webm 等）。
     *  只检查 path 部分（? 之前）——query 里带 .m3u8 的 HTML 播放器页
     *  （如 artplayer 的 index.html?url=xxx.m3u8）不能被误判成视频源。 */
    private fun isMediaUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        // m3u8 用 contains（path 内）：部分站点的清单地址形如 /hls/index.m3u8/ 或带多余后缀
        if (path.contains(".m3u8")) return true
        return path.endsWith(".mp4") ||
            path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".flv")
    }

    /** 提取播放器页 query 中 url= 参数指向的真实视频源（artplayer 等常见形态：
     *  xxx/index.html?url=<真实m3u8|mp4>）。返回 null 表示不是这种形态或参数值不是媒体。 */
    private fun extractEmbeddedMediaUrl(url: String): String? {
        if (url.contains(".html") || url.contains(".htm")) {
            val q = url.substringAfter('?', "").ifEmpty { return null }
            for (pair in q.split('&')) {
                if (!pair.startsWith("url=")) continue
                val v = pair.substring(4)
                if (v.isBlank()) continue
                // 参数值可能被 URL 编码过，先解码一次再判定；解码失败就用原值
                if (isMediaUrl(v) || isMediaUrl(try {
                        java.net.URLDecoder.decode(v, "UTF-8")
                    } catch (e: Exception) {
                        v
                    })) {
                    return v
                }
            }
        }
        return null
    }

    /** 诊断用：单次解析中已记录的请求数（用于定位"页面到底请求了什么"） */
    private var probeCount = 0

    /** 过滤广告/统计类媒体请求 */
    private fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("googleads") || u.contains("googlesyndication.com") ||
            u.contains("prestrain.html") || u.contains("prestrain%2ehtml") ||
            u.contains("adtrafficquality") || u.contains("doubleclick")
    }

    /**
     * 对齐原版 _isRangeVideoRequest：带 `Range: bytes=` 的请求视为视频数据请求。
     * 这是"无扩展名直链 / 分片"的关键判据——只认 .m3u8 后缀会漏掉这类地址。
     */
    private fun isRangeVideoRequest(url: String, headers: Map<String, String>?): Boolean {
        if (headers == null) return false
        val range = headers.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value ?: return false
        if (!range.startsWith("bytes=")) return false
        val lower = url.lowercase()
        return !(lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".html") ||
            lower.endsWith(".json") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".woff") ||
            lower.endsWith(".woff2") || lower.endsWith(".wasm"))
    }

    /** 当前使用的解析器类型（决定注入脚本） */
    private var injectedLegacyParser = false

    /** 重试计数 */
    var count = 0

    /** 上次播放位置 */
    var offset = 0

    var isIframeLoaded = false
    @Volatile
    var isVideoSourceLoaded = false

    /** 视频源解析完成回调（主线程触发） */
    var onVideoSourceResolved: ((VideoParserEvent) -> Unit)? = null

    /** 日志回调 */
    var onLog: ((String) -> Unit)? = null

    /** 页面加载开始/完成回调 */
    var onPageStarted: ((String) -> Unit)? = null
    var onPageFinished: ((String) -> Unit)? = null

    /** 等待页面加载完成的挂起句柄 */
    private var loadCompletion: CompletableDeferred<Unit>? = null

    /** 等待视频源事件（用于 resolve 挂起等待）。
     *  @Volatile：shouldInterceptRequest 在 WebView IO 线程触发，需保证跨线程可见性 */
    @Volatile
    private var pendingSource: CompletableDeferred<VideoParserEvent>? = null

    /** 每秒轮询句柄（对齐原版 videoParserTimer）：MutationObserver 漏掉的延迟元素由它兜底 */
    private var pollRunnable: Runnable? = null

    /**
     * 初始化隐藏 WebView。
     * 必须在主线程调用。
     */
    suspend fun init() {
        if (webView != null) return

        // 代理配置读取必须在挂起上下文完成（DataStore 读是 suspend 函数）：
        // 不能在主线程 handler 内 runBlocking 等待，否则主线程自锁卡死。
        val (proxyEnabled, proxyUrl) = readProxyConfig()

        webView = suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    setupProxy(proxyEnabled, proxyUrl)
                    val view = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.blockNetworkImage = true
                        settings.loadsImagesAutomatically = false
                        // 对齐原版 video_webview_impl.dart：原版保持 true（不允许自动播放），
                        // 避免隐藏 WebView 自己出声；嗅探靠网络请求，不依赖自动播放
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.userAgentString = HttpHeaders.randomUA()
                        addJavascriptInterface(LogBridge(), "KazumiLogBridge")
                        // 第三方 iframe 播放器常需要 cookie（部分站点校验播放鉴权）
                        android.webkit.CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)
                        // 补齐 WebChromeClient：window.open / console / 弹窗回调在缺省时行为受限，
                        // 部分播放器脚本依赖这些能力；同时把 console 输出接入日志便于排查
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(
                                consoleMessage: android.webkit.ConsoleMessage?
                            ): Boolean {
                                consoleMessage?.let {
                                    onLog?.invoke("console: ${it.message().take(200)}")
                                }
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                onLog?.invoke("started loading: $url")
                                onPageStarted?.invoke(url ?: "")
                                // 关键修复：evaluateJavascript 注入只对"当前文档"有效，
                                // 页面导航后之前注入的脚本全部失效（对应官方 UserScript
                                // injectionTime=AT_DOCUMENT_START：每个文档开始时都要执行）。
                                // 这里在每次导航开始时对"新文档"重新注入。
                                val script = currentScript
                                if (script != null && view != null && url != null && url != "about:blank") {
                                    view.evaluateJavascript(script, null)
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                onLog?.invoke("loading completed: $url")
                                onPageFinished?.invoke(url ?: "")
                                loadCompletion?.complete(Unit)
                                // 兜底再注入一次（脚本内部有 __kazumiXxxHooked 幂等保护，不会重复包装）
                                val script = currentScript
                                if (script != null && view != null && url != null && url != "about:blank") {
                                    view.evaluateJavascript(script, null)
                                }
                            }

                            /**
                             * 原生嗅探兜底：所有 frame（含 iframe）的请求都会经过这里。
                             * 对齐原版 video_webview_impl.dart 的两条判定：
                             * 1) URL 命中媒体扩展名（.m3u8/.mp4...）
                             * 2) 请求头带 Range: bytes=（无扩展名的直链/分片只能靠这条抓到）
                             */
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                // 旧版解析器走 iframe 监听，不做原生嗅探（对齐原版）
                                if (injectedLegacyParser) return null
                                val u = request?.url?.toString() ?: return null
                                if (u.isEmpty() || isVideoSourceLoaded || isAdUrl(u)) return null

                                val headers = try {
                                    request.requestHeaders
                                } catch (e: Exception) {
                                    null
                                }
                                val range = headers?.entries
                                    ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
                                    ?.value

                                // 诊断埋点：记录请求（含 Range），用于定位真实取流地址
                                if (probeCount < 120 &&
                                    !u.startsWith("data:") && !u.startsWith("blob:")
                                ) {
                                    val noQuery = u.substringBefore('?').lowercase()
                                    val isStatic = noQuery.endsWith(".js") || noQuery.endsWith(".css") ||
                                        noQuery.endsWith(".png") || noQuery.endsWith(".jpg") ||
                                        noQuery.endsWith(".jpeg") || noQuery.endsWith(".gif") ||
                                        noQuery.endsWith(".svg") || noQuery.endsWith(".ico") ||
                                        noQuery.endsWith(".woff") || noQuery.endsWith(".woff2") ||
                                        noQuery.endsWith(".ttf")
                                    if (!isStatic) {
                                        probeCount++
                                        val extra = if (range != null) " [Range: ${range.take(40)}]" else ""
                                        onLog?.invoke("net[${probeCount}] ${u.take(220)}$extra")
                                    }
                                }

                                if (isMediaUrl(u) || isRangeVideoRequest(u, headers)) {
                                    onLog?.invoke("Native sniff hit: $u (range=${range ?: "none"})")
                                    isIframeLoaded = true
                                    isVideoSourceLoaded = true
                                    // artplayer 等 HTML 播放器页：query 里的 url= 参数才是真实视频源，
                                    // 不能把 HTML 页本身喂给播放器（会 PARSING_CONTAINER_UNSUPPORTED）
                                    emitSource(VideoParserEvent(extractEmbeddedMediaUrl(u) ?: u, offset))
                                }
                                return null
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean = false
                        }
                    }
                    cont.resume(view)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        }
    }

    /**
     * 加载集数页面并等待视频源解析结果。
     * 必须在主线程调用。
     *
     * @return 解析到的视频源事件；超时抛出 [VideoSourceTimeoutException]
     */
    suspend fun resolveVideoSource(
        url: String,
        useLegacyParser: Boolean,
        offset: Int,
        timeoutMillis: Long
    ): VideoParserEvent {
        val view = webView ?: throw IllegalStateException("WebView not initialized")
        unloadPageInternal(view)

        if (!hasInjectedScripts || injectedLegacyParser != useLegacyParser) {
            // 解析器类型变化时重建 WebView 并重新注入
            if (hasInjectedScripts && injectedLegacyParser != useLegacyParser) {
                disposeInternal()
                webView = null
                return resolveVideoSource(url, useLegacyParser, offset, timeoutMillis)
            }
            injectUserScripts(view, useLegacyParser)
            hasInjectedScripts = true
            injectedLegacyParser = useLegacyParser
        }

        count = 0
        probeCount = 0
        this.offset = offset
        isIframeLoaded = false
        isVideoSourceLoaded = false

        val deferred = CompletableDeferred<VideoParserEvent>()
        pendingSource = deferred

        mainHandler.post {
            view.loadUrl(url)
            // 立即再注入一次：尽量赶在新文档的页面脚本执行前挂上 hook
            // （与 onPageStarted / onPageFinished 的注入互为补充，脚本有幂等保护）
            currentScript?.let { view.evaluateJavascript(it, null) }
            // 对齐原版 videoParserTimer：每秒主动扫描一次 video/iframe
            startPolling(view)
        }

        return try {
            withTimeout(timeoutMillis) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pendingSource = null
            stopPolling()
            throw VideoSourceTimeoutException(timeoutMillis)
        }
    }

    /** 启动每秒轮询（必须在主线程调用） */
    private fun startPolling(view: WebView) {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                if (isVideoSourceLoaded) {
                    stopPolling()
                    return
                }
                view.evaluateJavascript(POLL_SCRIPT, null)
                mainHandler.postDelayed(this, 1000)
            }
        }
        pollRunnable = r
        mainHandler.postDelayed(r, 1200)
    }

    private fun stopPolling() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    /** 卸载当前页面（about:blank） */
    suspend fun unloadPage() {
        val view = webView ?: return
        unloadPageInternal(view)
    }

    private fun unloadPageInternal(view: WebView) {
        view.loadUrl("about:blank")
    }

    /** 释放 WebView 资源 */
    suspend fun dispose() {
        disposeInternal()
    }

    private fun disposeInternal() {
        stopPolling()
        val view = webView ?: return
        mainHandler.post {
            view.removeJavascriptInterface("KazumiLogBridge")
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
        webView = null
        hasInjectedScripts = false
    }

    // ---------- 每秒轮询脚本（对齐原版 _pollVideoSource）----------

    private val POLL_SCRIPT = """
    (function() {
      try {
        var videos = document.querySelectorAll('video');
        if (videos.length > 0) {
          KazumiLogBridge.log('Timer scan: found ' + videos.length + ' video element(s)');
        }
        for (var i = 0; i < videos.length; i++) {
          var v = videos[i];
          try { v.muted = true; } catch (e) {}
          var src = v.getAttribute('src');
          if (src && src.trim() !== '' && src.indexOf('blob:') !== 0 && src.indexOf('googleads') === -1) {
            KazumiLogBridge.onVideoSource(src);
            return;
          }
          var ss = v.getElementsByTagName('source');
          for (var j = 0; j < ss.length; j++) {
            var s2 = ss[j].getAttribute('src');
            if (s2 && s2.trim() !== '' && s2.indexOf('blob:') !== 0 && s2.indexOf('googleads') === -1) {
              KazumiLogBridge.onVideoSource(s2);
              return;
            }
          }
        }
        var iframes = document.querySelectorAll('iframe');
        for (var k = 0; k < iframes.length; k++) {
          var fs = iframes[k].getAttribute('src');
          if (fs) KazumiLogBridge.log('Timer scan iframe: ' + fs.substring(0, 160));
        }
      } catch (e) {}
    })();
    """.trimIndent()

    // ---------- JS 桥 ----------

    /** JS 日志桥：页面脚本通过 KazumiLogBridge.log 上报日志 */
    inner class LogBridge {
        @JavascriptInterface
        fun log(message: String) {
            // 不过滤 about:blank：注入脚本在 about:blank 上首次执行时上报的
            // "xxx script loaded" 是判断注入是否生效的关键证据
            onLog?.invoke(message)
        }

        /** 旧版解析器回传：iframe src（可能含编码后的视频地址） */
        @JavascriptInterface
        fun onLegacySource(raw: String) {
            onLog?.invoke("Callback received: $raw")
            onLog?.invoke("If there is audio but no video, please report it to the rule developer.")
            if ((raw.contains("http") || raw.startsWith("//")) &&
                !raw.contains("googleads") &&
                !raw.contains("googlesyndication.com") &&
                !raw.contains("prestrain.html") &&
                !raw.contains("prestrain%2Ehtml") &&
                !raw.contains("adtrafficquality")
            ) {
                onLog?.invoke("Parsing video source $raw")
                val encodedUrl = MediaUtils.decodeVideoSource(raw)
                if (encodedUrl != raw) {
                    isIframeLoaded = true
                    isVideoSourceLoaded = true
                    onLog?.invoke("Loading video source $encodedUrl")
                    unloadPageInternal(webView ?: return)
                    emitSource(VideoParserEvent(encodedUrl, offset))
                }
            }
        }

        /** 新版解析器回传：直接命中 m3u8 地址 */
        @JavascriptInterface
        fun onVideoSource(raw: String) {
            onLog?.invoke("Callback received: $raw")
            if (raw.contains("http") && !isVideoSourceLoaded) {
                onLog?.invoke("Loading video source: $raw")
                isIframeLoaded = true
                isVideoSourceLoaded = true
                unloadPageInternal(webView ?: return)
                emitSource(VideoParserEvent(raw, offset))
            }
        }
    }

    private fun emitSource(event: VideoParserEvent) {
        pendingSource?.complete(event)
        pendingSource = null
        stopPolling()
        // 对齐原版：命中后立即卸载页面，避免隐藏 WebView继续播放/消耗流量
        val view = webView
        if (view != null) {
            mainHandler.post {
                try {
                    unloadPageInternal(view)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    }

    /** 取消当前等待中的解析：以 [VideoSourceCancelledException] 完成 pendingSource */
    fun cancel() {
        stopPolling()
        pendingSource?.completeExceptionally(VideoSourceCancelledException())
        pendingSource = null
    }

    // ---------- 注入脚本 ----------

    /**
     * 注入解析脚本。
     * 必须在主线程调用。
     *
     * 注入的脚本通过 window.KazumiLogBridge 与原生侧通信：
     * - KazumiLogBridge.log(msg)   -> 日志
     * - KazumiLogBridge.onLegacySource(src) -> 旧版解析器 iframe 命中
     * - KazumiLogBridge.onVideoSource(url)  -> 新版解析器视频源命中
     */
    private fun injectUserScripts(view: WebView, useLegacyParser: Boolean) {
        val scripts = StringBuilder()
        if (useLegacyParser) {
            onLog?.invoke("Adding JSBridgeDebug UserScript")
            scripts.append(
                """
                (function() {
                  if (window.__kazumiIframeHooked) return;
                  window.__kazumiIframeHooked = true;
                  KazumiLogBridge.log('JSBridgeDebug script loaded: ' + window.location.href);
                  function processIframeElement(iframe) {
                    KazumiLogBridge.log('Processing iframe element');
                    var src = iframe.getAttribute('src');
                    if (src) {
                      KazumiLogBridge.onLegacySource(src);
                    }
                  }
                  var observer = new MutationObserver(function(mutations) {
                    KazumiLogBridge.log('Scanning for iframes...');
                    mutations.forEach(function(mutation) {
                      if (mutation.type === 'attributes' && mutation.target.nodeName === 'IFRAME') {
                        processIframeElement(mutation.target);
                      } else {
                        mutation.addedNodes.forEach(function(node) {
                          if (node.nodeName === 'IFRAME') processIframeElement(node);
                          if (node.querySelectorAll) {
                            node.querySelectorAll('iframe').forEach(processIframeElement);
                          }
                        });
                      }
                    });
                  });
                  observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['src']
                  });
                })();
                """.trimIndent()
            )
        } else {
            onLog?.invoke("Adding VideoBridgeDebug UserScripts")
            scripts.append(
                """
                (function() {
                  if (window.__kazumiBlobHooked) return;
                  window.__kazumiBlobHooked = true;
                  KazumiLogBridge.log('BlobParser script loaded: ' + window.location.href);
                  var _r_text = window.Response.prototype.text;
                  window.Response.prototype.text = function () {
                    // 保存 Response 实例引用：原实现用普通 function，回调里的 this 会丢失，
                    // 导致 this.url 取不到真实地址（对齐官方箭头函数语义）
                    var self = this;
                    return new Promise(function(resolve, reject) {
                      _r_text.call(self).then(function(text) {
                        resolve(text);
                        if (text.trim().indexOf('#EXTM3U') === 0) {
                          KazumiLogBridge.log('M3U8 source found: ' + self.url);
                          KazumiLogBridge.onVideoSource(self.url);
                        }
                      }).catch(reject);
                    });
                  };
                  var _open = window.XMLHttpRequest.prototype.open;
                  window.XMLHttpRequest.prototype.open = function() {
                    var args = arguments;
                    var xhr = this;
                    xhr.addEventListener('load', function() {
                      try {
                        // 部分解析站（含 WASM 解密）用 responseType='arraybuffer'，
                        // 此时访问 responseText 会抛异常，需分支处理
                        if (xhr.responseType === 'arraybuffer' && xhr.response) {
                          var buf = xhr.response;
                          var n = Math.min(buf.byteLength, 64);
                          var bytes = new Uint8Array(buf, 0, n);
                          var head = '';
                          for (var i = 0; i < n; i++) head += String.fromCharCode(bytes[i]);
                          if (head.indexOf('#EXTM3U') === 0) {
                            KazumiLogBridge.log('M3U8 source found (arraybuffer): ' + args[1]);
                            KazumiLogBridge.onVideoSource(args[1]);
                          }
                        } else {
                          var content = xhr.responseText;
                          if (content.trim().indexOf('#EXTM3U') === 0) {
                            KazumiLogBridge.log('M3U8 source found: ' + args[1]);
                            KazumiLogBridge.onVideoSource(args[1]);
                          }
                        }
                      } catch (e) {}
                    });
                    return _open.apply(this, args);
                  };
                })();
                """.trimIndent()
            )
            scripts.append(
                """
                (function() {
                  if (window.__kazumiVideoTagHooked) return;
                  window.__kazumiVideoTagHooked = true;
                  KazumiLogBridge.log('VideoTagParser script loaded: ' + window.location.href);
                  function processVideoElement(video) {
                    KazumiLogBridge.log('Scanning video element for source URL');
                    // 隐藏 WebView 会自动播放（mediaPlaybackRequiresUserGesture=false），
                    // 必须静音，否则用户会听到来源不明的音频
                    try { video.muted = true; } catch (e) {}
                    var src = video.getAttribute('src');
                    // blob: 说明走 MSE（分片由 JS 喂入），URL 无法直接交给播放器，
                    // 记录以便区分"没抓到"和"抓到但是 blob"两种情况
                    if (src && src.indexOf('blob:') === 0) {
                      KazumiLogBridge.log('video src is blob (MSE): ' + src.substring(0, 48));
                    } else if (video.currentSrc && video.currentSrc.indexOf('blob:') === 0) {
                      KazumiLogBridge.log('video currentSrc is blob (MSE): ' + video.currentSrc.substring(0, 48));
                    }
                    if (src && src.trim() !== '' && src.indexOf('blob:') !== 0 && src.indexOf('googleads') === -1) {
                      observer.disconnect();
                      KazumiLogBridge.log('VIDEO source found: ' + src);
                      KazumiLogBridge.onVideoSource(src);
                      return true;
                    }
                    var sources = video.getElementsByTagName('source');
                    for (var i = 0; i < sources.length; i++) {
                      src = sources[i].getAttribute('src');
                      if (src && src.trim() !== '' && src.indexOf('blob:') !== 0 && src.indexOf('googleads') === -1) {
                        observer.disconnect();
                        KazumiLogBridge.log('VIDEO source found (source tag): ' + src);
                        KazumiLogBridge.onVideoSource(src);
                        return true;
                      }
                    }
                    return false;
                  }
                  var observer = new MutationObserver(function(mutations) {
                    KazumiLogBridge.log('Scanning for video elements...');
                    for (var i = 0; i < mutations.length; i++) {
                      var mutation = mutations[i];
                      if (mutation.type === 'attributes' && mutation.target.nodeName === 'VIDEO') {
                        if (processVideoElement(mutation.target)) return;
                        continue;
                      }
                      for (var j = 0; j < mutation.addedNodes.length; j++) {
                        var node = mutation.addedNodes[j];
                        if (node.nodeName === 'VIDEO') {
                          if (processVideoElement(node)) return;
                        }
                        if (node.querySelectorAll) {
                          var videos = node.querySelectorAll('video');
                          for (var k = 0; k < videos.length; k++) {
                            if (processVideoElement(videos[k])) return;
                          }
                        }
                      }
                    }
                  });
                  function setupVideoProcessing() {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                      if (processVideoElement(videos[i])) return;
                    }
                    observer.observe(document.body, {
                      childList: true,
                      subtree: true,
                      attributes: true,
                      attributeFilter: ['src']
                    });
                  }
                  if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', setupVideoProcessing);
                  } else {
                    setupVideoProcessing();
                  }
                })();
                """.trimIndent()
            )
            // 追加：fetch / Response.arrayBuffer 通路。
            // 部分"解析站"用 WASM 解密并直接喂 MSE，地址不带 .m3u8，
            // 且不经 XMLHttpRequest/Response.text，只有这两条通路能看到。
            scripts.append(
                """
                (function() {
                  if (window.__kazumiFetchHooked) return;
                  window.__kazumiFetchHooked = true;
                  KazumiLogBridge.log('FetchParser script loaded: ' + window.location.href);
                  var _fetch = window.fetch;
                  if (_fetch) {
                    window.fetch = function(input, init) {
                      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
                      return _fetch.apply(this, arguments).then(function(resp) {
                        try {
                          if (resp && resp.ok && !window.__kazumiFetchHit) {
                            var ct = (resp.headers.get('content-type') || '').toLowerCase();
                            var maybeM3u8 = ct.indexOf('mpegurl') >= 0 || ct.indexOf('octet-stream') >= 0 ||
                              ct.indexOf('text/plain') >= 0 || ct.indexOf('json') >= 0 ||
                              url.indexOf('.m3u8') >= 0;
                            if (maybeM3u8) {
                              resp.clone().text().then(function(t) {
                                if (t && t.indexOf('#EXTM3U') === 0) {
                                  window.__kazumiFetchHit = true;
                                  KazumiLogBridge.log('M3U8(fetch) found: ' + url);
                                  KazumiLogBridge.onVideoSource(url);
                                }
                              }).catch(function() {});
                            }
                          }
                        } catch (e) {}
                        return resp;
                      });
                    };
                  }
                  var _ab = window.Response.prototype.arrayBuffer;
                  if (_ab) {
                    window.Response.prototype.arrayBuffer = function() {
                      var self = this;
                      return _ab.call(self).then(function(buf) {
                        try {
                          var n = Math.min(buf.byteLength, 64);
                          if (n > 6 && !window.__kazumiFetchHit) {
                            var bytes = new Uint8Array(buf, 0, n);
                            var head = '';
                            for (var i = 0; i < n; i++) head += String.fromCharCode(bytes[i]);
                            if (head.indexOf('#EXTM3U') === 0) {
                              window.__kazumiFetchHit = true;
                              KazumiLogBridge.log('M3U8(arrayBuffer) found: ' + self.url);
                              KazumiLogBridge.onVideoSource(self.url);
                            }
                          }
                        } catch (e) {}
                        return buf;
                      });
                    };
                  }
                })();
                """.trimIndent()
            )
        }

        // 注意：evaluateJavascript 只接受纯 JS 源码，不能加 "javascript:" 前缀
        // （那是 loadUrl 的写法；加到 evaluateJavascript 里会被当作表达式解析，
        //  直接抛 SyntaxError，整段脚本完全不执行——此前解析全部失败的根因之一）
        val full = scripts.toString()
        // 保存脚本：onPageStarted/onPageFinished 会在每次页面导航后重新注入
        // （evaluateJavascript 的作用域仅限当前文档，导航后旧注入全部失效）
        currentScript = full
        onLog?.invoke("scripts injected, len=${full.length}")
        view.evaluateJavascript(full, null)
    }

    // ---------- 代理 ----------

    /**
     * 挂起读取代理配置（DataStore 读是 suspend，必须在挂起上下文完成，
     * 不能在主线程 runBlocking——否则阻塞主线程导致自锁卡死）。
     */
    private suspend fun readProxyConfig(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            // 复用 KazumiApp 的全局 settings 单例，不能在这里 new SettingsManager：
            // 那会创建第二个 DataStore 实例，两个实例并发读写同一个 preferences 文件
            // 会抛 "There are multiple DataStores active for the same file"
            val app = com.kazumi.port.KazumiApp.get()
            val settings = app.settings
            val enabled = settings.getBoolean(
                com.kazumi.port.data.repository.SettingsManager.Proxy.ENABLE_SYSTEM_PROXY,
                false
            ) || settings.getBoolean(
                com.kazumi.port.data.repository.SettingsManager.Proxy.PROXY_ENABLE,
                false
            )
            val proxyUrl = settings.getString(
                com.kazumi.port.data.repository.SettingsManager.Proxy.PROXY_URL,
                ""
            )
            enabled to proxyUrl
        } catch (e: Exception) {
            com.kazumi.port.util.FileLogger.w("WebViewResolve", "read proxy config failed: ${e.message}")
            false to ""
        }
    }

    /**
     * 根据设置注入 WebView 代理（Android 10+ 通过 WebViewProxyController 覆盖代理）。
     * 对应原版 video_webview_android_impl.dart 的 setupProxy。
     * 参数在主线程外预读取，本方法只做内存操作，可在主线程安全执行。
     */
    private fun setupProxy(enabled: Boolean, proxyUrl: String) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val formatted = com.kazumi.port.util.ProxyUtils.getFormattedProxyUrl(proxyUrl)
                if (enabled && formatted != null) {
                    // 通过系统属性覆盖 WebView 代理（Android 10+ 的 WebView 遵循系统代理设置）
                    System.setProperty("http.proxyHost", formatted.substringAfter("://").substringBefore(":"))
                    System.setProperty("http.proxyPort", formatted.substringAfter(":"))
                    onLog?.invoke("WebView proxy set to $formatted")
                } else {
                    onLog?.invoke("WebView proxy skipped (disabled or empty)")
                }
            } else {
                onLog?.invoke("WebView proxy setup skipped (below Android 10)")
            }
        } catch (e: Exception) {
            onLog?.invoke("WebView proxy setup failed: ${e.message}")
        }
    }
}