package com.bangumi.ywylite.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.PagedUserCollection
import com.bangumi.ywylite.data.model.UserCollection
import com.bangumi.ywylite.ui.component.*
import kotlinx.coroutines.CancellationException

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
    val loadMoreError: Boolean = false
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
    var uiState by remember { mutableStateOf(CollectionUiState()) }
    var username by remember { mutableStateOf<String?>(null) }
    // 条目样式：瀑布流（与浏览页一致）或经典列表，设置页可随时切换
    val waterfall by app.settings.collectionWaterfall.collectAsState(initial = false)
    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()

    LaunchedEffect(Unit) {
        app.settings.username.collect { name -> username = name }
    }

    LaunchedEffect(username, uiState.selectedType, uiState.selectedSubjectType, uiState.retryKey) {
        if (username == null) {
            uiState = uiState.copy(loading = false)
            return@LaunchedEffect
        }
        val currentUsername = username!!
        uiState = uiState.copy(loading = true, offset = 0, error = null, loadMoreError = false)
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
            uiState.hasMore && !uiState.loadMoreError &&
                lastVisible >= totalCount - 4
        }
    }
    LaunchedEffect(nearEnd, uiState.offset, uiState.loadMoreKey) {
        if (!nearEnd || username == null) return@LaunchedEffect
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
        topBar = {
            TopAppBar(
                title = { Text("我的收藏") },
                actions = {
                    IconButton(onClick = onSearchClick) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
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
                    containerColor = MaterialTheme.colorScheme.surface,
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
                                    uiState = uiState.copy(selectedType = type)
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
                                onClick = { uiState = uiState.copy(selectedSubjectType = type) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

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
                            items(uiState.data, key = { it.subjectId }) { item ->
                                item.subject?.let { subject ->
                                    Column {
                                        SubjectListItem(
                                            subject = subject,
                                            onClick = { onSubjectClick(subject.id) }
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
                                                        shape = androidx.compose.foundation.shape.CircleShape,
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
