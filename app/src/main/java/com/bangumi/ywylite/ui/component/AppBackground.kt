package com.bangumi.ywylite.ui.component

import android.net.Uri
import androidx.compose.foundation.background
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
 * 蒙版：[maskAlpha] 为百分比（滑块 0–100 连续可调）。遮罩色固定用黑色——不透明度就是
 * 「压黑多少」，不按深浅主题换成白色（浅色模式提白会让深色区变成灰雾、亮部发白，
 * 前景文字对比度反而更差）。为了「整屏融合成一个平面」的效果，蒙版上限压得比原先低
 * （最高 0.6），让图片的深色区域也能透出来，而不是被糊成一片灰。
 */
@Composable
fun AppBackground(
    customUri: String?,
    maskAlpha: Int,
    modifier: Modifier = Modifier
) {
    val uri = customUri?.let { Uri.parse(it) } ?: return

    val context = LocalContext.current
    // 上限 0.6：过高会把整张图（尤其深色区）压成死板的灰面，失去「平面融合」感
    val finalAlpha = (maskAlpha / 100f * 0.6f).coerceIn(0f, 0.6f)

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
        // 蒙版层：黑色纵向渐变，中间稍强、上下略弱，兼顾可读性与背景可见度
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = finalAlpha * 0.75f),
                            Color.Black.copy(alpha = finalAlpha),
                            Color.Black.copy(alpha = finalAlpha * 0.9f)
                        )
                    )
                )
        )
    }
}
