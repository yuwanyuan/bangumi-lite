package com.bangumi.ywylite.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.TimelineItem
import com.bangumi.ywylite.data.model.User
import com.bangumi.ywylite.ui.component.EmptyView
import com.bangumi.ywylite.ui.component.LoadingView
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoggedIn: Boolean = false,
    val user: User? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val timelineLoading: Boolean = false,
    val timelineError: String? = null
)

private val timelineTypeLabels = mapOf(
    "watched" to "📺",
    "completed" to "✅",
    "watching" to "👀",
    "wish" to "⭐",
    "on_hold" to "⏸",
    "dropped" to "❌",
    "comment" to "💬",
    "other" to "📌"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit = {},
    onSubjectClick: (Int) -> Unit = {}
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(ProfileUiState()) }
    var token by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        app.settings.accessToken.collect { t -> token = t }
    }

    LaunchedEffect(token) {
        if (token != null) {
            uiState = uiState.copy(isLoggedIn = true, loading = true, error = null)
            try {
                val user = app.api.getMe()
                uiState = uiState.copy(loading = false, user = user)
                app.settings.saveUsername(user.username)
            } catch (e: Exception) {
                val isAuthError = e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true || e.message?.contains("token") == true
                if (isAuthError) {
                    app.settings.clearToken()
                    app.api.updateToken(null)
                    uiState = uiState.copy(loading = false, isLoggedIn = false, user = null, error = "Token 已失效，请重新登录")
                } else {
                    uiState = uiState.copy(loading = false, error = e.message)
                }
            }
        } else {
            uiState = uiState.copy(isLoggedIn = false, user = null, loading = false, error = null)
        }
    }

    LaunchedEffect(uiState.user?.username) {
        val username = uiState.user?.username
        if (username != null) {
            uiState = uiState.copy(timelineLoading = true, timelineError = null)
            try {
                val timeline = app.api.getUserTimeline(username)
                uiState = uiState.copy(timelineLoading = false, timeline = timeline)
            } catch (e: Exception) {
                uiState = uiState.copy(timelineLoading = false, timelineError = e.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (!uiState.isLoggedIn) {
            LoginContent(
                modifier = Modifier.padding(padding),
                onLoginSuccess = { user ->
                    uiState = uiState.copy(isLoggedIn = true, user = user, loading = false)
                },
                onError = { error ->
                    uiState = uiState.copy(loading = false, error = error)
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = user.avatar?.medium ?: "",
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

                Text(
                    text = "时间线",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                when {
                    uiState.timelineLoading -> LoadingView()
                    uiState.timelineError != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(uiState.timelineError ?: "加载失败", color = MaterialTheme.colorScheme.error)
                    }
                    uiState.timeline.isEmpty() -> EmptyView()
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(uiState.timeline, key = { _, item -> item.id }) { index, item ->
                            TimelineItemRow(
                                item = item,
                                isLast = index == uiState.timeline.size - 1,
                                onSubjectClick = onSubjectClick
                            )
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
    onLoginSuccess: (User) -> Unit,
    onError: (String) -> Unit
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tokenInput by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("登录 Bangumi", style = MaterialTheme.typography.headlineSmall)

        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://next.bgm.tv/demo/access-token"))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("前往 Bangumi 登录获取 Token")
        }

        Text(
            text = "在浏览器中复制 Token 后粘贴到下方",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Access Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                scope.launch {
                    val t = tokenInput.text.trim()
                    if (t.isNotBlank()) {
                        loading = true
                        error = null
                        try {
                            app.api.updateToken(t)
                            val me = app.api.getMe()
                            app.settings.saveToken(t)
                            app.settings.saveUsername(me.username)
                            onLoginSuccess(me)
                        } catch (e: Exception) {
                            app.api.updateToken(null)
                            error = "登录失败: ${e.message}"
                            onError(error ?: "登录失败")
                        }
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && tokenInput.text.isNotBlank()
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("登录")
            }
        }
    }
}

@Composable
private fun TimelineItemRow(
    item: TimelineItem,
    isLast: Boolean,
    onSubjectClick: (Int) -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val dotColor = when (item.type) {
        "watched", "completed" -> MaterialTheme.colorScheme.primary
        "watching" -> Color(0xFFFF9800)
        "wish" -> Color(0xFF2196F3)
        "on_hold" -> Color(0xFF9E9E9E)
        "dropped" -> Color(0xFFF44336)
        "comment" -> Color(0xFF4CAF50)
        else -> MaterialTheme.colorScheme.outline
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(if (item.subjectId > 0) Modifier.clickable { onSubjectClick(item.subjectId) } else Modifier)
    ) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    .background(if (isLast) Color.Transparent else lineColor)
            )
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .background(dotColor, CircleShape)
                    .align(Alignment.TopCenter)
            )
        }

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
                if (item.subjectImage.isNotEmpty()) {
                    AsyncImage(
                        model = item.subjectImage,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    val displayName = item.subjectNameCn.ifEmpty { item.subjectName }
                    val subName = if (item.subjectNameCn.isNotEmpty() && item.subjectName != item.subjectNameCn) item.subjectName else ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (item.episodeInfo.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    text = item.episodeInfo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
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
                }
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (item.userRating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(item.userRating.coerceAtMost(10)) {
                            Text(
                                text = "★",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFC107),
                                fontSize = 10.sp
                            )
                        }
                        Text(
                            text = " ${item.userRating}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (item.subjectScore > 0) {
                    Text(
                        text = "★${item.subjectScore}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.subjectRank > 0) {
                    Text(
                        text = "#${item.subjectRank}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (item.userComment.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = item.userComment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            if (item.time.isNotEmpty()) {
                Text(
                    text = item.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
