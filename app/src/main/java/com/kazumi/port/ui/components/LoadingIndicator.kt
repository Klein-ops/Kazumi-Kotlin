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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 加载指示器（对齐原版 lib/bean/widget/loading_indicator.dart + material_new_shapes）
 *
 * 原版 7 种形状循环形变：softBurst → cookie9Sided → pill → sunny → cookie4Sided → oval → cookie7Sided，
 * 每形状 650ms，完整循环 4550ms，同时整体旋转 2π。
 *
 * 形状坐标按原版 MaterialShapes 归一化到 [0,1]²（中心 0.5,0.5，最大半径≈0.5），
 * 绘制时 scale = size/sqrt2（原版 painter 逻辑），实际视觉直径 ≈ 0.7×size。
 * 之前版本错误地把半径归一到 1，导致直径整整大一倍、看起来又大又糙，已修正。
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
        // 原版：canvas.scale(size.shortestSide / sqrt2)，形状坐标在 [0,1]²
        val scale = side / sqrt(2f)
        val centerX = this.size.width / 2f
        val centerY = this.size.height / 2f
        val shapeCount = 7
        val position = progress * shapeCount
        val index = floor(position).toInt() % shapeCount
        val frac = position - floor(position)
        // 原版 Curves.easeInOutCubicEmphasized 近似（视觉等价 easeInOutCubic）
        val eased = easeInOutCubic(frac)
        val rotation = progress * 2f * PI.toFloat()

        val pointCount = 48
        val path = Path()
        for (i in 0 until pointCount) {
            val theta = 2.0 * PI * i / pointCount
            // 当前形状与下一形状在同角度下的半径，逐点插值（morph）
            val r0 = shapeRadius(index, theta)
            val r1 = shapeRadius((index + 1) % shapeCount, theta)
            val r = r0 + (r1 - r0) * eased
            // 本地坐标：中心 0.5,0.5，最大半径≈0.5 → 缩放 → 旋转 → 平移到画布中心
            val ox = (r * cos(theta) * 0.5f).toFloat() // 归一化伸缩在 shapeRadius 内完成
            val oy = (r * sin(theta) * 0.5f).toFloat()
            val rx = ox * cos(rotation) - oy * sin(rotation)
            val ry = ox * sin(rotation) + oy * cos(rotation)
            val x = rx * scale + centerX
            val y = ry * scale + centerY
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color = color)
    }
}

/** 原版 Curves.easeInOutCubicEmphasized 近似（标准 easeInOutCubic） */
private fun easeInOutCubic(t: Float): Double {
    val x = t.toDouble()
    return if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
}

/**
 * 7 种形状的极坐标半径函数。
 * 返回值归一化到 0..1（1 = 形状最远顶点/最远边缘在单位半径处）；
 * 与 0.5 相乘后即为 [0,1]² 坐标系里的实际半径（中心 0.5,0.5，最大半径 0.5）。
 */
private fun shapeRadius(shape: Int, theta: Double): Double = when (shape) {
    // softBurst：10 顶点 + 0.053 圆角，5 个"尖瓣"的软星形（原版 inner≈0.5/outer≈0.75）
    0 -> starRadius(5, inner = 0.5, outer = 0.75, theta)
    // cookie9Sided：9 角星，inner 0.8、round 0.5，旋转 -90°
    1 -> starRadius(9, inner = 0.8, outer = 1.0, theta + PI / 2)
    // pill：胶囊（横向宽、纵向窄，0.42 高）
    2 -> superEllipseRadius(3.0, 1.15, 0.52, theta)
    // sunny：8 角星，inner 0.8、round 0.15，不旋转
    3 -> starRadius(8, inner = 0.8, outer = 1.0, theta)
    // cookie4Sided：4 角曲边星（原版 inner≈0.55/outer≈0.75，round 0.25）
    4 -> starRadius(4, inner = 0.55, outer = 0.75, theta)
    // oval：旋转 -45° 的 0.64 纵向压缩圆（原版 circle → rotateZ(-45°) → scale(1, 0.64) → normalized）
    5 -> {
        val rot = -PI / 4
        val t = theta - rot
        val cosT = cos(t)
        val sinT = sin(t)
        // 椭圆极坐标半径：r(θ) = a*b / sqrt((b·cosθ)² + (a·sinθ)²)，a=1（长轴）、b=0.64（短轴）
        0.64 / sqrt((0.64 * cosT) * (0.64 * cosT) + sinT * sinT)
    }
    // cookie7Sided：7 角星，inner 0.75、round 0.5，旋转 -90°
    else -> starRadius(7, inner = 0.75, outer = 1.0, theta + PI / 2)
}

/**
 * 圆角星形极坐标半径（对齐原版 star：内外顶点交替，顶点间线性过渡 + 圆角平滑近似）。
 * 共 2*vertices 个顶点，外顶点处 r=outer，内顶点处 r=inner。
 */
private fun starRadius(
    vertices: Int,
    inner: Double,
    outer: Double,
    theta: Double
): Double {
    // 每个顶点间隔角度 = 2π / (2*vertices) = π/vertices
    val step = PI / vertices
    // 角度归一化到 [0, 2π)
    var a = theta % (2 * PI)
    if (a < 0) a += 2 * PI
    // 找所在顶点区间
    val f = a / step
    val i0 = floor(f).toInt() % (2 * vertices)
    val t = f - floor(f)
    // i0 偶数=外顶点，奇数=内顶点；i0+1 为下一顶点
    val r0 = if (i0 % 2 == 0) outer else inner
    val r1 = if ((i0 + 1) % 2 == 0) outer else inner
    // smoothstep 过渡：让顶点处更圆润（模拟 CornerRounding 圆角收缩）
    val tt = t * t * (3 - 2 * t)
    return r0 + (r1 - r0) * tt
}

/** 超椭圆极坐标半径（圆角矩形/胶囊近似）：(|cosθ|/a)^n + (|sinθ|/b)^n = 1 */
private fun superEllipseRadius(
    n: Double,
    a: Double,
    b: Double,
    theta: Double
): Double {
    val cx = (abs(cos(theta)) / a).pow(n)
    val sy = (abs(sin(theta)) / b).pow(n)
    return 1.0 / (cx + sy).pow(1.0 / n)
}