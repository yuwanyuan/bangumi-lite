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
    // 切换筛选时的内容过渡（滑出旧内容 → 换筛选 → 从另一侧滑入）
    val filterTransition = rememberFilterTransition()
    var switchJob by remember { mutableStateOf<Job?>(null) }
    // 横滑序列：全部 → 看过 → 想看。其余状态（在看/搁置/抛弃）不在序列里，
    // 由它们起滑时按标签顺序取方向上相邻的序列项，越界就原地不动
    val swipeCollectionTypes = listOf<Int?>(null, 2, 1)
    fun swipeTarget(from: Int?, forward: Boolean): Int? {
        val fromIndex = collectionTypes.indexOfFirst { it.first == from }
        val cycleIndex = swipeCollectionTypes.indexOf(from)
        if (cycleIndex >= 0) {
            return swipeCollectionTypes[(cycleIndex + if (forward) 1 else -1).coerceIn(0, swipeCollectionTypes.lastIndex)]
        }
        val candidates = swipeCollectionTypes.filter { target ->
            val index = collectionTypes.indexOfFirst { it.first == target }
            if (forward) index > fromIndex else index < fromIndex
        }
        return if (forward) candidates.firstOrNull() else candidates.lastOrNull()
    }
    // 切换中的目标筛选：过渡还没走完时 uiState 仍是旧筛选，
    // 下一次滑动要相对「正在切过去的那一屏」算方向，否则中间那次滑动会被当成原地不动丢掉
    var pendingFilter by remember { mutableStateOf<Pair<Int?, Int?>?>(null) }
    fun selectCollectionType(type: Int?) {
        val from = pendingFilter ?: (uiState.selectedType to uiState.selectedSubjectType)
        if (type == from.first) return
        // 方向按 tab 顺序算：目标位次在更后面 = 向左切换（与左滑同一个方向）
        val forward = collectionTypes.indexOfFirst { it.first == type } >
            collectionTypes.indexOfFirst { it.first == from.first }
        pendingFilter = type to from.second
        switchJob?.cancel()
        switchJob = scope.launch {
            filterTransition.run(forward) {
                if (waterfall) gridState.scrollToItem(0) else listState.scrollToItem(0)
                uiState = uiState.copy(selectedType = type)
            }
            if (pendingFilter == (type to from.second)) pendingFilter = null
        }
    }
    fun selectSubjectType(type: Int?) {
        val from = pendingFilter ?: (uiState.selectedType to uiState.selectedSubjectType)
        if (type == from.second) return
        val forward = subjectTypeFilters.indexOfFirst { it.first == type } >
            subjectTypeFilters.indexOfFirst { it.first == from.second }
        pendingFilter = from.first to type
        switchJob?.cancel()
        switchJob = scope.launch {
            filterTransition.run(forward) {
                if (waterfall) gridState.scrollToItem(0) else listState.scrollToItem(0)
                uiState = uiState.copy(selectedSubjectType = type)
            }
            if (pendingFilter == (from.first to type)) pendingFilter = null
        }
    }
    // 阈值按 dp 换算：裸像素在不同密度屏上手感差一倍
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }
    val collectionSwipeModifier = Modifier.pointerInput(uiState.selectedType, waterfall, swipeThresholdPx) {
        var dragDistance = 0f
        detectHorizontalDragGestures(
            onHorizontalDrag = { _, amount -> dragDistance += amount },
            onDragEnd = {
                if (kotlin.math.abs(dragDistance) >= swipeThresholdPx) {
                    val targetType = swipeTarget(uiState.selectedType, forward = dragDistance < 0f)
                    if (targetType != uiState.selectedType) selectCollectionType(targetType)
                }
                dragDistance = 0f
            },
            onDragCancel = { dragDistance = 0f }
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
        // 首次拿到用户名时结算缓存：命中同账号 → 跳过首拉读缓存；无缓存/换号 → 正常拉取
        val restored = restoredUsername
        if (restored != null) {
            restoredUsername = null
            if (restored == username) return@LaunchedEffect
        }
        val currentUsername = username!!
        // hasMore 必须一并清掉：否则列表被换成加载屏时 layoutInfo 为空，
        // nearEnd 会误判成「到底」，与这次首拉并发再取一遍 offset=0，
        // 之后 offset 多加一页 → 中间整页条目被跳过
        uiState = uiState.copy(loading = true, offset = 0, hasMore = false, error = null, loadMoreError = false)
        try {
            val result = app.api.getUserCollections(
                currentUsername,
                subjectType = uiState.selectedSubjectType,
                collectionType = uiState.selectedType,
                offset = 0
            )
            uiState = uiState.copy(
                loading = false,
                data = result.data,
                total = result.total,
                offset = result.data.size,
                hasMore = result.data.size < result.total
            )
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
                uiState.copy(
                    data = (uiState.data + result.data).distinctBy { it.subjectId },
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
