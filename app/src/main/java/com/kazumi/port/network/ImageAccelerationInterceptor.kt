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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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

        // ECH：先用原生 ECH 栈下载，成功直接解码返回；失败/超时降级镜像兜底。
        // 对齐原版语义（ImageFileService ECH 失败 rethrow）但增加容错：
        // 原版 ech_http 库在正常网络下稳定，因此失败即抛；移植版 JNI 桥在部分
        // 网络下间歇性超时（2026-11-14 实测第1遍通第2遍全超时），若裸抛会导致
        // 整页图片全挂。因此 ECH 失败后按序尝试镜像（corsproxy 实测可达），
        // 镜像也全失败才抛错。
        if (mode == ImageAcceleration.ECH) {
            try {
                val context = chain.request.context

                // 0) 自管 ECH 磁盘缓存优先（真正落盘，第 2 次冷启动免全量重下）：
                //    Coil 2.x 公共 DiskCache 无写入 API，ECH 下载结果必须由本拦截器
                //    自己写 cacheDir/ech_cache/，否则进程一杀全丢 → 第 2 遍全挂。
                val fileBytes = readEchFile(context, original)
                if (fileBytes != null) {
                    Log.i("ImageAccel", "ech file cache HIT url=$original len=${fileBytes.size}")
                    return chain.proceed(byteRequest(chain, original, fileBytes))
                }

                // 1) Coil 官方磁盘缓存（历史兼容；自定义拦截器塞字节 Coil 不写盘，
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

                // 2) 进程内 ECH 内存缓存命中：同一 URL 直接复用（列表滚动/详情页大图秒出，不碰 native）
                val cached = cacheGet(original)
                if (cached != null) {
                    FileLogger.d("ImageAccel", "ech memory cache HIT url=$original len=${cached.size}")
                    return chain.proceed(byteRequest(chain, original, cached))
                }

                // 3+4) ECH 下载与镜像兜底【并发竞速】：谁先成功用谁（对齐 BangumiClient
                //    mirror/direct race 思路）。原串行链路 ECH 单图最坏 15s 失败后才轮到
                //    镜像再 6s×N，冷启动无缓存时首屏十几张图被整体拖死（用户反馈"列表先出、
                //    图片很久才出、有缓存秒出"）。竞速后单图最坏约 8~12s，且两通道同时进行。
                return raceEchMirror(chain, original, context)
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
     * ECH 下载与镜像兜底并发竞速：两通道同时发起，谁先成功用谁。
     * - ECH 通道：25s 超时（对齐原版 EchTransport.timeout=30s 意图）。
     *   首页个别大图封面（如 2.4MB）走 ECH+CF 前端 IP 需要 10~30s，8s 短超时
     *   会提前放弃导致"个别图永远加载不出"（11-15 实测 513878 封面 2.4MB，
     *   8s 超时必挂、原版 30s 能出）。成功则写内存/磁盘缓存。
     * - 镜像通道：原 tryMirrorFallback（每候选 6s）。
     * 全部失败时抛异常（由 Coil 显示占位/重试）。
     */
    private suspend fun raceEchMirror(chain: Chain, original: String, context: Context): ImageResult =
        coroutineScope {
            val echDeferred = async(Dispatchers.IO) {
                try {
                    val bytes = withTimeoutOrNull(25_000L) {
                        val echConfig = EchResolver.fetchEchConfig()
                        if (echConfig != null) {
                            EchHttpNative.loadSuspend(original, echConfig)
                        } else {
                            null
                        }
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        FileLogger.i("ImageAccel", "ech download OK bytes=${bytes.size} for $original")
                        cachePut(original, bytes)
                        writeEchFile(context, original, bytes)
                        chain.proceed(byteRequest(chain, original, bytes))
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w("ImageAccel", "ech download error: ${e.message} | $original")
                    null
                }
            }
            val mirrorDeferred = async(Dispatchers.IO) {
                try {
                    tryMirrorFallback(chain, original)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w("ImageAccel", "mirror error: ${e.message} | $original")
                    null
                }
            }
            // 轮询等待，任一成功即返回；两路都结束时才抛错
            var lastError: Exception? = null
            try {
                while (true) {
                    val echDone = echDeferred.isCompleted
                    val mirrorDone = mirrorDeferred.isCompleted
                    if (echDone) {
                        val r = echDeferred.await()
                        if (r != null) return@coroutineScope r
                    }
                    if (mirrorDone) {
                        val r = mirrorDeferred.await()
                        if (r != null) return@coroutineScope r
                    }
                    if (echDone && mirrorDone) break
                    delay(5)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            } finally {
                echDeferred.cancel()
                mirrorDeferred.cancel()
            }

            // 4) ECH + 镜像全部失败：最后尝试直连原始地址（可能站内可达）。
            //    修复 11-15 实测：corsproxy 已要求 API key（返回营销页 HTML 非图片）、
            //    wsrv/weserv 在本网络连接失败 —— 镜像通道近乎全废；此时若 ECH 也失败
            //    （如 2.4MB 大图 ECH native 下载失败），不直连兜底就会让该图永久加载
            //    失败（"只有赛博朋克边缘行者 2 这张图死活加载不出"的根因）。
            //    对齐 mirror 模式已有的直连兜底语义（all mirror candidates failed, try original）。
            FileLogger.w("ImageAccel", "ECH与镜像均失败，尝试直连 | $original")
            try {
                val direct = withTimeoutOrNull(8_000L) { chain.proceed(chain.request) }
                if (direct is SuccessResult) return@coroutineScope direct
                FileLogger.w("ImageAccel", "直连 non-success: ${direct?.javaClass?.simpleName} | $original")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w("ImageAccel", "直连失败: ${e.message} | $original")
                lastError = e
            }
            throw lastError ?: IllegalStateException("ECH、镜像与直连均失败: $original")
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