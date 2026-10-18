package com.kazumi.port.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 规则卡片
 * 对应原版 lib/bean/card/rule_card.dart 的 RuleCard
 * - 标题 + 副标题(站点) + 标签(版本/可更新/搜索通过) + 尾部操作(菜单/选择框)
 * - 选中态：secondaryContainer 高亮 + 圆角 20 描边
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RuleCard(
    title: String,
    subtitle: String? = null,
    tags: List<@Composable () -> Unit> = emptyList(),
    caption: String? = null,
    selected: Boolean = false,
    installed: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val container = if (selected) colors.secondaryContainer else colors.surfaceContainerLow
    val shape = if (selected) RoundedCornerShape(20.dp) else RoundedCornerShape(28.dp)
    val iconBoxColor = if (selected || installed) colors.secondaryContainer else colors.primaryContainer
    val iconColor = if (selected || installed) colors.onSecondaryContainer else colors.onPrimaryContainer

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(container)
            .combinedClickable(
                enabled = onClick != null || onLongPress != null,
                onClick = { onClick?.invoke() },
                onLongClick = { onLongPress?.invoke() }
            )
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            // 左侧图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(iconBoxColor, RoundedCornerShape(if (installed) 24.dp else 16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (selected) Icons.Filled.Check else Icons.Filled.Extension,
                    contentDescription = null,
                    tint = iconColor
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.W700,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { tag -> tag() }
                    }
                }
                if (!caption.isNullOrEmpty()) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

/** 规则标签（版本 / 可更新 / 搜索通过） */
@Composable
fun RuleTag(
    label: String,
    background: Color,
    foreground: Color
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        fontWeight = FontWeight.W600,
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * 规则页顶部引导卡片
 * 对应原版 lib/pages/plugin_editor/rule_management_widgets.dart 的 RulePageIntro
 */
@Composable
fun RulePageIntro(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actions: @Composable () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.primaryContainer, RoundedCornerShape(32.dp))
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.W700,
                color = colors.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(32.dp)
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onPrimaryContainer,
            modifier = Modifier.padding(top = 8.dp)
        )
        actions()
    }
}