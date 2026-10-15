package com.bangumi.ywylite.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 过渡节奏：滑出快、滑入稍慢，位移约屏宽 28% */
private const val OUT_DURATION_MS = 140
private const val IN_DURATION_MS = 220
private const val SLIDE_SCREEN_FRACTION = 0.28f
private const val FADE_FROM = 0.35f

/**
 * 筛选切换的内容过渡（浏览页/收藏页的横滑与 tab、chip 点击共用）。
 *
 * 两段式：① 先把当前内容朝切换方向滑出——此时旧筛选的数据还在，滑走的就是用户刚看到的那一屏；
 * ② 再应用新筛选（列表回顶、进入加载态），让新内容从另一侧滑入并淡入。
 *
 * 不做「旧出 + 新入同时可见」：换筛选那一刻旧数据已从状态里换成加载态，要同时渲染两份列表
 * 就得让两个懒加载容器共用同一个 scroll state（会互相抢滚动位置），代价和风险都不值当。
 *
 * 同一时刻只应有一个切换在跑：调用方在再次切换前取消上一个任务（见两页的 selectXxx）。
 */
class FilterTransition internal constructor(
    private val slide: Animatable<Float, AnimationVector1D>,
    private val fade: Animatable<Float, AnimationVector1D>,
    private val offsetPx: Float
) {
    /**
     * 挂在内容容器上。位移与透明度在 graphicsLayer 的 lambda 里读，
     * 过渡期间只触发重绘（re-layer），不引起重组。
     */
    val modifier: Modifier
        get() = Modifier.graphicsLayer {
            translationX = slide.value
            alpha = fade.value
        }

    /**
     * 带过渡地切换筛选。
     * @param forward 沿序列向后（手势向左滑）：旧内容向左滑出、新内容从右侧进入。
     * @param apply 真正的切换动作（列表回顶 + 改筛选状态）。
     */
    suspend fun run(forward: Boolean, apply: suspend () -> Unit) {
        val direction = if (forward) -1f else 1f
        slide.animateTo(direction * offsetPx, tween(OUT_DURATION_MS, easing = FastOutLinearInEasing))
        fade.snapTo(FADE_FROM)
        apply()
        slide.snapTo(-direction * offsetPx)
        coroutineScope {
            launch { fade.animateTo(1f, tween(IN_DURATION_MS, easing = FastOutSlowInEasing)) }
            slide.animateTo(0f, tween(IN_DURATION_MS, easing = FastOutSlowInEasing))
        }
    }
}

@Composable
fun rememberFilterTransition(): FilterTransition {
    val offsetPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp * SLIDE_SCREEN_FRACTION).dp.toPx()
    }
    val slide = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    return remember(slide, fade, offsetPx) { FilterTransition(slide, fade, offsetPx) }
}
