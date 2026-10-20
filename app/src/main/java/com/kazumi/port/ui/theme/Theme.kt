package com.kazumi.port.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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

/** 浅色配色：官方 M3 调色算法 ColorScheme.fromSeed（与 Flutter fromSeed 同源于 material-color-utilities，色值逐字节一致） */
@Composable
fun kazumiLightColorScheme(seed: Color): ColorScheme =
    ColorScheme.fromSeed(seedColor = seed, darkColorScheme = false)

/** 深色配色：官方 M3 调色算法生成带 seed 色相的深色色板（原版深色背景的绿调由此而来，不是纯黑也不是提亮荧光绿） */
@Composable
fun kazumiDarkColorScheme(seed: Color): ColorScheme =
    ColorScheme.fromSeed(seedColor = seed, darkColorScheme = true)

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
