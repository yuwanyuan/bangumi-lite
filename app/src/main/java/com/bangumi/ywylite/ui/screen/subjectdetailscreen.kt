package com.bangumi.ywylite.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import coil.compose.AsyncImage
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.*
import com.bangumi.ywylite.ui.component.EmptyView
import com.bangumi.ywylite.ui.component.ErrorView
import com.bangumi.ywylite.ui.component.LoadingView
import kotlinx.coroutines.launch

data class SubjectDetailUiState(
    val loading: Boolean = true,
    val subject: Subject? = null,
    val error: String? = null,
    val userCollection: UserCollection? = null,
    val episodes: List<Episode> = emptyList(),
    val watchedEpisodes: Set<Int> = emptySet(),
    val relatedSubjects: List<RelatedSubject> = emptyList(),
    val comments: List<CommentItem> = emptyList(),
    val commentOffset: Int = 0,
    val commentHasMore: Boolean = false
)

val collectionTypeColors = mapOf(
    1 to Color(0xFF2196F3),
    2 to Color(0xFF4CAF50),
    3 to Color(0xFFFF9800),
    4 to Color(0xFF9E9E9E),
    5 to Color(0xFFF44336)
)

val collectionTypeLabels = mapOf(
    1 to "想看",
    2 to "看过",
    3 to "在看",
    4 to "搁置",
    5 to "抛弃"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectDetailScreen(
    subjectId: Int,
    onBack: () -> Unit,
    onOpenWebView: (String, String) -> Unit = { _, _ -> },
    onTagClick: (String, Int) -> Unit = { _, _ -> }
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(SubjectDetailUiState()) }
    val token by app.settings.accessToken.collectAsState(initial = null)

    LaunchedEffect(subjectId) {
        uiState = uiState.copy(loading = true, error = null)
        try {
            val subject = app.api.getSubject(subjectId)
            var episodes = emptyList<Episode>()
            var relatedSubjects = emptyList<RelatedSubject>()
            var comments = emptyList<CommentItem>()
            var userCollection: UserCollection? = null
            var watchedEpisodes = emptySet<Int>()

            val episodesResult = runCatching { app.api.getEpisodes(subjectId).data }
            episodes = episodesResult.getOrDefault(emptyList())
            val relatedResult = runCatching { app.api.getRelatedSubjects(subjectId) }
            relatedSubjects = relatedResult.getOrDefault(emptyList())
            val commentsResult = runCatching { app.api.getComments(subjectId, limit = 20) }
            comments = commentsResult.getOrNull()?.data ?: emptyList()
            val commentTotal = commentsResult.getOrNull()?.total ?: 0
            val initialCommentOffset = comments.size
            val initialCommentHasMore = initialCommentOffset < commentTotal
            if (token != null) {
                val collectionResult = runCatching { app.api.getSubjectCollection(subjectId, token) }
                userCollection = collectionResult.getOrNull()
                val epCollectionResult = runCatching { app.api.getEpisodeCollection(subjectId) }
                val epCollections = epCollectionResult.getOrNull()
                if (epCollections != null) {
                    watchedEpisodes = epCollections.filter { it.type == 2 }.mapNotNull { it.episode?.id }.toSet()
                }
            }

            uiState = uiState.copy(
                loading = false,
                subject = subject,
                episodes = episodes,
                relatedSubjects = relatedSubjects,
                comments = comments,
                commentOffset = initialCommentOffset,
                commentHasMore = initialCommentHasMore,
                userCollection = userCollection,
                watchedEpisodes = watchedEpisodes
            )
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    when {
        uiState.loading -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("加载中...") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding -> LoadingView(Modifier.padding(padding)) }
        uiState.error != null -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("条目详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            ErrorView(
                message = uiState.error ?: "加载失败",
                onRetry = {
                    scope.launch {
                        uiState = uiState.copy(loading = true, error = null)
                        try {
                            val subject = app.api.getSubject(subjectId)
                            uiState = uiState.copy(loading = false, subject = subject)
                        } catch (e: Exception) {
                            uiState = uiState.copy(loading = false, error = e.message)
                        }
                    }
                },
                modifier = Modifier.padding(padding)
            )
        }
        uiState.subject != null -> SubjectDetailContent(
            uiState = uiState,
            onCollect = { type, comment, rate ->
                scope.launch {
                    try {
                        app.api.collectSubject(subjectId, type, rate = rate, comment = comment)
                        if (type == 0) {
                            uiState = uiState.copy(userCollection = null)
                        } else if (token != null) {
                            val result = runCatching { app.api.getSubjectCollection(subjectId, token) }
                            uiState = uiState.copy(userCollection = result.getOrNull())
                        }
                    } catch (_: Exception) {}
                }
            },
            onEpisodeClick = { episodeId ->
                scope.launch {
                    try {
                        val isWatched = episodeId in uiState.watchedEpisodes
                        if (isWatched) {
                            app.api.updateEpisodeStatus(listOf(episodeId), 0)
                            uiState = uiState.copy(watchedEpisodes = uiState.watchedEpisodes - episodeId)
                        } else {
                            app.api.updateEpisodeStatus(listOf(episodeId), 2)
                            uiState = uiState.copy(watchedEpisodes = uiState.watchedEpisodes + episodeId)
                        }
                    } catch (_: Exception) {}
                }
            },
            onOpenWebView = onOpenWebView,
            onTagClick = onTagClick,
            onBack = onBack,
            onLoadMoreComments = {
                scope.launch {
                    try {
                        val result = app.api.getComments(subjectId, offset = uiState.commentOffset)
                        uiState = uiState.copy(
                            comments = uiState.comments + result.data,
                            commentOffset = uiState.commentOffset + result.data.size,
                            commentHasMore = (uiState.commentOffset + result.data.size) < result.total
                        )
                    } catch (_: Exception) {}
                }
            }
        )
        else -> EmptyView()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubjectDetailContent(
    uiState: SubjectDetailUiState,
    onCollect: (Int, String?, Int?) -> Unit,
    onEpisodeClick: (Int) -> Unit,
    onOpenWebView: (String, String) -> Unit,
    onTagClick: (String, Int) -> Unit,
    onBack: () -> Unit,
    onLoadMoreComments: () -> Unit
) {
    val subject = uiState.subject ?: return
    val imageUrl = subject.images?.common?.replace("http://", "https://")
        ?: subject.images?.medium?.replace("http://", "https://")
        ?: subject.image.replace("http://", "https://")

    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.15f
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        subject.nameCn.ifEmpty { subject.name },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "更多",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("在浏览器中查看") },
                                onClick = {
                                    showMenu = false
                                    val url = subject.url.ifEmpty { "https://bgm.tv/subject/${subject.id}" }
                                    onOpenWebView(url, subject.nameCn.ifEmpty { subject.name })
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = subject.nameCn.ifEmpty { subject.name },
                        modifier = Modifier
                            .width(120.dp)
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SelectionContainer {
                            Text(
                                text = subject.nameCn.ifEmpty { subject.name },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (subject.nameCn.isNotEmpty()) {
                            SelectionContainer {
                                Text(
                                    text = subject.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        subject.rating?.let { rating ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(imageVector = Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text("${rating.score}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text("${rating.total}人评分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        subject.collection?.let { col ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                                CollectionCountChip("想看", col.wish, Color(0xFF2196F3))
                                CollectionCountChip("看过", col.done, Color(0xFF4CAF50))
                                CollectionCountChip("在看", col.doing, Color(0xFFFF9800))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CollectionCountChip("搁置", col.onHold, Color(0xFF9E9E9E))
                                CollectionCountChip("抛弃", col.dropped, Color(0xFFF44336))
                            }
                        }
                        if (subject.airDate.isNotEmpty()) Text("放送: ${subject.airDate}", style = MaterialTheme.typography.bodySmall)
                        if (subject.eps > 0) Text("话数: ${subject.eps}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                CollectionBar(currentType = uiState.userCollection?.type, onCollect = onCollect)

                if (subject.summary.isNotEmpty()) {
                    CollapsibleSummary(summary = subject.summary)
                }

                if (subject.tags.isNotEmpty()) {
                    TagsSection(
                        tags = subject.tags,
                        subjectType = subject.type,
                        onTagClick = onTagClick
                    )
                }

                if (uiState.episodes.isNotEmpty()) {
                    EpisodeBlockSection(
                        episodes = uiState.episodes,
                        watchedEpisodes = uiState.watchedEpisodes,
                        onEpisodeClick = onEpisodeClick
                    )
                }

                if (uiState.relatedSubjects.isNotEmpty()) {
                    RelatedSection(relatedSubjects = uiState.relatedSubjects, onSubjectClick = {})
                }

                CommentSection(
                    comments = uiState.comments,
                    hasMore = uiState.commentHasMore,
                    onLoadMore = onLoadMoreComments,
                    onUserClick = { username ->
                        onOpenWebView(
                            "https://bgm.tv/user/$username",
                            username
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    tags: List<TagInfo>,
    subjectType: Int,
    onTagClick: (String, Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val maxVisibleTags = 10
    val shouldFold = tags.size > maxVisibleTags
    val displayTags = if (expanded || !shouldFold) tags else tags.take(maxVisibleTags)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (shouldFold) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            displayTags.forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.clickable { onTagClick(tag.name, subjectType) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (tag.count > 0) {
                            Text(
                                text = "${tag.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSummary(summary: String) {
    var expanded by remember { mutableStateOf(false) }
    val maxLines = 3

    Column {
        Text("简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(if (expanded) "收起" else "展开全文")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeBlockSection(
    episodes: List<Episode>,
    watchedEpisodes: Set<Int>,
    onEpisodeClick: (Int) -> Unit
) {
    val shouldFold = episodes.size > 12
    var expanded by remember { mutableStateOf(!shouldFold) }
    var selectedEpisodeId by remember { mutableIntStateOf(-1) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (shouldFold) Modifier.clickable { expanded = !expanded } else Modifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("章节 (${episodes.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (shouldFold) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                episodes.forEach { episode ->
                    val isWatched = watchedEpisodes.contains(episode.id)
                    val isSelected = selectedEpisodeId == episode.id

                    Column {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when {
                                isWatched -> MaterialTheme.colorScheme.primary
                                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 32.dp)
                                .clickable {
                                    if (selectedEpisodeId == episode.id) {
                                        onEpisodeClick(episode.id)
                                        selectedEpisodeId = -1
                                    } else {
                                        selectedEpisodeId = episode.id
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = episode.ep?.toString() ?: episode.sort.toInt().toString() ?: "?",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        isWatched -> MaterialTheme.colorScheme.onPrimary
                                        isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        AnimatedVisibility(visible = isSelected) {
                            Text(
                                text = episode.nameCn.ifEmpty { episode.name },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionCountChip(label: String, count: Int, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.height(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontSize = 10.sp)
            Text("$count", style = MaterialTheme.typography.labelSmall, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CollectionBar(
    currentType: Int?,
    onCollect: (Int, String?, Int?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var selectedType by remember { mutableIntStateOf(currentType ?: 3) }
    var comment by remember { mutableStateOf("") }
    var rating by remember { mutableIntStateOf(0) }

    val isCollected = currentType != null
    val currentColor = collectionTypeColors[currentType] ?: MaterialTheme.colorScheme.primary
    val currentLabel = collectionTypeLabels[currentType] ?: "收藏"

    LaunchedEffect(currentType) {
        if (currentType != null) {
            selectedType = currentType
        }
    }

    if (isCollected) {
        Button(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = currentColor.copy(alpha = 0.15f),
                contentColor = currentColor
            )
        ) {
            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(currentLabel, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("收藏")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("收藏与评分") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("收藏状态", style = MaterialTheme.typography.labelMedium)
                    listOf(3 to "在看", 2 to "看过", 1 to "想看", 4 to "搁置", 5 to "抛弃").forEach { (type, label) ->
                        val color = collectionTypeColors[type] ?: Color.Gray
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { selectedType = type }
                        ) {
                            RadioButton(selected = selectedType == type, onClick = { selectedType = type }, colors = RadioButtonDefaults.colors(selectedColor = color))
                            Text(label, color = if (selectedType == type) color else MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Text("评分", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..10).forEach { i ->
                            OutlinedButton(
                                onClick = { rating = if (rating == i) 0 else i },
                                modifier = Modifier.size(32.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (rating >= i) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    contentColor = if (rating >= i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text("$i", fontSize = 10.sp)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("评论（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onCollect(selectedType, comment.ifBlank { null }, if (rating > 0) rating else null)
                    showDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (isCollected) {
                        TextButton(onClick = {
                            onCollect(0, null, null)
                            showDialog = false
                        }) { Text("取消收藏", color = Color(0xFFF44336)) }
                    }
                    TextButton(onClick = { showDialog = false }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun RelatedSection(
    relatedSubjects: List<RelatedSubject>,
    onSubjectClick: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("关联条目 (${relatedSubjects.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (expanded) {
        relatedSubjects.forEach { related ->
            ListItem(
                headlineContent = { Text(related.nameCn.ifEmpty { related.name }, style = MaterialTheme.typography.bodySmall) },
                supportingContent = { Text(related.relation, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.clickable { onSubjectClick(related.id) }
            )
        }
    }
}

@Composable
private fun CommentSection(
    comments: List<CommentItem>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("评论 (${comments.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        comments.forEach { comment ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val avatarUrl = comment.user?.avatar?.medium?.replace("http://", "https://") ?: ""
                        if (avatarUrl.isNotEmpty()) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = comment.user?.nickname ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val username = comment.user?.username ?: ""
                                if (username.isNotEmpty()) {
                                    onUserClick(username)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (comment.rate > 0) {
                            Text("★${comment.rate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (comment.comment.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SelectionContainer {
                            Text(comment.comment, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (hasMore) {
            TextButton(onClick = onLoadMore, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("加载更多评论")
            }
        }
    }
}
