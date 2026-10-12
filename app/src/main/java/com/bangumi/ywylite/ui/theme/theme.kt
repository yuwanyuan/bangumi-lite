package com.bangumi.ywylite.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

private val Pink = Color(0xFFE91E63)
private val PinkDark = Color(0xFFF48FB1)
private val PinkLight = Color(0xFFAD1457)

private val LightColors = lightColorScheme(
    primary = Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE4EC),
    onPrimaryContainer = PinkLight,
    secondary = Color(0xFF7B1FA2),
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = PinkDark,
    onPrimary = Color.Black,
    primaryContainer = PinkLight,
    onPrimaryContainer = Color(0xFFFCE4EC),
    secondary = Color(0xFFCE93D8),
    onSecondary = Color.Black,
)

/** 按色相/饱和度/明度改写颜色（保留种子色相，只调明度与彩度） */
private fun Color.tone(lightness: Float, saturationScale: Float = 1f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    return Color.hsl(hsl[0], (hsl[1] * saturationScale).coerceIn(0f, 1f), lightness)
}

/** 依据前景色亮度选黑/白，保证文字对比度 */
private fun readableOn(background: Color): Color =
    if (ColorUtils.calculateLuminance(background.toArgb()) > 0.5) Color.Black else Color.White

/**
 * 由壁纸种子色生成浅色配色。
 * 明度阶梯沿用 Material 3 的 tonal 思路（primary 中调、容器色浅、文字色深）。
 * 背景/表面刻意保留一点种子色彩度：纯白表面压在可见的壁纸上会割裂成生硬的白块，
 * 带淡色相才像是「跟随壁纸」的一套配色。
 */
private fun lightSchemeFromSeed(seed: Color): ColorScheme {
    val primary = seed.tone(0.42f)
    val primaryContainer = seed.tone(0.90f, 0.5f)
    val onPrimaryContainer = seed.tone(0.18f)
    return lightColorScheme(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = seed.tone(0.45f, 0.6f),
        onSecondary = Color.White,
        secondaryContainer = seed.tone(0.88f, 0.35f),
        onSecondaryContainer = seed.tone(0.20f),
        background = seed.tone(0.97f, 0.18f),
        onBackground = seed.tone(0.12f, 0.20f),
        surface = seed.tone(0.96f, 0.22f),
        onSurface = seed.tone(0.12f, 0.20f),
        surfaceVariant = seed.tone(0.90f, 0.28f),
        onSurfaceVariant = seed.tone(0.34f, 0.22f),
        outline = seed.tone(0.52f, 0.16f)
    )
}

/** 由壁纸种子色生成深色配色（primary 提亮、容器色压深，保证深底上的可读性） */
private fun darkSchemeFromSeed(seed: Color): ColorScheme {
    val primary = seed.tone(0.76f, 0.7f)
    val primaryContainer = seed.tone(0.30f, 0.7f)
    return darkColorScheme(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = seed.tone(0.92f, 0.5f),
        secondary = seed.tone(0.78f, 0.5f),
        onSecondary = Color.Black,
        secondaryContainer = seed.tone(0.32f, 0.5f),
        onSecondaryContainer = seed.tone(0.92f, 0.4f),
        background = seed.tone(0.12f, 0.22f),
        onBackground = seed.tone(0.92f, 0.10f),
        surface = seed.tone(0.16f, 0.26f),
        onSurface = seed.tone(0.92f, 0.10f),
        surfaceVariant = seed.tone(0.26f, 0.26f),
        onSurfaceVariant = seed.tone(0.80f, 0.12f),
        outline = seed.tone(0.58f, 0.14f)
    )
}

/**
 * @param wallpaperSeed 用户自选背景图提取的种子色；非空时整套配色由它派生
 *   （界面与文字颜色跟随壁纸，保证前景清晰），为空则维持原有品牌粉/动态取色
 */
@Composable
fun BangumiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    wallpaperSeed: Color? = null,
    content: @Composable () -> Unit
) {
    // 配色优先级：壁纸取色 > 系统动态取色（设置里显式开启） > 品牌粉主题
    val colorScheme = when {
        wallpaperSeed != null -> {
            if (darkTheme) darkSchemeFromSeed(wallpaperSeed) else lightSchemeFromSeed(wallpaperSeed)
        }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // statusBarColor 已废弃（API 35）：enableEdgeToEdge 下系统按深浅色自动处理状态栏
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
