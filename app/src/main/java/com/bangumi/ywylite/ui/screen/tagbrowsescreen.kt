package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
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
import kotlinx.coroutines.launch

data class TagBrowseUiState(
    val loading: Boolean = true,
    val data: List<SubjectSmall> = emptyList(),
    val error: String? = null,
    val selectedType: Int = 2,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val total: Int = 0
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
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(TagBrowseUiState(selectedType = type)) }

    LaunchedEffect(uiState.selectedType) {
        uiState = uiState.copy(loading = true, offset = 0, error = null)
        try {
            val result = api.browseByTag(type = uiState.selectedType, tag = tagSlug, offset = 0)
            uiState = uiState.copy(
                loading = false,
                data = result.data,
                offset = result.data.size,
                hasMore = result.data.size < result.total,
                total = result.total
            )
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
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
                uiState.error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(uiState.error ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    }
                }
                uiState.data.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalItemSpacing = 6.dp,
                        modifier = Modifier.fillMaxSize()
                    ) {
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
                                        val result = api.browseByTag(
                                            type = uiState.selectedType,
                                            tag = tagSlug,
                                            offset = uiState.offset
                                        )
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

private fun formatRatingCount(count: Int): String {
    return when {
        count >= 10000 -> "${count / 10000}万"
        count >= 1000 -> "${count / 1000}k"
        else -> count.toString()
    }
}
