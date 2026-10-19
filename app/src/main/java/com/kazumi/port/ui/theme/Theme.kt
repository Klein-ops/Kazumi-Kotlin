package com.kazumi.port.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题配色
 *
 * 对齐原版 Flutter：
 * - 9 种主题色（color_type.dart）：default(绿)/teal/blue/indigo/violet/pink/yellow/orange/deepOrange
 * - OLED 深色纯黑（utils/theme.dart oledDarkTheme）
 * - 动态取色（Android 12+ Monet，app_widget.dart DynamicColorBuilder）
 */
object KazumiThemeColors {
    val DEFAULT = Color(0xFF4CAF50)     // Colors.green 原版默认（非荧光绿）
    val TEAL = Color(0xFF009688)        // Colors.teal
    val BLUE = Color(0xFF2196F3)        // Colors.blue
    val INDIGO = Color(0xFF3F51B5)      // Colors.indigo
    val VIOLET = Color(0xFF6750A4)      // Color(0xff6750a4)
    val PINK = Color(0xFFE91E63)        // Colors.pink
    val YELLOW = Color(0xFFFFEB3B)      // Colors.yellow
    val ORANGE = Color(0xFFFF9800)      // Colors.orange
    val DEEP_ORANGE = Color(0xFFFF5722) // Colors.deepOrange

    /** 与原版 colorThemeTypes 顺序一致的展示列表：key 存进设置，label 用于界面 */
    val ALL: List<Pair<String, String>> = listOf(
        "default" to "默认",
        "teal" to "青色",
        "blue" to "蓝色",
        "indigo" to "靛蓝色",
        "violet" to "紫罗兰色",
        "pink" to "粉红色",
        "yellow" to "黄色",
        "orange" to "橙色",
        "deepOrange" to "深橙色",
    )

    /** 按原版语义解析 themeColor 存储值：
     *  'default' → 默认绿；否则按 16 进制色值字符串解析（原版存 8 位 ARGB hex，如 "ff6750a4"） */
    fun resolve(value: String): Color = when (value) {
        "default" -> DEFAULT
        "teal" -> TEAL
        "blue" -> BLUE
        "indigo" -> INDIGO
        "violet" -> VIOLET
        "pink" -> PINK
        "yellow" -> YELLOW
        "orange" -> ORANGE
        "deepOrange" -> DEEP_ORANGE
        else -> {
            val raw = value.removePrefix("#")
            val rgb = raw.toLongOrNull(16)
            when {
                rgb == null -> DEFAULT
                raw.length == 6 -> Color(0xFF000000L or rgb)
                else -> Color(rgb)
            }
        }
    }
}

/** 通道缩放，用于从种子色派生 secondary/tertiary */
private fun adjust(value: Color, factor: Float): Color {
    fun c(v: Float) = (v * factor).coerceIn(0f, 1f)
    return Color(c(value.red), c(value.green), c(value.blue), value.alpha)
}

/** 浅色配色：以种子色为主色（近似 Flutter ColorScheme.fromSeed 的观感） */
fun kazumiLightColorScheme(seed: Color): ColorScheme {
    val onSeed = if (seed.luminance() > 0.5f) Color.Black else Color.White
    return lightColorScheme(
        primary = seed,
        onPrimary = onSeed,
        primaryContainer = seed.copy(alpha = 0.16f).compositeOver(Color.White),
        onPrimaryContainer = seed,
        secondary = adjust(seed, 0.8f),
        onSecondary = onSeed,
        secondaryContainer = seed.copy(alpha = 0.12f).compositeOver(Color.White),
        onSecondaryContainer = seed,
        tertiary = adjust(seed, 0.6f),
        onTertiary = onSeed,
        tertiaryContainer = seed.copy(alpha = 0.10f).compositeOver(Color.White),
        onTertiaryContainer = seed,
        background = Color.White,
        onBackground = Color(0xFF1A1A1A),
        surface = Color.White,
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFEDEDED),
        onSurfaceVariant = Color(0xFF444444),
        outline = Color(0xFF9E9E9E),
    )
}

/** 深色配色：以调亮后的种子色为主色 */
fun kazumiDarkColorScheme(seed: Color): ColorScheme {
    // 原版 colorSchemeSeed 深色下不会过度提亮主色；这里只做温和提亮，避免荧光感
    val lightSeed = adjust(seed, 1.15f)
    val onSeed = if (lightSeed.luminance() > 0.5f) Color.Black else Color.White
    // 原版深色背景是带绿调的深色（Material3 fromSeed 对 green 生成的 surface），不是纯黑
    val bg = Color(0xFF161D16)
    return darkColorScheme(
        primary = lightSeed,
        onPrimary = onSeed,
        primaryContainer = seed.copy(alpha = 0.25f).compositeOver(bg),
        onPrimaryContainer = lightSeed,
        secondary = adjust(lightSeed, 0.85f),
        onSecondary = onSeed,
        secondaryContainer = seed.copy(alpha = 0.18f).compositeOver(bg),
        onSecondaryContainer = lightSeed,
        tertiary = adjust(lightSeed, 0.7f),
        onTertiary = onSeed,
        tertiaryContainer = seed.copy(alpha = 0.14f).compositeOver(bg),
        onTertiaryContainer = lightSeed,
        background = bg,
        onBackground = Color(0xFFE0E0E0),
        surface = bg,
        onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2A2A2A),
        onSurfaceVariant = Color(0xFFBDBDBD),
        outline = Color(0xFF757575),
    )
}

/** OLED 深色纯黑覆盖（对应原版 oledDarkTheme） */
private fun ColorScheme.oledDark(): ColorScheme = copy(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color.White,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    outline = Color(0xFF333333),
)

/**
 * Kazumi 主题入口
 *
 * @param themeMode 'system' | 'light' | 'dark'，对应原版 SettingsKeys.themeMode
 * @param themeColor 主题色存储值，见 [KazumiThemeColors.resolve]
 * @param oledEnhance OLED 纯黑增强（仅对深色模式生效，对应原版 oledEnhance）
 * @param useDynamicColor 使用系统动态取色（Android 12+，对应原版 useDynamicColor）
 */
@Composable
fun KazumiTheme(
    themeMode: String = "system",
    themeColor: String = "default",
    oledEnhance: Boolean = false,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val seed = KazumiThemeColors.resolve(themeColor)

    val colorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) kazumiDarkColorScheme(seed) else kazumiLightColorScheme(seed)
    }

    val effectiveScheme = if (darkTheme && oledEnhance) colorScheme.oledDark() else colorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = effectiveScheme,
        content = content,
    )
}
