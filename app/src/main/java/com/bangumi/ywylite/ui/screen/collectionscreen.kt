package com.bangumi.ywylite.ui.screen

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.PagedUserCollection
import com.bangumi.ywylite.data.model.UserCollection
import com.bangumi.ywylite.ui.component.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 加载更多 footer：列表与瀑布流两种容器共用 */
@Composable
private fun CollectionLoadMoreFooter(loadMoreError: Boolean, onRetry: () -> Unit) {
    if (loadMoreError) {
        TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
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

/**
 * 进程级收藏列表缓存：底栏左右切换回页时直接恢复、不重新请求；冷启动为空 → 首次进入必然拉新。
 * 筛选切换/下拉刷新成功后覆盖。刷新/加载中的瞬态不写入。
 */
private var cachedCollectionState: CollectionUiState? = null

/** 缓存归属的用户名：换账号登录后旧缓存作废，避免新账号先看到上一个账号的收藏 */
private var cachedCollectionUsername: String? = null

data class CollectionUiState(
    val loading: Boolean = true,
    val data: List<UserCollection> = emptyList(),
    val error: String? = null,
    val selectedType: Int? = 3,
    val selectedSubjectType: Int? = null,
    val showTypeFilter: Boolean = false,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val total: Int = 0,
    /** 重试信号：+1 触发主 LaunchedEffect 重新加载（此前 keys 不含重试信号，按钮无效） */
    val retryKey: Int = 0,
    /** 加载更多重试信号 */
    val loadMoreKey: Int = 0,
    /** 加载更多失败标记：显示"点击重试"替代永久转圈 */
    val loadMoreError: Boolean = false,
    /** 下拉刷新进行中：顶部转圈、旧列表保持可见（与整页 loading 区分） */
    val refreshing: Boolean = false
)

private val collectionTypes = listOf(
    null to "全部",
    3 to "在看",
    2 to "看过",
    1 to "想看",
    4 to "搁置",
    5 to "抛弃"
)

private val subjectTypeFilters = listOf(
    null to "全部",
    2 to "动画",
    1 to "书籍",
    3 to "音乐",
    4 to "游戏",
    6 to "三次元"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    isLoggedIn: Boolean,
    onSubjectClick: (Int) -> Unit,
    onSearchClick: () -> Unit = {}
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var uiState by remember { mutableStateOf(cachedCollectionState ?: CollectionUiState()) }
    // 恢复的缓存归属用户（null = 无缓存）；主加载效应首次拿到用户名时判定缓存是否可用
    var restoredUsername by remember {
        mutableStateOf(if (cachedCollectionState != null) cachedCollectionUsername else null)
    }
    var username by remember { mutableStateOf<String?>(null) }
    // 条目样式：瀑布流（与浏览页一致）或经典列表，设置页可随时切换
    val waterfall by app.settings.collectionWaterfall.collectAsState(initial = false)
    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    // 切换筛选时的内容过渡（跟手拖动 → 滑出旧内容 → 换筛选 → 从另一侧滑入）
    val filterTransition = rememberFilterTransition()
    var switchJob by remember { mutableStateOf<Job?>(null) }
    // 每个筛选的第一页缓存 + 相邻筛选预取：滑动切换不再重新请求
    val filterCache = remember {
        FilterPageCache<Pair<Int?, Int?>, UserCollection>(scope) { key, offset ->
            val name = username ?: throw IllegalStateException("未登录")
            val page = app.api.getUserCollections(
                name,
                subjectType = key.second,
                collectionType = key.first,
                offset = offset
            )
            FilterPage(page.data, page.total)
        }
    }
    // 切换中的目标筛选：过渡还没走完时 uiState 仍是旧筛选，
    // 下一次滑动要相对「正在切过去的那一屏」算方向，否则中间那次滑动会被当成原地不动丢掉
    var pendingFilter by remember { mutableStateOf<Pair<Int?, Int?>?>(null) }
    fun currentFilter(): Pair<Int?, Int?> =
        pendingFilter ?: (uiState.selectedType to uiState.selectedSubjectType)
    fun selectCollectionType(type: Int?) {
        val from = currentFilter()
        if (type == from.first) return
        // 方向按 tab 顺序算：目标位次在更后面 = 向左切换（与左滑同一个方向）
        val forward = collectionTypes.indexOfFirst { it.first == type } >
            collectionTypes.indexOfFirst { it.first == from.first }
        pendingFilter = type to from.second
        switchJob?.cancel()
        switchJob = scope.launch {
            filterTransition.switchTo(forward) {
                if (waterfall) gridState.scrollToItem(0) else listState.scrollToItem(0)
                uiState = uiState.copy(selectedType = type)
            }
            if (pendingFilter == (type to from.second)) pendingFilter = null
        }
    }
    fun selectSubjectType(type: Int?) {
        val from = currentFilter()
        if (type == from.second) return
        val forward = subjectTypeFilters.indexOfFirst { it.first == type } >
            subjectTypeFilters.indexOfFirst { it.first == from.second }
        pendingFilter = from.first to type
        switchJob?.cancel()
        switchJob = scope.launch {
            filterTransition.switchTo(forward) {
                if (waterfall) gridState.scrollToItem(0) else listState.scrollToItem(0)
                uiState = uiState.copy(selectedSubjectType = type)
            }
            if (pendingFilter == (from.first to type)) pendingFilter = null
        }
    }
    /** 横滑一次：序列就是 tab 顺序（全部 → 在看 → 看过 → 想看 → 搁置 → 抛弃），首尾不越界。
     *  返回 false = 没切（已在首尾），调用方要把跟手的位移弹回去 */
    fun swipeStep(forward: Boolean): Boolean {
        val from = currentFilter()
        val index = collectionTypes.indexOfFirst { it.first == from.first }
        if (index < 0) return false
        val target = collectionTypes[(index + if (forward) 1 else -1).coerceIn(0, collectionTypes.lastIndex)]
        if (target.first == from.first) return false
        selectCollectionType(target.first)
        return true
    }
    /** 左右相邻筛选（预取用） */
    fun neighbourFilters(type: Int?, subjectType: Int?): List<Pair<Int?, Int?>> {
        val index = collectionTypes.indexOfFirst { it.first == type }
        if (index < 0) return emptyList()
        return listOfNotNull(collectionTypes.getOrNull(index - 1), collectionTypes.getOrNull(index + 1))
            .map { it.first to subjectType }
    }
    // 阈值按 dp 换算：裸像素在不同密度屏上手感差一倍
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val collectionSwipeModifier = Modifier.pointerInput(uiState.selectedType, waterfall, swipeThresholdPx) {
        var dragDistance = 0f
        var dragging = false
        detectHorizontalDragGestures(
            // 跟手：手势期间内容就按手指位移走，松手才决定切页还是弹回
            onHorizontalDrag = { _, amount ->
                if (!dragging) {
                    // 上一次切换可能还在跑动画：手指按下就接管，否则两边一起写位移会互相打架
                    dragging = true
                    switchJob?.cancel()
                }
                dragDistance += amount
                filterTransition.drag(dragDistance)
            },
            onDragEnd = {
                val distance = dragDistance
                dragDistance = 0f
                dragging = false
                // 没切页（未达阈值或已在首尾）就把跟手的位移弹回去
                if (kotlin.math.abs(distance) < swipeThresholdPx || !swipeStep(forward = distance < 0f)) {
                    filterTransition.settleBack()
                }
            },
            onDragCancel = {
                dragDistance = 0f
                dragging = false
                filterTransition.settleBack()
            }
        )
    }

    // 状态落定即写入进程缓存；加载/刷新中的瞬态不入缓存，切页回来不会停在中间态
    LaunchedEffect(uiState) {
        if (!uiState.loading && !uiState.refreshing) {
            cachedCollectionState = uiState
            username?.let { cachedCollectionUsername = it }
        }
    }

    // 下拉刷新：整表替换回第一页。item 按条目 id 复用、封面 URL 归一化稳定，
    // 未变化的图片由 Coil 内存缓存直接命中，不会二次下载
    val refresh: () -> Unit = {
        scope.launch {
            val u = username ?: return@launch
            // 记下发起时的筛选：刷新期间用户切了筛选，这份响应就属于旧筛选，不能再写回
            val filterType = uiState.selectedType
            val filterSubjectType = uiState.selectedSubjectType
            fun filterChanged() = filterType != uiState.selectedType || filterSubjectType != uiState.selectedSubjectType
            uiState = uiState.copy(refreshing = true)
            try {
                val result = app.api.getUserCollections(
                    u,
                    subjectType = filterSubjectType,
                    collectionType = filterType,
                    offset = 0
                )
                if (filterChanged()) {
                    // 新筛选的首拉正在进行（会自己覆盖列表）：丢弃过期响应，只把转圈停掉
                    uiState = uiState.copy(refreshing = false)
                    return@launch
                }
                uiState = uiState.copy(
                    refreshing = false,
                    data = result.data.distinctBy { it.subjectId },
                    total = result.total,
                    offset = result.data.size,
                    hasMore = result.data.size < result.total,
                    loadMoreError = false
                )
                // 刷新结果写回筛选缓存：之后滑回这一屏看到的是刚刷过的数据
                filterCache.store(
                    filterType to filterSubjectType,
                    FilterPage(result.data.distinctBy { it.subjectId }, result.total)
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

    LaunchedEffect(Unit) {
        app.settings.username.collect { name -> username = name }
    }

    LaunchedEffect(username, uiState.selectedType, uiState.selectedSubjectType, uiState.retryKey) {
        if (username == null) {
            uiState = uiState.copy(loading = false)
            return@LaunchedEffect
        }
        val key = uiState.selectedType to uiState.selectedSubjectType
        fun prefetchNeighbours() = filterCache.prefetch(neighbourFilters(key.first, key.second))
        // 首次拿到用户名时结算缓存：命中同账号 → 跳过首拉读缓存；无缓存/换号 → 正常拉取
        val restored = restoredUsername
        if (restored != null) {
            restoredUsername = null
            if (restored == username) {
                // 进程缓存可用：灌进筛选缓存并预取邻居，之后左右滑动都是现成的
                filterCache.store(key, FilterPage(uiState.data, uiState.total))
                prefetchNeighbours()
                return@LaunchedEffect
            }
        }
        // 切回已经拉过的筛选：直接给缓存，不再请求（下拉刷新才是手动更新数据的方式）
        filterCache.peek(key)?.let { entry ->
            uiState = uiState.copy(
                loading = false,
                error = null,
                data = entry.page.data,
                total = entry.page.total,
                offset = entry.offset,
                hasMore = entry.hasMore,
                loadMoreError = false
            )
            prefetchNeighbours()
            return@LaunchedEffect
        }
        // hasMore 必须一并清掉：否则列表被换成加载屏时 layoutInfo 为空，
        // nearEnd 会误判成「到底」，与这次首拉并发再取一遍 offset=0，
        // 之后 offset 多加一页 → 中间整页条目被跳过
        uiState = uiState.copy(loading = true, offset = 0, hasMore = false, error = null, loadMoreError = false)
        try {
            // firstPage 内部按筛选去重：预取已经在拉的话这里直接等它，不会重复请求
            val entry = filterCache.firstPage(key)
            uiState = uiState.copy(
                loading = false,
                data = entry.page.data,
                total = entry.page.total,
                offset = entry.offset,
                hasMore = entry.hasMore
            )
            prefetchNeighbours()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    // 滚动接近底部时加载下一页。旧实现把 LaunchedEffect 放在 footer item 内且以
    // offset 为 key：offset 变化 + footer 随数据追加重建都会重触发 → 连环拉完全部收藏
    val nearEnd by remember(waterfall) {
        derivedStateOf {
            // 列表与瀑布流的 layoutInfo 是两种类型，无法用 if 表达式合一，只能分支取值
            val lastVisible: Int
            val totalCount: Int
            if (waterfall) {
                val info = gridState.layoutInfo
                lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
                totalCount = info.totalItemsCount
            } else {
                val info = listState.layoutInfo
                lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                totalCount = info.totalItemsCount
            }
            uiState.hasMore && !uiState.loadMoreError && !uiState.loading && !uiState.refreshing &&
                lastVisible >= totalCount - 4
        }
    }
    LaunchedEffect(nearEnd, uiState.offset, uiState.loadMoreKey) {
        if (!nearEnd || username == null || uiState.loading || uiState.refreshing) return@LaunchedEffect
        try {
            val result = app.api.getUserCollections(
                username!!,
                subjectType = uiState.selectedSubjectType,
                collectionType = uiState.selectedType,
                offset = uiState.offset
            )
            uiState = if (result.data.isEmpty()) {
                uiState.copy(hasMore = false, loadMoreError = false)
            } else {
                val merged = (uiState.data + result.data).distinctBy { it.subjectId }
                // 追加的页也写回缓存，滑走再滑回来时这一段不用重新拉
                filterCache.store(
                    uiState.selectedType to uiState.selectedSubjectType,
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

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("我的收藏") },
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isLoggedIn) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "请先在「我的」页面登录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                ScrollableTabRow(
                    edgePadding = 16.dp,
                    selectedTabIndex = collectionTypes.indexOfFirst { it.first == uiState.selectedType }.coerceAtLeast(0),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    collectionTypes.forEach { (type, label) ->
                        val color = collectionTypeColors[type]
                        Tab(
                            selected = uiState.selectedType == type,
                            onClick = {
                                if (uiState.selectedType == type) {
                                    // 再次点击当前筛选：弹出/收起类型二级筛选
                                    uiState = uiState.copy(showTypeFilter = !uiState.showTypeFilter)
                                } else {
                                    selectCollectionType(type)
                                }
                            },
                            text = {
                                if (color != null) {
                                    Text(label, color = if (uiState.selectedType == type) color else MaterialTheme.colorScheme.onSurface, fontWeight = if (uiState.selectedType == type) FontWeight.Bold else FontWeight.Normal)
                                } else {
                                    Text(label)
                                }
                            }
                        )
                    }
                }

                AnimatedVisibility(visible = uiState.showTypeFilter) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        subjectTypeFilters.forEach { (type, label) ->
                            FilterChip(
                                selected = uiState.selectedSubjectType == type,
                                onClick = { selectSubjectType(type) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                PullToRefreshBox(
                    isRefreshing = uiState.refreshing,
                    onRefresh = refresh,
                    // 过渡挂在内容区上（tab 与二级筛选行留在原地不跟着动）
                    modifier = Modifier.fillMaxSize()
                        .then(collectionSwipeModifier)
                        .then(filterTransition.modifier)
                ) {
                when {
                    uiState.loading -> LoadingView()
                    uiState.error != null -> ErrorView(
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
                    uiState.data.isEmpty() -> EmptyView("暂无收藏")
                    else -> {
                        if (waterfall) {
                            // 与浏览页一致的瀑布流：自适应列宽 + SubjectCard
                            LazyVerticalStaggeredGrid(
                                columns = StaggeredGridCells.Adaptive(110.dp),
                                state = gridState,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalItemSpacing = 6.dp,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // 翻页数据可能重叠，去重后再交给网格（重复 key 会崩溃）
                                items(uiState.data.distinctBy { it.subjectId }, key = { it.subjectId }) { item ->
                                    item.subject?.let { subject ->
                                        SubjectCard(
                                            subject = subject,
                                            onClick = { onSubjectClick(subject.id) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                if (uiState.hasMore) {
                                    item(span = StaggeredGridItemSpan.FullLine) {
                                        CollectionLoadMoreFooter(
                                            loadMoreError = uiState.loadMoreError,
                                            onRetry = {
                                                uiState = uiState.copy(
                                                    loadMoreError = false,
                                                    loadMoreKey = uiState.loadMoreKey + 1
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // 翻页数据可能重叠，去重后再交给列表（重复 key 会崩溃）
                            items(uiState.data.distinctBy { it.subjectId }, key = { it.subjectId }) { item ->
                                item.subject?.let { subject ->
                                    Column {
                                        val cardShape = RoundedCornerShape(18.dp)
                                        val cardBackground = if (isSystemInDarkTheme()) {
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                                        } else {
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                                        }
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(cardShape)
                                                .background(cardBackground, cardShape)
                                                .then(wallpaperGlassPanel(isSystemInDarkTheme()))
                                        ) {
                                            SubjectListItem(
                                                subject = subject,
                                                onClick = { onSubjectClick(subject.id) },
                                                transparentContainer = true
                                            )
                                            if (uiState.selectedType == null) {
                                                item.type?.let { type ->
                                                    val color = collectionTypeColors[type]
                                                    val label = collectionTypeLabels[type]
                                                    if (color != null && label != null) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Surface(
                                                                modifier = Modifier.size(8.dp),
                                                                shape = CircleShape,
                                                                color = color
                                                            ) {}
                                                            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                                                            if (item.rate > 0) {
                                                                Text("★${item.rate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (uiState.hasMore) {
                                item {
                                    CollectionLoadMoreFooter(
                                        loadMoreError = uiState.loadMoreError,
                                        onRetry = {
                                            uiState = uiState.copy(
                                                loadMoreError = false,
                                                loadMoreKey = uiState.loadMoreKey + 1
                                            )
                                        }
                                    )
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
