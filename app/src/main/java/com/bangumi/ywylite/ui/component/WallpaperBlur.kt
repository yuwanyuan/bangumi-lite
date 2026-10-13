package com.bangumi.ywylite.ui.component

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 壁纸模糊上下文：携带当前背景模糊强度（0–100）与「是否有壁纸」。
 * 有壁纸时由 [com.bangumi.ywylite.ui.BangumiApp] 注入非零值，底栏与控件页面据此叠加高斯模糊 + 半透明遮罩，
 * 让壁纸在控件背后呈现毛玻璃效果；详情页不参与（自带封面背景）。
 */
data class WallpaperBlurScope(
    /** 0–100，0 表示无壁纸或关闭模糊 */
    val blurAmount: Int = 0,
    /** 是否设置了壁纸（决定是否启用模糊路径） */
    val hasWallpaper: Boolean = false
)

val LocalWallpaperBlur = staticCompositionLocalOf { WallpaperBlurScope() }

/**
 * 把 0–100 的档位映射成实际模糊半径（dp）。
 * 三级档位 0/50/100 对应 0/12/24dp：12dp 已能看出明显毛玻璃，24dp 接近完全虚化。
 */
private fun Int.toBlurRadius(): Dp = (this / 100f * 24f).dp

/**
 * 在有壁纸时给底栏/控件页面叠加「高斯模糊 + 半透明遮罩」背景的 Modifier。
 * - 模糊用 Modifier.blur，仅 API 31+ 生效（低于 31 退化为纯遮罩，仍保证文字可读）。
 * - 遮罩色按深浅主题取黑/白半透明，与 [AppBackground] 的蒙版同思路：中性色压得住任意壁纸。
 *
 * 这是一个 @Composable 函数（返回 Modifier），因为要读 [LocalWallpaperBlur]。
 * 调用方在 composable 里 `Modifier.then(wallpaperScrim(darkTheme))` 使用。
 */
@Composable
fun wallpaperScrim(darkTheme: Boolean): Modifier {
    val scope = LocalWallpaperBlur.current
    if (!scope.hasWallpaper) return Modifier
    val radius = scope.blurAmount.toBlurRadius()
    val scrimColor = if (darkTheme) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.55f)
    val withBlur = if (radius > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.blur(radius)
    } else {
        Modifier
    }
    return withBlur.drawBehind {
        drawRect(scrimColor)
    }
}
