package com.bangumi.ywylite.ui.component

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 过渡节奏：跟手弹回 200ms、滑出 140ms、滑入 220ms；翻页距离约屏宽 28% */
private const val OUT_DURATION_MS = 140
private const val IN_DURATION_MS = 220
private const val SETTLE_DURATION_MS = 200
private const val SLIDE_SCREEN_FRACTION = 0.28f
private const val FADE_FROM = 0.35f
/** 超出翻页距离后继续跟手的阻尼比 */
private const val DRAG_RESISTANCE = 0.25f

/**
 * 筛选切换的内容过渡（浏览页 / 收藏页 / 每日放送共用）。
 *
 * 三种用法：
 * - 手势跟手：[drag] 直接按手指位移平移内容，不用等松手。
 * - 松手未达阈值：[settleBack] 弹回原位。
 * - 达阈值或点 tab/chip：[switchTo] 把当前内容沿方向推出 → 应用新筛选 → 新内容从另一侧滑入淡入。
 *   跟手松手后调用时从当前手指位置继续推，所以动作连贯，不会先弹回去再滑出。
 *
 * 位置与透明度都是普通 float 状态，在 graphicsLayer 的 lambda 里读：过渡期间只重绘不重组。
 */
class FilterTransition internal constructor(
    private val travelPx: Float,
    private val scope: CoroutineScope
) {
    private val offset = mutableFloatStateOf(0f)
    private val fade = mutableFloatStateOf(1f)
    private var animJob: Job? = null

    val modifier: Modifier
        get() = Modifier.graphicsLayer {
            translationX = offset.floatValue
            alpha = fade.floatValue
        }

    /** 手势跟手：内容 1:1 跟手指走，超过翻页距离后加阻尼；同时轻微淡出作为翻页反馈 */
    fun drag(totalPx: Float) {
        animJob?.cancel()
        offset.floatValue = withResistance(totalPx)
        fade.floatValue = (1f - abs(offset.floatValue) / (travelPx * 4f)).coerceIn(0.7f, 1f)
    }

    /** 松手但不切页：弹回原位 */
    fun settleBack() {
        animJob?.cancel()
        animJob = scope.launch {
            coroutineScope {
                launch {
                    animate(
                        offset.floatValue, 0f,
                        animationSpec = tween(SETTLE_DURATION_MS, easing = FastOutSlowInEasing)
                    ) { value, _ -> offset.floatValue = value }
                }
                launch {
                    animate(
                        fade.floatValue, 1f,
                        animationSpec = tween(SETTLE_DURATION_MS, easing = FastOutSlowInEasing)
                    ) { value, _ -> fade.floatValue = value }
                }
            }
        }
    }

    /**
     * 切换筛选。
     * @param forward 沿序列向后（手势向左滑）：当前内容向左推出，新内容从右侧进入。
     * @param apply 真正的切换动作（列表回顶 + 改筛选状态）。这里只做状态变更，不发起加载等待，
     *   否则新内容会在「加载中」的位置停住。
     */
    suspend fun switchTo(forward: Boolean, apply: suspend () -> Unit) {
        animJob?.cancel()
        val direction = if (forward) -1f else 1f
        animate(
            offset.floatValue, direction * travelPx,
            animationSpec = tween(OUT_DURATION_MS, easing = FastOutLinearInEasing)
        ) { value, _ -> offset.floatValue = value }
        fade.floatValue = FADE_FROM
        apply()
        offset.floatValue = -direction * travelPx
        coroutineScope {
            launch {
                animate(
                    fade.floatValue, 1f,
                    animationSpec = tween(IN_DURATION_MS, easing = FastOutSlowInEasing)
                ) { value, _ -> fade.floatValue = value }
            }
            launch {
                animate(
                    offset.floatValue, 0f,
                    animationSpec = tween(IN_DURATION_MS, easing = FastOutSlowInEasing)
                ) { value, _ -> offset.floatValue = value }
            }
        }
    }

    /** 翻页距离之外按 1/4 速率继续跟手，避免手指一划就把内容甩到天边 */
    private fun withResistance(px: Float): Float = when {
        px > travelPx -> travelPx + (px - travelPx) * DRAG_RESISTANCE
        px < -travelPx -> -travelPx + (px + travelPx) * DRAG_RESISTANCE
        else -> px
    }
}

@Composable
fun rememberFilterTransition(): FilterTransition {
    val scope = rememberCoroutineScope()
    val travelPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp * SLIDE_SCREEN_FRACTION).dp.toPx()
    }
    return remember(scope, travelPx) { FilterTransition(travelPx, scope) }
}
