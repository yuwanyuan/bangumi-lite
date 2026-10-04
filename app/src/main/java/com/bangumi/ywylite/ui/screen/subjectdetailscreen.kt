package com.bangumi.ywylite.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import com.bangumi.ywylite.ui.component.UserAges
import com.bangumi.ywylite.ui.component.openInBrowser
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SubjectDetailUiState(
    val loading: Boolean = true,
    val subject: Subject? = null,
    val error: String? = null,
    val userCollection: UserCollection? = null,
    val episodes: List<Episode> = emptyList(),
    val watchedEpisodes: Set<Int> = emptySet(),
    val relatedSubjects: List<RelatedSubject> = emptyList()
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
    onTagClick: (String, Int) -> Unit = { _, _ -> }
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(SubjectDetailUiState()) }
    val token by app.settings.accessToken.collectAsState(initial = null)
    val username by app.settings.username.collectAsState(initial = null)
    val snackbarHostState = remember { SnackbarHostState() }

    // 章节状态接口要求条目已收藏；未登录/未收藏时给出提示而不是静默失败
    val ensureCanMark: suspend () -> Boolean = {
        when {
            token == null -> {
                snackbarHostState.showSnackbar("登录后才能标记进度")
                false
            }
            uiState.userCollection == null -> {
                snackbarHostState.showSnackbar("收藏条目后才能标记进度")
                false
            }
            else -> true
        }
    }

    // 拉取当前账号对该条目的收藏（状态/评分/吐槽/标签）与观看进度，写回 uiState
    val syncCollection: suspend () -> Unit = sync@{
        val t = token
        val u = username
        if (t == null || u.isNullOrBlank()) {
            uiState = uiState.copy(userCollection = null, watchedEpisodes = emptySet())
            return@sync
        }
        var attempts = 0
        while (attempts < 4) {
            val result = runCatching { app.api.getSubjectCollection(u, subjectId, t) }
            if (result.isSuccess) {
                // null = 服务端明确返回「未收藏」
                uiState = uiState.copy(userCollection = result.getOrNull())
                val epCollections = runCatching { app.api.getEpisodeCollection(subjectId, accessToken = t) }.getOrNull()
                if (epCollections != null) {
                    uiState = uiState.copy(
                        watchedEpisodes = epCollections.filter { it.type == 2 }.mapNotNull { it.episode?.id }.toSet()
                    )
                }
                return@sync
            }
            attempts++
            delay(1000)
        }
        snackbarHostState.showSnackbar("收藏状态获取失败，请检查网络后重试")
    }

    LaunchedEffect(subjectId) {
        uiState = uiState.copy(loading = true, error = null)
        try {
            val subject = app.api.getSubject(subjectId)
            val episodes = runCatching { app.api.getEpisodes(subjectId).data }.getOrDefault(emptyList())
            val relatedSubjects = runCatching { app.api.getRelatedSubjects(subjectId) }.getOrDefault(emptyList())

            uiState = uiState.copy(
                loading = false,
                subject = subject,
                episodes = episodes,
                relatedSubjects = relatedSubjects
            )
        } catch (e: Exception) {
            uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    LaunchedEffect(subjectId, token, username) { syncCollection() }

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
            onSyncCollection = { scope.launch { syncCollection() } },
            onCollect = { type, comment, rate, tags ->
                scope.launch {
                    var failed: String? = null
                    try {
                        app.api.collectSubject(subjectId, type, rate = rate, comment = comment, tags = tags)
                        if (type == 0) {
                            uiState = uiState.copy(userCollection = null)
                        } else {
                            uiState = uiState.copy(
                                userCollection = uiState.userCollection?.copy(
                                    type = type,
                                    tags = tags ?: uiState.userCollection?.tags ?: emptyList()
                                )
                                    ?: UserCollection(
                                        subject_id = subjectId,
                                        type = type,
                                        rate = rate ?: 0,
                                        comment = comment ?: "",
                                        tags = tags ?: emptyList()
                                    )
                            )
                            if (!username.isNullOrBlank()) {
                                runCatching { app.api.getSubjectCollection(username!!, subjectId, token) }
                                    .getOrNull()?.let { freshCollection ->
                                        uiState = uiState.copy(userCollection = freshCollection)
                                    }
                            }
                        }
                    } catch (e: Exception) {
                        failed = e.message
                    }
                    failed?.let { snackbarHostState.showSnackbar("收藏失败：$it") }
                }
            },
            onToggleWatched = { episodeId ->
                scope.launch {
                    if (!ensureCanMark()) return@launch
                    try {
                        val isWatched = episodeId in uiState.watchedEpisodes
                        if (isWatched) {
                            app.api.updateEpisodeStatus(episodeId, 0)
                            uiState = uiState.copy(watchedEpisodes = uiState.watchedEpisodes - episodeId)
                        } else {
                            app.api.updateEpisodeStatus(episodeId, 2)
                            uiState = uiState.copy(watchedEpisodes = uiState.watchedEpisodes + episodeId)
                        }
                    } catch (_: Exception) {
                        snackbarHostState.showSnackbar("标记失败，请重试")
                    }
                }
            },
            onMarkWatchedUpTo = { episode ->
                scope.launch {
                    if (!ensureCanMark()) return@launch
                    // legacy 接口按正篇序号记进度；多季度番 sort 可能不从 1 开始，取正篇内的位次
                    val mainEps = uiState.episodes.filter { it.type == 0 }.sortedBy { it.sort }
                    val index = mainEps.indexOfFirst { it.id == episode.id }
                    val targetSort = if (index >= 0) index + 1 else episode.sort.toInt()
                    val legacyOk = runCatching { app.api.markWatchedUpTo(subjectId, targetSort) }.isSuccess
                    if (!legacyOk) {
                        mainEps.take(index + 1).forEach { ep ->
                            runCatching { app.api.updateEpisodeStatus(ep.id, 2) }
                        }
                    }
                    runCatching { app.api.getEpisodeCollection(subjectId) }.getOrNull()?.let { list ->
                        uiState = uiState.copy(
                            watchedEpisodes = list.filter { it.type == 2 }.mapNotNull { it.episode?.id }.toSet()
                        )
                    }
                    snackbarHostState.showSnackbar("已标记看到第 ${targetSort} 话")
                }
            },
            getEpisodeComments = { episodeId ->
                app.api.getEpisodeComments(episodeId)
            },
            snackbarHostState = snackbarHostState,
            getComments = { sid, offset, limit ->
                app.api.getComments(sid, offset = offset, limit = limit)
            },
            onTagClick = onTagClick,
            onBack = onBack
        )
        else -> EmptyView()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubjectDetailContent(
    uiState: SubjectDetailUiState,
    onSyncCollection: () -> Unit,
    onCollect: (Int, String?, Int?, List<String>?) -> Unit,
    onToggleWatched: (Int) -> Unit,
    onMarkWatchedUpTo: (Episode) -> Unit,
    getEpisodeComments: suspend (Int) -> List<EpisodeComment>,
    getComments: suspend (Int, Int, Int) -> CommentResponse,
    snackbarHostState: SnackbarHostState,
    onTagClick: (String, Int) -> Unit,
    onBack: () -> Unit
) {
    val subject = uiState.subject ?: return
    val context = LocalContext.current
    val imageUrl = subject.images?.common?.replace("http://", "https://")
        ?: subject.images?.medium?.replace("http://", "https://")
        ?: subject.image.replace("http://", "https://")

    var showMenu by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }

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
                                    openInBrowser(context, url)
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

                CollectionBar(
                    currentType = uiState.userCollection?.type,
                    currentRate = uiState.userCollection?.rate ?: 0,
                    currentComment = uiState.userCollection?.comment ?: "",
                    currentTags = uiState.userCollection?.tags ?: emptyList(),
                    publicTags = subject.tags,
                    onSyncCollection = onSyncCollection,
                    onCollect = onCollect
                )

                if (subject.summary.isNotEmpty()) {
                    CollapsibleSummary(summary = subject.summary)
                }

                if (uiState.episodes.isNotEmpty()) {
                    EpisodeBlockSection(
                        episodes = uiState.episodes,
                        watchedEpisodes = uiState.watchedEpisodes,
                        onToggleWatched = onToggleWatched,
                        onMarkWatchedUpTo = onMarkWatchedUpTo,
                        getEpisodeComments = getEpisodeComments
                    )
                }

                if (subject.tags.isNotEmpty()) {
                    TagsSection(
                        tags = subject.tags,
                        subjectType = subject.type,
                        onTagClick = onTagClick
                    )
                }

                if (uiState.relatedSubjects.isNotEmpty()) {
                    RelatedSection(relatedSubjects = uiState.relatedSubjects, onSubjectClick = {})
                }

                Spacer(modifier = Modifier.height(72.dp))
            }
        }

        // 评论拉手：默认折叠在底部，点按向上展开评论弹层
        Surface(
            onClick = { showCommentsSheet = true },
            shape = RoundedCornerShape(50),
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (subject.comment > 0) "评论 (${subject.comment})" else "评论",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showCommentsSheet) {
        ModalBottomSheet(onDismissRequest = { showCommentsSheet = false }) {
            SubjectCommentsSheet(
                subjectId = subject.id,
                getComments = getComments,
                onUserClick = { username ->
                    if (username.isNotEmpty()) {
                        openInBrowser(context, "https://bgm.tv/user/$username")
                    }
                }
            )
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
    // 默认折叠为两行；超过约两行的数量才显示展开按钮
    val canFold = tags.size > 8

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (canFold) {
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
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = FlowRowOverflow.Clip,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tags.forEach { tag ->
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeBlockSection(
    episodes: List<Episode>,
    watchedEpisodes: Set<Int>,
    onToggleWatched: (Int) -> Unit,
    onMarkWatchedUpTo: (Episode) -> Unit,
    getEpisodeComments: suspend (Int) -> List<EpisodeComment>
) {
    val shouldFold = episodes.size > 12
    var expanded by remember { mutableStateOf(!shouldFold) }
    var selectedEpisodeId by remember { mutableIntStateOf(-1) }
    var menuEpisode by remember { mutableStateOf<Episode?>(null) }
    var commentEpisode by remember { mutableStateOf<Episode?>(null) }

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
                    EpisodeBlock(
                        episode = episode,
                        isWatched = episode.id in watchedEpisodes,
                        isSelected = selectedEpisodeId == episode.id,
                        onSelect = {
                            // 单击：展开/收起集名
                            selectedEpisodeId = if (selectedEpisodeId == episode.id) -1 else episode.id
                        },
                        onToggleWatched = { onToggleWatched(episode.id) },
                        onLongPress = { menuEpisode = episode }
                    )
                }
            }
        } else if (shouldFold) {
            // 折叠时保留一行不折叠：显示观看进度所在行；没有看过任何一集则显示第一行
            BoxWithConstraints {
                val perRow = (maxWidth / (44.dp + 6.dp)).toInt().coerceAtLeast(1)
                val firstUnwatched = episodes.indexOfFirst { it.id !in watchedEpisodes }
                val targetIdx = if (firstUnwatched >= 0) firstUnwatched else episodes.size - 1
                val start = (targetIdx / perRow) * perRow
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    episodes.drop(start).take(perRow).forEach { episode ->
                        EpisodeBlock(
                            episode = episode,
                            isWatched = episode.id in watchedEpisodes,
                            isSelected = selectedEpisodeId == episode.id,
                            onSelect = {
                                selectedEpisodeId = if (selectedEpisodeId == episode.id) -1 else episode.id
                            },
                            onToggleWatched = { onToggleWatched(episode.id) },
                            onLongPress = { menuEpisode = episode }
                        )
                    }
                }
            }
        }
    }

    menuEpisode?.let { episode ->
        ModalBottomSheet(onDismissRequest = { menuEpisode = null }) {
            EpisodeActionSheet(
                episode = episode,
                isWatched = episode.id in watchedEpisodes,
                onToggleWatched = {
                    menuEpisode = null
                    onToggleWatched(episode.id)
                },
                onWatchedUpTo = {
                    menuEpisode = null
                    onMarkWatchedUpTo(episode)
                },
                onComments = {
                    menuEpisode = null
                    commentEpisode = episode
                }
            )
        }
    }

    commentEpisode?.let { episode ->
        ModalBottomSheet(onDismissRequest = { commentEpisode = null }) {
            EpisodeCommentsSheet(
                episode = episode,
                getComments = getEpisodeComments
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeBlock(
    episode: Episode,
    isWatched: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleWatched: () -> Unit,
    onLongPress: () -> Unit
) {
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
                .combinedClickable(
                    onClick = onSelect,
                    onDoubleClick = onToggleWatched,
                    onLongClick = onLongPress
                )
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = episode.ep?.toString() ?: episode.sort.toInt().toString(),
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
                modifier = Modifier.layout { measurable, constraints ->
                    // 集名不参与 FlowRow 布局（占位 0 宽，避免把格子撑开出现空档），
                    // 从级数块左缘起绘、向右延伸至整行宽度
                    val placeable = measurable.measure(constraints.copy(minWidth = 0))
                    layout(0, placeable.height) { placeable.placeRelative(0, 0) }
                }
            )
        }
    }
}

@Composable
private fun EpisodeActionSheet(
    episode: Episode,
    isWatched: Boolean,
    onToggleWatched: () -> Unit,
    onWatchedUpTo: () -> Unit,
    onComments: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = buildString {
                append("第 ${episode.ep ?: episode.sort.toInt()} 话")
                val name = episode.nameCn.ifEmpty { episode.name }
                if (name.isNotEmpty()) append("　$name")
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        ListItem(
            headlineContent = { Text(if (isWatched) "取消看过" else "看过") },
            leadingContent = { Icon(Icons.Default.Done, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onToggleWatched)
        )
        ListItem(
            headlineContent = { Text("看到第 ${episode.ep ?: episode.sort.toInt()} 话") },
            supportingContent = { Text("本集及之前全部标为看过") },
            leadingContent = { Icon(Icons.Default.FastForward, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onWatchedUpTo)
        )
        ListItem(
            headlineContent = { Text(if (episode.comment > 0) "当集评论 (${episode.comment})" else "当集评论") },
            leadingContent = { Icon(Icons.Default.Forum, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onComments)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeCommentsSheet(
    episode: Episode,
    getComments: suspend (Int) -> List<EpisodeComment>
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var comments by remember { mutableStateOf<List<EpisodeComment>>(emptyList()) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(episode.id, retryKey) {
        loading = true
        error = null
        runCatching { getComments(episode.id) }
            .onSuccess { comments = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = buildString {
                append("第 ${episode.ep ?: episode.sort.toInt()} 话 · 评论")
                if (episode.comment > 0) append(" (${episode.comment})")
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            error != null -> ErrorView(
                message = error ?: "加载失败",
                onRetry = { retryKey++ },
                modifier = Modifier.height(180.dp)
            )
            comments.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有评论", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .padding(horizontal = 16.dp)
            ) {
                items(comments, key = { it.id }) { comment ->
                    EpisodeCommentRow(comment)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun EpisodeCommentRow(comment: EpisodeComment) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (comment.avatar.isNotEmpty()) {
            AsyncImage(
                model = comment.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = comment.nickname,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                RegAgeText(0, comment.avatar)
                Text(
                    text = "#${comment.floor}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = comment.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            if (comment.content.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                SelectionContainer {
                    Text(comment.content, style = MaterialTheme.typography.bodySmall)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionBar(
    currentType: Int?,
    currentRate: Int = 0,
    currentComment: String = "",
    currentTags: List<String> = emptyList(),
    publicTags: List<TagInfo> = emptyList(),
    onSyncCollection: () -> Unit = {},
    onCollect: (Int, String?, Int?, List<String>?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var selectedType by remember { mutableIntStateOf(currentType ?: 3) }
    var comment by remember { mutableStateOf(currentComment) }
    var rating by remember { mutableIntStateOf(currentRate) }
    var ratingTouched by remember { mutableStateOf(false) }
    val selectedTags = remember { mutableStateListOf<String>() }
    // 标签区：0 我的标签 / 1 大家打的；折叠时限制高度可上下滑动
    var tagMode by remember { mutableIntStateOf(0) }
    var tagsExpanded by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf(TextFieldValue("")) }

    val openDialog = {
        // 打开时先同步一次账号最新状态，再把状态/评分/吐槽/标签全量回显
        onSyncCollection()
        currentType?.let { selectedType = it }
        rating = currentRate
        ratingTouched = false
        comment = currentComment
        selectedTags.clear()
        selectedTags.addAll(currentTags)
        showDialog = true
    }

    val isCollected = currentType != null
    val currentColor = collectionTypeColors[currentType] ?: MaterialTheme.colorScheme.primary
    val currentLabel = collectionTypeLabels[currentType] ?: "收藏"

    LaunchedEffect(currentType, currentRate, currentComment, currentTags) {
        if (currentType != null) {
            selectedType = currentType
        }
        rating = currentRate
        comment = currentComment
        selectedTags.clear()
        selectedTags.addAll(currentTags)
    }

    if (isCollected) {
        Button(
            onClick = { openDialog() },
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
            onClick = { openDialog() },
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
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("观看状态", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(3 to "在看", 2 to "看过", 1 to "想看", 4 to "搁置", 5 to "抛弃").forEach { (type, label) ->
                            val color = collectionTypeColors[type] ?: Color.Gray
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = color.copy(alpha = 0.18f),
                                    selectedLabelColor = color
                                )
                            )
                        }
                    }

                    // 标签区：可切换「我的标签 / 大家打的」，默认折叠为两行高度、区域内可上下滑动
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("标签", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0 to "我的标签", 1 to "大家打的").forEach { (mode, label) ->
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = if (tagMode == mode) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    modifier = Modifier.clickable { tagMode = mode }
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (tagMode == mode) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            Icons.Default.ExpandLess,
                            contentDescription = if (tagsExpanded) "收起标签区" else "展开标签区",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { tagsExpanded = !tagsExpanded }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (tagsExpanded) 160.dp else 64.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (tagMode == 0) {
                                    selectedTags.forEach { tag ->
                                        SmallTagChip(text = tag, selected = true, onClick = { selectedTags.remove(tag) })
                                    }
                                    SmallTagChip(text = "＋ 添加", selected = false, onClick = { showAddTagDialog = true })
                                } else {
                                    publicTags.forEach { tag ->
                                        SmallTagChip(
                                            text = if (tag.count > 0) "${tag.name} ${tag.count}" else tag.name,
                                            selected = tag.name in selectedTags,
                                            onClick = {
                                                if (tag.name in selectedTags) selectedTags.remove(tag.name)
                                                else selectedTags.add(tag.name)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    if (tagMode == 0 && selectedTags.isEmpty()) {
                        Text(
                            "点「＋ 添加」自定义，或切到「大家打的」点选",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text("吐槽（我的评论）", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    Text(
                        "评分 ★${rating.coerceIn(1, 10)} / 10",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = rating.coerceIn(1, 10).toFloat(),
                        onValueChange = {
                            rating = it.toInt()
                            ratingTouched = true
                        },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 未拖动过滑块且原无评分时提交 null，避免误打 1 星；标签不能含空格
                    val rate = if (ratingTouched || currentRate > 0) rating.coerceIn(1, 10) else null
                    val cleanTags = selectedTags
                        .map { it.replace(Regex("""\s+"""), "") }
                        .filter { it.isNotBlank() }
                        .distinct()
                    onCollect(
                        selectedType,
                        comment.ifBlank { null },
                        rate,
                        cleanTags.ifEmpty { null }
                    )
                    showDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                Row {
                    if (isCollected) {
                        TextButton(onClick = {
                            onCollect(0, null, null, null)
                            showDialog = false
                        }) { Text("取消收藏", color = Color(0xFFF44336)) }
                    }
                    TextButton(onClick = { showDialog = false }) { Text("取消") }
                }
            }
        )

        if (showAddTagDialog) {
            AlertDialog(
                onDismissRequest = { showAddTagDialog = false },
                title = { Text("添加标签") },
                text = {
                    OutlinedTextField(
                        value = newTagText,
                        onValueChange = { newTagText = it },
                        label = { Text("标签名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val t = newTagText.text.trim()
                            if (t.isNotEmpty() && selectedTags.none { it.equals(t, ignoreCase = true) }) {
                                selectedTags.add(t)
                            }
                            newTagText = TextFieldValue("")
                            showAddTagDialog = false
                        },
                        enabled = newTagText.text.isNotBlank()
                    ) { Text("添加") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddTagDialog = false }) { Text("取消") }
                }
            )
        }
    }
}

/** 收藏弹窗内的小号标签芯片 */
@Composable
private fun SmallTagChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectCommentsSheet(
    subjectId: Int,
    getComments: suspend (Int, Int, Int) -> CommentResponse,
    onUserClick: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var comments by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(subjectId, retryKey) {
        loading = true
        error = null
        runCatching { getComments(subjectId, 0, 20) }
            .onSuccess { result ->
                comments = result.data
                offset = result.data.size
                total = result.total
            }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            "条目评论",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            error != null -> ErrorView(
                message = error ?: "加载失败",
                onRetry = { retryKey++ },
                modifier = Modifier.height(180.dp)
            )
            comments.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有评论", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(comments, key = { it.id }) { comment ->
                    SubjectCommentCard(comment = comment, onUserClick = onUserClick)
                }
                if (offset < total) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                TextButton(onClick = {
                                    if (loadingMore) return@TextButton
                                    scope.launch {
                                        loadingMore = true
                                        runCatching { getComments(subjectId, offset, 20) }
                                            .onSuccess { result ->
                                                comments = comments + result.data
                                                offset += result.data.size
                                                total = result.total
                                            }
                                        loadingMore = false
                                    }
                                }) {
                                    Text("加载更多评论")
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

/** 用户名旁的站龄徽标：按 ID/头像离线推算，无法推算时不显示 */
@Composable
private fun RegAgeText(userId: Int, avatar: String) {
    val age = remember(userId, avatar) { UserAges.estimate(userId, avatar) }
    if (age != null) {
        Text(
            text = "站龄 ${UserAges.format(age)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun SubjectCommentCard(
    comment: CommentItem,
    onUserClick: (String) -> Unit
) {
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable {
                            val username = comment.user?.username ?: ""
                            if (username.isNotEmpty()) {
                                onUserClick(username)
                            }
                        }
                )
                Spacer(modifier = Modifier.width(6.dp))
                RegAgeText(comment.user?.id ?: 0, avatarUrl)
                Spacer(modifier = Modifier.width(6.dp))
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
