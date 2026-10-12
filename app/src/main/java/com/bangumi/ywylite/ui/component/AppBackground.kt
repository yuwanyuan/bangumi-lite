package com.bangumi.ywylite.ui.component

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 主界面背景图：用户从相册自选的固定图片（URI 持久化），上压蒙版遮罩。
 * 作用范围由调用方控制（BangumiApp 在除详情外的页面外层挂载）。
 *
 * 蒙版：[maskAlpha] 为百分比（档位预设即写入该值，滑块可再微调）。遮罩色用中性的
 * 白/黑而非壁纸派生色——后者会随壁纸色相偏移，把整屏染成同一色系且压不住花纹，
 * 前景文字对比度不可控。中性遮罩下浅色主题提亮、深色主题压暗，任何壁纸上都保证可读。
 */
@Composable
fun AppBackground(
    customUri: String?,
    maskAlpha: Int,
    modifier: Modifier = Modifier
) {
    val uri = customUri?.let { Uri.parse(it) } ?: return

    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val maskColor = if (darkTheme) Color.Black else Color.White
    val finalAlpha = (maskAlpha / 100f).coerceIn(0f, 0.97f)

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // 蒙版层：中间稍强、上下略弱，兼顾可读性与背景可见度
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            maskColor.copy(alpha = finalAlpha * 0.75f),
                            maskColor.copy(alpha = finalAlpha),
                            maskColor.copy(alpha = finalAlpha * 0.9f)
                        )
                    )
                )
        )
    }
}
