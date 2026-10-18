package com.kazumi.port.utils

/**
 * 版本比较工具
 * 对应原版 lib/utils/version.dart 的 needUpdate
 */
object VersionUtil {

    /**
     * 远程版本是否高于本地版本（按点分数字段逐段比较）
     * 解析失败时回退为字符串不相等
     */
    fun needUpdate(localVersion: String, remoteVersion: String): Boolean {
        return try {
            val local = localVersion.split(".")
            val remote = remoteVersion.split(".")
            val maxLength = maxOf(local.size, remote.size)
            for (i in 0 until maxLength) {
                val l = if (i < local.size) local[i].toInt() else 0
                val r = if (i < remote.size) remote[i].toInt() else 0
                if (r > l) return true
                if (r < l) return false
            }
            false
        } catch (_: NumberFormatException) {
            localVersion != remoteVersion
        }
    }
}