package com.kazumi.port.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.os.Build
import coil.size.Size
import coil.transform.Transformation

/**
 * RenderScript 高斯模糊变换，用于 API 24-30（RenderEffect 不可用）的封面背景模糊。
 *
 * 背景：Compose 的 [androidx.compose.ui.draw.blur] 底层走 RenderEffect，
 * 官方仅 Android 12+（API 31+）支持，低版本会被静默忽略（见 ui 1.7.0 源码
 * Blur.kt 注释 "only supported on Android 12 and above... will be ignored"）。
 * 本变换在图片解码阶段（EngineInterceptor 内）执行，Coil 会把结果写入
 * 内存/磁盘缓存，因此同一封面只在首次加载时模糊一次，后续直接复用缓存，
 * 没有每帧开销。
 *
 * @param context 用于创建 RenderScript 实例的 Context（传 applicationContext）
 * @param radiusPx 高斯模糊半径（px）。原版 Flutter _InfoHeaderBackground
 *   （_blurSigma=15, _downsample=0.5 → 等效全分辨率 sigma≈15px）视觉对齐取 20px。
 */
class RenderScriptBlurTransformation(
    context: Context,
    private val radiusPx: Float = 20f,
) : Transformation {

    private val appContext: Context = context.applicationContext

    override val cacheKey: String = "RenderScriptBlur($radiusPx)"

    @Suppress("DEPRECATION")
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // RenderScript 在 API 31+ 被标记 deprecated（但仍可用），这里只负责 24-30。
        // 保险起见仍加保护：API 31+ 直接返回原图（应由 Modifier.blur 处理）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return input

        val radius = radiusPx.coerceIn(1f, 25f)
        // ScriptIntrinsicBlur 规定半径范围 0 < radius <= 25。
        // 输入位图可能为 RGBA_F16（Source 层解码时启用），Allocation 只稳妥处理
        // ARGB_8888，先统一转换（config 相同则 copy 返回自身，无额外开销）。
        val src = if (input.config != Bitmap.Config.ARGB_8888) {
            input.copy(Bitmap.Config.ARGB_8888, false) ?: return input
        } else {
            input
        }
        val rs = RenderScript.create(appContext)
        var inputAlloc: Allocation? = null
        var outputAlloc: Allocation? = null
        return try {
            inputAlloc = Allocation.createFromBitmap(rs, src)
            val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            outputAlloc = Allocation.createFromBitmap(rs, output)
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            blur.setRadius(radius)
            blur.setInput(inputAlloc)
            blur.forEach(outputAlloc)
            outputAlloc.copyTo(output)
            output
        } catch (_: Exception) {
            // 任何失败（驱动不支持、OOM 等）都回退原图，绝不让页面崩溃
            input
        } finally {
            inputAlloc?.destroy()
            outputAlloc?.destroy()
            rs.destroy()
        }
    }
}