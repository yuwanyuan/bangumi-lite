package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class CollectionUiState(
    val loading: Boolean = true,
    val data: List<UserCollection> = emptyList(),
    val error: String? = null,
    val selectedType: Int? = 3,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val total: Int = 0
)

private val collectionTypes = listOf(
    null to "全部",
    3 to "在看",
    2 to "看过",
    1 to "想看",
    4 to "搁置",
    5 to "抛弃"
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

    LaunchedEffect(Unit) {
        app.settings.username.collect { name -> username = name }
    }

    LaunchedEffect(username, uiState.selectedType) {
        if (username == null) {
            uiState = uiState.copy(loading = false)
            return@LaunchedEffect
        }
        val currentUsername = username!!
        uiState = uiState.copy(loading = true, offset = 0, error = null)
        try {
            val result = app.api.getUserCollections(
                currentUsername,
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
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
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
                            onClick = { uiState = uiState.copy(selectedType = type) },
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

                when {
                    uiState.loading -> LoadingView()
                    uiState.error != null -> ErrorView(
                        message = uiState.error ?: "加载失败",
                        onRetry = {
                            uiState = uiState.copy(loading = true, error = null, offset = 0)
                        }
                    )
                    uiState.data.isEmpty() -> EmptyView("暂无收藏")
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uiState.data, key = { it.subject_id }) { item ->
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
                                    LaunchedEffect(Unit) {
                                        try {
                                            val result = app.api.getUserCollections(
                                                username!!,
                                                collectionType = uiState.selectedType,
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
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
