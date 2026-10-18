package com.kazumi.port.network

import android.util.Base64
import java.security.MessageDigest

/**
 * Bangumi 镜像签名凭据与签名算法
 *
 * 对应原版：
 * - lib/utils/bangumi_mirror_credentials.dart（凭据由 --dart-define=KAZUMI_APPID/KAZUMI_KEY 注入）
 * - lib/utils/crypto.dart::generateBangumiMirrorSearchSignature
 *
 * 签名规则（与原版逐字一致，已用官方 2.3.9 发布包内的同一凭据对
 * api.kazumi.fyi/p1/subjects/{id}/comments 实测 200 验证）：
 *
 *   bodyDigest = hex(sha256(body))
 *   data       = appId + timestamp(秒) + METHOD + path(不含 query) + bodyDigest + key
 *   signature  = base64(sha256(data))
 *
 * 请求头：X-AppId / X-Timestamp / X-Signature。
 * 仅镜像模式（BangumiAcceleration.mirror）且命中受保护接口时才附加。
 */
object BangumiMirrorCredentials {

    /**
     * 官方发布包内编译进去的凭据（从 Kazumi_android_2.3.9.apk 的 libapp.so 常量池取得）。
     * 用于让移植版与原版走同一条镜像鉴权通路，否则 api.kazumi.fyi 的评论与搜索接口必然 401。
     */
    const val APP_ID = "kazumi-hh47hcih6xfodp50"
    const val KEY = "EKlABDVRMb8g5OkCH78SL14riZU4zmkR8TvRmu3GORIeJcdQ"

    /** 是否需要签名：对齐原版 _shouldSignProtectedMirrorRequest */
    fun requiresSignature(method: String, path: String): Boolean {
        val m = method.uppercase()
        if (m == "POST" && path == "/v0/search/subjects") return true
        if (m != "GET") return false
        if (!path.endsWith("/comments")) return false
        return path.startsWith("/p1/subjects/") ||
            path.startsWith("/p1/episodes/") ||
            path.startsWith("/p1/characters/")
    }

    /** 生成签名：base64(sha256(appId + ts + method + path + hex(sha256(body)) + key)) */
    fun signature(method: String, path: String, body: String, timestamp: Long): String {
        val bodyDigest = sha256Hex(body)
        val data = APP_ID + timestamp.toString() + method.uppercase() + path + bodyDigest + KEY
        return Base64.encodeToString(sha256Bytes(data), Base64.NO_WRAP)
    }

    private fun sha256Bytes(text: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(text: String): String {
        val bytes = sha256Bytes(text)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
