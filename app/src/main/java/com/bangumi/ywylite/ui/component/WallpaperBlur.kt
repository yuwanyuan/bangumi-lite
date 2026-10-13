package com.bangumi.ywylite.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeSource

/**
 * 壁纸毛玻璃上下文：携带共享的 [HazeState]、是否有壁纸、模糊强度。
 *
 * Haze 的工作方式：背景层（壁纸）用 `Modifier.hazeSource(state)` 标记为采样源，
 * 前景控件用 `Modifier.hazeChild(state) { ... }` 采样背后像素做实时模糊。
 *
 * 两类玻璃：
 * - 页底玻璃（[wallpaperGlass]）：设置/账号等整页内容层，弱模糊，让页面与壁纸融合。
 * - 面板玻璃（[wallpaperGlassPanel]）：设置页的选项块（卡片），更强模糊 + 圆角 + 更实的底，
 *   与选项外区分开。
 */
data class WallpaperBlurScope(
    val hasWallpaper: Boolean = false,
    val hazeState: HazeState? = null,
    /** 0–100 档位，映射成页底玻璃的 blurRadius（dp） */
    val blurAmount: Int = 0
)

val LocalWallpaperBlur = staticCompositionLocalOf { WallpaperBlurScope() }

/** 页底玻璃：0–100 → 0–20dp（弱，保持整页通透融合） */
private fun Int.toPageBlurRadius(): Float = this / 100f * 20f

/** 面板玻璃：固定更强，比页底明显，让选项块浮起来 */
private const val PANEL_BLUR_DP = 48f

/**
 * 标记背景层为 Haze 采样源。仅在设置了壁纸时生效。
 * 用在 AppBackground（壁纸图）上，让 hazeChild 能采样到它。
 */
fun Modifier.hazeWallpaperSource(scope: WallpaperBlurScope): Modifier {
    if (!scope.hasWallpaper || scope.hazeState == null) return this
    return this.hazeSource(scope.hazeState)
}

/**
 * 页底玻璃：设置/账号/缓存/代理等整页内容层的背景。
 * 弱模糊 + 轻遮罩，让页面内容与壁纸融成一个平面。
 */
@Composable
fun wallpaperGlass(darkTheme: Boolean): Modifier {
    val scope = LocalWallpaperBlur.current
    if (!scope.hasWallpaper || scope.hazeState == null) return Modifier
    val radius = scope.blurAmount.toPageBlurRadius().dp
    val tintColor = if (darkTheme) Color.Black.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.35f)
    return Modifier.hazeChild(scope.hazeState) {
        blurRadius = radius
        // 透明底：让模糊后的壁纸完整透出（Haze 只要求「已指定」而非不透明）
        backgroundColor = Color.Transparent
        tints = listOf(HazeTint(tintColor))
        fallbackTint = HazeTint(tintColor)
    }
}

/**
 * 面板玻璃：设置页的选项块。更强模糊 + 圆角 + 淡遮罩，与选项外区域明显区分。
 * 返回的 Modifier 已带圆角裁剪，调用方直接铺在选项块外层即可。
 * （Haze 1.5.3 的 hazeChild 无 shape 参数，圆角用 Modifier.clip 实现）
 */
@Composable
fun wallpaperGlassPanel(darkTheme: Boolean): Modifier {
    val scope = LocalWallpaperBlur.current
    if (!scope.hasWallpaper || scope.hazeState == null) return Modifier
    // tint 压到很淡：面板要的是「糊开的壁纸透出来」，不是一块实心板
    val tintColor = if (darkTheme) Color.Black.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.22f)
    return Modifier
        .clip(RoundedCornerShape(18.dp))
        .hazeChild(scope.hazeState) {
            blurRadius = PANEL_BLUR_DP.dp
            backgroundColor = Color.Transparent
            tints = listOf(HazeTint(tintColor))
            fallbackTint = HazeTint(tintColor)
        }
}

/** 创建 Haze 状态；用 remember 保持跨重组稳定 */
@Composable
fun rememberWallpaperHazeState(): HazeState = remember { HazeState() }
