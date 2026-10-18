package com.kazumi.port.network

import coil.intercept.Interceptor
import coil.intercept.Interceptor.Chain
import coil.request.ImageResult
import coil.request.SuccessResult
import com.kazumi.port.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coil 图片加速拦截器（Coil 2.x）
 *
 * 对应原版 ImageFileService + ProxyAwareImageCacheManager 的加速策略：
 * - direct：直连原始图片 URL
 * - ech：走 libech_http（ECH 加密握手）原生下载，先拉 ECHConfig 再用写死 CF IP 下载；
 *   失败直接抛错（对齐原版，不拿镜像轮询）
 * - mirror：将 Bangumi 图片重写为 wsrv.nl 等镜像后加载（原版 BangumiImageUrlRewriter）
 *
 * 性能设计（对齐原版"复用 session"思路）：
 * - EchResolver 缓存 ECHConfig（60s 内不重复 DoH）
 * - 这里缓存 ECH 下载结果（同一 URL 内存缓存，列表滚动/详情大图秒出）
 * - native JNI 是全局状态必须串行，但缓存命中后不触碰 native，吞吐不受串行拖累
 */
class ImageAccelerationInterceptor(
    private val acceleration: () -> ImageAcceleration,
) : Interceptor {

    /** ECH 下载结果内存缓存：URL -> bytes。上限 256 张，超限时按写入顺序淘汰最旧 */
    private val echCache = object : LinkedHashMap<String, ByteArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > 256
    }
    private val echCacheLock = Any()

    private fun cacheGet(url: String): ByteArray? = synchronized(echCacheLock) { echCache[url] }

    private fun cachePut(url: String, bytes: ByteArray) {
        synchronized(echCacheLock) { echCache[url] = bytes }
    }

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
                // 1) 内存缓存命中：同一 URL 直接复用（列表滚动/详情页大图秒出，不碰 native）
                val cached = cacheGet(original)
                if (cached != null) {
                    FileLogger.d("ImageAccel", "ech cache HIT url=$original len=${cached.size}")
                    return chain.proceed(chain.request.newBuilder().data(cached).build())
                }
                // 2) fetchEchConfig 内部是同步 OkHttp 阻塞调用，放到 IO 线程避免卡住 Coil 调度线程；
                //    60s 内复用缓存，不重复打 DoH
                val echConfig = withContext(Dispatchers.IO) {
                    EchResolver.fetchEchConfig()
                }
                if (echConfig != null) {
                    FileLogger.d("ImageAccel", "echConfig fetched len=${echConfig.length}")
                    val bytes = EchHttpNative.loadSuspend(original, echConfig)
                    FileLogger.i("ImageAccel", "ech download OK bytes=${bytes.size} for $original")
                    if (bytes.isNotEmpty()) {
                        cachePut(original, bytes)
                        // 把下载好的字节重新作为 data 交给 Coil 解码链路（Coil 支持 ByteArray）
                        val byteReq = chain.request.newBuilder().data(bytes).build()
                        return chain.proceed(byteReq)
                    }
                } else {
                    FileLogger.w("ImageAccel", "echConfig is null, ech load aborted")
                }
                // ECH 未拿到/字节为空：直接抛错，不降级到镜像（对齐原版）
                throw IllegalStateException("ECH 图片加载失败: echConfig=${echConfig != null}")
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