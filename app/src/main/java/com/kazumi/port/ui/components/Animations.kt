package com.kazumi.port.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 首次组合淡入（观感打磨）
 *
 * 组件进入组合时从透明淡入到不透明，消除「加载态/空态/标题」切换的生硬感。
 * 只作用于 graphicsLayer.alpha（transform-only，不触发重布局），开销极低，
 * 适合列表外的整块状态组件（EmptyState / LoadingState / PageHeader 等）。
 *
 * 注意：使用方组件在组合期间 alpha 从 0→1 只播放一次；重新进入组合（如切页返回）会重播，
 * 这是「内容重新出现时淡入」的预期行为。
 */
fun Modifier.fadeInOnFirstCompose(durationMillis: Int = 220): Modifier = composed {
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, animationSpec = tween(durationMillis))
    }
    graphicsLayer { this.alpha = alpha.value }
}

/**
 * 按压缩放反馈状态（手感打磨）
 *
 * 提供"按下轻微缩小、松手弹回"的卡片手感，配合 interactionSource 与组件自身的
 * clickable/combinedClickable 共享按压状态，不额外吞事件、不叠加水波纹。
 *
 * 用法（组件内部）：
 * ```
 * val press = rememberPressScale()
 * Card(
 *     modifier = Modifier
 *         .graphicsLayer {
 *             scaleX = press.scale
 *             scaleY = press.scale
 *         }
 *         .clickable(
 *             interactionSource = press.interactionSource,
 *             indication = LocalIndication.current,
 *             onClick = onClick
 *         )
 * )
 * ```
 */
@Composable
fun rememberPressScale(
    pressedScale: Float = 0.96f,
    animationMillis: Int = 120
): PressScaleState {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 直接读取 animateFloatAsState 的 value（by delegate），确保动画每一帧都触发重组
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(animationMillis),
        label = "pressScale"
    )
    return PressScaleState(interactionSource, scale)
}

/**
 * [rememberPressScale] 的返回状态：
 * 共享的 [interactionSource]（传给 clickable/combinedClickable）+ 当前缩放值 [scale]
 */
class PressScaleState(
    val interactionSource: MutableInteractionSource,
    val scale: Float
)