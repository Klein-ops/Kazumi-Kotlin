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

/**
 * 浅色配色：与原版普通模式完全一致。
 * 原版 Flutter: ThemeData(useMaterial3: true, brightness: light, colorSchemeSeed: seed)
 * 本构建环境 ColorScheme.fromSeed 不可用，改用同源算法（@material/material-color-utilities）
 * 生成的显式色板 M3SeedPalettes，输出与原版逐值一致。
 */
fun kazumiLightColorScheme(seed: Color): ColorScheme = M3SeedPalettes.light(seed)

/**
 * 深色配色：与原版普通模式深色完全一致（非 OLED 纯黑，就是原版默认深色）。
 * 原版 Flutter: ThemeData(useMaterial3: true, brightness: dark, colorSchemeSeed: seed)
 */
fun kazumiDarkColorScheme(seed: Color): ColorScheme = M3SeedPalettes.dark(seed)

/**
 * OLED 深色纯黑覆盖（对应原版 oledDarkTheme）
 */
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
 * 补齐 M3SeedPalettes 显式色板中未设置的 surfaceContainer* 槽位。
 *
 * 背景：M3SeedPalettes 用 ColorScheme(...) 显式构造（构建环境 ColorScheme.fromSeed
 * 不可用），但显式构造只给了旧字段；Material3 1.2+ 新增的 surfaceContainerLow/
 * surfaceContainerHigh/surfaceContainerHighest 等槽位未赋值 → 默认 Color.Unspecified
 * （透明）。这会导致引用这些槽位的 UI（下拉刷新指示器、PlayerHud 时间气泡、
 * SearchBar 容器、Card 背景、RuleTag 等）渲染成透明/看不见。
 *
 * 这里的取值基于现有色板推导（对齐 Material3 LightColorScheme/DarkColorScheme 的
 * 默认推导公式），视觉上与官方默认一致：
 * - light:  surfaceContainerLowest=white, low=surface+2% blend, container=+4%, high=+8%, highest=+12%
 * - dark:   surfaceContainerLowest=black, low=surface-8% blend, container=-10%, high=-14%, highest=-18%
 * surfaceBright/surfaceDim 也一并补上（dark 模式 surface=surfaceDim）。
 */
private fun ColorScheme.completeSurfaces(): ColorScheme {
    val s = surface
    return copy(
        surfaceContainerLowest = if (surfaceContainerLowest == Color.Unspecified) {
            if (isLight()) Color.White else Color.Black
        } else surfaceContainerLowest,
        surfaceContainerLow = if (surfaceContainerLow == Color.Unspecified) {
            if (isLight()) blend(s, Color.White, 0.02f) else blend(s, Color.Black, 0.08f)
        } else surfaceContainerLow,
        surfaceContainer = if (surfaceContainer == Color.Unspecified) {
            if (isLight()) blend(s, Color.White, 0.04f) else blend(s, Color.Black, 0.10f)
        } else surfaceContainer,
        surfaceContainerHigh = if (surfaceContainerHigh == Color.Unspecified) {
            if (isLight()) blend(s, Color.White, 0.08f) else blend(s, Color.Black, 0.14f)
        } else surfaceContainerHigh,
        surfaceContainerHighest = if (surfaceContainerHighest == Color.Unspecified) {
            if (isLight()) blend(s, Color.White, 0.12f) else blend(s, Color.Black, 0.18f)
        } else surfaceContainerHighest,
        surfaceDim = if (surfaceDim == Color.Unspecified) {
            if (isLight()) blend(s, Color.Black, 0.06f) else s
        } else surfaceDim,
        surfaceBright = if (surfaceBright == Color.Unspecified) {
            if (isLight()) s else blend(s, Color.White, 0.06f)
        } else surfaceBright,
    )
}

/** 简单亮度判断：浅色方案 surface 亮度 > 深色方案 surface */
private fun ColorScheme.isLight(): Boolean = surface.luminance() > 0.5f

/** 两个颜色按 alpha 线性混合 */
private fun blend(a: Color, b: Color, amount: Float): Color {
    val inv = 1f - amount
    return Color(
        red = a.red * inv + b.red * amount,
        green = a.green * inv + b.green * amount,
        blue = a.blue * inv + b.blue * amount,
        alpha = a.alpha * inv + b.alpha * amount
    )
}

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
    // 补齐 M3SeedPalettes 显式色板缺失的 surfaceContainer*/surfaceBright/surfaceDim 槽位
    // （默认 Unspecified 透明，会导致引用这些槽位的 UI 看不见）
    val finalScheme = effectiveScheme.completeSurfaces()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = finalScheme,
        content = content,
    )
}
