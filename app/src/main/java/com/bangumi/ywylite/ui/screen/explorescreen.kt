package com.bangumi.ywylite.ui.screen

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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.api.BangumiApi
import com.bangumi.ywylite.data.model.PagedSubject
import com.bangumi.ywylite.data.model.SubjectSmall
import com.bangumi.ywylite.ui.component.*
import kotlinx.coroutines.CancellationException

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
    val loadMoreError: Boolean = false
)

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
    var uiState by remember { mutableStateOf(ExploreUiState()) }
    val gridState = rememberLazyStaggeredGridState()

    // 滚动接近底部时加载下一页（替代 footer 内 LaunchedEffect：
    // footer item 随数据追加会被 LazyGrid 重建，旧写法会连环拉完全部数据且失败静默）
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            uiState.hasMore && lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, uiState.offset, uiState.loadMoreKey) {
        if (!nearEnd) return@LaunchedEffect
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
                uiState.copy(
                    data = (uiState.data + result.data).distinctBy { it.id },
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

    LaunchedEffect(uiState.selectedType, uiState.selectedSort, uiState.retryKey) {
        uiState = uiState.copy(loading = true, offset = 0, error = null, loadMoreError = false)
        try {
            val result = fetchBrowsePage(
                api = app.api,
                type = uiState.selectedType,
                sort = uiState.selectedSort,
                offset = 0
            )
            uiState = uiState.copy(
                loading = false,
                data = result.data,
                offset = result.data.size,
                hasMore = result.data.size < result.total,
                total = result.total
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    Scaffold(
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
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(
                edgePadding = 16.dp,
                selectedTabIndex = subjectTypes.indexOfFirst { it.first == uiState.selectedType }.coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.height(52.dp)
            ) {
                subjectTypes.forEach { (type, label) ->
                    Tab(
                        selected = uiState.selectedType == type,
                        onClick = { uiState = uiState.copy(selectedType = type) },
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
                        onClick = { uiState = uiState.copy(selectedSort = value) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

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
