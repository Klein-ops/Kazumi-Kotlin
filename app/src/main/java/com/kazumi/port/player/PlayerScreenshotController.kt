package com.kazumi.port.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * 截图控制器（对应原版 player_screenshot_controller.dart 的 Android 路径）
 *
 * 原版在 Android 上截图 = 捕获当前帧 -> 直接保存到相册（SaverGallery.saveImage）。
 * 候选挑选/批量保存 sheet 属于桌面端，Android 不展示。
 *
 * 本控制器：
 * - 从 TextureView 截取当前帧（由 UI 层提供 Bitmap 源）
 * - 通过 MediaStore 保存 PNG 到系统相册（API 29+ 免权限；低版本需 WRITE_EXTERNAL_STORAGE）
 * - 暴露截图反馈动画触发计数（对应原版 _playScreenshotFeedback）
 */
class PlayerScreenshotController(private val context: Context) {

    data class ScreenshotState(
        val capturing: Boolean = false,
        val message: String? = null,
        val hasError: Boolean = false,
        /** 截图成功触发一次自增，供 UI 播放反馈动画 */
        val feedbackCount: Int = 0
    )

    private val _state = MutableStateFlow(ScreenshotState())
    val state: StateFlow<ScreenshotState> = _state.asStateFlow()

    /**
     * 截取当前帧并保存到相册。
     * @param captureBitmap 从视频纹理截取 Bitmap 的函数（TextureView.bitmap）
     * @return 是否保存成功
     */
    suspend fun captureAndSave(captureBitmap: () -> Bitmap?): Boolean {
        if (_state.value.capturing) return false
        _state.value = _state.value.copy(capturing = true, message = null, hasError = false)
        return try {
            val bitmap = withContext(Dispatchers.Main) { captureBitmap() }
            if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0) {
                _state.value = _state.value.copy(
                    capturing = false,
                    message = "暂未获取到画面，请等视频显示后重试",
                    hasError = true
                )
                return false
            }
            val saved = withContext(Dispatchers.IO) { saveToGallery(bitmap) }
            if (saved) {
                _state.value = _state.value.copy(
                    capturing = false,
                    message = "截图已保存到相册",
                    hasError = false,
                    feedbackCount = _state.value.feedbackCount + 1
                )
                true
            } else {
                _state.value = _state.value.copy(
                    capturing = false,
                    message = "截图保存失败",
                    hasError = true
                )
                false
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                capturing = false,
                message = "截图失败：${e.message ?: "未知错误"}",
                hasError = true
            )
            false
        }
    }

    private fun saveToGallery(bitmap: Bitmap): Boolean {
        return try {
            val fileName = "kazumi_${System.currentTimeMillis()}.png"
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