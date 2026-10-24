package com.bangumi.ywylite.ui.screen

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.*
import com.bangumi.ywylite.ui.component.EmptyView
import com.bangumi.ywylite.ui.component.ErrorView
import com.bangumi.ywylite.ui.component.ImageUrls
import com.bangumi.ywylite.ui.component.LoadingView
import com.bangumi.ywylite.ui.component.NetworkTimeoutDialog
import com.bangumi.ywylite.ui.component.openInBrowser
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class SubjectDetailUiState(
    val loading: Boolean = true,
    val subject: Subject? = null,
    val error: String? = null,
    val userCollection: UserCollection? = null,
    val episodes: List<Episode> = emptyList(),
    val watchedEpisodes: Set<Int> = emptySet(),
    val relatedSubjects: List<RelatedSubject> = emptyList(),
    val characters: List<CharacterItem> = emptyList(),
    // 网页版完整 infobox（v0 接口只有部分字段），查看器优先使用
    val webInfobox: List<InfoboxItem> = emptyList(),
    /** 章节/角色/关联等次要数据是否全部取到；false 时不写进程缓存，避免残缺页面被当成完整数据复用 */
    val complete: Boolean = true
)

/**
 * 进程级详情缓存（LRU，上限 8 条）：再次进入同一条目先展示上次数据、后台静默拉新。
 * 增量刷新——页面不闪整页加载屏；封面等图片 URL 未变时 Coil 直接命中缓存，不二次请求。
 */
private val detailCache = object : LinkedHashMap<Int, SubjectDetailUiState>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SubjectDetailUiState>) = size > 8
}

/** 缓存归属的会话代号：换账号（登录/登出）后缓存里的收藏状态、评分、观看进度都不能再用 */
private var detailCacheGeneration = -1

private fun cachedDetailState(subjectId: Int): SubjectDetailUiState? {
    if (detailCacheGeneration != App.INSTANCE.api.sessionGeneration) {
        detailCache.clear()
        detailCacheGeneration = App.INSTANCE.api.sessionGeneration
        return null
    }
    return detailCache[subjectId]
}

private fun putCachedDetailState(subjectId: Int, state: SubjectDetailUiState) {
    detailCacheGeneration = App.INSTANCE.api.sessionGeneration
    detailCache[subjectId] = state
}

/** 章节在界面上的称呼：正篇用 ep；SP/OP/ED 的 ep 是 0，退回升序 sort（18.5 就写 18.5，不与第 18 话混淆） */
private fun episodeLabel(episode: Episode): String {
    val ep = episode.ep
    if (ep != null && ep > 0) return ep.toString()
    val sort = episode.sort
    return if (sort % 1.0 == 0.0) sort.toInt().toString() else sort.toString()
}

/** 弹层标题：正篇写「第 N 话」，非正篇按类型指名（OP/ED/SP），别把 OP 说成第 1 话 */
private fun episodeSheetTitle(episode: Episode): String = when (episode.type) {
    0 -> "第 ${episodeLabel(episode)} 话"
    1 -> "SP ${episodeLabel(episode)}"
    2 -> "OP"
    3 -> "ED"
    else -> "章节 ${episodeLabel(episode)}"
}

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
    onTagClick: (String, Int) -> Unit = { _, _ -> },
    onSubjectClick: (Int) -> Unit = {}
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    // 命中进程缓存 → 先展示旧数据（跳过整页 loading），后台增量拉新；未命中 → 正常整页加载
    val cachedDetail = cachedDetailState(subjectId)
    var uiState by remember(subjectId) {
        mutableStateOf(cachedDetail?.copy(loading = false, error = null) ?: SubjectDetailUiState())
    }
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

    // 收藏变更代次：用户改过收藏（含取消收藏）后，正在飞行或重试中的同步结果直接作废，
    // 否则「取消收藏」的 DELETE 之后，先前发出的 GET 会把状态又填回「在看」
    var collectionGeneration by remember(subjectId) { mutableIntStateOf(0) }

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
            try {
                val generation = collectionGeneration
                // null = 服务端明确返回「未收藏」（404）
                val collection = app.api.getSubjectCollection(u, subjectId, t)
                if (generation != collectionGeneration) return@sync
                uiState = uiState.copy(userCollection = collection)
                val epCollections = runCatching { app.api.getAllEpisodeCollections(subjectId, accessToken = t) }.getOrNull()
                if (epCollections != null) {
                    uiState = uiState.copy(
                        watchedEpisodes = epCollections.filter { it.type == 2 }.mapNotNull { it.episode?.id }.toSet()
                    )
                }
                return@sync
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // 4xx 是明确失败（无权限等），重试无意义
                snackbarHostState.showSnackbar("收藏状态获取失败（HTTP ${e.response.status.value}）")
                return@sync
            } catch (_: Exception) {
                // 网络异常，延迟后重试
            }
            attempts++
            delay(1000)
        }
        snackbarHostState.showSnackbar("收藏状态获取失败，请检查网络后重试")
    }

    // 全量加载详情与关联数据：首次进入（整页 loading）、「重试」与缓存后增量刷新（后台静默）共用。
    // 原先重试只重新拉 subject，章节/角色/信息盒仍是失败时的空值，页面残缺
    val loadAll: suspend (background: Boolean) -> Unit = load@{ background ->
        if (!background) uiState = uiState.copy(loading = true, error = null)
        try {
            val subject = app.api.getSubject(subjectId)
            var partial = false
            // 次要数据失败只置 complete=false（不写缓存、也不把正在看的页面打成错误页）。
            // 不能用 runCatching：它连 CancellationException 一起吞，取消后还会继续写状态
            suspend fun <T> optional(countsAsPartial: Boolean = true, fallback: T, block: suspend () -> T): T =
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (countsAsPartial) partial = true
                    fallback
                }

            // getAllEpisodes 内部循环翻页：长番（>100 集）此前只拿到第一页
            val episodes = optional(fallback = emptyList<Episode>()) { app.api.getAllEpisodes(subjectId) }
            val relatedSubjects = optional(fallback = emptyList<RelatedSubject>()) { app.api.getRelatedSubjects(subjectId) }
            val characters = optional(fallback = emptyList<CharacterItem>()) { app.api.getSubjectCharacters(subjectId) }
            // 信息盒只有网页版有，R18 条目对未登录会话整段不下发——缺它不算残缺
            val webInfobox = optional(countsAsPartial = false, fallback = emptyList<InfoboxItem>()) {
                app.api.getSubjectWebInfobox(subjectId)
            }

            uiState = uiState.copy(
                loading = false,
                error = null,
                complete = !partial,
                subject = subject,
                episodes = episodes,
                relatedSubjects = relatedSubjects,
                characters = characters,
                webInfobox = webInfobox
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 增量刷新失败：旧数据照常展示，不把正在看的页面打成错误页
            if (!background) uiState = uiState.copy(loading = false, error = e.message)
        }
    }

    LaunchedEffect(subjectId) { loadAll(cachedDetail == null) }

    LaunchedEffect(subjectId, token, username) { syncCollection() }

    // 详情数据落定即写进程缓存（残缺数据不写），下次进入同一条目走增量刷新
    LaunchedEffect(uiState) {
        if (!uiState.loading && uiState.error == null && uiState.subject != null && uiState.complete) {
            putCachedDetailState(subjectId, uiState)
        }
    }

    // 连不上时详情会一直停在“加载中...”，超过 10 秒给一次明确的网络提示
    NetworkTimeoutDialog(loading = uiState.loading)

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
                    scope.launch { loadAll(false) }
                },
                modifier = Modifier.padding(padding)
            )
        }
        // 详情页永远走深色配色：背景是深色封面/黑色，正文若用浅色主题的深色文字会看不见。
        // 从当前配色 copy 出深色前景/底色，保留 primary/secondary 等强调色（粉或壁纸派生色），
        // 避免换成 Material 默认紫色导致星级/链接/标签变色。
        // 详情正文直接铺在 Box 上（无 Surface），无色 Text 读的是 LocalContentColor——
        // 内层 MaterialTheme 不会自动刷新它，必须显式提供白色。
        uiState.subject != null -> {
            val base = MaterialTheme.colorScheme
            androidx.compose.material3.MaterialTheme(
                colorScheme = base.copy(
                    background = Color.Black,
                    onBackground = Color.White,
                    surface = Color(0xFF121212),
                    onSurface = Color.White,
                    surfaceVariant = Color(0xFF2A2A2A),
                    onSurfaceVariant = Color(0xFFCFCFCF),
                    outline = Color(0xFF8A8A8A),
                    outlineVariant = Color(0xFF3C3C3C),
                    inverseSurface = Color.White,
                    inverseOnSurface = Color.Black,
                    surfaceContainerLowest = Color(0xFF0D0D0D),
                    surfaceContainerLow = Color(0xFF161616),
                    surfaceContainer = Color(0xFF1C1C1C),
                    surfaceContainerHigh = Color(0xFF242424),
                    surfaceContainerHighest = Color(0xFF2E2E2E)
                )
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Color.White
                ) {
                    SubjectDetailContent(
                        uiState = uiState,
                        onSyncCollection = { scope.launch { syncCollection() } },
                        onCollect = { type, comment, rate, tags ->
                            scope.launch {
                                var failed: String? = null
                                // 先记账：本次变更之前的同步结果一律作废
                                collectionGeneration++
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
                                                    subjectId = subjectId,
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
                                // legacy 的 watched_eps 按「正篇第几话」计，只对正篇集成立：
                                // 非正篇集（SP/OP/ED）的 sort 是独立编号（如 SP 的 18.5），
                                // 拿它当正篇序号会把正篇进度改坏（点一集 SP 标掉 18 集）
                                val mainEps = uiState.episodes.filter { it.type == 0 }.sortedBy { it.sort }
                                val index = mainEps.indexOfFirst { it.id == episode.id }
                                var ok: Boolean
                                var markedLabel: String
                                if (index >= 0) {
                                    markedLabel = "已标记看到第 ${index + 1} 话"
                                    ok = runCatching { app.api.markWatchedUpTo(subjectId, index + 1) }.isSuccess
                                    if (!ok) {
                                        // legacy 失败退化为逐集标记。
                                        // 统计实际成功数：原先无论成败都提示"已标记"
                                        val targets = mainEps.take(index + 1)
                                        ok = targets.isNotEmpty() && targets.count { ep ->
                                            runCatching { app.api.updateEpisodeStatus(ep.id, 2) }.isSuccess
                                        } == targets.size
                                    }
                                } else {
                                    // 非正篇集：只标记这一集，正篇进度一个都不动
                                    markedLabel = "已标记看过本集"
                                    ok = runCatching { app.api.updateEpisodeStatus(episode.id, 2) }.isSuccess
                                }
                                runCatching { app.api.getAllEpisodeCollections(subjectId) }.getOrNull()?.let { list ->
                                    uiState = uiState.copy(
                                        watchedEpisodes = list.filter { it.type == 2 }.mapNotNull { it.episode?.id }.toSet()
                                    )
                                }
                                snackbarHostState.showSnackbar(if (ok) markedLabel else "标记失败，请重试")
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
                        onSubjectClick = onSubjectClick,
                        onBack = onBack
                    )
                }
            }
        }
        else -> EmptyView()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
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
    onSubjectClick: (Int) -> Unit,
    onBack: () -> Unit
) {
    val subject = uiState.subject ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // 正文头图 400（与列表卡片同档，缓存互通）；查看器另取 800 大图
    val imageUrl = ImageUrls.cover(
        subject.images?.common ?: subject.images?.medium ?: subject.image,
        ImageUrls.GRID
    )

    var showMenu by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }
    var showImageViewer by remember { mutableStateOf(false) }
    // 封面在窗口中的位置与查看器尺寸，用于「从封面放大/缩回封面」的过渡动画
    var coverBounds by remember { mutableStateOf<Rect?>(null) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val viewerSpec = remember(coverBounds, overlaySize) {
        val cb = coverBounds
        if (cb != null && overlaySize.width > 0 && overlaySize.height > 0) {
            TransformOrigin(
                (cb.center.x / overlaySize.width).coerceIn(0f, 1f),
                (cb.center.y / overlaySize.height).coerceIn(0f, 1f)
            ) to (cb.width / overlaySize.width).coerceIn(0.1f, 0.9f)
        } else {
            TransformOrigin(0.5f, 0.5f) to 0.6f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { overlaySize = it }
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.20f
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.90f)
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

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        // 平板等宽屏下内容限宽居中，避免文字行被拉得过长
                        .widthIn(max = 840.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .align(Alignment.TopCenter),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = subject.nameCn.ifEmpty { subject.name },
                        modifier = Modifier
                            .width(120.dp)
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { showImageViewer = true }
                            )
                            .onGloballyPositioned { coverBounds = it.boundsInWindow() },
                        contentScale = ContentScale.Crop
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = subject.nameCn.ifEmpty { subject.name },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    clipboard.setText(AnnotatedString(subject.nameCn.ifEmpty { subject.name }))
                                    scope.launch { snackbarHostState.showSnackbar("已复制标题") }
                                }
                            )
                        )
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
                                CollectionCountChip("看过", col.collect, Color(0xFF4CAF50))
                                CollectionCountChip("在看", col.doing, Color(0xFFFF9800))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CollectionCountChip("搁置", col.onHold, Color(0xFF9E9E9E))
                                CollectionCountChip("抛弃", col.dropped, Color(0xFFF44336))
                                CollectionCountChip("全部", col.total, Color(0xFF607D8B))
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

                if (uiState.characters.isNotEmpty()) {
                    CharactersSection(uiState.characters)
                }

                if (uiState.relatedSubjects.isNotEmpty()) {
                    RelatedSection(relatedSubjects = uiState.relatedSubjects, onSubjectClick = onSubjectClick)
                }

                Spacer(modifier = Modifier.height(72.dp))
                }
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

    // 封面查看器：页内覆盖层（非独立窗口），打开/关闭都从封面位置缩放过渡
    BackHandler(enabled = showImageViewer) { showImageViewer = false }
    AnimatedVisibility(
        visible = showImageViewer,
        enter = scaleIn(
            initialScale = viewerSpec.second,
            transformOrigin = viewerSpec.first,
            animationSpec = tween(300)
        ) + fadeIn(tween(200)),
        exit = scaleOut(
            targetScale = viewerSpec.second,
            transformOrigin = viewerSpec.first,
            animationSpec = tween(260)
        ) + fadeOut(tween(180))
    ) {
        SubjectImageViewer(
            imageUrl = ImageUrls.cover(
                subject.images?.large ?: subject.images?.common ?: subject.image,
                ImageUrls.DETAIL
            ),
            title = subject.nameCn.ifEmpty { subject.name },
            infobox = uiState.webInfobox.ifEmpty { subject.infobox },
            onDismiss = { showImageViewer = false }
        )
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
            Text(
                "章节 (${episodes.size})",
                style = MaterialTheme.typography.titleMedium.copy(shadow = null),
                fontWeight = FontWeight.Bold
            )
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
                canMarkUpTo = episode.type == 0,
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
                    text = episodeLabel(episode),
                    style = MaterialTheme.typography.labelMedium.copy(shadow = null),
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
                style = MaterialTheme.typography.labelSmall.copy(shadow = null),
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
    /** 只有正篇集能「看到第 N 话」：legacy 进度接口按正篇序号计，SP/OP/ED 用不了 */
    canMarkUpTo: Boolean,
    onToggleWatched: () -> Unit,
    onWatchedUpTo: () -> Unit,
    onComments: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = buildString {
                append(episodeSheetTitle(episode))
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
        if (canMarkUpTo) {
            ListItem(
                headlineContent = { Text("看到第 ${episodeLabel(episode)} 话") },
                supportingContent = { Text("本集及之前全部标为看过") },
                leadingContent = { Icon(Icons.Default.FastForward, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onWatchedUpTo)
            )
        }
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
        try {
            comments = getComments(episode.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = buildString {
                append("${episodeSheetTitle(episode)} · 评论")
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
    // 标签区：0 我的 / 1 all（大家打的）；默认显示 all
    var tagMode by remember { mutableIntStateOf(1) }
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

    // 弹层打开期间不回填：openDialog 会顺带触发一次后台同步，而同步在弱网下会重试，
    // 晚到的响应会把用户正在编辑的状态/评分/吐槽/标签冲掉。关掉弹层后再对齐服务端值
    LaunchedEffect(currentType, currentRate, currentComment, currentTags, showDialog) {
        if (showDialog) return@LaunchedEffect
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

                    // 标签区：可切换「all（大家打的）/ 我的」，默认折叠为两行高度、区域内可上下滑动
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("标签", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(1 to "all", 0 to "我的").forEach { (mode, label) ->
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
                            "点「＋ 添加」自定义，或切到「all」点选",
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

                    // 最左档为「不选择」（0），v0 接口约定 rate=0 表示删除评分
                    Text(
                        if (rating <= 0) "评分：不选择" else "评分 ★${rating.coerceIn(1, 10)} / 10",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = rating.coerceIn(0, 10).toFloat(),
                        onValueChange = {
                            rating = it.toInt()
                            ratingTouched = true
                        },
                        valueRange = 0f..10f,
                        steps = 9
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 拖到最左（0）= 明确不评分，v0 约定提交 0 即删除评分；
                    // 未拖动过且原无评分时提交 null；未拖动过且原有评分则保持原值
                    val rate = when {
                        ratingTouched -> rating.coerceIn(0, 10)
                        currentRate > 0 -> currentRate
                        else -> null
                    }
                    // 只去首尾空白：标签名内部允许有空格（如 Fate Zero），
                    // 原先把所有空白删掉会提交成另一个标签
                    val cleanTags = selectedTags
                        .map { it.trim() }
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

/** infobox 的 value 展平为字符串：字符串直接用，[{v: "..."}] 数组用「、」连接 */
private fun infoboxValue(value: JsonElement): String = when (value) {
    is JsonNull -> ""
    is JsonPrimitive -> value.content
    is JsonArray -> value.mapNotNull { (it as? JsonObject)?.get("v")?.jsonPrimitive?.content }.joinToString("、")
    else -> ""
}

/** infobox 文本 + 可点击链接：人名/公司名等按网页版一样跳转对应页面；整值为 URL 时整体高亮 */
private fun buildInfoboxAnnotatedString(
    item: InfoboxItem,
    value: String,
    wholeIsUrl: Boolean,
    context: Context
): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = Color(0xFF9E9E9E), fontWeight = FontWeight.SemiBold)) {
        append("${item.key}  ")
    }
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = Color(0xFF7FB3FF), textDecoration = TextDecoration.Underline)
    )
    if (item.links.isEmpty()) {
        if (wholeIsUrl) {
            withStyle(linkStyle.style ?: SpanStyle()) { append(value) }
        } else {
            append(value)
        }
        return@buildAnnotatedString
    }
    var cursor = 0
    for (link in item.links) {
        if (cursor >= value.length) break
        val idx = value.indexOf(link.text, cursor)
        if (idx < 0) continue
        if (idx > cursor) append(value.substring(cursor, idx))
        val end = (idx + link.text.length).coerceAtMost(value.length)
        val text = value.substring(idx, end)
        withLink(
            LinkAnnotation.Url(link.href, linkStyle) { _ ->
                openInBrowser(context, link.href)
            }
        ) { append(text) }
        cursor = end
    }
    if (cursor < value.length) append(value.substring(cursor))
}

/**
 * 全屏封面查看器：上半放大封面（长按保存到相册），下半滚动显示条目 infobox 详情。
 * 详情列表滚到顶后继续下拉会跟手拖出整层，松手（或拖过阈值）关闭并缩回封面位置。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubjectImageViewer(
    imageUrl: String,
    title: String,
    infobox: List<InfoboxItem>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var saving by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf(false) }

    // 下拉关闭手势：列表在顶时接管下拉位移，超过阈值直接关，未过阈值松手回弹
    val dismissThreshold = remember { with(density) { 170.dp.toPx() } }
    var dragY by remember { mutableFloatStateOf(0f) }
    val bounce = remember { Animatable(0f) }
    val detailsScroll = rememberScrollState()
    val dragConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                val current = dragY
                // 列表已到顶仍往下拉，或正处于拖出状态时，位移由本层吸收
                val takeOver = (dy > 0 && detailsScroll.value == 0) || current > 0f
                if (!takeOver) return Offset.Zero
                val next = (current + dy).coerceAtLeast(0f)
                dragY = next
                if (next > dismissThreshold) onDismiss()
                return Offset(0f, next - current)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val current = dragY
                if (current > 0f) {
                    if (current > dismissThreshold) {
                        onDismiss()
                    } else {
                        bounce.snapTo(current)
                        dragY = 0f
                        bounce.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val doSave = {
        if (!saving) {
            saving = true
            scope.launch {
                val message = runCatching { saveImageToGallery(context, imageUrl) }
                    .fold({ "已保存到相册 Pictures/ywylite" }, { "保存失败：${it.message}" })
                saving = false
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        Unit
    }

    // Android 9 及以下写共享存储需要运行时权限，授权后再继续保存
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingSave) {
            pendingSave = false
            doSave()
        } else if (!granted) {
            pendingSave = false
            Toast.makeText(context, "没有存储权限，无法保存图片", Toast.LENGTH_SHORT).show()
        }
    }

    val saveAction = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            doSave()
        } else {
            pendingSave = true
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        Unit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                translationY = if (bounce.isRunning || bounce.value != 0f) bounce.value else dragY
                alpha = 1f - (maxOf(bounce.value, dragY) / (dismissThreshold * 1.6f)).coerceIn(0f, 0.7f)
            }
    ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxSize()
                        .combinedClickable(
                            onClick = {},
                            onLongClick = saveAction
                        ),
                    contentScale = ContentScale.Fit
                )
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color.White)
                } else {
                    Text(
                        text = "长按图片可保存 · 详情滑到顶部后继续下拉可关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.55f)
                    .background(Color(0xFF121212))
                    .nestedScroll(dragConnection)
                    .verticalScroll(detailsScroll)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "详情",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (infobox.isEmpty()) {
                    Text(
                        "暂无详细信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
                infobox.forEach { item ->
                    val value = infoboxValue(item.value)
                    if (value.isEmpty()) return@forEach
                    val url = asBrowsableUrl(value)
                    Text(
                        text = buildInfoboxAnnotatedString(item, value, url != null, context),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = if (url != null && item.links.isEmpty()) {
                            Modifier.clickable { openInBrowser(context, url) }
                        } else {
                            Modifier
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
}

/** 裸域名的判定式：提到文件级，避免每次重组都重新编译（infobox 有几十行） */
private val BARE_DOMAIN_REGEX = Regex("""^[A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,}(/\S*)?$""")

/** infobox 值可跳转时返回补全 scheme 的 URL；完整 http(s) 链接或裸域名均识别 */
private fun asBrowsableUrl(value: String): String? {
    val trimmed = value.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        BARE_DOMAIN_REGEX.matches(trimmed) -> "https://$trimmed"
        else -> null
    }
}

/** 下载图片并写入系统相册（Pictures/ywylite），返回保存路径 */
private suspend fun saveImageToGallery(context: Context, url: String): String = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false)
        .build()
    val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
        ?: error("图片加载失败")
    val bitmap = drawable.toBitmap()
    val fileName = "bgm_${System.currentTimeMillis()}.jpg"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Q+：MediaStore 托管写入。IS_PENDING 期间条目对外不可见，
        // 失败要删掉残留条目，否则相册里会留下 0 字白图
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ywylite")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入相册")
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: error("无法写入相册")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            "Pictures/ywylite/$fileName"
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
    } else {
        // API 26-28：必须自己写公共目录文件并把绝对路径登记进 DATA 列。
        // 原先只插 MediaStore 不写文件，旧系统上保存"成功"但相册里没有图
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ywylite")
        if (!dir.exists() && !dir.mkdirs()) error("无法创建图片目录")
        val file = File(dir, fileName)
        try {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATA, file.absolutePath)
            }
            runCatching { context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }
            "Pictures/ywylite/$fileName"
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
}

/** 角色介绍：默认折叠；展开为横向滑动卡片（方形头像，顶部对齐裁切露出头部），点击打开角色页 */
@Composable
private fun CharactersSection(characters: List<CharacterItem>) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("角色介绍 (${characters.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(characters, key = { it.id }) { character ->
                    // 角色立绘原图 1MB+，取 r/200 小图（约 17KB）；保持原比例顶部裁切露头
                    val avatar = ImageUrls.character(
                        character.images?.medium ?: character.images?.small,
                        ImageUrls.THUMB
                    )
                    Column(
                        modifier = Modifier
                            .width(64.dp)
                            .clickable { openInBrowser(context, "https://bgm.tv/character/${character.id}") },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (avatar.isNotEmpty()) {
                            AsyncImage(
                                model = avatar,
                                contentDescription = character.name,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                                // 角色立绘是竖图，顶部对齐裁切才不会切掉头
                                alignment = Alignment.TopCenter
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = character.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subInfo = buildList {
                            if (character.relation.isNotEmpty()) add(character.relation)
                            character.actors.firstOrNull()?.let { add("CV ${it.name}") }
                        }
                        if (subInfo.isNotEmpty()) {
                            Text(
                                text = subInfo.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 关联条目：默认折叠只显示标题行，展开后为横向滑动的大图卡片，点击跳转条目详情 */
@Composable
private fun RelatedSection(
    relatedSubjects: List<RelatedSubject>,
    onSubjectClick: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("关联条目 (${relatedSubjects.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(relatedSubjects, key = { it.id }) { related ->
                    // 关联条目封面 96dp 宽，取 200 档；与列表卡片同档可复用缓存
                    val cover = ImageUrls.cover(
                        related.images?.common ?: related.images?.medium,
                        ImageUrls.THUMB
                    )
                    Column(
                        modifier = Modifier
                            .width(96.dp)
                            .clickable { onSubjectClick(related.id) }
                    ) {
                        AsyncImage(
                            model = cover,
                            contentDescription = related.nameCn.ifEmpty { related.name },
                            modifier = Modifier
                                .size(96.dp, 128.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = related.nameCn.ifEmpty { related.name },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (related.relation.isNotEmpty()) {
                            Text(
                                text = related.relation,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
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
        try {
            val result = getComments(subjectId, 0, 20)
            comments = result.data
            offset = result.data.size
            total = result.total
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        }
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
                                    // 同步置位：否则第二次点击会在协程体执行前通过同一个判断
                                    loadingMore = true
                                    scope.launch {
                                        try {
                                            val result = getComments(subjectId, offset, 20)
                                            comments = (comments + result.data).distinctBy { it.id }
                                            offset += result.data.size
                                            total = result.total
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
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
                val avatarUrl = ImageUrls.avatar(comment.user?.avatar?.medium ?: comment.user?.avatar?.large)
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
