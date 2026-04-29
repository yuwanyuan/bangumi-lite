package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
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
import com.bangumi.ywylite.data.model.SubjectSmall
import com.bangumi.ywylite.ui.component.*

data class ExploreUiState(
    val loading: Boolean = true,
    val data: List<SubjectSmall> = emptyList(),
    val error: String? = null,
    val selectedType: Int = 2,
    val selectedSort: String = "heat",
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val total: Int = 0
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

private val sortOptions = listOf(
    "rank" to "排名",
    "heat" to "热度"
)

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

    LaunchedEffect(uiState.selectedType, uiState.selectedSort) {
        uiState = uiState.copy(loading = true, offset = 0, error = null)
        try {
            if (uiState.selectedSort == "heat") {
                val result = app.api.searchSubjectsByHeat(type = uiState.selectedType, offset = 0)
                uiState = uiState.copy(
                    loading = false,
                    data = result.data,
                    offset = result.data.size,
                    hasMore = result.data.size < result.total,
                    total = result.total
                )
            } else {
                val result = app.api.browseSubjects(
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
            }
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
                columns = StaggeredGridCells.Fixed(3),
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
                                uiState = uiState.copy(loading = true, error = null, offset = 0)
                            }
                        )
                    }
                    uiState.data.isEmpty() -> item(span = StaggeredGridItemSpan.FullLine) { EmptyView() }
                    else -> {
                        items(uiState.data, key = { it.id }) { subject ->
                            SubjectCard(
                                subject = subject,
                                onClick = { onSubjectClick(subject.id) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (uiState.hasMore) {
                            item(span = StaggeredGridItemSpan.FullLine) {
                                LaunchedEffect(Unit) {
                                    try {
                                        val result = if (uiState.selectedSort == "heat") {
                                            app.api.searchSubjectsByHeat(
                                                type = uiState.selectedType,
                                                offset = uiState.offset
                                            )
                                        } else {
                                            app.api.browseSubjects(
                                                type = uiState.selectedType,
                                                sort = uiState.selectedSort,
                                                offset = uiState.offset
                                            )
                                        }
                                        uiState = uiState.copy(
                                            data = uiState.data + result.data,
                                            offset = uiState.offset + result.data.size,
                                            hasMore = (uiState.offset + result.data.size) < uiState.total
                                        )
                                    } catch (_: Exception) {}
                                }
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
