package com.kazumi.port.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设置页公共组件（对应原版 bean/settings/settings_list.dart 的 SettingsList / SettingsSection / SettingsTile）
 */

/** 子设置页顶栏（返回 + 标题 + 可选 actions） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubPageScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surface)
    ) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = { actions() }
        )
        content()
    }
}

/** 分组标题 */
@Composable
fun SettingSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

/** 开关行（整行点击切换开关，对齐原版 SwitchListTile 的手感） */
@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leading: ImageVector? = null,
    enabled: Boolean = true,
    topRadius: Dp = 4.dp,
    bottomRadius: Dp = 4.dp
) {
    val press = rememberPressScale()
    val pressed by press.interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 24.dp else 4.dp,
        animationSpec = tween(250),
        label = "switchRowCorner"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .clip(
                RoundedCornerShape(
                    topStart = if (pressed) corner else topRadius,
                    topEnd = if (pressed) corner else topRadius,
                    bottomStart = if (pressed) corner else bottomRadius,
                    bottomEnd = if (pressed) corner else bottomRadius
                )
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = LocalIndication.current,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/** 点击行（带描述与右箭头） */
@Composable
fun SettingClickRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    leading: ImageVector? = null,
    trailingValue: String? = null,
    enabled: Boolean = true,
    topRadius: Dp = 4.dp,
    bottomRadius: Dp = 4.dp
) {
    val press = rememberPressScale()
    val pressed by press.interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 24.dp else 4.dp,
        animationSpec = tween(250),
        label = "clickRowCorner"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = press.scale
                scaleY = press.scale
            }
            .clip(
                RoundedCornerShape(
                    topStart = if (pressed) corner else topRadius,
                    topEnd = if (pressed) corner else topRadius,
                    bottomStart = if (pressed) corner else bottomRadius,
                    bottomEnd = if (pressed) corner else bottomRadius
                )
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(
                enabled = enabled,
                interactionSource = press.interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailingValue != null) {
            Text(
                text = trailingValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 滑块行（label + Slider 在同一独立框内，对应原版 SliderSettingsTile）
 * 用于设置组里"标题+滑块"组合，与 Switch/Click 行保持一致的独立框+按压圆角样式
 */
@Composable
fun SettingSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    topRadius: Dp = 4.dp,
    bottomRadius: Dp = 4.dp
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(
                    topStart = topRadius,
                    topEnd = topRadius,
                    bottomStart = bottomRadius,
                    bottomEnd = bottomRadius
                )
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange
        )
    }
}

/**
 * 分组容器（对应原版 SplitListGroup）：
 * 每个子项是独立的圆角小框，行间 4dp 间隙；首行 topRadius=24、末行 bottomRadius=24
 * （对齐原版 tonalCardRadius），按压时每行圆角动画到 24dp（对齐原版 pressedRadius）。
 * 调用点用法：组内首行设 topRadius = 24.dp、末行设 bottomRadius = 24.dp。
 */
@Composable
fun SettingGroup(
    title: String? = null,
    content: @Composable () -> Unit
) {
    if (title != null) {
        SettingSectionTitle(title)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        content()
    }
    Spacer(Modifier.height(8.dp))
}
