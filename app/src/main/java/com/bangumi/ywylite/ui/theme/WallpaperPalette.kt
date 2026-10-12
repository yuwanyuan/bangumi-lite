package com.bangumi.ywylite.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** 从用户壁纸提取的取色结果 */
data class WallpaperPalette(
    /** 种子色：壁纸中最具代表性的鲜艳色，用于生成整套配色 */
    val seed: Color,
    /** 整图平均亮度（0=黑，1=白），供调用方判断壁纸明暗倾向 */
    val luminance: Float
)

/** 采样边长：48×48 足够统计主色，解码与遍历开销可忽略 */
private const val SAMPLE_SIZE = 48

/**
 * 从用户自选壁纸提取种子色与平均亮度。
 *
 * 做法（不引入 material-color-utilities 依赖，纯离线统计）：
 * 1. 解码后缩到 [SAMPLE_SIZE]² 采样；
 * 2. 按饱和度×中间调权重做 36 段色相直方图，取权重最高的色相作为主色相；
 * 3. 平均亮度用 ColorUtils.calculateLuminance 逐像素平均。
 *
 * 图片读不到（权限失效/文件被删）返回 null，调用方回落到原有品牌配色。
 */
suspend fun extractWallpaperPalette(context: Context, uri: Uri): WallpaperPalette? =
    withContext(Dispatchers.IO) {
        val source = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return@withContext null

        val bitmap = runCatching {
            if (source.width <= SAMPLE_SIZE && source.height <= SAMPLE_SIZE) source
            else Bitmap.createScaledBitmap(source, SAMPLE_SIZE, SAMPLE_SIZE, true)
        }.getOrNull() ?: source

        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return@withContext null

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (bitmap !== source) bitmap.recycle()
        source.recycle()

        val hsl = FloatArray(3)
        val hueWeight = FloatArray(36)
        var luminanceSum = 0.0
        var counted = 0

        pixels.forEach { pixel ->
            val alpha = (pixel ushr 24) and 0xFF
            // 全透明像素不参与统计（PNG 壁纸常见留白）
            if (alpha < 128) return@forEach
            luminanceSum += ColorUtils.calculateLuminance(pixel)
            counted++

            ColorUtils.colorToHSL(pixel, hsl)
            val saturation = hsl[1]
            val lightness = hsl[2]
            // 权重：越鲜艳越算数；过亮/过暗的像素参考价值低
            val weight = saturation * (1f - abs(lightness - 0.5f) * 1.6f)
            if (weight > 0f) {
                val bucket = (hsl[0] / 360f * 36f).toInt().coerceIn(0, 35)
                hueWeight[bucket] += weight
            }
        }

        if (counted == 0) return@withContext null

        val avgLuminance = (luminanceSum / counted).toFloat()
        val bestBucket = hueWeight.indices.maxByOrNull { hueWeight[it] } ?: return@withContext null
        val dominantHue = if (hueWeight[bestBucket] <= 0f) {
            // 整张图接近灰阶（无鲜艳色）：退化为平均色相，取不到就用品牌粉
            ColorUtils.colorToHSL(averageColor(pixels), hsl)
            hsl[0]
        } else {
            (bestBucket + 0.5f) * 10f
        }

        WallpaperPalette(
            seed = Color.hsl(dominantHue.coerceIn(0f, 360f), 0.55f, 0.5f),
            luminance = avgLuminance
        )
    }

/** 平均色：按不透明像素做算术平均，仅用于灰阶壁纸的兜底色相 */
private fun averageColor(pixels: IntArray): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0
    pixels.forEach { pixel ->
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 128) return@forEach
        r += (pixel shr 16) and 0xFF
        g += (pixel shr 8) and 0xFF
        b += pixel and 0xFF
        n++
    }
    if (n == 0) return 0xFFE91E63.toInt()
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}
