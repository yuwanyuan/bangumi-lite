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
import kotlinx.coroutines.launch

enum class TestState { Idle, Testing, Success, Failed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsScreen(
    settings: Settings,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val app = App.INSTANCE

    var enabled by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf("HTTP") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("7890") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testState by remember { mutableStateOf(TestState.Idle) }
    var latency by remember { mutableStateOf(0L) }
    var testResult by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        settings.proxyEnabled.collect { enabled = it }
    }
    LaunchedEffect(Unit) {
        settings.proxyType.collect { type = it }
    }
    LaunchedEffect(Unit) {
        settings.proxyHost.collect { host = it }
    }
    LaunchedEffect(Unit) {
        settings.proxyPort.collect { port = it.toString() }
    }
    LaunchedEffect(Unit) {
        settings.proxyUsername.collect { username = it }
    }
    LaunchedEffect(Unit) {
        settings.proxyPassword.collect { password = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("代理设置") },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("启用代理", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }

            OutlinedTextField(
                value = type,
                onValueChange = { type = it },
                label = { Text("代理类型") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("代理地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("代理端口") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            when (testState) {
                TestState.Testing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                TestState.Success -> {
                    Text("连接成功，延迟: ${latency}ms", color = MaterialTheme.colorScheme.primary)
                }
                TestState.Failed -> {
                    Text("连接失败: $testResult", color = MaterialTheme.colorScheme.error)
                }
                else -> {}
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            testState = TestState.Testing
                            try {
                                val start = System.currentTimeMillis()
                                app.api.updateProxy(enabled, type, host, port.toIntOrNull() ?: 7890, username, password)
                                app.api.getCalendar()
                                latency = System.currentTimeMillis() - start
                                testState = TestState.Success
                            } catch (e: Exception) {
                                testResult = e.message ?: "未知错误"
                                testState = TestState.Failed
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = testState != TestState.Testing
                ) {
                    Text("测试连接")
                }

                Button(
                    onClick = {
                        scope.launch {
                            settings.saveProxyEnabled(enabled)
                            settings.saveProxyType(type)
                            settings.saveProxyHost(host)
                            settings.saveProxyPort(port.toIntOrNull() ?: 7890)
                            settings.saveProxyUsername(username)
                            settings.saveProxyPassword(password)
                            app.api.updateProxy(enabled, type, host, port.toIntOrNull() ?: 7890, username, password)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}
