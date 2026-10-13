package com.bangumi.ywylite.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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

/** 把颜色明度整体推一档：[steps] 为正变亮、为负变暗，每档约 0.12 明度 */
private fun Color.shiftLightness(steps: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    return Color.hsl(hsl[0], hsl[1], (hsl[2] + steps * 0.12f).coerceIn(0f, 1f))
}

/**
 * 由壁纸种子色生成浅色配色。
 *
 * [textShift] 用于把整套前景文字明度整体推一档：壁纸偏亮时往深推（正文字更黑），
 * 壁纸偏暗时往浅推，保证文字在壁纸上始终比背景「更极端」，拉开对比。
 */
private fun lightSchemeFromSeed(seed: Color, textShift: Float): ColorScheme {
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
        onBackground = seed.tone(0.12f, 0.20f).shiftLightness(textShift),
        surface = seed.tone(0.96f, 0.22f),
        onSurface = seed.tone(0.12f, 0.20f).shiftLightness(textShift),
        surfaceVariant = seed.tone(0.90f, 0.28f),
        onSurfaceVariant = seed.tone(0.34f, 0.22f).shiftLightness(textShift),
        outline = seed.tone(0.52f, 0.16f)
    )
}

/** 由壁纸种子色生成深色配色（primary 提亮、容器色压深，保证深底上的可读性） */
private fun darkSchemeFromSeed(seed: Color, textShift: Float): ColorScheme {
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
        onBackground = seed.tone(0.92f, 0.10f).shiftLightness(textShift),
        surface = seed.tone(0.16f, 0.26f),
        onSurface = seed.tone(0.92f, 0.10f).shiftLightness(textShift),
        surfaceVariant = seed.tone(0.26f, 0.26f),
        onSurfaceVariant = seed.tone(0.80f, 0.12f).shiftLightness(textShift),
        outline = seed.tone(0.58f, 0.14f)
    )
}

/**
 * 依据壁纸平均亮度决定文字推档方向：
 * 壁纸偏亮（luminance 高）→ 文字往深推一档（负）；偏暗 → 往浅推一档（正）。
 * 中间调不动，避免来回跳。
 */
private fun textShiftFor(wallpaperLuminance: Float?): Float = when {
    wallpaperLuminance == null -> 0f
    wallpaperLuminance > 0.6f -> -1f
    wallpaperLuminance < 0.4f -> 1f
    else -> 0f
}

/**
 * 给整套排版加黑色描边（阴影实现）：文字直接压在壁纸照片上时，
 * 纯色字在花纹/亮暗交界处会糊掉，加一圈黑边后任意背景都可读。
 * 只有设置了壁纸时才加；详情页与条目列表通过各自的主题覆盖掉（见 withTextOutline=false）。
 */
private fun Typography.withTextOutline(enabled: Boolean, blurPx: Float): Typography {
    if (!enabled) return this
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = blurPx)
    fun TextStyle.sh(): TextStyle = copy(shadow = shadow)
    return Typography(
        displayLarge = displayLarge.sh(), displayMedium = displayMedium.sh(), displaySmall = displaySmall.sh(),
        headlineLarge = headlineLarge.sh(), headlineMedium = headlineMedium.sh(), headlineSmall = headlineSmall.sh(),
        titleLarge = titleLarge.sh(), titleMedium = titleMedium.sh(), titleSmall = titleSmall.sh(),
        bodyLarge = bodyLarge.sh(), bodyMedium = bodyMedium.sh(), bodySmall = bodySmall.sh(),
        labelLarge = labelLarge.sh(), labelMedium = labelMedium.sh(), labelSmall = labelSmall.sh()
    )
}

/**
 * 配色只由应用内壁纸驱动：设了壁纸就用它提取的种子色派生整套配色，
 * 没设壁纸就固定走品牌粉/品牌深色主题。
 *
 * 不用系统的 dynamicLightColorScheme/dynamicDarkColorScheme：它们跟随「系统壁纸」
 * 取色，前景色可能与实际呈现的底色不同步，出现文字看不见的问题。
 *
 * [wallpaperLuminance] 为壁纸平均亮度：据此把整套前景文字往深/往浅推一档，
 * 并决定是否给文字加黑色描边（有壁纸才加）。
 */
@Composable
fun BangumiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    wallpaperSeed: Color? = null,
    wallpaperLuminance: Float? = null,
    content: @Composable () -> Unit
) {
    val textShift = textShiftFor(wallpaperLuminance)
    val colorScheme = when {
        wallpaperSeed != null -> {
            if (darkTheme) darkSchemeFromSeed(wallpaperSeed, textShift)
            else lightSchemeFromSeed(wallpaperSeed, textShift)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val hasWallpaper = wallpaperSeed != null
    val typography = MaterialTheme.typography.withTextOutline(hasWallpaper, blurPx = 3.5f)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        // 无色 Text 用 LocalContentColor 而非 colorScheme.onSurface；M3 的 MaterialTheme
        // 不提供它（由 Surface 提供），普通 Box/Column 里的 Text 会回落到默认黑色——
        // 深色模式下出现「黑底黑字」。这里全局对齐到 onBackground，普通文字随主题变色。
        content = {
            CompositionLocalProvider(
                LocalContentColor provides colorScheme.onBackground,
                content = content
            )
        }
    )
}

/**
 * 关掉文字描边的主题包装：详情页（自带深色封面背景）与条目列表文字
 * 按需求保持原样，用它在局部还原不带描边的排版。
 */
@Composable
fun WithoutTextOutline(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val clean = base.withTextOutline(enabled = false, blurPx = 0f)
    MaterialTheme(typography = clean) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}
