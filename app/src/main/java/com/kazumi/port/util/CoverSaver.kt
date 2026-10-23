package com.kazumi.port.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import com.kazumi.port.network.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.OutputStream

/**
 * 封面保存（对齐原版 InfoActionsMenu 的"保存封面"）
 *
 * 原版行为：
 * - 取 images 里第一个非空尺寸（large > common > medium > small > grid）；
 * - 统一转成 PNG，文件名「标题-id.png」，标题非法字符替换为下划线并截断 60 字；
 * - Android 走相册保存（SaverGallery），提示「已保存到相册」。
 *
 * 移植版用 MediaStore 落到 Pictures/Kazumi（与截图保存同一套逻辑，API 29+ 免权限）。
 */
object CoverSaver {

    /** 生成与原版一致的封面文件名 */
    fun fileName(title: String, subjectId: Int): String {
        val safe = title.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim().take(60)
        val name = if (safe.isEmpty()) "封面" else safe
        return "$name-$subjectId.png"
    }

    /** 下载封面并保存到相册；返回是否成功 */
    suspend fun saveCoverFromUrl(context: Context, url: String, fileName: String): Boolean {
        if (url.isBlank()) return false
        val bitmap = withContext(Dispatchers.IO) { runCatching { downloadBitmap(url) }.getOrNull() }
            ?: return false
        return withContext(Dispatchers.IO) { saveBitmapToGallery(context, bitmap, fileName) }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        val request = Request.Builder().url(url).build()
        HttpClientFactory.getClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /** 保存 PNG 到系统相册（Pictures/Kazumi） */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Kazumi")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = context.contentResolver.insert(collection, values) ?: return false
            val ok: Boolean = try {
                val out: OutputStream? = context.contentResolver.openOutputStream(uri)
                if (out == null) {
                    false
                } else {
                    out.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    true
                }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, update, null, null)
                }
            }
            if (!ok) {
                context.contentResolver.delete(uri, null, null)
            }
            ok
        } catch (e: Exception) {
            false
        }
    }
}