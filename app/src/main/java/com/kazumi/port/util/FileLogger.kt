package com.kazumi.port.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

/**
 * 文件日志（供排错用）。
 *
 * 日志写入应用外部目录：/storage/emulated/0/Android/data/com.kazumi.port/files/logs/
 * （无需任何权限，adb 或文件管理器可直接查看）。
 *
 * - 按天轮转：kazumi_YYYY-MM-DD.log
 * - 自动清理 7 天前的日志
 * - 同时输出到 Logcat（方便 adb logcat 实时看）
 * - 写文件走单线程 executor，不阻塞调用方
 */
object FileLogger {

    private const val TAG = "KazumiFile"
    private const val KEEP_DAYS = 7

    private var logDir: File? = null
    private var initialized = false
    private val lock = ReentrantLock()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kazumi-file-logger").apply { isDaemon = true }
    }

    /** 必须在 Application.onCreate 调用一次 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val dir = File(context.getExternalFilesDir(null), "logs")
            dir.mkdirs()
            logDir = dir
            cleanupOldLogs()
        } catch (e: Exception) {
            Log.w(TAG, "FileLogger init failed: ${e.message}")
        }
    }

    fun d(tag: String, msg: String) = write('D', tag, msg)
    fun i(tag: String, msg: String) = write('I', tag, msg)
    fun w(tag: String, msg: String) = write('W', tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    private fun write(level: Char, tag: String, msg: String, t: Throwable? = null) {
        // 同步输出到 logcat，便于 adb 实时观察
        when (level) {
            'D' -> Log.d(tag, msg)
            'I' -> Log.i(tag, msg)
            'W' -> Log.w(tag, msg)
            'E' -> {
                if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
            }
        }
        val dir = logDir ?: return
        val line = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append(" [")
            append(level)
            append("] [")
            append(tag)
            append("] ")
            append(msg)
            if (t != null) {
                append(" | ")
                append(t.javaClass.simpleName)
                append(": ")
                append(t.message)
            }
        }
        executor.execute {
            lock.withLock {
                try {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val f = File(dir, "kazumi_$today.log")
                    FileWriter(f, true).use { it.write(line + "\n") }
                } catch (e: Exception) {
                    Log.w(TAG, "file write failed: ${e.message}")
                }
            }
        }
    }

    /** 删除 KEEP_DAYS 天前的日志文件 */
    private fun cleanupOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000L
        dir.listFiles()?.filter { it.name.startsWith("kazumi_") && it.name.endsWith(".log") }?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }
}
