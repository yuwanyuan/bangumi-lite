package com.bangumi.ywylite.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

/** 把颜色明度整体推一档：[steps] 为正变亮、为负变暗，每档约 0.12 明度 */
private fun Color.shiftLightness(steps: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)
    return Color.hsl(hsl[0], hsl[1], (hsl[2] + steps * 0.12f).coerceIn(0f, 1f))
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
 * 实际生效的深浅色（已计入「有背景图即固定深色」）。
 *
 * 屏幕内部一律用它判断深浅，不要再调 isSystemInDarkTheme()：后者只反映系统设置，
 * 与设置页选的浅色/深色、以及背景图强制的深色都可能不一致，
 * 毛玻璃 tint 会按相反的一套取色（深色配色下压白色薄雾）。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 配色只由应用内壁纸驱动：设了壁纸就用它提取的种子色派生深色配色，
 * 没设壁纸就固定走品牌粉/品牌深色主题。
 *
 * 不用系统的 dynamicLightColorScheme/dynamicDarkColorScheme：它们跟随「系统壁纸」
 * 取色，前景色可能与实际呈现的底色不同步，出现文字看不见的问题。
 *
 * 有壁纸即固定深色：深色配色的前景/玻璃罩色才是为壁纸准备的那一套（用户确认的观感），
 * 因此调用方（MainActivity）在设了背景图时直接传 darkTheme=true——
 * 用它判定的墙纸 URI 立即可用，比种子色提取完成早，换背景时不闪浅色。
 * 这里再兜一层：种子色在而 darkTheme 意外为 false 时仍走深色配色。
 *
 * [wallpaperLuminance] 为壁纸平均亮度：据此把整套前景文字往深/往浅推一档。
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
        wallpaperSeed != null -> darkSchemeFromSeed(wallpaperSeed, textShift)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // 无色 Text 用 LocalContentColor 而非 colorScheme.onSurface；M3 的 MaterialTheme
        // 不提供它（由 Surface 提供），普通 Box/Column 里的 Text 会回落到默认黑色——
        // 深色模式下出现「黑底黑字」。这里全局对齐到 onBackground，普通文字随主题变色。
        content = {
            CompositionLocalProvider(
                LocalContentColor provides colorScheme.onBackground,
                LocalDarkTheme provides darkTheme,
                content = content
            )
        }
    )
}
