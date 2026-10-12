package com.bangumi.ywylite.ui.component

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.SubjectSmall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主界面背景图：按设置图源渲染，上压蒙版遮罩。
 * 作用范围由调用方控制（BangumiApp 在除详情外的页面外层挂载）。
 *
 * 图源：
 * - none：不渲染（返回空），走主题纯色背景
 * - calendar：每日放送日历返回的条目封面，取首张做主背景（每日自动更新）
 * - explore：浏览页当前类型热门条目封面，取首张
 * - custom：用户从相册选的固定图（URI 持久化）
 *
 * 蒙版：用 background 颜色做纵向渐变 + 按 alpha 叠加纯色层，保证前景文字可读。
 * 档位 light/medium/dark 对应不同基础 alpha，滑块在此基础上微调。
 */
@Composable
fun AppBackground(
    source: String,
    maskPreset: String,
    maskAlpha: Int,
    customUri: String?,
    modifier: Modifier = Modifier
) {
    if (source == "none") return

    val context = LocalContext.current
    val app = App.INSTANCE
    var backgroundImageUri by remember { mutableStateOf<Uri?>(null) }

    // 按图源解析背景图 URI：calendar/explore 走网络拉封面，custom 直接用本地 URI
    LaunchedEffect(source, customUri) {
        backgroundImageUri = null
        val resolved: Uri? = when (source) {
            "calendar" -> runCatching {
                val calendar = withContext(Dispatchers.IO) { app.api.getCalendar() }
                pickFirstCover(calendar)?.let { Uri.parse(it) }
            }.getOrNull()
            "explore" -> runCatching {
                val page = withContext(Dispatchers.IO) {
                    app.api.browseSubjectsByTrend(type = 2, offset = 0)
                }
                pickFirstCoverFromSubjects(page.data)?.let { Uri.parse(it) }
            }.getOrNull()
            "custom" -> customUri?.let { Uri.parse(it) }
            else -> null
        }
        backgroundImageUri = resolved
    }

    val bgUri = backgroundImageUri ?: return

    // 蒙版基础 alpha：档位决定下限，滑块在 0–100 范围内微调
    val baseAlpha = when (maskPreset) {
        "light" -> 0.35f
        "dark" -> 0.85f
        else -> 0.6f
    }
    val finalAlpha = (baseAlpha * maskAlpha / 100f).coerceIn(0f, 0.97f)
    val bgColor = Color.Black

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(bgUri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        // 纯色蒙版层：按 finalAlpha 压暗背景图，保证前景内容可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            bgColor.copy(alpha = finalAlpha * 0.7f),
                            bgColor.copy(alpha = finalAlpha),
                            bgColor.copy(alpha = finalAlpha * 0.9f)
                        )
                    )
                )
        )
    }
}

/** 从日历数据取第一张可用封面 URL（按周一到周日顺序） */
private fun pickFirstCover(calendar: List<com.bangumi.ywylite.data.model.CalendarDay>): String? =
    calendar.asSequence()
        .flatMap { it.items.asSequence() }
        .firstNotNullOfOrNull { it.coverUrl() }

/** 从条目列表取第一张可用封面 URL */
private fun pickFirstCoverFromSubjects(subjects: List<SubjectSmall>): String? =
    subjects.asSequence().firstNotNullOfOrNull { it.coverUrl() }

private fun SubjectSmall.coverUrl(): String? =
    ImageUrls.cover(
        images?.common ?: images?.medium ?: image ?: images?.large,
        ImageUrls.GRID
    ).takeIf { it.isNotBlank() }
