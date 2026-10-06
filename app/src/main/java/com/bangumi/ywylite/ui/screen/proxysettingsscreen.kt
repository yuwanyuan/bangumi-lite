package com.bangumi.ywylite.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TestState { Idle, Testing, Success, Failed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsScreen(
    settings: Settings,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val app = App.INSTANCE
    val context = LocalContext.current

    // 只读一次初值。此前用持续 collect 回显，输入过程可能被 flow 发射覆盖，
    // 且保存协程随页面销毁被取消导致只写了一半字段（表现为输入丢失、代理未生效）
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
        enabled = settings.proxyEnabled.first()
        type = settings.proxyType.first()
        host = settings.proxyHost.first()
        port = settings.proxyPort.first().toString()
        username = settings.proxyUsername.first()
        password = settings.proxyPassword.first()
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
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
                        val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
                        when {
                            host.isBlank() -> {
                                testResult = "请先填写代理地址"
                                testState = TestState.Failed
                            }
                            portNum == null -> {
                                testResult = "端口无效（需 1-65535）"
                                testState = TestState.Failed
                            }
                            else -> {
                                scope.launch {
                                    testState = TestState.Testing
                                    try {
                                        // 隔离测试：不改动全局代理配置。原先点"测试连接"
                                        // 会直接 updateProxy 把整个应用切到待测代理，
                                        // 且不落盘 DataStore，造成内存态与设置不一致
                                        latency = app.api.testProxyConnection(type, host, portNum, username, password)
                                        testState = TestState.Success
                                    } catch (e: Exception) {
                                        testResult = e.message ?: "未知错误"
                                        testState = TestState.Failed
                                    }
                                }
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
                        // 保存前校验端口：原先静默回落 7890，用户填错端口也毫无提示
                        val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
                        if (portNum == null) {
                            testResult = "端口无效（需 1-65535）"
                            testState = TestState.Failed
                        } else {
                            scope.launch {
                                // NonCancellable：保存后立刻退出页面也不会中断写入，
                                // 否则部分字段没落盘（输入看似丢失）且代理未生效
                                withContext(NonCancellable) {
                                    settings.saveProxyEnabled(enabled)
                                    settings.saveProxyType(type)
                                    settings.saveProxyHost(host)
                                    settings.saveProxyPort(portNum)
                                    settings.saveProxyUsername(username)
                                    settings.saveProxyPassword(password)
                                    app.api.updateProxy(enabled, type, host, portNum, username, password)
                                }
                                Toast.makeText(context, "代理配置已保存", Toast.LENGTH_SHORT).show()
                            }
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
