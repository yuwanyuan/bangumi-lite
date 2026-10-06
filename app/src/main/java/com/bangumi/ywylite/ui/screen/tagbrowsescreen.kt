package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.data.api.BangumiApi
import com.bangumi.ywylite.data.model.SubjectSmall
import com.bangumi.ywylite.ui.component.*
import kotlinx.coroutines.CancellationException

data class TagBrowseUiState(
    val loading: Boolean = true,
    val data: List<SubjectSmall> = emptyList(),
    val error: String? = null,
    val selectedType: Int = 2,
    /** 网页分页用页号直传（网页每页固定 24 条，原先用 offset/30 换算，第 2 页起数据错位） */
    val page: Int = 1,
    val totalPages: Int = 1,
    /** 重试信号：+1 触发主 LaunchedEffect 重新加载 */
    val retryKey: Int = 0,
    /** 加载更多重试信号 */
    val loadMoreKey: Int = 0,
    /** 加载更多失败标记 */
    val loadMoreError: Boolean = false
)

private val tagBrowseSubjectTypes = listOf(
    2 to "动画",
    1 to "书籍",
    4 to "游戏",
    3 to "音乐",
    6 to "三次元"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagBrowseScreen(
    tagName: String,
    tagSlug: String,
    type: Int,
    api: BangumiApi,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit
) {
    var uiState by remember { mutableStateOf(TagBrowseUiState(selectedType = type)) }
    val gridState = rememberLazyStaggeredGridState()

    LaunchedEffect(uiState.selectedType, uiState.retryKey) {
        uiState = uiState.copy(loading = true, page = 1, error = null, loadMoreError = false)
        try {
            val result = api.browseByTag(type = uiState.selectedType, tag = tagSlug, page = 1)
            uiState = uiState.copy(
                loading = false,
                data = result.subjects,
                page = result.page,
                totalPages = result.totalPages
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    // 滚动接近底部时加载下一页（同 ExploreScreen：footer 内 LaunchedEffect 会被
    // 数据追加重建导致连环翻页，统一改为滚动位置检测 + 显式失败重试）
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            uiState.page < uiState.totalPages && !uiState.loadMoreError &&
                lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, uiState.page, uiState.loadMoreKey) {
        if (!nearEnd) return@LaunchedEffect
        try {
            val nextPage = uiState.page + 1
            val result = api.browseByTag(type = uiState.selectedType, tag = tagSlug, page = nextPage)
            uiState = if (result.subjects.isEmpty()) {
                // 下一页为空说明已到末页（HTML 分页信息缺失时的兜底）
                uiState.copy(totalPages = uiState.page, loadMoreError = false)
            } else {
                uiState.copy(
                    data = (uiState.data + result.subjects).distinctBy { it.id },
                    page = nextPage,
                    totalPages = result.totalPages,
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
                title = { Text(tagName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = tagBrowseSubjectTypes.indexOfFirst { it.first == uiState.selectedType }.coerceAtLeast(0),
                edgePadding = 16.dp,
                modifier = Modifier.height(52.dp)
            ) {
                tagBrowseSubjectTypes.forEach { (t, label) ->
                    Tab(
                        selected = uiState.selectedType == t,
                        onClick = { uiState = uiState.copy(selectedType = t) },
                        text = { Text(label) }
                    )
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
                            page = 1,
                            retryKey = uiState.retryKey + 1
                        )
                    }
                )
                uiState.data.isEmpty() -> EmptyView()
                else -> {
                    LazyVerticalStaggeredGrid(
                        // 自适应列宽：窄屏 3 列左右，平板自动多列
                        columns = StaggeredGridCells.Adaptive(110.dp),
                        state = gridState,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalItemSpacing = 6.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.data.distinctBy { it.id }, key = { it.id }) { subject ->
                            SubjectCard(
                                subject = subject,
                                onClick = { onSubjectClick(subject.id) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (uiState.page < uiState.totalPages) {
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
