package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.Settings
import com.bangumi.ywylite.ui.component.BangumiLoginPanel
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    settings: Settings,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<com.bangumi.ywylite.data.model.User?>(null) }
    var token by remember { mutableStateOf("") }
    var isLoggedIn by remember { mutableStateOf(false) }

    val app = App.INSTANCE

    LaunchedEffect(Unit) {
        app.settings.accessToken.collect { t ->
            token = t ?: ""
            isLoggedIn = !t.isNullOrEmpty()
        }
    }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            try {
                user = app.api.getMe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                // 401 用类型判定（原先是 e.message 字符串匹配，误判率高）；
                // 先尝试续期，续期成功用新 token 重试一次
                if (e.response.status.value == 401) {
                    if (app.recoverFromUnauthorized(token.ifBlank { null })) {
                        user = runCatching { app.api.getMe() }.getOrNull()
                    } else {
                        isLoggedIn = false
                        user = null
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoggedIn) {
                user?.let { u ->
                    Text("已登录: ${u.nickname} (@${u.username})", style = MaterialTheme.typography.bodyLarge)
                }
                Text("Token: ${token.take(10)}...", style = MaterialTheme.typography.bodySmall)

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            app.settings.clearToken()
                            app.api.updateToken(null)
                            // 登出要连 refresh_token 与网页会话一起清，
                            // 否则残留的 Cookie/refresh_token 会让下次登录或请求行为异常
                            app.api.refreshToken = null
                            app.api.clearWebSession()
                            isLoggedIn = false
                            user = null
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("退出登录")
                }
            } else {
                Text("未登录", style = MaterialTheme.typography.bodyLarge)
                BangumiLoginPanel(
                    onLoginSuccess = { me ->
                        isLoggedIn = true
                        user = me
                    }
                )
            }
        }
    }
}
