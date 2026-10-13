package com.bangumi.ywylite.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeSource

/**
 * 壁纸毛玻璃上下文：携带共享的 [HazeState] 与是否有壁纸。
 *
 * Haze 的工作方式：背景层（壁纸）用 `Modifier.hazeSource(state)` 标记为采样源，
 * 前景控件用 `Modifier.hazeChild(state) { ... }` 采样背后像素做实时模糊。
 * 滚动时毛玻璃里的画面跟着动，是真 backdrop blur（不是再画一遍壁纸模糊它）。
 *
 * [hazeState] 由 [com.bangumi.ywylite.ui.BangumiApp] 创建并注入：AppBackground 标为 source，
 * 底栏与设置类页面标为 child。详情页不参与（自带封面背景，不走壁纸路径）。
 */
data class WallpaperBlurScope(
    val hasWallpaper: Boolean = false,
    val hazeState: HazeState? = null,
    /** 0–100 档位，映射成 blurRadius（dp） */
    val blurAmount: Int = 0
)

val LocalWallpaperBlur = staticCompositionLocalOf { WallpaperBlurScope() }

/** 0–100 档位 → blurRadius dp。三级 0/50/100 → 0/16/32dp */
private fun Int.toBlurRadius(): Float = this / 100f * 32f

/**
 * 标记背景层为 Haze 采样源。仅在设置了壁纸时生效。
 * 用在 AppBackground（壁纸图）上，让 hazeChild 能采样到它。
 */
fun Modifier.hazeWallpaperSource(scope: WallpaperBlurScope): Modifier {
    if (!scope.hasWallpaper || scope.hazeState == null) return this
    return this.hazeSource(scope.hazeState)
}

/**
 * 返回一个在前景控件上叠加毛玻璃的 Modifier：采样 hazeSource 捕获的背景像素做模糊 + 压一层中性遮罩。
 *
 * - blurRadius 由 [WallpaperBlurScope.blurAmount] 换算；0 时不模糊但仍铺遮罩保证文字可读。
 * - 遮罩（tint）按深浅主题取黑/白半透明，与 AppBackground 蒙版同思路：中性色压得住任意壁纸。
 * - Haze 在 API 31+ 用 RenderEffect 硬件加速，低版本自动降级为纯 tint（scrim），无需手动兼容。
 *
 * 这是 @Composable 因为要读 [LocalWallpaperBlur]。调用方用 `Modifier.then(wallpaperGlass(...))`。
 */
@Composable
fun wallpaperGlass(darkTheme: Boolean): Modifier {
    val scope = LocalWallpaperBlur.current
    if (!scope.hasWallpaper || scope.hazeState == null) return Modifier
    val radius = scope.blurAmount.toBlurRadius().dp
    val tintColor = if (darkTheme) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.6f)
    return Modifier.hazeChild(scope.hazeState) {
        blurRadius = radius
        // Haze 1.x 要求必须给 backgroundColor（不透明底），否则绘制时直接
        // IllegalArgumentException: "backgroundColor not specified"
        backgroundColor = if (darkTheme) Color.Black else Color.White
        tints = listOf(HazeTint(tintColor))
        // API < 31 无 RenderEffect 时走纯遮罩降级
        fallbackTint = HazeTint(tintColor)
    }
}

/** 创建 Haze 状态；用 remember 保持跨重组稳定 */
@Composable
fun rememberWallpaperHazeState(): HazeState = remember { HazeState() }
