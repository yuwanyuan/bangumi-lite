package com.bangumi.ywylite.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.api.BangumiApi
import com.bangumi.ywylite.data.model.TagInfo
import com.bangumi.ywylite.ui.component.ErrorView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

data class TagsUiState(
    val loading: Boolean = true,
    val tags: List<TagInfo> = emptyList(),
    val error: String? = null,
    val selectedType: Int = 2,
    /** 已加载到的索引页号（0 = 尚未加载；网页索引每页 100 个标签、按标注人数排序） */
    val page: Int = 0,
    val totalPages: Int = Int.MAX_VALUE,
    val loadMoreError: Boolean = false,
    /** 顶部搜索框展开中 */
    val searching: Boolean = false,
    /** 索引搜索关键词；空 = 不过滤 */
    val searchQuery: String = "",
    /** 重试信号：+1 触发主 LaunchedEffect 重新加载 */
    val retryKey: Int = 0,
    /** 分段加载重试信号 */
    val loadMoreKey: Int = 0
)

/** 进程内标签索引缓存：累积的标签 + 已翻到的页号，重进页面接着往下翻，不再从头请求 */
private class TagIndexCache(val tags: List<TagInfo>, val page: Int, val totalPages: Int)

private val tagsCache = mutableMapOf<String, TagIndexCache>()

/** 标签真实条目数缓存：key = "typePath|标签名"。索引页给的数字是标注人数，不能直接展示 */
private val tagSubjectCountCache = mutableMapOf<String, Int>()

/** 条目数缓存的条数上限：动画索引 2000+ 页，一直往下翻不能让它无限长 */
private const val TAG_COUNT_CACHE_LIMIT = 3000

/** 缓存归属的会话代号：R18 条目只对登录会话下发，登录/登出后索引内容会变，缓存必须整体作废 */
private var tagsCacheGeneration = -1

private fun ensureTagsCache() {
    val generation = App.INSTANCE.api.sessionGeneration
    if (tagsCacheGeneration != generation) {
        tagsCache.clear()
        tagSubjectCountCache.clear()
        tagsCacheGeneration = generation
    }
}

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
    // 入参决定首屏看哪一类：浏览页切到「游戏」再进标签页，打开的应该是游戏标签索引
    val initialType = remember(typePath) {
        tagsTypePaths.entries.firstOrNull { it.value == typePath }?.key ?: 2
    }
    var uiState by remember { mutableStateOf(TagsUiState(selectedType = initialType)) }
    val gridState = rememberLazyGridState()

    // 首次加载 / 切类型 / 重试：有缓存直接展示，没有则取索引第 1 页
    LaunchedEffect(uiState.selectedType, uiState.retryKey) {
        ensureTagsCache()
        val path = tagsTypePaths[uiState.selectedType] ?: "anime"
        val cached = tagsCache[path]
        if (cached != null) {
            uiState = uiState.copy(
                loading = false,
                error = null,
                tags = cached.tags,
                page = cached.page,
                totalPages = cached.totalPages,
                loadMoreError = false
            )
        } else {
            uiState = uiState.copy(loading = true, error = null, tags = emptyList(), page = 0)
            try {
                val result = api.getTagIndexPage(path, page = 1)
                tagsCache[path] = TagIndexCache(result.tags, result.page, result.totalPages)
                uiState = uiState.copy(
                    loading = false,
                    tags = result.tags,
                    page = result.page,
                    totalPages = result.totalPages
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState = uiState.copy(loading = false, error = e.message)
            }
        }
    }

    // 滑到底自动加载下一页索引（同 TagBrowseScreen：滚动位置检测 + 显式失败重试）。
    // 注意不要在这里引入「loading 中间状态位」：effect 一改状态 nearEnd 就翻 false，
    // key 变化会取消正在进行的请求且该状态永远等不到复位——此前分段加载因此完全失效
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            !uiState.loading && uiState.error == null && uiState.tags.isNotEmpty() &&
                uiState.searchQuery.isBlank() &&
                uiState.page < uiState.totalPages && !uiState.loadMoreError &&
                lastVisible >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, uiState.page, uiState.loadMoreKey) {
        if (!nearEnd) return@LaunchedEffect
        val type = uiState.selectedType
        val path = tagsTypePaths[type] ?: "anime"
        val currentTags = uiState.tags
        val nextPage = uiState.page + 1
        try {
            val result = api.getTagIndexPage(path, page = nextPage)
            val merged = (currentTags + result.tags).distinctBy { it.name }
            // 空页说明已到末页（分页信息缺失时的兜底），封住不再继续翻
            val effectiveTotalPages = if (result.tags.isEmpty()) uiState.page else result.totalPages
            tagsCache[path] = TagIndexCache(merged, result.page, effectiveTotalPages)
            // 切类型后旧任务只写对应 path 的缓存，不覆盖新类型的列表
            if (uiState.selectedType == type) {
                uiState = uiState.copy(
                    tags = merged.map { it.copy(subjectCount = tagSubjectCountCache["$path|${it.name}"] ?: -1) },
                    page = result.page,
                    totalPages = effectiveTotalPages,
                    loadMoreError = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (uiState.selectedType == type) {
                uiState = uiState.copy(loadMoreError = true)
            }
        }
    }

    // 标签真实条目数「滚动到哪补到哪」：只为可见标签发请求（每个标签 1~2 个网页请求），
    // 结果进 tagSubjectCountCache——翻回来或重进页面都不再重复请求
    LaunchedEffect(uiState.selectedType, uiState.retryKey) {
        ensureTagsCache()
        val type = uiState.selectedType
        val path = tagsTypePaths[type] ?: "anime"
        val requested = mutableSetOf<String>()
        // 失败过的标签允许再试一次：否则网络抖一下，那几个标签的条目数会永久停在「…」
        val retried = mutableSetOf<String>()
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .collect { names ->
                val pending = names.filter { it !in requested && tagSubjectCountCache["$path|$it"] == null }
                if (pending.isEmpty()) return@collect
                requested.addAll(pending)
                pending.chunked(4).forEach { batch ->
                    batch.map { name -> async { name to api.getTagSubjectCount(type, name) } }
                        .awaitAll()
                        .forEach { (name, count) ->
                            if (count != null) {
                                if (tagSubjectCountCache.size >= TAG_COUNT_CACHE_LIMIT) tagSubjectCountCache.clear()
                                tagSubjectCountCache["$path|$name"] = count
                            } else if (retried.add(name)) {
                                requested.remove(name)
                            }
                        }
                    if (uiState.selectedType == type) {
                        uiState = uiState.copy(
                            tags = uiState.tags.map { t ->
                                tagSubjectCountCache["$path|${t.name}"]?.let { c -> t.copy(subjectCount = c) } ?: t
                            }
                        )
                    }
                }
            }
    }

    val searchQuery = uiState.searchQuery.trim()
    val displayTags = remember(uiState.tags, searchQuery) {
        if (searchQuery.isEmpty()) uiState.tags
        else uiState.tags.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(uiState.searching) {
        if (uiState.searching) searchFocusRequester.requestFocus()
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    // 点搜索：放大镜图标从右端（原按钮位）被推到左端框内，
                    // 圆角搜索框从右端向左生长、左缘始终贴着放大镜，叉号固定在框右端
                    val p by animateFloatAsState(
                        targetValue = if (uiState.searching) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
                        label = "tagSearchOpen"
                    )
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        val W = maxWidth
                        // 搜索框：宽从 0 长到满宽，锚右端 → 左缘从右端推进到左端
                        val boxW = W * p
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(boxW)
                        ) {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(48.dp)) // 给外面的放大镜留位
                                // 用 BasicTextField 而非 TextField：后者最小高度 56dp，
                                // 放进 48dp 的圆角 Surface（会裁剪子项）里会把文字上缘切掉
                                BasicTextField(
                                    value = uiState.searchQuery,
                                    onValueChange = { uiState = uiState.copy(searchQuery = it) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { inner ->
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (uiState.searchQuery.isEmpty()) {
                                                Text(
                                                    text = "输入标签名过滤已加载的标签",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .focusRequester(searchFocusRequester)
                                )
                                IconButton(
                                    onClick = {
                                        focusManager.clearFocus()
                                        uiState = uiState.copy(searching = false, searchQuery = "")
                                    }
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
                                }
                            }
                        }
                        // 放大镜（唯一一个）：左缘从右端按钮位插值到左端框内位
                        val magLeft = (W - 60.dp) * (1f - p) + 8.dp * p
                        Box(
                            modifier = Modifier
                                .offset(x = magLeft)
                                .fillMaxHeight()
                                .width(48.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            IconButton(onClick = {
                                if (uiState.searching) {
                                    searchFocusRequester.requestFocus()
                                } else {
                                    uiState = uiState.copy(searching = true)
                                }
                            }) {
                                Icon(Icons.Filled.Search, contentDescription = "搜索标签")
                            }
                        }
                        // 标题：随展开淡出左移
                        Text(
                            text = "标签",
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 16.dp)
                                .graphicsLayer {
                                    alpha = 1f - p
                                    translationX = -(p * 40f)
                                }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {}
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = tagsSubjectTypes.indexOfFirst { it.first == uiState.selectedType }.coerceAtLeast(0),
                edgePadding = 16.dp,
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                divider = {}
            ) {
                tagsSubjectTypes.forEach { (type, label) ->
                    val selected = uiState.selectedType == type
                    Tab(
                        selected = selected,
                        onClick = { uiState = uiState.copy(selectedType = type, searchQuery = "") },
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
                displayTags.isEmpty() -> {
                    // 搜索无匹配：已加载的只是前几页（全量 2000+ 页），未加载的标签允许直接打开
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "已加载的标签中没有「$searchQuery」",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { onNavigateToTagBrowse(searchQuery, searchQuery, uiState.selectedType) }) {
                            Text("直接打开标签「$searchQuery」")
                        }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        // 自适应列宽：窄屏约 4 列，平板自动多列
                        columns = GridCells.Adaptive(88.dp),
                        state = gridState,
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(displayTags, key = { it.name }) { tag ->
                            TagCard(tag = tag, onClick = {
                                onNavigateToTagBrowse(tag.name, tag.name, uiState.selectedType)
                            })
                        }
                        // 滑到底的分段加载提示：进行中显示动画，失败显示重试
                        if (uiState.searchQuery.isBlank() && uiState.page < uiState.totalPages) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
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
            // 标签下的真实条目数（索引页给的数字是「标注人数」，量级完全不同，不能直接用）
            val subjectCount = tag.subjectCount
            Text(
                text = if (subjectCount >= 0) formatTagCount(subjectCount) else "…",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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
