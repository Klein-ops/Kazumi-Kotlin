package com.kazumi.port.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 播放调节 HUD（对应原版 player_adjustment_hud.dart 的 PlayerAdjustmentHud）
 * 显示在播放器顶部中央，用于亮度/音量手势反馈。
 */
@Composable
fun PlayerAdjustmentHud(
    visible: Boolean,
    type: PlayerAdjustmentHudType,
    value: Float
) {
    val accent = if (type == PlayerAdjustmentHudType.Brightness) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val container = if (type == PlayerAdjustmentHudType.Brightness) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    val onContainer = if (type == PlayerAdjustmentHudType.Brightness) {
        MaterialTheme.colorScheme.onTertiary
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val progress = when (type) {
        PlayerAdjustmentHudType.Brightness -> value.coerceIn(0f, 1f)
        PlayerAdjustmentHudType.Volume -> (value / 100f).coerceIn(0f, 1f)
    }
    val percent = (progress * 100).roundToInt()
    val icon = when {
        type == PlayerAdjustmentHudType.Brightness && percent <= 8 -> Icons.Rounded.BrightnessLow
        type == PlayerAdjustmentHudType.Brightness && percent < 55 -> Icons.Rounded.BrightnessMedium
        type == PlayerAdjustmentHudType.Brightness -> Icons.Rounded.BrightnessHigh
        percent <= 0 -> Icons.Rounded.VolumeOff
        percent < 45 -> Icons.Rounded.VolumeDown
        else -> Icons.Rounded.VolumeUp
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 6 } + scaleIn(tween(200)),
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 6 }
    ) {
        Box(
            modifier = Modifier
                .width(200.dp)
                .shadow(24.dp, RoundedCornerShape(30.dp))
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(vertical = 4.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(container),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = onContainer, modifier = Modifier.size(20.dp))
                }
                Slider(
                    value = progress,
                    onValueChange = {},
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

enum class PlayerAdjustmentHudType { Brightness, Volume }

/**
 * 播放倍速 HUD（对应原版 PlayerSpeedHud）
 */
@Composable
fun PlayerSpeedHud(
    visible: Boolean,
    speed: Float
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { -it / 8 } + scaleIn(tween(160)),
        exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 8 }
    ) {
        Box(
            modifier = Modifier
                .width(94.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(vertical = 4.dp, horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.inverseSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Text(
                    text = "${"%.1f".format(speed)}x",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 播放 seek HUD（对应原版 PlayerSeekHud）
 * 显示进度/方向/取消提示。
 */
@Composable
fun PlayerSeekHud(
    visible: Boolean,
    currentPositionMs: Long,
    playerPositionMs: Long,
    durationMs: Long,
    direction: Int,
    cancelPending: Boolean
) {
    val isForward = when {
        currentPositionMs != playerPositionMs -> currentPositionMs > playerPositionMs
        else -> direction >= 0
    }
    val offsetMs = kotlin.math.abs(currentPositionMs - playerPositionMs)
    val offsetText = "${if (isForward) "+" else "-"}${formatMsShort(offsetMs)}"
    val icon = if (cancelPending) Icons.Rounded.Close
    else if (isForward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind
    val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 6 } + scaleIn(tween(200)),
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 6 }
    ) {
        Box(
            modifier = Modifier
                .width(248.dp)
                .shadow(24.dp, RoundedCornerShape(30.dp))
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(vertical = 8.dp, horizontal = 10.dp)
        ) {
            // 背景进度条
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        if (progress > 0) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        }
                    )
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text(
                        text = if (cancelPending) "松开取消" else offsetText,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        text = if (cancelPending) "移回画面继续调整" else "${formatMsShort(currentPositionMs)} / ${formatMsShort(durationMs)}",
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatMsShort(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format("%02d:%02d", m, s)
}