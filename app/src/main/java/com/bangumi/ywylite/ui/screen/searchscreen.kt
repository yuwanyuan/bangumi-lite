package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.PagedSubject
import com.bangumi.ywylite.data.model.SubjectSmall
import com.bangumi.ywylite.ui.component.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** 搜索范围：null = all（条目（所有）），其余为条目类型（v0 搜索的 filter.type） */
private val searchScopes = listOf(
    null to "all",
    2 to "动画",
    1 to "书籍",
    4 to "游戏",
    3 to "音乐",
    6 to "三次元"
)

@Serializable
data class SearchUiState(
    val query: String = "",
    /** 当前搜索范围，null = 条目（所有） */
    val selectedType: Int? = null,
    val loading: Boolean = false,
    val results: List<SubjectSmall> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val error: String? = null,
    val hasMore: Boolean = false,
    /** 加载更多重试信号（带默认值，旧保存的 JSON 反序列化不受影响） */
    val loadMoreKey: Int = 0,
    /** 加载更多失败标记：显示"点击重试"替代永久转圈 */
    val loadMoreError: Boolean = false
)

/** 搜索状态整体 JSON 化保存：从条目详情返回时还原关键词与结果，而不是回到空白初始页 */
private val searchStateSaver = Saver<SearchUiState, String>(
    save = { Json.encodeToString(SearchUiState.serializer(), it) },
    restore = {
        runCatching { Json.decodeFromString(SearchUiState.serializer(), it) }
            .getOrDefault(SearchUiState())
            .copy(loading = false)
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onSubjectClick: (Int) -> Unit,
    onBack: () -> Unit = {}
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    var uiState by rememberSaveable(stateSaver = searchStateSaver) { mutableStateOf(SearchUiState()) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()

    // 全新搜索（非翻页追加）；type 显式传参，避免与切范围的并发请求互相串值
    suspend fun freshSearch(query: String, type: Int?) {
        uiState = uiState.copy(loading = true, error = null, offset = 0, loadMoreError = false)
        try {
            val result = app.api.searchSubjects(query, type = type?.let { listOf(it) }, offset = 0)
            uiState = uiState.copy(
                loading = false,
                results = result.data,
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

    // 切换搜索范围：有关键词时立即按新范围重搜
    val selectScope: (Int?) -> Unit = { type ->
        if (uiState.selectedType != type) {
            uiState = uiState.copy(selectedType = type)
            searchJob?.cancel()
            if (uiState.query.isNotBlank()) {
                searchJob = scope.launch { freshSearch(uiState.query, type) }
            }
        }
    }

    // 滚动接近底部时加载下一页（原 footer 内 LaunchedEffect 失败被吞、且会被数据追加重建）
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            uiState.hasMore && !uiState.loadMoreError &&
                lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, uiState.offset, uiState.loadMoreKey) {
        if (!nearEnd) return@LaunchedEffect
        try {
            val result = app.api.searchSubjects(
                uiState.query,
                type = uiState.selectedType?.let { listOf(it) },
                offset = uiState.offset
            )
            uiState = if (result.data.isEmpty()) {
                uiState.copy(hasMore = false, loadMoreError = false)
            } else {
                uiState.copy(
                    results = (uiState.results + result.data).distinctBy { it.id },
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
                title = {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = { newQuery ->
                            uiState = uiState.copy(query = newQuery)
                            searchJob?.cancel()
                            searchJob = scope.launch {
                                delay(500)
                                if (newQuery.isNotBlank()) {
                                    freshSearch(newQuery, uiState.selectedType)
                                } else {
                                    uiState = uiState.copy(results = emptyList(), total = 0, offset = 0)
                                }
                            }
                        },
                        placeholder = { Text("搜索动画、书籍、游戏…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 搜索范围筛选条：条目（所有）/ 动画 / 书籍 / 游戏 / 音乐 / 三次元
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                searchScopes.forEach { (type, label) ->
                    FilterChip(
                        selected = uiState.selectedType == type,
                        onClick = { selectScope(type) },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            when {
                uiState.query.isBlank() -> {
                    EmptyView("输入关键词开始搜索")
                }
                uiState.loading && uiState.results.isEmpty() -> {
                    LoadingView()
                }
                uiState.error != null && uiState.results.isEmpty() -> {
                    ErrorView(
                        message = uiState.error ?: "搜索失败",
                        onRetry = {
                            searchJob?.cancel()
                            searchJob = scope.launch { freshSearch(uiState.query, uiState.selectedType) }
                        }
                    )
                }
                uiState.results.isEmpty() -> {
                    EmptyView("没有找到相关结果")
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.results, key = { it.id }) { subject ->
                            SubjectListItem(
                                subject = subject,
                                onClick = { onSubjectClick(subject.id) }
                            )
                        }
                        if (uiState.hasMore) {
                            item {
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = androidx.compose.ui.Alignment.Center
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
