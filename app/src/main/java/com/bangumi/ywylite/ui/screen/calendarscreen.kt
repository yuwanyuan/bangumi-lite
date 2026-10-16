package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.CalendarDay
import com.bangumi.ywylite.data.model.SubjectSmall
import com.bangumi.ywylite.ui.component.*
import kotlinx.coroutines.CancellationException

data class CalendarUiState(
    val loading: Boolean = true,
    val data: List<CalendarDay> = emptyList(),
    val error: String? = null,
    val selectedDay: Int = 0,
    /** 重试信号：+1 触发 LaunchedEffect 重新加载（此前 key 恒为 Unit，重试按钮无效） */
    val retryKey: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onSubjectClick: (Int) -> Unit,
    onSearchClick: () -> Unit = {}
) {
    val app = App.INSTANCE
    var uiState by remember { mutableStateOf(CalendarUiState()) }
    // 左右滑动切星期，与浏览页/收藏页共用同一套翻页过渡（相邻两天各占一屏，紧贴当前页边缘一起移动）。
    // 动画由过渡自己管，屏幕侧不持 Job：手指接管或下一次翻页会把它取消
    val filterTransition = rememberFilterTransition()
    // 每天一个滚动状态：翻页时相邻天各占一屏，静止后同一天还会作为当前页回来，
    // 共用一个状态翻页前后滚动位置才连贯（各建一个会先跳回顶部再恢复）
    val dayListStates = remember { mutableMapOf<Int, LazyListState>() }
    fun listStateOf(day: Int): LazyListState = dayListStates.getOrPut(day) { LazyListState() }
    // 切换中的目标：过渡没走完时 uiState 还是旧的一天，下一次滑动要相对它算方向
    var pendingDay by remember { mutableStateOf<Int?>(null) }
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }

    /**
     * @param velocity 松手速度（横滑传来）：决定滑出快慢，点星期 tab 时为 0
     */
    fun selectDay(index: Int, velocity: Float = 0f) {
        // 首尾边界不越界（向后滑过星期日、向前滑过星期一都不动）
        if (index !in uiState.data.indices) return
        val from = pendingDay ?: uiState.selectedDay
        if (index == from) return
        val forward = index > from
        pendingDay = index
        filterTransition.switchTo(forward, velocity) {
            // 整周数据都在内存里，换天不需要等加载：这一帧就换上新一天。
            // 滚动位置由 listStateOf 按天持有，不需要在这里回顶
            uiState = uiState.copy(selectedDay = index)
        }.invokeOnCompletion {
            if (pendingDay == index) pendingDay = null
        }
    }

    /** 横滑一次：下一天 / 上一天。返回 false = 已在首尾没切，调用方要把跟手的位移弹回去 */
    fun swipeStep(forward: Boolean, velocity: Float): Boolean {
        val from = pendingDay ?: uiState.selectedDay
        val target = from + if (forward) 1 else -1
        if (target !in uiState.data.indices) return false
        selectDay(target, velocity)
        return true
    }

    val daySwipeModifier = Modifier.filterSwipe(filterTransition, swipeThresholdPx) { forward, velocity ->
        swipeStep(forward, velocity)
    }

    LaunchedEffect(uiState.retryKey) {
        uiState = uiState.copy(loading = true, error = null)
        try {
            val calendar = app.api.getCalendar()
            val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            val todayIndex = when (today) {
                java.util.Calendar.MONDAY -> 0
                java.util.Calendar.TUESDAY -> 1
                java.util.Calendar.WEDNESDAY -> 2
                java.util.Calendar.THURSDAY -> 3
                java.util.Calendar.FRIDAY -> 4
                java.util.Calendar.SATURDAY -> 5
                java.util.Calendar.SUNDAY -> 6
                else -> 0
            }
            uiState = uiState.copy(loading = false, data = calendar, selectedDay = todayIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("每日放送") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
        when {
            uiState.loading -> LoadingView(Modifier.fillMaxSize())
            uiState.error != null -> ErrorView(
                message = uiState.error ?: "加载失败",
                onRetry = {
                    uiState = uiState.copy(
                        loading = true,
                        error = null,
                        retryKey = uiState.retryKey + 1
                    )
                },
                modifier = Modifier.fillMaxSize()
            )
            // 服务端返回空列表时原先整页空白，给个说明
            uiState.data.isEmpty() -> EmptyView("暂无放送数据")
            else -> CalendarContent(
                uiState = uiState,
                onDaySelected = { index -> selectDay(index) },
                onSubjectClick = onSubjectClick,
                listStateOf = ::listStateOf,
                filterTransition = filterTransition,
                // 手势与过渡只挂在放送列表区域：顶部星期 tab 要保留自己的横向滚动
                contentModifier = Modifier
                    .fillMaxSize()
                    .then(daySwipeModifier)
            )
        }
        }
    }
}

@Composable
private fun CalendarContent(
    uiState: CalendarUiState,
    onDaySelected: (Int) -> Unit,
    onSubjectClick: (Int) -> Unit,
    listStateOf: (Int) -> LazyListState,
    filterTransition: FilterTransition,
    contentModifier: Modifier = Modifier
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            edgePadding = 16.dp,
            selectedTabIndex = uiState.selectedDay,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            uiState.data.forEachIndexed { index, day ->
                Tab(
                    selected = uiState.selectedDay == index,
                    onClick = { onDaySelected(index) },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(day.weekday.cn, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                )
            }
        }

        FilterPager(
            transition = filterTransition,
            modifier = contentModifier,
            // 相邻两天各一屏：整周数据都在内存里，横滑时左右两页紧跟手指露出来。
            // 滚动状态按天取自 listStateOf，翻页落定后跟作为当前页时是同一份
            prev = uiState.data.getOrNull(uiState.selectedDay - 1)?.let { day ->
                { key(day) { CalendarDayList(day, onSubjectClick, listStateOf(uiState.selectedDay - 1)) } }
            },
            next = uiState.data.getOrNull(uiState.selectedDay + 1)?.let { day ->
                { key(day) { CalendarDayList(day, onSubjectClick, listStateOf(uiState.selectedDay + 1)) } }
            }
        ) {
            val currentDay = uiState.data.getOrNull(uiState.selectedDay)
            if (currentDay != null) {
                CalendarDayList(currentDay, onSubjectClick, listStateOf(uiState.selectedDay))
            }
        }
    }
}

@Composable
private fun CalendarDayList(
    day: CalendarDay,
    onSubjectClick: (Int) -> Unit,
    state: LazyListState
) {
    if (day.items.isEmpty()) {
        EmptyView("今日没有放送")
        return
    }
    // 懒加载：一天几十个条目，非 Lazy 的 Column 会一次性组合全部条目、
    // 所有封面同时挤进图片加载队列（每主机并发有限），表现为图片长时间出不来
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(day.items, key = { it.id }) { subject ->
            CalendarItem(
                subject = subject,
                onClick = { onSubjectClick(subject.id) }
            )
        }
    }
}

@Composable
private fun CalendarItem(
    subject: SubjectSmall,
    onClick: () -> Unit
) {
    // 与收藏页列表同一套毛玻璃：有壁纸时条目行采样背后壁纸做模糊，
    // 无壁纸时 wallpaperGlassPanel 退化为仅裁圆角，外观与原先一致
    val darkTheme = isSystemInDarkTheme()
    val shape = RoundedCornerShape(18.dp)
    val background = if (darkTheme) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .then(wallpaperGlassPanel(darkTheme, shape))
    ) {
        SubjectListItem(subject = subject, onClick = onClick, transparentContainer = true)
    }
}
