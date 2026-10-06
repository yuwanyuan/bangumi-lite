package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

@Serializable
data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<SubjectSmall> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val error: String? = null,
    val hasMore: Boolean = false
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
                                    uiState = uiState.copy(loading = true, offset = 0, error = null)
                                    try {
                                        val result = app.api.searchSubjects(newQuery, offset = 0)
                                        uiState = uiState.copy(
                                            loading = false,
                                            results = result.data,
                                            total = result.total,
                                            offset = result.data.size,
                                            hasMore = result.data.size < result.total
                                        )
                                    } catch (e: Exception) {
                                        uiState = uiState.copy(loading = false, error = e.message)
                                    }
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
                            scope.launch {
                                uiState = uiState.copy(loading = true, error = null)
                                try {
                                    val result = app.api.searchSubjects(uiState.query)
                                    uiState = uiState.copy(
                                        loading = false,
                                        results = result.data,
                                        total = result.total,
                                        offset = result.data.size,
                                        hasMore = result.data.size < result.total
                                    )
                                } catch (e: Exception) {
                                    uiState = uiState.copy(loading = false, error = e.message)
                                }
                            }
                        }
                    )
                }
                uiState.results.isEmpty() -> {
                    EmptyView("没有找到相关结果")
                }
                else -> {
                    LazyColumn(
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
                                LaunchedEffect(Unit) {
                                    try {
                                        val result = app.api.searchSubjects(
                                            uiState.query,
                                            offset = uiState.offset
                                        )
                                        uiState = uiState.copy(
                                            results = uiState.results + result.data,
                                            offset = uiState.offset + result.data.size,
                                            hasMore = (uiState.offset + result.data.size) < uiState.total
                                        )
                                    } catch (_: Exception) {}
                                }
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
