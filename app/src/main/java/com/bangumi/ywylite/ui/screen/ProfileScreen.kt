package com.bangumi.ywylite.ui.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.TimelineEntry
import com.bangumi.ywylite.data.model.TimelineItem
import com.bangumi.ywylite.data.model.User
import com.bangumi.ywylite.data.model.mergeTimeline
import com.bangumi.ywylite.ui.component.BangumiLoginPanel
import com.bangumi.ywylite.ui.component.EmptyView
import com.bangumi.ywylite.ui.component.ImageUrls
import com.bangumi.ywylite.ui.component.LoadingView
import com.bangumi.ywylite.ui.component.openInBrowser
import com.bangumi.ywylite.ui.component.wallpaperGlassPanel
import com.bangumi.ywylite.ui.theme.LocalDarkTheme
import androidx.compose.ui.platform.LocalContext
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoggedIn: Boolean = false,
    val user: User? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val timelinePage: Int = 1,
    val timelineHasMore: Boolean = false,
    val timelineLoadingMore: Boolean = false,
    val timelineLoading: Boolean = false,
    val timelineError: String? = null
)

/** 左侧导轨：收起时只显示彩色点，点开后拉宽成写着状态的柱形 */
private val RailCollapsedWidth = 28.dp
private val RailExpandedWidth = 68.dp
private val MarkerCollapsedSize = 10.dp
private val MarkerExpandedWidth = 60.dp
private val MarkerExpandedHeight = 20.dp

private class RailMetrics(fraction: Float) {
    val width = lerp(RailCollapsedWidth, RailExpandedWidth, fraction)
    val markerWidth = lerp(MarkerCollapsedSize, MarkerExpandedWidth, fraction)
    val markerHeight = lerp(MarkerCollapsedSize, MarkerExpandedHeight, fraction)
}

private data class TimelineStateStyle(val background: Color, val foreground: Color)

/** 状态的配色：不同颜色的点 = 不同状态，拉宽后柱形里写的就是这个状态名 */
@Composable
private fun timelineStateStyle(kind: String): TimelineStateStyle = when (kind) {
    TimelineItem.STATE_DONE -> TimelineStateStyle(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
    TimelineItem.STATE_DOING -> TimelineStateStyle(Color(0xFFFF9800), Color.White)
    TimelineItem.STATE_WISH -> TimelineStateStyle(Color(0xFF2196F3), Color.White)
    TimelineItem.STATE_ON_HOLD -> TimelineStateStyle(Color(0xFF9E9E9E), Color.White)
    TimelineItem.STATE_DROPPED -> TimelineStateStyle(Color(0xFFF44336), Color.White)
    TimelineItem.STATE_PROGRESS -> TimelineStateStyle(Color(0xFF26A69A), Color.White)
    TimelineItem.STATE_SAY -> TimelineStateStyle(Color(0xFF4CAF50), Color.White)
    else -> TimelineStateStyle(MaterialTheme.colorScheme.outline, Color.White)
}

/** 表头 2026-10-9 的解析式：提到文件级，避免每次重组都重新编译（日期表头是吸顶项，滚动时反复执行） */
private val TIMELINE_DATE_REGEX = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")

/** 网页表头 2026-10-9 → 10月9日（不是今年才带上年份） */
private fun formatTimelineDate(label: String): String {
    val match = TIMELINE_DATE_REGEX.find(label) ?: return label
    val year = match.groupValues[1].toIntOrNull() ?: return label
    val month = match.groupValues[2].toIntOrNull() ?: return label
    val day = match.groupValues[3].toIntOrNull() ?: return label
    return if (year == java.time.LocalDate.now().year) "${month}月${day}日" else "${year}年${month}月${day}日"
}

/** 合并后一条动态的进度标签：单条直接写，多条按时间串起来（6/12话 → 8/12话） */
private fun progressChipText(labels: List<String>): String? = when {
    labels.isEmpty() -> null
    labels.size <= 2 -> labels.joinToString(" → ")
    else -> "${labels.first()} → ${labels.last()}"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit = {},
    onSubjectClick: (Int) -> Unit = {}
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    val timelineSnackbar = remember { SnackbarHostState() }
    // loading 初值 true：登录态来自 DataStore，首帧还没读到，先转圈而不是闪一下登录表单
    var uiState by remember { mutableStateOf(ProfileUiState(loading = true)) }
    var token by remember { mutableStateOf<String?>(null) }
    // 持久化设置是唯一数据源：本地再存一份镜像会跟 DataStore 的首次/后续发射打架，
    // 出现「点开了又自己收起」
    val railExpanded by app.settings.timelineRailExpanded.collectAsState(initial = false)

    // 导轨拉宽是个全局开关，动画值提到列表外面算一次，各行共用同一条曲线
    val railFraction by animateFloatAsState(
        targetValue = if (railExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "timelineRail"
    )
    val toggleRail: () -> Unit = {
        scope.launch { app.settings.saveTimelineRailExpanded(!railExpanded) }
    }
    // 网页上「同一次操作」常常是相邻的好几条动态，这里合成一行
    val entries = remember(uiState.timeline) { mergeTimeline(uiState.timeline) }

    LaunchedEffect(Unit) {
        app.settings.accessToken.collect { t -> token = t }
    }

    LaunchedEffect(token) {
        if (token != null) {
            uiState = uiState.copy(isLoggedIn = true, loading = true, error = null)
            suspend fun loadUser() {
                val user = app.api.getMe()
                uiState = uiState.copy(loading = false, user = user)
                app.settings.saveUsername(user.username)
            }
            try {
                loadUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // 401 用类型判定（原先靠 e.message 匹配 "401"/"token" 字符串，误判率高）；
                // 先尝试 refresh_token 续期，续期成功用新 token 重试一次
                if (e.response.status.value == 401 && app.recoverFromUnauthorized(token)) {
                    try {
                        loadUser()
                    } catch (retry: CancellationException) {
                        throw retry
                    } catch (retry: Exception) {
                        uiState = uiState.copy(loading = false, error = retry.message)
                    }
                } else if (e.response.status.value == 401) {
                    uiState = uiState.copy(loading = false, isLoggedIn = false, user = null, error = "Token 已失效，请重新登录")
                } else {
                    uiState = uiState.copy(loading = false, error = e.message)
                }
            } catch (e: Exception) {
                uiState = uiState.copy(loading = false, error = e.message)
            }
        } else {
            // 登出/未登录：整个状态复位——只清 user 会把上一个账号的时间线留在界面上
            uiState = ProfileUiState()
        }
    }

    LaunchedEffect(uiState.user?.username) {
        val username = uiState.user?.username
        if (username != null) {
            uiState = uiState.copy(timelineLoading = true, timelineError = null)
            try {
                val timeline = app.api.getUserTimeline(username)
                uiState = uiState.copy(
                    timelineLoading = false,
                    timeline = timeline,
                    timelinePage = 1,
                    timelineHasMore = timeline.isNotEmpty()
                )
            } catch (e: CancellationException) {
                // 取消（切账号）必须向上传播：吞掉会把 "Job was cancelled" 当成时间线错误写进界面
                throw e
            } catch (e: Exception) {
                uiState = uiState.copy(timelineLoading = false, timelineError = e.message)
            }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = { SnackbarHost(timelineSnackbar) },
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }
    ) { padding ->
        if (uiState.loading && uiState.user == null) {
            // 首帧还没读到 DataStore 里的登录态：转圈，别闪登录表单
            LoadingView(Modifier.padding(padding))
        } else if (!uiState.isLoggedIn) {
            LoginContent(
                modifier = Modifier.padding(padding).fillMaxSize(),
                onLoginSuccess = { user ->
                    uiState = uiState.copy(isLoggedIn = true, user = user, loading = false)
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                val user = uiState.user
                if (user != null) {
                    val context = LocalContext.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val url = "${app.api.currentWebHost}/user/${user.username}"
                                openInBrowser(context, url)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = ImageUrls.avatar(user.avatar?.medium ?: user.avatar?.large),
                            contentDescription = "头像",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = user.nickname,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "@${user.username}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (user.sign.isNotEmpty()) {
                                Text(
                                    text = user.sign,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "时间线",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    // 左侧导轨的开关：点导轨本身也能切换，这里给一个看得见的入口
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                            .clickable { toggleRail() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ViewColumn,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (railExpanded) "收起状态" else "显示状态",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                when {
                    uiState.timelineLoading -> LoadingView()
                    uiState.timelineError != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(uiState.timelineError ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    }
                    entries.isEmpty() -> EmptyView()
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        entries.forEachIndexed { index, entry ->
                            if (entry.dateLabel.isNotEmpty() && entry.dateLabel != entries.getOrNull(index - 1)?.dateLabel) {
                                stickyHeader(key = "date_${entry.dateLabel}") {
                                    TimelineDateHeader(
                                        dateLabel = entry.dateLabel,
                                        isFirst = index == 0,
                                        railFraction = railFraction,
                                        onToggleRail = toggleRail
                                    )
                                }
                            }
                            item(key = "tml_${entry.key}") {
                                TimelineEntryRow(
                                    entry = entry,
                                    isLast = index == entries.size - 1,
                                    railFraction = railFraction,
                                    onToggleRail = toggleRail,
                                    onSubjectClick = onSubjectClick,
                                    onEpisodeFallbackClick = { episodeId ->
                                        scope.launch {
                                            try {
                                                val subjectId = app.api.fetchEpisodeSubjectId(episodeId)
                                                if (subjectId > 0) onSubjectClick(subjectId)
                                                else timelineSnackbar.showSnackbar("无法找到这条章节记录所属的条目")
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                timelineSnackbar.showSnackbar("查询所属条目失败，请检查网络后重试")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        if (uiState.timelineHasMore) {
                            item(key = "timeline_load_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (uiState.timelineLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        // 滑到底后出现的小箭头，点一下加载下一页
                                        IconButton(
                                            onClick = {
                                                val username = uiState.user?.username ?: return@IconButton
                                                scope.launch {
                                                    if (uiState.timelineLoadingMore || !uiState.timelineHasMore) return@launch
                                                    uiState = uiState.copy(timelineLoadingMore = true)
                                                    try {
                                                        val next = app.api.getUserTimeline(username, page = uiState.timelinePage + 1)
                                                        val existingIds = uiState.timeline.map { it.id }.toSet()
                                                        val fresh = next.filter { it.id !in existingIds }
                                                        uiState = if (fresh.isEmpty()) {
                                                            uiState.copy(timelineLoadingMore = false, timelineHasMore = false)
                                                        } else {
                                                            uiState.copy(
                                                                timeline = uiState.timeline + fresh,
                                                                timelinePage = uiState.timelinePage + 1,
                                                                timelineLoadingMore = false
                                                            )
                                                        }
                                                    } catch (_: Exception) {
                                                        uiState = uiState.copy(timelineLoadingMore = false)
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = "加载更多时间线",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
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
}

@Composable
private fun LoginContent(
    modifier: Modifier = Modifier,
    onLoginSuccess: (User) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        BangumiLoginPanel(onLoginSuccess = onLoginSuccess)
    }
}

/** 列表左侧的导轨单元：一个是「点」，拉宽后是「写好状态的柱」；点一下切换 */
@Composable
private fun TimelineRailCell(
    fraction: Float,
    isLast: Boolean,
    stateKind: String?,
    stateLabel: String,
    onToggle: () -> Unit
) {
    val metrics = RailMetrics(fraction)
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .width(metrics.width)
            .fillMaxHeight()
            .clickable(onClick = onToggle)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .fillMaxHeight()
                .background(if (isLast) Color.Transparent else lineColor)
        )
        if (stateKind != null) {
            val style = timelineStateStyle(stateKind)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(metrics.markerWidth, metrics.markerHeight)
                    .clip(RoundedCornerShape(50))
                    .background(style.background),
                contentAlignment = Alignment.Center
            ) {
                // 收起时柱宽等于点宽，这段文字被裁掉；展开过程中随宽度淡入
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = style.foreground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.graphicsLayer { alpha = fraction }
                )
            }
        }
    }
}

/** 日期分隔：网页按天分组的表头，做成列表的粘性表头，导轨照常连过去 */
@Composable
private fun TimelineDateHeader(
    dateLabel: String,
    isFirst: Boolean,
    railFraction: Float,
    onToggleRail: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        TimelineRailCell(
            fraction = railFraction,
            isLast = false,
            stateKind = null,
            stateLabel = "",
            onToggle = onToggleRail
        )
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.padding(top = if (isFirst) 2.dp else 10.dp, bottom = 6.dp)
        ) {
            Text(
                text = formatTimelineDate(dateLabel),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun TimelineChip(
    text: String,
    container: Color,
    content: Color,
    maxWidth: Dp
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = container,
        modifier = Modifier.widthIn(max = maxWidth)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun TimelineEntryRow(
    entry: TimelineEntry,
    isLast: Boolean,
    railFraction: Float,
    onToggleRail: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onEpisodeFallbackClick: (Int) -> Unit
) {
    val darkTheme = LocalDarkTheme.current
    val titleShape = RoundedCornerShape(6.dp)
    val titleGlassTint = MaterialTheme.colorScheme.surface.copy(alpha = if (darkTheme) 0.72f else 0.78f)
    val displayName = entry.subjectNameCn.ifEmpty { entry.subjectName }
    val subName = if (entry.subjectNameCn.isNotEmpty() && entry.subjectName != entry.subjectNameCn) entry.subjectName else ""
    val progressText = progressChipText(entry.progressLabels)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                when {
                    entry.subjectId > 0 -> Modifier.clickable { onSubjectClick(entry.subjectId) }
                    entry.episodeId > 0 -> Modifier.clickable { onEpisodeFallbackClick(entry.episodeId) }
                    else -> Modifier
                }
            )
    ) {
        TimelineRailCell(
            fraction = railFraction,
            isLast = isLast,
            stateKind = entry.stateKind,
            stateLabel = entry.stateLabel,
            onToggle = onToggleRail
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (entry.subjectImage.isNotEmpty()) {
                    AsyncImage(
                        model = ImageUrls.cover(entry.subjectImage, ImageUrls.THUMB),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (displayName.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(titleShape)
                                    .background(titleGlassTint, titleShape)
                                    .then(wallpaperGlassPanel(darkTheme, titleShape))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (progressText != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            TimelineChip(
                                text = progressText,
                                container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                content = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxWidth = 140.dp
                            )
                        }
                        // 一次操作涉及多个条目时，网页给的数量后缀原样带出来（2 部番组、3 本书）
                        if (entry.batchSuffix.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            TimelineChip(
                                text = entry.batchSuffix,
                                container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                content = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxWidth = 80.dp
                            )
                        }
                    }
                    if (subName.isNotEmpty()) {
                        Text(
                            text = subName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 没有条目的动态（状态、改名、加入小组等）没有"标题"可言，
                    // 正文就放在标题下方的正文位置，不占标题位、不加玻璃
                    if (displayName.isEmpty() && entry.description.isNotEmpty()) {
                        Text(
                            text = entry.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (entry.userRating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(entry.userRating.coerceAtMost(10)) {
                            Text(
                                text = "★",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFC107),
                                fontSize = 10.sp
                            )
                        }
                        Text(
                            text = " ${entry.userRating}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (entry.subjectScore > 0) {
                    Text(
                        text = "★${entry.subjectScore}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.subjectRank > 0) {
                    Text(
                        text = "#${entry.subjectRank}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (entry.userComment.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = entry.userComment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            if (entry.time.isNotEmpty()) {
                Text(
                    text = entry.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
