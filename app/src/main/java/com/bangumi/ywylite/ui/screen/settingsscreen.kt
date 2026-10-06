package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.ui.component.openInBrowser
import android.content.Context
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAccountSettings: () -> Unit,
    onNavigateToCacheSettings: () -> Unit,
    onNavigateToProxySettings: () -> Unit
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val darkMode by app.settings.darkMode.collectAsState(initial = "system")
    val dynamicColor by app.settings.dynamicColor.collectAsState(initial = false)
    var showDarkModeDialog by remember { mutableStateOf(false) }

    // 官方 API / Web 端域名切换
    val apiHosts = listOf(
        "https://api.bgm.tv" to "api.bgm.tv（默认）",
        "https://api.bgmapi.com" to "api.bgmapi.com（备用）"
    )
    val webHosts = listOf(
        "https://bgm.tv" to "bgm.tv（默认）",
        "https://bangumi.tv" to "bangumi.tv（备用）",
        "https://chii.in" to "chii.in（备用）"
    )
    val apiHost by app.settings.apiHost.collectAsState(initial = "")
    val webHost by app.settings.webHost.collectAsState(initial = "")
    var showApiHostDialog by remember { mutableStateOf(false) }
    var showWebHostDialog by remember { mutableStateOf(false) }
    // 地址连通性测试结果：null 测试中 / true 通（绿）/ false 不通（红）
    var pingResults by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }

    LaunchedEffect(showApiHostDialog, showWebHostDialog) {
        val targets = buildList {
            if (showApiHostDialog) addAll(apiHosts.map { it.first })
            if (showWebHostDialog) addAll(webHosts.map { it.first })
        }
        if (targets.isEmpty()) return@LaunchedEffect
        pingResults = targets.associateWith { null }
        targets.forEach { url ->
            scope.launch {
                val ok = runCatching { app.api.pingHost(url) }.getOrDefault(false)
                pingResults = pingResults + (url to ok)
            }
        }
    }

    if (showDarkModeDialog) {
        AlertDialog(
            onDismissRequest = { showDarkModeDialog = false },
            title = { Text("深色模式") },
            text = {
                Column {
                    listOf("system" to "跟随系统", "light" to "浅色模式", "dark" to "深色模式").forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settings.saveDarkMode(value) }
                                    showDarkModeDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = darkMode == value,
                                onClick = {
                                    scope.launch { app.settings.saveDarkMode(value) }
                                    showDarkModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDarkModeDialog = false }) { Text("取消") }
            }
        )
    }

    if (showApiHostDialog) {
        AlertDialog(
            onDismissRequest = { showApiHostDialog = false },
            title = { Text("API 地址") },
            text = {
                Column {
                    apiHosts.forEach { (value, label) ->
                        HostOptionRow(
                            label = label,
                            selected = apiHost == value,
                            pingState = pingResults[value],
                            onSelect = {
                                scope.launch {
                                    app.settings.saveApiHost(value)
                                    app.api.updateApiHost(apiHost = value)
                                }
                                showApiHostDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showApiHostDialog = false }) { Text("取消") }
            }
        )
    }

    if (showWebHostDialog) {
        AlertDialog(
            onDismissRequest = { showWebHostDialog = false },
            title = { Text("Web 端地址") },
            text = {
                Column {
                    webHosts.forEach { (value, label) ->
                        HostOptionRow(
                            label = label,
                            selected = webHost == value,
                            pingState = pingResults[value],
                            onSelect = {
                                scope.launch {
                                    app.settings.saveWebHost(value)
                                    app.api.updateApiHost(webHost = value)
                                }
                                showWebHostDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWebHostDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsSection(title = "外观") {
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = "深色模式",
                    subtitle = when (darkMode) {
                        "light" -> "浅色模式"
                        "dark" -> "深色模式"
                        else -> "跟随系统"
                    },
                    onClick = { showDarkModeDialog = true }
                )
                // 动态取色仅 Android 12+ 支持，低版本不展示
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsItem(
                        icon = Icons.Default.Palette,
                        title = "动态取色",
                        subtitle = if (dynamicColor) "跟随壁纸取色" else "使用品牌粉色主题",
                        onClick = { scope.launch { app.settings.saveDynamicColor(!dynamicColor) } }
                    )
                }
            }

            SettingsSection(title = "账号") {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = "账号设置",
                    subtitle = "管理登录状态和Token",
                    onClick = onNavigateToAccountSettings
                )
            }

            SettingsSection(title = "网络") {
                SettingsItem(
                    icon = Icons.Default.Dns,
                    title = "API 地址",
                    subtitle = apiHosts.firstOrNull { it.first == apiHost }?.second ?: "api.bgm.tv（默认）",
                    onClick = { showApiHostDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = "Web 端地址",
                    subtitle = webHosts.firstOrNull { it.first == webHost }?.second ?: "bgm.tv（默认）",
                    onClick = { showWebHostDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.VpnLock,
                    title = "代理设置",
                    subtitle = "配置HTTP/SOCKS代理",
                    onClick = onNavigateToProxySettings
                )
            }

            SettingsSection(title = "存储") {
                SettingsItem(
                    icon = Icons.Default.DeleteSweep,
                    title = "缓存管理",
                    subtitle = "查看和清理缓存",
                    onClick = onNavigateToCacheSettings
                )
            }

            SettingsSection(title = "关于") {
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "GitHub",
                    subtitle = "github.com/yuwanyuan/bangumi-lite",
                    onClick = { openInBrowser(context, "https://github.com/yuwanyuan/bangumi-lite") }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    content()
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

/** 地址选择行：单选 + 连通性状态（绿通 / 红不通 / 灰测试中） */
@Composable
private fun HostOptionRow(
    label: String,
    selected: Boolean,
    pingState: Boolean?,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val (color, text) = when (pingState) {
                true -> Color(0xFF4CAF50) to "通"
                false -> Color(0xFFF44336) to "不通"
                null -> MaterialTheme.colorScheme.outline to "测试中"
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
