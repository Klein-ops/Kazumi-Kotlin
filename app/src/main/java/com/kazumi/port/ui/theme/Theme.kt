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

/** 浅色配色：M3 风格绿色方案（fromSeed 算法在 CodeAssist 的旧 material3 上无法编译，改用等价的显式色板） */
fun kazumiLightColorScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),            // M3 浅色主绿
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5D6A7),
    onPrimaryContainer = Color(0xFF002200),
    secondary = Color(0xFF4E8A55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEED0),
    onSecondaryContainer = Color(0xFF0A2F10),
    tertiary = Color(0xFF3F7A6A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2F2E3),
    onTertiaryContainer = Color(0xFF00241B),
    background = Color(0xFFF4FBF4),         // 带绿调的浅背景
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFF4FBF4),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDCE8DD),
    onSurfaceVariant = Color(0xFF434A44),
    outline = Color(0xFF738074),
)

/** 深色配色：M3 风格深色绿方案——主色柔和（非荧光），背景深墨绿（不是纯黑），对齐原版观感 */
fun kazumiDarkColorScheme(seed: Color): ColorScheme = darkColorScheme(
    primary = Color(0xFF8BC98E),            // M3 深色主绿（tone80 柔和绿，非提亮荧光绿）
    onPrimary = Color(0xFF002E16),          // 深绿
    primaryContainer = Color(0xFF1C5C29),
    onPrimaryContainer = Color(0xFFB8F5BE),
    secondary = Color(0xFF9CD3A1),
    onSecondary = Color(0xFF0A3B15),
    secondaryContainer = Color(0xFF2A5C33),
    onSecondaryContainer = Color(0xFFC9F0CC),
    tertiary = Color(0xFF81C9B0),
    onTertiary = Color(0xFF00382B),
    tertiaryContainer = Color(0xFF1E5C4A),
    onTertiaryContainer = Color(0xFFB2F0DC),
    background = Color(0xFF17271B),         // 深墨绿背景（肉眼可见的绿调，非纯黑）
    onBackground = Color(0xFFDDE9DF),
    surface = Color(0xFF17271B),
    onSurface = Color(0xFFDDE9DF),
    surfaceVariant = Color(0xFF2A3A2E),
    onSurfaceVariant = Color(0xFFA8B8AB),
    outline = Color(0xFF718477),
)

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
