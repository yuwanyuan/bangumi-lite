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
