package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.Settings
import com.bangumi.ywylite.ui.component.openInBrowser
import kotlinx.coroutines.launch

/** Bangumi 官方个人访问令牌创建页 */
private const val TOKEN_URL = "https://next.bgm.tv/demo/access-token"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsScreen(
    settings: Settings,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<com.bangumi.ywylite.data.model.User?>(null) }
    var token by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var isLoggedIn by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
            } catch (e: Exception) {
                val isAuthError = e.message?.contains("401") == true || e.message?.contains("Unauthorized") == true
                if (isAuthError) {
                    app.settings.clearToken()
                    app.api.updateToken(null)
                    isLoggedIn = false
                    user = null
                }
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

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Access Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                val context = LocalContext.current
                TextButton(
                    onClick = { openInBrowser(context, TOKEN_URL) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text("获取 Access Token ↗", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "点击上方链接在浏览器中登录 Bangumi，创建个人访问令牌后复制粘贴到上方输入框。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = {
                        scope.launch {
                            val t = tokenInput.text.trim()
                            if (t.isNotBlank()) {
                                isLoading = true
                                error = ""
                                try {
                                    app.api.updateToken(t)
                                    val me = app.api.getMe()
                                    app.settings.saveToken(t)
                                    app.settings.saveUsername(me.username)
                                    isLoggedIn = true
                                    user = me
                                } catch (e: Exception) {
                                    app.api.updateToken(null)
                                    error = "登录失败: ${e.message}"
                                }
                                isLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && tokenInput.text.isNotBlank()
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("登录")
                }
            }
        }
    }
}
