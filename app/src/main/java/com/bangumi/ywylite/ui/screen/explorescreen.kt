package com.bangumi.ywylite.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.api.BangumiApi
import com.bangumi.ywylite.data.model.PagedSubject
import com.bangumi.ywylite.data.model.SubjectSmall
import com.bangumi.ywylite.ui.component.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ExploreUiState(
    val loading: Boolean = true,
    val data: List<SubjectSmall> = emptyList(),
    val error: String? = null,
    val selectedType: Int = 2,
    // 取值与网页 browser 页的 sort 参数一致（rank/trends/collects/date）
    val selectedSort: String = "rank",
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val total: Int = 0,
    /** 重试信号：+1 触发主 LaunchedEffect 重新加载（此前 keys 不变，重试按钮点了也白点） */
    val retryKey: Int = 0,
    /** 加载更多重试信号 */
    val loadMoreKey: Int = 0,
    /** 加载更多失败标记：显示"点击重试"替代永久转圈 */
    val loadMoreError: Boolean = false,
    /** 下拉刷新进行中：顶部转圈、旧列表保持可见（与整页 loading 区分） */
    val refreshing: Boolean = false
)

/**
 * 进程级列表缓存：底栏左右切换（saveState/restoreState）回页时直接恢复、不重新请求；
 * 冷启动为空 → 首次进入必然拉新。筛选切换/下拉刷新成功后覆盖。
 */
private var cachedExploreState: ExploreUiState? = null

private val subjectTypes = listOf(
    2 to "动画",
    1 to "书籍",
    4 to "游戏",
    3 to "音乐",
    6 to "三次元"
)

val typePaths = mapOf(
    2 to "anime",
    1 to "book",
    4 to "game",
    3 to "music",
    6 to "real"
)

// 与网页 bgm.tv/anime/browser 的排序项一一对应（值即网页的 sort 参数）
private val sortOptions = listOf(
    "rank" to "排名",
    "trends" to "热度",
    "collects" to "收藏",
    "date" to "日期"
)

/** 横滑序列里的排序（不含「日期」）：每种类型内 排名 → 热度 → 收藏 */
private val swipeSorts = listOf("rank", "trends", "collects")

/** 按排序值分发到对应接口：rank/date 走 v0 列表（官方支持），trends/collects 走搜索排序 */
private suspend fun fetchBrowsePage(
    api: BangumiApi,
    type: Int,
    sort: String,
    offset: Int
): PagedSubject = when (sort) {
    "trends" -> api.browseSubjectsByTrend(type = type, offset = offset)
    "collects" -> api.browseSubjectsByCollects(type = type, offset = offset)
    else -> api.browseSubjects(type = type, sort = sort, offset = offset)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onSubjectClick: (Int) -> Unit,
    onSearchClick: () -> Unit = {},
    onNavigateToTags: (String) -> Unit = {},
    onNavigateToCalendar: () -> Unit = {}
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 有进程缓存就先上缓存，并跳过本次进入的自动首拉（筛选/重试/下拉刷新仍会正常请求）
    var uiState by remember { mutableStateOf(cachedExploreState ?: ExploreUiState()) }
    var skipAutoLoad by remember { mutableStateOf(cachedExploreState != null) }
    val gridState = rememberLazyStaggeredGridState()
    // 切换筛选时的内容过渡（跟手拖动 → 滑出旧内容 → 换筛选 → 从另一侧滑入并渐显）。
    // 动画由过渡自己管，屏幕侧不再持 Job：手指接管或下一次切换会把它取消
    val filterTransition = rememberFilterTransition()
    // 每个筛选的第一页缓存 + 相邻筛选预取：滑动切换不再重新请求
    val filterCache = remember {
        FilterPageCache<Pair<Int, String>, SubjectSmall>(scope) { key, offset ->
            val page = fetchBrowsePage(api = app.api, type = key.first, sort = key.second, offset = offset)
            FilterPage(page.data, page.total)
        }
    }
    // 切换中的目标位置：过渡还没走完时 uiState 仍是旧筛选，
    // 下一次滑动要相对「正在切过去的那一屏」算方向，否则中间那次滑动会被当成原地不动丢掉
    var pendingPosition by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun currentPosition(): Pair<Int, String> = pendingPosition ?: (uiState.selectedType to uiState.selectedSort)

    // 状态落定即写入进程缓存；加载/刷新中的瞬态不入缓存，切页回来不会停在中间态
    LaunchedEffect(uiState) {
        if (!uiState.loading && !uiState.refreshing) cachedExploreState = uiState
    }

    // 下拉刷新：整表替换回第一页。item 按条目 id 复用、封面 URL 归一化稳定，
    // 未变化的图片由 Coil 内存缓存直接命中，不会二次下载
    val refresh: () -> Unit = {
        scope.launch {
            // 记下发起时的筛选：刷新期间用户切了类型/排序，这份响应就属于旧筛选，不能再写回
            val filterType = uiState.selectedType
            val filterSort = uiState.selectedSort
            fun filterChanged() = filterType != uiState.selectedType || filterSort != uiState.selectedSort
            uiState = uiState.copy(refreshing = true)
            try {
                val result = fetchBrowsePage(
                    api = app.api,
                    type = filterType,
                    sort = filterSort,
                    offset = 0
                )
                if (filterChanged()) {
                    // 新筛选的首拉正在进行（会自己覆盖列表）：丢弃过期响应，只把转圈停掉
                    uiState = uiState.copy(refreshing = false)
                    return@launch
                }
                uiState = uiState.copy(
                    refreshing = false,
                    data = result.data.distinctBy { it.id },
                    offset = result.data.size,
                    hasMore = result.data.size < result.total,
                    total = result.total,
                    loadMoreError = false
                )
                // 刷新结果写回筛选缓存：之后滑回这一屏看到的是刚刷过的数据
                filterCache.store(
                    filterType to filterSort,
                    FilterPage(result.data.distinctBy { it.id }, result.total)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                uiState = uiState.copy(refreshing = false)
                if (!filterChanged()) {
                    Toast.makeText(context, "刷新失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 滚动接近底部时加载下一页（替代 footer 内 LaunchedEffect：
    // footer item 随数据追加会被 LazyGrid 重建，旧写法会连环拉完全部数据且失败静默）
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            uiState.hasMore && !uiState.loadMoreError && !uiState.loading && !uiState.refreshing &&
                lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, uiState.offset, uiState.loadMoreKey) {
        if (!nearEnd || uiState.loading || uiState.refreshing) return@LaunchedEffect
        try {
            val result = fetchBrowsePage(
                api = app.api,
                type = uiState.selectedType,
                sort = uiState.selectedSort,
                offset = uiState.offset
            )
            uiState = if (result.data.isEmpty()) {
                uiState.copy(hasMore = false, loadMoreError = false)
            } else {
                val merged = (uiState.data + result.data).distinctBy { it.id }
                // 追加的页也写回缓存，滑走再滑回来时这一段不用重新拉
                filterCache.store(
                    uiState.selectedType to uiState.selectedSort,
                    FilterPage(merged, uiState.total)
                )
                uiState.copy(
                    data = merged,
                    offset = uiState.offset + result.data.size,
                    hasMore = (uiState.offset + result.data.size) < uiState.total,
                    loadMoreError = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            uiState = uiState.copy(loadMoreError = true)
        }
    }

    val swipePositions = remember {
        subjectTypes.flatMap { (type, _) ->
            swipeSorts.map { sort -> type to sort }
        }
    }
    /** 横滑序列里的位次；「日期」不在序列里，按本类型序列的入口位次算 */
    fun browsePositionIndex(type: Int, sort: String): Int {
        val exact = swipePositions.indexOf(type to sort)
        if (exact >= 0) return exact
        val typeIndex = subjectTypes.indexOfFirst { it.first == type }
        return (if (typeIndex >= 0) typeIndex else 0) * swipeSorts.size
    }
    /**
     * @param velocity 松手速度（横滑传来）：决定滑出快慢，点 tab/chip 时为 0
     */
    fun selectBrowsePosition(type: Int, sort: String, velocity: Float = 0f) {
        val from = currentPosition()
        if (type == from.first && sort == from.second) return
        // 方向按序列位次算：目标位次在更后面 = 向左切换（与左滑同一个方向）
        val forward = browsePositionIndex(type, sort) > browsePositionIndex(from.first, from.second)
        pendingPosition = type to sort
        filterTransition.switchTo(forward, velocity) {
            gridState.scrollToItem(0)
            uiState = uiState.copy(selectedType = type, selectedSort = sort)
        }.invokeOnCompletion {
            // 过渡跑完（或被手指接管/被下一次切换取消）且没有更新的一次切换，交回给 uiState 作为基准
            if (pendingPosition == (type to sort)) pendingPosition = null
        }
    }
    /** 横滑一次：按序列位次取相邻位置，首尾不越界。返回 false = 没切（边界），调用方要弹回 */
    fun swipeStep(forward: Boolean, velocity: Float): Boolean {
        val from = currentPosition()
        val target = if (swipePositions.indexOf(from) < 0) {
            // 「日期」不在横滑序列里：只在本类型的序列内移动（向后滑 → 排名，向前滑 → 收藏），
            // 不跨到别的类型，否则一次滑动会同时跳掉类型和排序两个维度
            from.first to if (forward) swipeSorts.first() else swipeSorts.last()
        } else {
            val index = browsePositionIndex(from.first, from.second)
            swipePositions[(index + if (forward) 1 else -1).coerceIn(0, swipePositions.lastIndex)]
        }
        if (target == from) return false
        selectBrowsePosition(target.first, target.second, velocity)
        return true
    }
    /** 左右相邻筛选（预取用） */
    fun neighbourPositions(type: Int, sort: String): List<Pair<Int, String>> {
        val index = browsePositionIndex(type, sort)
        return listOfNotNull(swipePositions.getOrNull(index - 1), swipePositions.getOrNull(index + 1))
    }

    LaunchedEffect(uiState.selectedType, uiState.selectedSort, uiState.retryKey) {
        val key = uiState.selectedType to uiState.selectedSort
        fun prefetchNeighbours() = filterCache.prefetch(neighbourPositions(key.first, key.second))
        // 刚从进程缓存恢复时跳过自动首拉；此后筛选变化/重试正常触发
        if (skipAutoLoad) {
            skipAutoLoad = false
            filterCache.store(key, FilterPage(uiState.data, uiState.total))
            prefetchNeighbours()
            return@LaunchedEffect
        }
        // 切回已经拉过的筛选：直接给缓存，不再请求（下拉刷新才是手动更新数据的方式）
        filterCache.peek(key)?.let { entry ->
            uiState = uiState.copy(
                loading = false,
                error = null,
                data = entry.page.data,
                offset = entry.offset,
                hasMore = entry.hasMore,
                total = entry.page.total,
                loadMoreError = false
            )
            prefetchNeighbours()
            // 预取命中：新内容就在这一帧，放行过渡的渐显
            filterTransition.contentReady()
            return@LaunchedEffect
        }
        // hasMore 必须一并清掉：列表被换成加载屏时 layoutInfo 为空，
        // nearEnd 会误判成「到底」而与这次首拉并发再取一遍 offset=0，
        // 之后 offset 多加一页 → 中间整页条目被跳过
        uiState = uiState.copy(loading = true, offset = 0, hasMore = false, error = null, loadMoreError = false)
        try {
            // firstPage 内部按筛选去重：预取已经在拉的话这里直接等它，不会重复请求
            val entry = filterCache.firstPage(key)
            uiState = uiState.copy(
                loading = false,
                data = entry.page.data,
                offset = entry.offset,
                hasMore = entry.hasMore,
                total = entry.page.total
            )
            prefetchNeighbours()
            // 现拉的首拉回来了：放行渐显
            filterTransition.contentReady()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
            // 失败同样要放行：错误页也得看得清，不能一直停在半透明
            filterTransition.contentReady()
        }
    }

    // 阈值按 dp 换算：裸像素在不同密度屏上手感差一倍
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val browseSwipeModifier = Modifier.filterSwipe(filterTransition, swipeThresholdPx) { forward, velocity ->
        swipeStep(forward, velocity)
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("浏览") },
                actions = {
                    IconButton(onClick = onNavigateToCalendar) {
                        Icon(Icons.Default.DateRange, contentDescription = "放送")
                    }
                    IconButton(onClick = {
                        val typePath = typePaths[uiState.selectedType] ?: "anime"
                        onNavigateToTags(typePath)
                    }) {
                        Icon(Icons.Default.Tag, contentDescription = "标签")
                    }
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(
                edgePadding = 16.dp,
                selectedTabIndex = subjectTypes.indexOfFirst { it.first == uiState.selectedType }.coerceAtLeast(0),
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.height(52.dp)
            ) {
                subjectTypes.forEach { (type, label) ->
                    Tab(
                        selected = uiState.selectedType == type,
                        // 与横滑一致：切类型/排序后列表回到顶部，不沿用旧滚动位置
                        onClick = { selectBrowsePosition(type, uiState.selectedSort) },
                        text = { Text(label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sortOptions.forEach { (value, label) ->
                    FilterChip(
                        selected = uiState.selectedSort == value,
                        onClick = { selectBrowsePosition(uiState.selectedType, value) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = uiState.refreshing,
                onRefresh = refresh,
                // 过渡挂在内容区上（类型 tab 与排序 chip 留在原地不跟着动）
                modifier = Modifier.fillMaxSize()
                    .then(browseSwipeModifier)
                    .then(filterTransition.modifier)
            ) {
            LazyVerticalStaggeredGrid(
                // 自适应列宽：窄屏 3 列左右，平板自动多列
                columns = StaggeredGridCells.Adaptive(110.dp),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalItemSpacing = 6.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    uiState.loading -> item(span = StaggeredGridItemSpan.FullLine) { LoadingView() }
                    uiState.error != null -> item(span = StaggeredGridItemSpan.FullLine) {
                        ErrorView(
                            message = uiState.error ?: "加载失败",
                            onRetry = {
                                uiState = uiState.copy(
                                    loading = true,
                                    error = null,
                                    offset = 0,
                                    retryKey = uiState.retryKey + 1
                                )
                            }
                        )
                    }
                    uiState.data.isEmpty() -> item(span = StaggeredGridItemSpan.FullLine) { EmptyView() }
                    else -> {
                        // 翻页接口可能返回重叠数据，重复 key 会让网格直接崩溃（平板宽屏预取更多更容易触发）
                        items(uiState.data.distinctBy { it.id }, key = { it.id }) { subject ->
                            SubjectCard(
                                subject = subject,
                                onClick = { onSubjectClick(subject.id) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (uiState.hasMore) {
                            item(span = StaggeredGridItemSpan.FullLine) {
                                if (uiState.loadMoreError) {
                                    TextButton(
                                        onClick = {
                                            uiState = uiState.copy(
                                                loadMoreError = false,
                                                loadMoreKey = uiState.loadMoreKey + 1
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("加载失败，点击重试")
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
