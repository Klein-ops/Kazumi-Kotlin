package com.kazumi.port.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * ECH 原生下载（JNI 封装）。
 *
 * 依赖：
 * - libech_http.so（官方 ech_http 库，libcurl8.22+BoringSSL）
 * - libech_http_jni.so（自编译 JNI 桥，调用 eh_* 回调 ABI）
 *
 * 对齐原版 BangumiEchResolver：
 * - DoH 用 alidns 拉 crypto.cloudflare.com 的共享 ECHConfig
 * - 连接 IP 写死为原版三个 Cloudflare 前端 IP（addressOverrides）
 *
 * 并发模型（JNI 桥 v4，ech_http_jni.c）：per-request 独立 EchCtx（每请求自己的
 * 锁/cond/body），回调经 port=ctx 指针找回对应请求状态并立即 ack；全局仅复用
 * client（对齐原版 session 复用）。**并发安全**，所以这里用 Semaphore(6) 限流，
 * 而不是 Mutex 串行——列表页 20+ 张图可并行下载，对齐官方"1 秒全出"的体验。
 * 真机已验证：ech_http 库把 port 原样回传给回调（test_port 85 次回调全部一致）。
 */
object EchHttpNative {

    /** 与原版 BangumiEchResolver._addresses 完全一致 */
    private val CONNECT_IPS = listOf(
        "172.67.134.140",
        "104.21.6.61",
        "172.67.73.67",
    )

    private const val DEFAULT_HEADERS = "user-agent: Kazumi/2.3.9 (Android)\r\n"

    /** 并发限流：JNI v4 per-request 独立状态安全，6 路并行（对齐官方并发数） */
    private val semaphore = Semaphore(6)

    /** 返回体只要 200 且 ECH 被接受 */
    fun load(url: String, echConfig: String): ByteArray =
        runBlockingLoad(url, echConfig)

    private fun runBlockingLoad(url: String, echConfig: String): ByteArray {
        var last: Exception? = null
        for (ip in CONNECT_IPS) {
            try {
                val body = request(url, echConfig, ip, DEFAULT_HEADERS, IntArray(4))
                if (body != null && body.isNotEmpty()) return body
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("所有 ECH 入口均失败")
    }

    /**
     * 并发执行原生下载（供 suspend 调用方使用）。
     * JNI v4 已并发安全（per-request 状态），Semaphore(6) 仅限流防线程爆炸。
     */
    suspend fun loadSuspend(url: String, echConfig: String): ByteArray =
        semaphore.withPermit {
            withContext(Dispatchers.IO) {
                runBlockingLoad(url, echConfig)
            }
        }

    /** JNI 原生函数（C 端符号：Java_com_kazumi_port_network_EchHttpNative_request） */
    private external fun request(
        url: String,
        echConfig: String,
        connectIp: String,
        headers: String,
        outMeta: IntArray,
    ): ByteArray?

    init {
        System.loadLibrary("ech_http_jni")
    }
}