package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bangumi.ywylite.data.api.BangumiApi
import com.bangumi.ywylite.data.model.TagInfo
import com.bangumi.ywylite.ui.component.ErrorView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class TagsUiState(
    val loading: Boolean = true,
    val tags: List<TagInfo> = emptyList(),
    val error: String? = null,
    val selectedType: Int = 2,
    /** 重试信号：+1 触发主 LaunchedEffect 重新加载 */
    val retryKey: Int = 0
)

/** 进程内标签缓存：标签列表基本不变，缓存后重进页面不再重发请求 */
private val tagsCache = mutableMapOf<String, List<TagInfo>>()

private val tagsSubjectTypes = listOf(
    2 to "动画",
    1 to "书籍",
    4 to "游戏",
    3 to "音乐",
    6 to "三次元"
)

private val tagsTypePaths = mapOf(
    2 to "anime",
    1 to "book",
    4 to "game",
    3 to "music",
    6 to "real"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    typePath: String,
    api: BangumiApi,
    onBack: () -> Unit,
    onNavigateToTagBrowse: (String, String, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(TagsUiState()) }

    LaunchedEffect(uiState.selectedType, uiState.retryKey) {
        uiState = uiState.copy(loading = true, error = null, tags = emptyList())
        val path = tagsTypePaths[uiState.selectedType] ?: "anime"
        // 命中缓存直接展示，避免每次进入都重发请求
        tagsCache[path]?.let { cached ->
            uiState = uiState.copy(loading = false, tags = cached)
            return@LaunchedEffect
        }
        try {
            val tags = api.getTags(path)
            uiState = uiState.copy(loading = false, tags = tags)
            tagsCache[path] = tags
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("标签") },
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
                selectedTabIndex = tagsSubjectTypes.indexOfFirst { it.first == uiState.selectedType }.coerceAtLeast(0),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {}
            ) {
                tagsSubjectTypes.forEach { (type, label) ->
                    val selected = uiState.selectedType == type
                    Tab(
                        selected = selected,
                        onClick = { uiState = uiState.copy(selectedType = type) },
                        text = {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.border(
                                    width = 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(16.dp)
                                )
                            ) {
                                Text(
                                    text = label,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    )
                }
            }

            HorizontalDivider()

            when {
                uiState.loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                uiState.error != null -> ErrorView(
                    message = uiState.error ?: "加载失败",
                    onRetry = {
                        uiState = uiState.copy(
                            loading = true,
                            error = null,
                            tags = emptyList(),
                            retryKey = uiState.retryKey + 1
                        )
                    }
                )
                uiState.tags.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        // 自适应列宽：窄屏约 4 列，平板自动多列
                        columns = GridCells.Adaptive(88.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.tags, key = { it.name }) { tag ->
                            TagCard(tag = tag, onClick = {
                                onNavigateToTagBrowse(tag.name, tag.name, uiState.selectedType)
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagCard(tag: TagInfo, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            // 标签下条目数（网页 /{type}/tag 自带，稳定且一次请求全部拿到）
            if (tag.count > 0) {
                Text(
                    text = formatTagCount(tag.count),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun formatTagCount(count: Int): String {
    return when {
        count >= 100000 -> "${"%.1f".format(count / 10000.0)}w"
        count >= 10000 -> "${count / 10000}w"
        count >= 1000 -> "${"%.1f".format(count / 1000.0)}k"
        else -> "$count"
    }
}
