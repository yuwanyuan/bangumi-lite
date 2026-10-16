package com.bangumi.ywylite.ui.component

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** 过渡节奏：跟手弹回 200ms、滑出 120–240ms（松手越快越短）、滑入渐显 300ms */
private const val OUT_MIN_MS = 120
private const val OUT_MAX_MS = 240
private const val IN_DURATION_MS = 300
private const val SETTLE_DURATION_MS = 200

/**
 * 新内容迟迟没渲染出来的兜底等待；正常路径由 [FilterTransition.contentReady] 放行。
 * 给得宽一点：这段时间透明度停在 [SWAP_FADE]，转圈是淡的但看得见，
 * 数据一到就能淡上来；真超过这个时长（网络很慢）就只能先亮起来、随内容到位再硬换
 */
private const val CONTENT_WAIT_MS = 1500L

/**
 * 跟手阶段 1:1 能拖到的距离（再往外按阻尼走），与整页滑出/滑入距离，都按屏宽比。
 *
 * [PAGE_SCREEN_FRACTION] 必须大于 1：滑出终点和滑入起点都得真的离开屏幕。
 * 换页时新旧内容是同一个容器（不可能同时渲染两份），所以那一刻的内容位置只要还有一部分
 * 露在屏内，就会被看成「瞬移」——用户反馈的「新的一页突然出现在屏幕中间」就是这么来的。
 * 两头都出屏，换页发生在屏幕外，新内容才是从边缘完整滑进来的。
 */
private const val SLIDE_SCREEN_FRACTION = 0.28f
private const val PAGE_SCREEN_FRACTION = 1.05f

/** 内容透明度：跟手时最低 0.7；换页时两边同为 0.3，接起来像一次淡出淡入 */
private const val DRAG_FADE_MIN = 0.7f
private const val SWAP_FADE = 0.3f

/**
 * 超出翻页距离后继续跟手的阻尼比。给得偏大是刻意的：这一阶段越跟手，松开后要补的位移越少，
 * 整体越像「手指把这一页划走」，而不是「划到一半卡住等动画」
 */
private const val DRAG_RESISTANCE = 0.4f

/** 用松手速度算滑出时长时的上下限（px/s），避免慢悠悠拖尾或一帧闪走 */
private const val MIN_EXIT_VELOCITY = 900f
private const val MAX_EXIT_VELOCITY = 4000f

/**
 * 筛选切换的内容过渡（浏览页 / 收藏页 / 每日放送共用）。
 *
 * 三种用法：
 * - 手势跟手：[takeOver] 接管（取消进行中的动画）+ [drag] 直接按手指位移平移内容，不用等松手。
 * - 松手未达阈值：[settleBack] 弹回原位。
 * - 达阈值或点 tab/chip：[switchTo] 从手指松开的位置继续推，把旧内容真正推出屏幕并淡出；
 *   换内容后新内容从另一侧滑入，透明度由 [contentReady] 放行后渐显。
 *
 * 位置与透明度都是普通 float 状态，在 graphicsLayer 的 lambda 里读：过渡期间只重绘不重组。
 */
class FilterTransition internal constructor(
    private val travelPx: Float,
    private val pagePx: Float,
    private val scope: CoroutineScope
) {
    private val offset = mutableFloatStateOf(0f)
    private val fade = mutableFloatStateOf(1f)
    private var animJob: Job? = null

    /** 本次跟手的起点：接管时从动画停住的位置接着拖，内容不会跳一下 */
    private var dragBase = 0f

    /** 当前这次切换的「新内容已就绪」信号；每次切换换一个新的 */
    private var contentSignal: CompletableDeferred<Unit>? = null

    val modifier: Modifier
        get() = Modifier.graphicsLayer {
            translationX = offset.floatValue
            alpha = fade.floatValue
        }

    /**
     * 手指按下接管：取消进行中的动画（否则动画和手指会同时写位移，互相打架），
     * 并把当前停住的位置记为本次跟手的起点——不管是滑到一半还是刚从屏外滑回来，
     * 内容都从它此刻所在的位置继续被拖，不会跳一下。夹 ±整页距离只是防御越界值。
     */
    fun takeOver() {
        animJob?.cancel()
        animJob = null
        dragBase = offset.floatValue.coerceIn(-pagePx, pagePx)
    }

    /** 手势跟手：内容 1:1 跟手指走，超过翻页距离后加阻尼；同时轻微淡出作为翻页反馈 */
    fun drag(totalPx: Float) {
        val moved = dragBase + withResistance(totalPx)
        offset.floatValue = moved
        // 取小值：从动画中途接管时透明度只降不升，不会因为「跟手曲线」比当前值高而闪一下
        fade.floatValue = minOf(fade.floatValue, dragFade(moved))
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
     * 切换筛选。两个关键点：
     *
     * 1. 不能停：滑出的终点要真正离开屏幕，而不是跟手能达到的那点距离——否则正常力度一划松手时
     *    内容已经贴在跟手终点上，滑出动画无事可做，中间就是一段空白停顿；时长再按松手速度算，
     *    快划干脆、慢放平稳，动作和手指接得上。
     * 2. 换页要发生在屏幕外：新旧内容共用一个容器，滑出终点与滑入起点都在屏外，
     *    这一帧的「换」才看不见；否则新内容会像是「突然出现在屏幕中间」，再挪那么一小段。
     *
     * @param forward 沿序列向后（手势向左滑）：旧内容向左推出，新内容从右侧进入。
     * @param velocityPxPerSec 松手瞬间的手指速度，点 tab/chip 触发时传 0。
     * @param apply 真正的切换动作（列表回顶 + 改筛选状态）。这里只做状态变更，不发起加载等待。
     * @return 本次过渡的 Job，可用来在跑完后收尾（被下一次切换/手指接管会取消）
     */
    fun switchTo(
        forward: Boolean,
        velocityPxPerSec: Float = 0f,
        apply: suspend () -> Unit
    ): Job {
        animJob?.cancel()
        val signal = CompletableDeferred<Unit>()
        contentSignal = signal
        val direction = if (forward) -1f else 1f
        val job = scope.launch {
            // 1) 滑出：从当前位置继续往前推，一路推到屏幕外
            //    （终点一定比当前位置更靠外，绝不往回走；否则松手时动画没有位移可走，中间就是一段停顿）
            val from = offset.floatValue
            val target = direction * maxOf(pagePx, abs(from) + travelPx * 0.35f)
            val outMs = outDuration(abs(target - from), velocityPxPerSec)
            coroutineScope {
                launch {
                    animate(
                        from, target,
                        animationSpec = tween(outMs, easing = FastOutLinearInEasing)
                    ) { value, _ -> offset.floatValue = value }
                }
                launch {
                    animate(
                        fade.floatValue, SWAP_FADE,
                        animationSpec = tween(outMs, easing = FastOutLinearInEasing)
                    ) { value, _ -> fade.floatValue = value }
                }
            }

            // 2) 换内容。此刻旧内容已经出屏，新内容也从屏外的起点出发，
            //    这一帧的「换」发生在屏幕外，看不见；新内容随后整页滑入 + 渐显
            apply()
            offset.floatValue = -direction * pagePx
            fade.floatValue = SWAP_FADE
            coroutineScope {
                launch {
                    animate(
                        offset.floatValue, 0f,
                        animationSpec = tween(IN_DURATION_MS, easing = FastOutSlowInEasing)
                    ) { value, _ -> offset.floatValue = value }
                }
                launch {
                    // 透明度先停在 0.3：等新内容真的渲染出来再升到 1。
                    // 新筛选的数据要现拉时，这一步能避免「转圈渐显完 → 真列表整页硬弹出来」
                    withTimeoutOrNull(CONTENT_WAIT_MS) { signal.await() }
                    animate(
                        fade.floatValue, 1f,
                        animationSpec = tween(IN_DURATION_MS, easing = FastOutSlowInEasing)
                    ) { value, _ -> fade.floatValue = value }
                }
            }
        }
        animJob = job
        return job
    }

    /**
     * 新筛选的内容已经渲染出来了（缓存命中那一帧、或请求返回/失败时）：放行滑入后的渐显。
     * 每次 [switchTo] 后都必须有人调用，否则透明度只能等兜底超时才升起来。
     */
    fun contentReady() {
        contentSignal?.complete(Unit)
    }

    /** 跟手时的透明度：越推越淡，最多淡到 [DRAG_FADE_MIN] */
    private fun dragFade(px: Float): Float =
        (1f - abs(px) / (travelPx * 4f)).coerceIn(DRAG_FADE_MIN, 1f)

    /** 距离 / 速度 = 时长，再夹在 120–240ms 之间 */
    private fun outDuration(distancePx: Float, velocityPxPerSec: Float): Int {
        val speed = abs(velocityPxPerSec).coerceIn(MIN_EXIT_VELOCITY, MAX_EXIT_VELOCITY)
        return (distancePx / speed * 1000f).toInt().coerceIn(OUT_MIN_MS, OUT_MAX_MS)
    }

    /** 翻页距离之外按 [DRAG_RESISTANCE] 的速率继续跟手，避免手指一划就把内容甩到天边 */
    private fun withResistance(px: Float): Float = when {
        px > travelPx -> travelPx + (px - travelPx) * DRAG_RESISTANCE
        px < -travelPx -> -travelPx + (px + travelPx) * DRAG_RESISTANCE
        else -> px
    }
}

/**
 * 跟手横滑手势：位移与速度交给 [transition]，松手按阈值/边界决定切页还是弹回。
 *
 * 手势节点只在 transition / 阈值变化时重启，所以 [onSwipe] 捕获的是首次组合的那个闭包——
 * 里面要读的必须是 state 委托（或 remember 的常量），调用时才取值；
 * 若要捕获普通局部变量，得先包一层 rememberUpdatedState。
 *
 * @param thresholdPx 触发切换的位移阈值
 * @param onSwipe 触发切换，参数为「是否向后」（手指向左滑）与松手速度（px/s）；
 *   返回 false = 已在序列首尾没得切，此时内容弹回。
 */
fun Modifier.filterSwipe(
    transition: FilterTransition,
    thresholdPx: Float,
    onSwipe: (forward: Boolean, velocity: Float) -> Boolean
): Modifier = pointerInput(transition, thresholdPx) {
    val tracker = VelocityTracker()
    var dragDistance = 0f
    detectHorizontalDragGestures(
        onDragStart = {
            tracker.resetTracking()
            dragDistance = 0f
            transition.takeOver()
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
            // 没切页（未达阈值或已在序列首尾）就把跟手的位移弹回去
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
    val widthDp = LocalConfiguration.current.screenWidthDp
    val (travelPx, pagePx) = with(LocalDensity.current) {
        (widthDp * SLIDE_SCREEN_FRACTION).dp.toPx() to (widthDp * PAGE_SCREEN_FRACTION).dp.toPx()
    }
    return remember(scope, travelPx, pagePx) { FilterTransition(travelPx, pagePx, scope) }
}
