package com.kazumi.port.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 加载指示器（对齐原版 lib/bean/widget/loading_indicator.dart）
 *
 * 原版用 material_new_shapes 的 7 种形状做形变动画而不是转圈：
 *   softBurst → cookie9Sided → pill → sunny → cookie4Sided → oval → cookie7Sided
 * 每个形状 650ms，完整循环 4550ms，整体同时旋转 2π。
 *
 * 本组件用极坐标半径函数生成每种形状的 48 采样点，相邻形状逐点插值（morph），
 * 复刻原版的"形状循环变形 + 旋转"效果；颜色取主题 primary。
 */
@Composable
fun KazumiLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "kazumi_loading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4550, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "kazumi_loading_progress"
    )
    Canvas(modifier = modifier.size(size)) {
        val side = this.size.minDimension
        // 对角适配：形状旋转时始终落在槽位内（对齐原版 shortestSide / sqrt2）
        val scale = (side / sqrt(2f)).toDouble()
        val centerX = (this.size.width / 2f).toDouble()
        val centerY = (this.size.height / 2f).toDouble()
        val shapeCount = 7
        val position = progress * shapeCount
        val index = floor(position).toInt() % shapeCount
        val frac = position - floor(position)
        // 相邻形状逐点插值（对齐原版 Curves.easeInOutCubicEmphasized 的缓动效果）
        val eased = easeInOutCubic(frac)
        val rotation = progress.toDouble() * 2.0 * PI

        val pointCount = 48
        val path = Path()
        for (i in 0 until pointCount) {
            val theta = 2.0 * PI * i / pointCount
            val r0 = shapeRadius(index, theta)
            val r1 = shapeRadius((index + 1) % shapeCount, theta)
            val r = r0 + (r1 - r0) * eased
            val angle = theta + rotation
            val x = (r * cos(angle) * scale + centerX).toFloat()
            val y = (r * sin(angle) * scale + centerY).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color = color)
    }
}

/** Cubic 缓动（近似原版 easeInOutCubic） */
private fun easeInOutCubic(t: Float): Double {
    val x = t.toDouble()
    return if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
}

/** 7 种形状的极坐标半径函数（归一化 0..~1，中心为原点） */
private fun shapeRadius(shape: Int, theta: Double): Double = when (shape) {
    0 -> { // softBurst：8 瓣软爆裂（花瓣端部圆润）
        val t = abs(sin(4 * theta))
        0.45 + 0.55 * t.pow(1.25)
    }
    1 -> regularPolygonRadius(9, theta)      // cookie9Sided
    2 -> superEllipseRadius(3.2, 1.40, 0.62, theta) // pill：横向圆角胶囊
    3 -> { // sunny：中心圆 + 8 个三角尖
        val spike = max(0.0, cos(4 * theta)).pow(2)
        0.60 + 0.40 * spike
    }
    4 -> regularPolygonRadius(4, theta)      // cookie4Sided（圆角方形）
    5 -> { // oval：纵向椭圆
        val a = 1.35
        val b = 0.65
        a * b / sqrt((b * cos(theta)).pow(2) + (a * sin(theta)).pow(2))
    }
    else -> regularPolygonRadius(7, theta)   // cookie7Sided
}

/** 正多边形半径：外接圆半径 1，顶点处 1，边中点处 cos(π/N) */
private fun regularPolygonRadius(sides: Int, theta: Double): Double {
    val sector = 2.0 * PI / sides
    // 映射到 [-π/N, π/N]
    var delta = (theta + sector / 2) % sector
    if (delta < 0) delta += sector
    delta -= sector / 2
    return cos(PI / sides) / cos(delta)
}

/** 超椭圆（圆角矩形/胶囊近似）：(|cosθ|/a)^n + (|sinθ|/b)^n = 1 的极坐标半径 */
private fun superEllipseRadius(n: Double, a: Double, b: Double, theta: Double): Double {
    val cx = (abs(cos(theta)) / a).pow(n)
    val sy = (abs(sin(theta)) / b).pow(n)
    return 1.0 / (cx + sy).pow(1.0 / n)
}