package com.bangumi.ywylite.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 过渡节奏：未达阈值弹回 200ms、翻页 200–340ms（行程越短、松手越快越短） */
private const val SETTLE_DURATION_MS = 200
private const val PAGE_MIN_MS = 200
private const val PAGE_MAX_MS = 340

/** 用松手速度估算翻页时长时的上下限（px/s），避免慢悠悠拖尾或一帧闪走 */
private const val MIN_SWIPE_VELOCITY = 900f
private const val MAX_SWIPE_VELOCITY = 4000f

/**
 * 翻页过渡（浏览页 / 收藏页 / 每日放送共用）：页面之间始终相邻，翻页过程中屏幕永远被两页填满。
 *
 * 手势和动画都只改 [offset] 一个值：
 * - 当前页按 offset 平移（[FilterPager] 的 current 槽）
 * - 左边一页 = offset - 页宽，右边一页 = offset + 页宽（prev / next 槽）
 *
 * 这样手指拖到哪，相邻页的边就跟到哪，中间不会露出空白；翻页动画把 offset 补到 ±页宽时，
 * 相邻页正好落在屏幕正中，此时才 [apply] 换筛选并把 offset 复位——「换内容」发生在屏幕外，
 * 而且换之前屏幕上那页（相邻页，数据来自缓存）与换之后（当前页）是同一份数据，所以看不见切换。
 *
 * 位置是普通 float 状态，在 graphicsLayer 的 lambda 里读：过渡期间只重绘不重组。
 */
class FilterTransition internal constructor(
    private val scope: CoroutineScope
) {
    /** 一页的宽度（px）：由 [FilterPager] 按容器实测宽度写入（容器不一定是屏宽） */
    internal var pageWidthPx = 0f

    private val offsetState = mutableFloatStateOf(0f)
    private var animJob: Job? = null

    /** 本次跟手的起点：接管时从动画停住的位置接着拖，内容不会跳一下 */
    private var dragBase = 0f

    /** 当前页的位移（px，负 = 内容向左移，即向后翻一页） */
    val offset: Float get() = offsetState.floatValue

    /** 是否处在翻页过程中（拖动中或动画中）：只有这时才需要渲染相邻页 */
    val engaged: Boolean get() = offsetState.floatValue != 0f

    /**
     * 手指按下接管：取消进行中的动画（否则动画和手指会同时写位移，互相打架），
     * 并把当前停住的位置记为本次跟手的起点——内容从它此刻所在的位置继续被拖，不会跳一下。
     */
    fun takeOver() {
        animJob?.cancel()
        animJob = null
        dragBase = offsetState.floatValue
    }

    /** 手势跟手：1:1 跟手指走，最多拖满一页（再多就是序列边界，硬边界由 coerceIn 兜住） */
    fun drag(totalPx: Float) {
        val width = pageWidthPx
        offsetState.floatValue = (dragBase + totalPx).coerceIn(-width, width)
    }

    /** 松手但不切页：弹回原位 */
    fun settleBack() {
        animJob?.cancel()
        animJob = scope.launch {
            animate(
                offsetState.floatValue, 0f,
                animationSpec = tween(SETTLE_DURATION_MS, easing = FastOutSlowInEasing)
            ) { value, _ -> offsetState.floatValue = value }
        }
    }

    /**
     * 翻页。
     *
     * @param forward 沿序列向后（手势向左滑）：当前页向左移出，右边那页移到正中。
     * @param velocityPxPerSec 松手瞬间的手指速度，点 tab/chip 触发时传 0。
     * @param apply 换筛选的动作（列表回顶 + 改筛选状态 + 写入新筛选已缓存的数据）。
     *   在动画跑完（相邻页已在屏幕正中）之后调用，此刻屏幕上看不到这次内容切换。
     * @return 本次翻页的 Job，可用来在跑完后收尾（被手指接管或下一次翻页会取消）
     */
    fun switchTo(
        forward: Boolean,
        velocityPxPerSec: Float = 0f,
        apply: suspend () -> Unit
    ): Job {
        animJob?.cancel()
        val target = (if (forward) -1f else 1f) * pageWidthPx
        val job = scope.launch {
            val from = offsetState.floatValue
            val durationMs = pageDuration(abs(target - from), velocityPxPerSec)
            animate(
                from, target,
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) { value, _ -> offsetState.floatValue = value }

            // apply 与复位写成同一帧：内容换成新筛选、位移回到 0，屏幕上不会闪一下旧内容
            apply()
            offsetState.floatValue = 0f
        }
        animJob = job
        return job
    }

    /** 距离 / 速度 = 时长，再夹在 200–340ms 之间 */
    private fun pageDuration(distancePx: Float, velocityPxPerSec: Float): Int {
        val speed = abs(velocityPxPerSec).coerceIn(MIN_SWIPE_VELOCITY, MAX_SWIPE_VELOCITY)
        return (distancePx / speed * 1000f).toInt().coerceIn(PAGE_MIN_MS, PAGE_MAX_MS)
    }
}

/**
 * 翻页容器：当前页跟手平移，左右相邻页紧贴其边缘一起移动。
 *
 * [prev] / [next] 传 null 表示该方向已是序列首尾（滑到底就是边界，不渲染那一页）。
 * 相邻页只在翻页过程中参与渲染（静止时不组合、不加载它们的封面）。
 *
 * 手势 modifier 挂在 [modifier] 上（由调用方传入 `Modifier.filterSwipe(...)`）。
 */
@Composable
fun FilterPager(
    transition: FilterTransition,
    modifier: Modifier = Modifier,
    prev: (@Composable () -> Unit)? = null,
    next: (@Composable () -> Unit)? = null,
    current: @Composable () -> Unit
) {
    BoxWithConstraints(modifier) {
        // 页宽按容器实测宽度，不用屏宽：容器可能带内缩/留白，用屏宽算两页之间会露缝
        val pageWidth = constraints.maxWidth.toFloat()
        SideEffect { transition.pageWidthPx = pageWidth }
        val engaged = transition.engaged
        Box(Modifier.fillMaxSize()) {
            if (engaged) {
                prev?.let { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = transition.offset - pageWidth }
                    ) { page() }
                }
                next?.let { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = transition.offset + pageWidth }
                    ) { page() }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = transition.offset }
            ) { current() }
        }
    }
}

/**
 * 跟手横滑手势：位移与速度交给 [transition]，松手按阈值/边界决定翻页还是弹回。
 *
 * 手势节点只在 transition / 阈值变化时重启，所以 [onTakeOver] 与 [onSwipe] 捕获的是首次组合的
 * 那个闭包——里面要读的必须是 state 委托（或 remember 的常量），调用时才取值；
 * 若要捕获普通局部变量，得先包一层 rememberUpdatedState。
 *
 * @param thresholdPx 触发翻页的位移阈值
 * @param onTakeOver 手指按下的那一刻（横滑固定按某个序列翻页时，用它把相邻页的序列切回来）
 * @param onSwipe 触发翻页，参数为「是否向后」（手指向左滑）与松手速度（px/s）；
 *   返回 false = 已在序列首尾没得翻，此时内容弹回。
 */
fun Modifier.filterSwipe(
    transition: FilterTransition,
    thresholdPx: Float,
    onTakeOver: () -> Unit = {},
    onSwipe: (forward: Boolean, velocity: Float) -> Boolean
): Modifier = pointerInput(transition, thresholdPx) {
    val tracker = VelocityTracker()
    var dragDistance = 0f
    detectHorizontalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            dragDistance = 0f
            transition.takeOver()
            onTakeOver()
        },
        onHorizontalDrag = { change, amount ->
            tracker.addPosition(change.uptimeMillis, change.position)
            dragDistance += amount
            transition.drag(dragDistance)
        },
        onDragEnd = {
            val distance = dragDistance
            val velocity = tracker.calculateVelocity().x
            dragDistance = 0f
            // 没翻页（未达阈值或已在序列首尾）就把跟手的位移弹回去
            if (abs(distance) < thresholdPx || !onSwipe(distance < 0f, velocity)) {
                transition.settleBack()
            }
        },
        onDragCancel = {
            dragDistance = 0f
            transition.settleBack()
        }
    )
}

@Composable
fun rememberFilterTransition(): FilterTransition {
    val scope = rememberCoroutineScope()
    return remember(scope) { FilterTransition(scope) }
}
