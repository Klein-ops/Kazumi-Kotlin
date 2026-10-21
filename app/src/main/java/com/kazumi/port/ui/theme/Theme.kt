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

/** 浅色配色：基于 seed 主色动态生成（HSV 近似 M3 色调映射，绕开 CodeAssist 对 fromSeed 的解析缺陷） */
fun kazumiLightColorScheme(seed: Color): ColorScheme {
    fun mix(a: Color, b: Color, frac: Float) = Color(
        red = (a.red * (1 - frac) + b.red * frac).coerceIn(0f, 1f),
        green = (a.green * (1 - frac) + b.green * frac).coerceIn(0f, 1f),
        blue = (a.blue * (1 - frac) + b.blue * frac).coerceIn(0f, 1f),
        alpha = 1f
    )
    fun mixB(c: Color, frac: Float) = mix(c, Color.Black, frac)
    fun mixW(c: Color, frac: Float) = mix(c, Color.White, frac)
    fun secondaryOf(c: Color) = mix(c, Color(0xFF808080), 0.28f) // 去饱和
    fun tertiaryOf(c: Color) = mix(c, Color(0xFF008080), 0.18f)  // 微转青色调

    val primary = seed
    val secondary = secondaryOf(seed)
    val tertiary = tertiaryOf(seed)
    return lightColorScheme(
        primary = primary,
        onPrimary = contrastColor(primary),
        primaryContainer = mixW(primary, 0.82f),
        onPrimaryContainer = contrastColor(mixW(primary, 0.82f)),
        secondary = secondary,
        onSecondary = contrastColor(secondary),
        secondaryContainer = mixW(secondary, 0.80f),
        onSecondaryContainer = contrastColor(mixW(secondary, 0.80f)),
        tertiary = tertiary,
        onTertiary = contrastColor(tertiary),
        tertiaryContainer = mixW(tertiary, 0.80f),
        onTertiaryContainer = contrastColor(mixW(tertiary, 0.80f)),
        background = mixW(seed, 0.92f),
        onBackground = Color(0xFF191C19),
        surface = mixW(seed, 0.92f),
        onSurface = Color(0xFF191C19),
        surfaceVariant = mixW(seed, 0.86f),
        onSurfaceVariant = Color(0xFF434A44),
        outline = mixB(mixW(seed, 0.6f), 0.35f),
    )
}

/** 深色配色：基于 seed 主色动态生成（深墨色调，非纯黑；OLED 纯黑由 oledDark 覆盖） */
fun kazumiDarkColorScheme(seed: Color): ColorScheme {
    fun mix(a: Color, b: Color, frac: Float) = Color(
        red = (a.red * (1 - frac) + b.red * frac).coerceIn(0f, 1f),
        green = (a.green * (1 - frac) + b.green * frac).coerceIn(0f, 1f),
        blue = (a.blue * (1 - frac) + b.blue * frac).coerceIn(0f, 1f),
        alpha = 1f
    )
    fun mixB(c: Color, frac: Float) = mix(c, Color.Black, frac)
    fun mixW(c: Color, frac: Float) = mix(c, Color.White, frac)
    fun secondaryOf(c: Color) = mix(c, Color(0xFF9E9E9E), 0.25f)
    fun tertiaryOf(c: Color) = mix(c, Color(0xFF3FBFAF), 0.15f)

    val primary = mixW(seed, 0.22f)        // 深色模式主色提亮
    val secondary = secondaryOf(seed)
    val tertiary = tertiaryOf(seed)
    val bg = mixB(mixW(seed, 0.10f), 0.72f) // 深墨背景带 seed 色调
    return darkColorScheme(
        primary = primary,
        onPrimary = contrastColor(primary),
        primaryContainer = mixB(seed, 0.55f),
        onPrimaryContainer = mixW(seed, 0.60f),
        secondary = secondary,
        onSecondary = contrastColor(secondary),
        secondaryContainer = mixB(secondary, 0.55f),
        onSecondaryContainer = mixW(secondary, 0.55f),
        tertiary = tertiary,
        onTertiary = contrastColor(tertiary),
        tertiaryContainer = mixB(tertiary, 0.55f),
        onTertiaryContainer = mixW(tertiary, 0.45f),
        background = bg,
        onBackground = Color(0xFFDDE9DF),
        surface = bg,
        onSurface = Color(0xFFDDE9DF),
        surfaceVariant = mixB(mixW(seed, 0.08f), 0.35f),
        onSurfaceVariant = Color(0xFFA8B8AB),
        outline = Color(0xFF718477),
    )
}

/** WCAG 相对亮度（0..1），用于选对比色（黄/青等亮主色配深字，暗主色配白字） */
private fun luminance(c: Color): Double {
    fun lin(ch: Float): Double {
        val v = ch.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/** 黑白对比色选择 */
private fun contrastColor(c: Color): Color = if (luminance(c) > 0.5) Color(0xFF002200) else Color.White

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
