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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TestState { Idle, Testing, Success, Failed }

/** 代理类型二选一：原先的自由文本只有全大写 "SOCKS" 才生效，socks5 会静默按 HTTP 处理 */
private val proxyTypes = listOf("HTTP", "SOCKS5")

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

    /** 地址格式校验：代理地址只填主机名或 IP，协议前缀与端口各有专门的地方 */
    fun hostErrorOf(raw: String): String? {
        val clean = raw.trim()
        return when {
            clean.isBlank() -> "请先填写代理地址"
            clean.contains("://") -> "代理地址只填主机名或 IP，不要带协议前缀"
            clean.contains(":") -> "端口请填在端口框里，地址中不要带冒号"
            else -> null
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("代理设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("代理类型", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.width(12.dp))
                proxyTypes.forEach { value ->
                    FilterChip(
                        selected = type == value,
                        onClick = { type = value },
                        label = { Text(value) }
                    )
                }
            }

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
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
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
                        val hostError = hostErrorOf(host)
                        val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
                        when {
                            hostError != null -> {
                                testResult = hostError
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
                                        // 隔离测试：不改动全局代理配置。探活用的是自备 Dispatcher/
                                        // ConnectionPool 的一次性客户端，不会碰到全局连接池
                                        latency = app.api.testProxyConnection(type, host.trim(), portNum, username.trim(), password)
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
                        // 保存前校验端口，并校验地址：原先只查端口，地址留空也能「保存成功」，
                        // 但代理此时根本没生效（buildProxyConfig 遇空地址直接返回 null），
                        // 界面开关是开的、请求却还是直连
                        val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
                        val cleanHost = host.trim()
                        val hostError = if (enabled) hostErrorOf(cleanHost) else null
                        when {
                            hostError != null -> {
                                testResult = hostError
                                testState = TestState.Failed
                            }
                            portNum == null -> {
                                testResult = "端口无效（需 1-65535）"
                                testState = TestState.Failed
                            }
                            else -> {
                                scope.launch {
                                    // NonCancellable：保存后立刻退出页面也不会中断写入，
                                    // 否则部分字段没落盘（输入看似丢失）且代理未生效
                                    withContext(NonCancellable) {
                                        settings.saveProxyEnabled(enabled)
                                        settings.saveProxyType(type)
                                        settings.saveProxyHost(cleanHost)
                                        settings.saveProxyPort(portNum)
                                        settings.saveProxyUsername(username.trim())
                                        settings.saveProxyPassword(password)
                                        app.api.updateProxy(enabled, type, cleanHost, portNum, username.trim(), password)
                                    }
                                    Toast.makeText(context, "代理配置已保存", Toast.LENGTH_SHORT).show()
                                }
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
