package com.bangumi.ywylite.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
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
    val darkMode by app.settings.darkMode.collectAsState(initial = "system")
    var showDarkModeDialog by remember { mutableStateOf(false) }

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
