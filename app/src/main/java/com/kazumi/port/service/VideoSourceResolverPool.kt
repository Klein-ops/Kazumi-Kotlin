package com.kazumi.port.service

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 视频源解析器池
 *
 * 对应原版 video_source_resolver_pool.dart。
 *
 * 维护一组 [WebViewVideoSourceService] 工作实例（每个持有一个 WebView），
 * 通过租约（lease）方式按 key 分配：同一 key 同时只允许一个活跃解析，
 * 其余请求返回 null（表示需要等待/重试）。
 *
 * 并发度 maxWorkers 限制在 1..5，超出时裁剪空闲 worker。
 */
class VideoSourceResolverPool(
    private val context: Context
) {
    private val workers = CopyOnWriteArrayList<VideoSourceResolverWorker>()
    private val activeLeases = ConcurrentHashMap<String, VideoSourceResolverLease>()
    private var maxWorkers = 1

    /** 调整并发度（限制 1..5） */
    fun resize(maxWorkers: Int) {
        this.maxWorkers = maxWorkers.coerceIn(1, 5)
        trimIdleWorkers()
    }

    /**
     * 尝试获取租约。
     * 若 key 已有活跃租约返回 null；否则从空闲 worker 或新建 worker 中分配。
     */
    fun tryAcquire(key: String): VideoSourceResolverLease? {
        if (activeLeases.containsKey(key)) return null

        trimIdleWorkers()

        var worker = findIdleWorker()
        if (worker == null && workers.size < maxWorkers) {
            worker = VideoSourceResolverWorker(context)
            workers.add(worker)
        }
        if (worker == null) return null

        worker.isBusy = true
        val lease = VideoSourceResolverLease(key, worker)
        activeLeases[key] = lease
        return lease
    }

    /** 取消指定 key 的解析 */
    fun cancel(key: String): Boolean {
        val lease = activeLeases[key] ?: return false
        lease.cancel()
        return true
    }

    /** 取消全部解析 */
    fun cancelAll() {
        activeLeases.values.toList().forEach { it.cancel() }
    }

    /** 释放租约 */
    fun release(lease: VideoSourceResolverLease) {
        val current = activeLeases[lease.key] ?: return
        if (current !== lease) return

        activeLeases.remove(lease.key)
        val worker = lease.worker
        worker.isBusy = false

        if (lease.shouldRetireFlag || worker.isRetired) {
            workers.remove(worker)
            worker.retire()
            // 异步释放 WebView 资源
            Thread {
                try { runBlocking { worker.dispose() } } catch (_: Exception) {}
            }.start()
            return
        }

        trimIdleWorkers()
    }

    /** 释放全部资源 */
    suspend fun dispose() {
        cancelAll()
        val all = workers.toList()
        activeLeases.clear()
        workers.clear()
        all.forEach { worker ->
            try { worker.dispose() } catch (_: Exception) {}
        }
    }

    private fun findIdleWorker(): VideoSourceResolverWorker? {
        for (worker in workers) {
            if (!worker.isBusy && !worker.isRetired) return worker
        }
        return null
    }

    private fun trimIdleWorkers() {
        while (workers.size > maxWorkers) {
            var idleWorker: VideoSourceResolverWorker? = null
            for (worker in workers) {
                if (!worker.isBusy) {
                    idleWorker = worker
                    break
                }
            }
            if (idleWorker == null) return

            workers.remove(idleWorker)
            idleWorker.retire()
            Thread {
                try { runBlocking { idleWorker.dispose() } } catch (_: Exception) {}
            }.start()
        }
    }
}

/**
 * 视频源解析租约
 * 持有 worker 引用，提供 resolve / cancel / retire 操作。
 */
class VideoSourceResolverLease internal constructor(
    val key: String,
    internal val worker: VideoSourceResolverWorker
) {
    @Volatile
    private var isCancelled = false

    @Volatile
    private var shouldRetire = false

    val isCancelledFlag: Boolean get() = isCancelled
    val shouldRetireFlag: Boolean get() = shouldRetire

    suspend fun resolve(
        episodeUrl: String,
        useLegacyParser: Boolean,
        offset: Int = 0,
        timeoutMillis: Long = 30_000
    ): VideoSource {
        if (isCancelled) throw VideoSourceCancelledException()
        return worker.resolve(
            episodeUrl,
            useLegacyParser = useLegacyParser,
            offset = offset,
            timeoutMillis = timeoutMillis
        )
    }

    fun cancel() {
        isCancelled = true
        worker.cancel()
    }

    fun retire() {
        shouldRetire = true
        worker.retire()
    }
}

/** 池内 worker：包装一个 [WebViewVideoSourceService] */
class VideoSourceResolverWorker internal constructor(
    context: Context
) {
    private val service = WebViewVideoSourceService(context)

    @Volatile
    var isBusy = false

    @Volatile
    var isRetired = false

    var onLog: ((String) -> Unit)? = null

    suspend fun resolve(
        episodeUrl: String,
        useLegacyParser: Boolean,
        offset: Int,
        timeoutMillis: Long
    ): VideoSource {
        service.onLog = onLog
        return service.resolve(
            episodeUrl,
            useLegacyParser = useLegacyParser,
            offset = offset,
            timeoutMillis = timeoutMillis
        )
    }

    fun cancel() {
        service.cancel()
    }

    fun retire() {
        isRetired = true
    }

    suspend fun dispose() {
        service.dispose()
    }
}