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
import kotlinx.coroutines.withContext
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

    /** 把字节作为 data 交给 Coil 解码链路；钉住 memoryCacheKey/diskCacheKey=原始 URL */
    private fun byteRequest(chain: Chain, original: String, bytes: ByteArray) =
        chain.request.newBuilder()
            .data(bytes)
            .memoryCacheKey(original)
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

        // ECH：先用原生 ECH 栈下载，成功直接解码返回；失败直接抛错（对齐原版：
        // BangumiEchImageService 的 ECH 失败即 rethrow，不拿镜像轮询——镜像兜底是 mirror 模式的事）
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

                // 3) ECH 下载：全程 15s 总超时兜底（native 无内置超时，熔断黑洞 IP，
                //    避免单张图卡死拖累整个列表）。fetchEchConfig 在 IO 线程执行
                //    （内部是同步 OkHttp 阻塞调用），60s 内复用缓存不重复打 DoH。
                val bytes = withTimeoutOrNull(15_000L) {
                    withContext(Dispatchers.IO) {
                        val echConfig = EchResolver.fetchEchConfig()
                        if (echConfig != null) {
                            EchHttpNative.loadSuspend(original, echConfig)
                        } else {
                            null
                        }
                    }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    FileLogger.i("ImageAccel", "ech download OK bytes=${bytes.size} for $original")
                    cachePut(original, bytes)
                    writeEchFile(context, original, bytes)
                    return chain.proceed(byteRequest(chain, original, bytes))
                }
                // ECH 超时（15s）/配置为空/字节为空：直接抛错，不降级到镜像（对齐原版）
                throw IllegalStateException("ECH 图片加载失败: 下载超时或配置为空")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 失败抛出（不再逐个试镜像），由 Coil 上层显示占位/重试
                FileLogger.w("ImageAccel", "ech download failed: ${e.message} | $original")
                throw e
            }
        }

        // mirror 模式：走镜像候选，首个成功即返回（对齐原版 ImageFileService 的 mirror 重写）
        val candidates = BangumiImageUrlRewriter.rewriteCandidates(original)
        FileLogger.d("ImageAccel", "mirror candidates: $candidates")
        var lastError: Exception? = null
        for (candidate in candidates) {
            if (candidate == original) continue // 跳过原始地址，代理候选优先
            try {
                val result = chain.proceed(chain.request.newBuilder().data(candidate).build())
                if (result is SuccessResult) {
                    FileLogger.i("ImageAccel", "mirror OK via $candidate")
                    return result
                }
                FileLogger.d("ImageAccel", "mirror candidate $candidate returned non-success: ${result.javaClass.simpleName}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w("ImageAccel", "mirror candidate $candidate failed: ${e.message}")
                lastError = e
            }
        }
        // 全部候选失败：最后再尝试一次原始地址（可能站内可达）
        FileLogger.w("ImageAccel", "all mirror candidates failed, try original")
        return try {
            chain.proceed(chain.request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw lastError ?: e
        }
    }
}