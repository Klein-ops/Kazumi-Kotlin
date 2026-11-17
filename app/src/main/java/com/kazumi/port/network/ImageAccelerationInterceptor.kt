package com.kazumi.port.network

import android.content.Context
import android.util.Log
import coil.imageLoader
import coil.intercept.Interceptor
import coil.intercept.Interceptor.Chain
import coil.request.ImageResult
import coil.request.SuccessResult
import com.kazumi.port.util.FileLogger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

/**
 * Coil 图片加速拦截器（Coil 2.x）
 *
 * 对应原版 ImageFileService + ProxyAwareImageCacheManager 的加速策略：
 * - direct：直连原始图片 URL
 * - ech：走 libech_http（ECH 加密握手）原生下载，先拉 ECHConfig 再用写死 CF IP 下载；
 *   失败直接抛错（对齐原版，不拿镜像轮询）
 * - mirror：将 Bangumi 图片重写为 wsrv.nl 等镜像后加载（原版 BangumiImageUrlRewriter）
 *
 * 性能设计（对齐原版"先查盘、miss 才下载"思路）：
 * - 【自管磁盘缓存】ech_cache/ 目录：ECH 下载结果由本拦截器主动落盘（Coil 2.x 公共
 *   DiskCache 接口没有写入 API，EngineInterceptor 只对"网络源"写盘，拦截器塞字节
 *   给它永远不会落盘 → 冷启动必然全量重下）。这是第 2 次打开 App 图片全挂的根因修复。
 * - EchResolver 缓存 ECHConfig（60s 内不重复 DoH）
 * - 进程内内存缓存上限 64 张（防 OOM），仅用于同一进程列表滚动/详情大图秒出
 * - native JNI 是全局状态必须串行，但缓存命中后不触碰 native，吞吐不受串行拖累
 */
class ImageAccelerationInterceptor(
    private val acceleration: () -> ImageAcceleration,
) : Interceptor {

    /** ECH 下载结果内存缓存：URL -> bytes。上限 64 张（防 OOM），按访问序淘汰最旧 */
    private val echCache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > 64
    }
    private val echCacheLock = Any()

    /** 自管磁盘缓存目录：全部 ECH 下载结果真正落盘在这里（cacheDir/ech_cache） */
    private fun echDir(context: Context): File =
        File(context.cacheDir, "ech_cache").apply { mkdirs() }

    /**
     * 进行中 ECH 下载去重（2026-11-16 新增，修复首屏同一张图并发下载 N 次）：
     * 详情页背景与封面小图会同时请求同一 URL，拦截器里两路都 miss 缓存后各自
     * 走全套 ECH 下载 + 镜像竞速（日志实测同一 URL 并发 2 路、各等 7s 才出一路）。
     * 这里把"同 URL 的下载"收敛为一个共享 Deferred：第一个请求发起下载，
     * 后续请求 await 同一个结果，下载完成后从表移除（结果已进 echCache 内存缓存，
     * 后续请求直接命中缓存，不再重复下载）。
     */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, Deferred<ByteArray?>>()

    /** 获取 ECH 字节：有缓存用缓存；无缓存则共享一次下载（并发去重）。
     *  @param url 缓存/去重 key（始终用列表页传入的原始 URL，保证详情页/列表共用同一缓存）
     *  @param downloadUrl 实际下载地址（api.bgm.tv 动态端点时是解析出的 lain.bgm.tv 静态 URL）
     */
    private suspend fun obtainEchBytes(context: Context, url: String, downloadUrl: String = url): ByteArray? {
        // 1) 自管磁盘缓存（落盘命中，进程冷启也能秒回）
        readEchFile(context, url)?.let { return it }
        // 2) 进程内内存缓存
        cacheGet(url)?.let { return it }
        // 3) 无缓存：走共享下载（并发同 URL 只下载一次）
        val existing = inflight[url]
        if (existing != null) {
            FileLogger.d("ImageAccel", "inflight dedup HIT url=$url")
            return existing.await()
        }
        val deferred = downloadScope.async(start = CoroutineStart.LAZY) {
            try {
                val bytes = downloadEchWithRetry(downloadUrl, context)
                if (bytes != null && bytes.isNotEmpty()) {
                    FileLogger.i("ImageAccel", "ech download OK bytes=${bytes.size} for $url")
                    cachePut(url, bytes)
                    writeEchFile(context, url, bytes)
                } else {
                    FileLogger.w("ImageAccel", "ech download empty/failed for $url")
                }
                bytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w("ImageAccel", "ech download error: ${e.message} | $url")
                null
            }
        }
        val winner = inflight.putIfAbsent(url, deferred) ?: deferred.also {
            FileLogger.d("ImageAccel", "dedup: another download already inflight url=$url")
        }
        // start() 幂等：重复调用只生效一次；无论 winner 是谁都启动（若是已有任务则无副作用）
        winner.start()
        return try {
            winner.await()
        } finally {
            inflight.remove(url, winner)
        }
    }

    /** 关闭重定向的 OkHttp 客户端：用于解析 api.bgmapi.com 镜像的 302 Location */
    private val noRedirectClient: okhttp3.OkHttpClient by lazy {
        com.kazumi.port.network.HttpClientFactory.getClient().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 解析 api.bgm.tv 动态图片端点（/v0/subjects/{id}/image）的实际静态图 URL：
     * 请求 bgmapi.com 镜像同路径（实测 302 → lain.bgm.tv/pic/cover/...），
     * 拿 Location 后用 ECH 下载静态图——astute：当前网络 api.bgm.tv 整域不可达
     * （ECH/直连/镜像全失败），但 lain.bgm.tv 静态图 ECH 秒出。这是搜索页封面
     * 加载慢的根治（日志 09:42~09:44 一批 api.bgm.tv 动态端点 ECH 全挂）。
     */
    private suspend fun resolveApiImageStaticUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val mirror = BangumiImageUrlRewriter.apiImageMirrorUrl(url)
            val req = Request.Builder().url(mirror).get().build()
            noRedirectClient.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                if (loc != null && BangumiImageUrlRewriter.isBangumiImage(loc)) {
                    FileLogger.i("ImageAccel", "api 动态端点解析静态: $loc | $url")
                    loc
                } else {
                    FileLogger.d("ImageAccel", "api 动态端点无有效 Location code=${resp.code} | $url")
                    null
                }
            }
        } catch (e: Exception) {
            FileLogger.w("ImageAccel", "resolve api image static failed: ${e.message} | $url")
            null
        }
    }

    /**
     * ECH 下载（带重试）：单次 10s 超时（旧值 25s，config 过期后每张图白等 25s，
     * 列表 20+ 张连环拖慢，实测 06:56:06 起 44 张全部 25.0s 超时失败）；
     * 失败后强制刷新 ECHConfig 重试一次（Cloudflare config TTL 仅 1~5s，旧 config
     * 复用必失败，刷新后第二次握手往往直接成功——这是"只有前 2-3 张能加载"的根因修复）。
     * 仍失败返回 null（调用方将走镜像/直连兜底，保证图能出来）。
     */
    private suspend fun downloadEchWithRetry(url: String, context: Context): ByteArray? {
        // 第一次：用当前（可能刚缓存的）config 下载
        var bytes = withTimeoutOrNull(10_000L) {
            val echConfig = EchResolver.fetchEchConfig()
            if (echConfig != null) EchHttpNative.loadSuspend(url, echConfig) else null
        }
        if (bytes != null && bytes.isNotEmpty()) return bytes
        // 失败：config 可能已过期，强制刷新重试一次
        FileLogger.w("ImageAccel", "ech 首次下载失败（config 可能过期），强制刷新重新握手 | $url")
        EchResolver.invalidate()
        bytes = withTimeoutOrNull(10_000L) {
            val echConfig = EchResolver.fetchEchConfig()
            if (echConfig != null) EchHttpNative.loadSuspend(url, echConfig) else null
        }
        if (bytes != null && bytes.isNotEmpty()) {
            FileLogger.i("ImageAccel", "ech 刷新 config 后重试成功 | $url")
            return bytes
        }
        return null
    }

    /** 以 URL 的 SHA-1 作为文件名，避免非法字符且天然去重 */
    private fun echKey(url: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 读自管磁盘缓存：命中返回字节，否则 null（文件损坏/不存在都按 miss 处理） */
    private fun readEchFile(context: Context, url: String): ByteArray? = try {
        val f = File(echDir(context), echKey(url))
        if (f.isFile && f.length() > 0) f.readBytes() else null
    } catch (e: Exception) {
        null
    }

    /** 写自管磁盘缓存：下载成功后立即落盘；附带简单 LRU 清理（超过 1024 张删最旧） */
    private fun writeEchFile(context: Context, url: String, bytes: ByteArray) {
        try {
            val dir = echDir(context)
            val f = File(dir, echKey(url))
            synchronized(echCacheLock) { f.writeBytes(bytes) }
            val files = dir.listFiles() ?: return
            if (files.size > 1024) {
                files.sortedBy { it.lastModified() }
                    .take(files.size - 1024)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            // 落盘失败不阻塞主流程（本次靠内存缓存兜底，下次重下）
        }
    }

    private fun cacheGet(url: String): ByteArray? = synchronized(echCacheLock) { echCache[url] }

    private fun cachePut(url: String, bytes: ByteArray) {
        synchronized(echCacheLock) { echCache[url] = bytes }
    }

    /**
     * 把字节作为 data 交给 Coil 解码链路。
     *
     * 缓存 key 设计（2026-11-16 修复封面被模糊图污染的根因）：
     * - Coil 默认 memoryCacheKey 会纳入 request.transformations 的 cacheKey 与 size，
     *   但 ECH 拦截器把字节塞进 data 后，Coil 引擎的默认 key 基于 bytes 计算不稳定，
     *   因此这里显式构造【复合 key】。关键：memoryCacheKey 必须带上变换维度——
     *   Coil 的 MemoryCacheService.newCacheKey 一旦命中显式 memoryCacheKey 就直接返回、
     *   跳过 transformations 维度。若钉死为原始 URL，详情页背景（URL+RenderScriptBlur）
     *   与封面小图（同一 URL 无变换）就会共用同一内存缓存条目，互相污染：
     *   先进详情页 → 封面拿到模糊位图；先进列表 → 背景拿到清晰位图（模糊依赖叠加层）。
     *   复合 key 语义与 Coil 默认计算一致：URL + 变换 cacheKey。
     * - diskCacheKey 保留原始 URL：磁盘缓存存的是解码前的字节，与变换无关，天然可复用。
     */
    private fun byteRequest(chain: Chain, original: String, bytes: ByteArray) =
        chain.request.newBuilder()
            .data(bytes)
            .memoryCacheKey(
                buildString {
                    append(original)
                    chain.request.transformations.forEach { t -> append('|').append(t.cacheKey) }
                }
            )
            .diskCacheKey(original)
            .build()

    override suspend fun intercept(chain: Chain): ImageResult {
        val mode = acceleration()
        val original = chain.request.data.toString()
        FileLogger.i("ImageAccel", "intercept mode=$mode url=$original")

        // 非 Bangumi 图片：直接走默认链路
        if (!BangumiImageUrlRewriter.isBangumiImage(original)) {
            FileLogger.d("ImageAccel", "non-bangumi, pass through")
            return chain.proceed(chain.request)
        }

        // direct：直连
        if (mode == ImageAcceleration.DIRECT) {
            FileLogger.d("ImageAccel", "direct mode, proceed")
            return chain.proceed(chain.request)
        }

        // ECH：统一走共享下载（自管磁盘缓存 → 内存缓存 → 并发去重下载），
        // 失败后镜像兜底，最后直连兜底（对齐原版 ImageFileService 语义 + 容错）。
        if (mode == ImageAcceleration.ECH) {
            try {
                val context = chain.request.context

                // 0) Coil 官方磁盘缓存（历史兼容；自定义拦截器塞字节 Coil 不写盘，
                //    此分支绝大多数情况 miss，保留只为兼容极少数的直连/镜像写入）
                val diskCache = chain.request.context.imageLoader.diskCache
                val diskHit = diskCache?.get(original)
                if (diskHit != null) {
                    diskHit.use { snapshot ->
                        // Coil 2.x DiskCache.Snapshot.data 是 okio.Path（非 java.io.File），
                        // 没有 readBytes() 扩展；用 toString() 拿到路径串再构造 File 读取。
                        val bytes = java.io.File(snapshot.data.toString()).readBytes()
                        if (bytes.isNotEmpty()) {
                            Log.i("ImageAccel", "disk cache HIT url=$original len=${bytes.size}")
                            return chain.proceed(byteRequest(chain, original, bytes))
                        }
                    }
                }

                // 1) ECH 共享下载（obtainEchBytes 内部：自管磁盘缓存 → 内存缓存 → 并发去重下载）
                //    对 api.bgm.tv 动态图片端点（/v0/subjects/{id}/image）先经 bgmapi.com 镜像
                //    解析出 lain.bgm.tv 静态 URL 再下载——当前网络 api.bgm.tv 整域不可达，
                //    直接 ECH 必失败（日志里搜索页封面整批 20s 超时的根因），静态图 ECH 秒出。
                val echDownloadUrl = if (BangumiImageUrlRewriter.isApiImageUrl(original)) {
                    resolveApiImageStaticUrl(original) ?: original
                } else {
                    original
                }
                val bytes = obtainEchBytes(context, original, echDownloadUrl)
                if (bytes != null) {
                    return chain.proceed(byteRequest(chain, original, bytes))
                }

                // 2) ECH 失败 → 镜像兜底
                FileLogger.w("ImageAccel", "ech 下载失败，尝试镜像兜底 | $original")
                val mirrorResult = tryMirrorFallback(chain, original)
                if (mirrorResult != null) return mirrorResult

                // 3) 镜像也失败 → 最后直连原始地址（可能站内可达）
                //    修复 11-15 实测：corsproxy 已要求 API key（返回营销页 HTML 非图片）、
                //    wsrv/weserv 在本网络连接失败 —— 镜像通道近乎全废；此时若 ECH 也失败
                //    （如 2.4MB 大图 ECH native 下载失败），不直连兜底就会让该图永久加载
                //    失败（"只有赛博朋克边缘行者 2 这张图死活加载不出"的根因）。
                FileLogger.w("ImageAccel", "ECH与镜像均失败，尝试直连 | $original")
                val direct = withTimeoutOrNull(8_000L) { chain.proceed(chain.request) }
                if (direct is SuccessResult) return direct
                FileLogger.w("ImageAccel", "直连 non-success: ${direct?.javaClass?.simpleName} | $original")
                throw IllegalStateException("ECH、镜像与直连均失败: $original")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败抛出（镜像已兜底仍失败），由 Coil 上层显示占位/重试
                FileLogger.w("ImageAccel", "ech download failed: ${e.message} | $original")
                throw e
            }
        }

        // mirror 模式：走镜像候选，首个成功即返回（对齐原版 ImageFileService 的 mirror 重写）
        val mirrorResult = tryMirrorFallback(chain, original)
        if (mirrorResult != null) return mirrorResult
        // 全部候选失败：最后再尝试一次原始地址（可能站内可达）
        FileLogger.w("ImageAccel", "all mirror candidates failed, try original")
        return try {
            chain.proceed(chain.request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        }
    }

    /**
     * 按序尝试镜像候选（corsproxy/wsrv/weserv），任一成功即返回；全部失败返回 null。
     * 每个候选带 6s 超时（实测 corsproxy 可用但慢 ~8s，wsrv/weserv 快速失败），
     * 避免单个镜像卡死拖累整页。
     */
    private suspend fun tryMirrorFallback(chain: Chain, original: String): ImageResult? {
        val candidates = BangumiImageUrlRewriter.rewriteCandidates(original)
        FileLogger.d("ImageAccel", "mirror candidates: $candidates")
        var lastError: Exception? = null
        // 先试代理候选（原始地址放最后，仅当全部镜像失败时兜底直连——因为候选首项
        // 恒为原始地址，若其直接可达就不会走到这里；走到这里说明镜像更重要）。
        val ordered = candidates.filter { it != original } + original
        for (candidate in ordered) {
            try {
                val result = withTimeoutOrNull(6_000L) {
                    chain.proceed(chain.request.newBuilder().data(candidate).build())
                }
                if (result is SuccessResult) {
                    FileLogger.i("ImageAccel", "mirror OK via $candidate")
                    return result
                }
                FileLogger.d("ImageAccel", "mirror candidate $candidate non-success: ${result?.javaClass?.simpleName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w("ImageAccel", "mirror candidate $candidate failed: ${e.message}")
                lastError = e
            }
        }
        return null
    }
}