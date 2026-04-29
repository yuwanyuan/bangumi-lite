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
import com.bangumi.ywylite.data.CacheCleanResult
import com.bangumi.ywylite.data.CacheInfo
import com.bangumi.ywylite.data.CacheManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    cacheManager: CacheManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var cacheInfo by remember { mutableStateOf(CacheInfo()) }
    var isCleaning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        cacheInfo = cacheManager.getCacheInfo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
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
            Text("缓存信息", style = MaterialTheme.typography.titleMedium)

            CacheDetailRow("总缓存", formatSize(cacheInfo.totalSize))
            CacheDetailRow("图片缓存", formatSize(cacheInfo.imageCacheSize))
            CacheDetailRow("网络缓存", formatSize(cacheInfo.networkCacheSize))

            if (isCleaning) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }

            if (result.isNotEmpty()) {
                Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = {
                    scope.launch {
                        isCleaning = true
                        progress = 0f
                        val cleanResult = cacheManager.cleanAllCache { p -> progress = p }
                        result = "已清理 ${formatSize(cleanResult.freedSize)}"
                        cacheInfo = cacheManager.getCacheInfo()
                        isCleaning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCleaning
            ) {
                Text("清理全部缓存")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        isCleaning = true
                        progress = 0f
                        val cleanResult = cacheManager.cleanImageCache { p -> progress = p }
                        result = "已清理 ${formatSize(cleanResult.freedSize)}"
                        cacheInfo = cacheManager.getCacheInfo()
                        isCleaning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCleaning
            ) {
                Text("清理图片缓存")
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        isCleaning = true
                        progress = 0f
                        val cleanResult = cacheManager.cleanNetworkCache { p -> progress = p }
                        result = "已清理 ${formatSize(cleanResult.freedSize)}"
                        cacheInfo = cacheManager.getCacheInfo()
                        isCleaning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isCleaning
            ) {
                Text("清理网络缓存")
            }
        }
    }
}

@Composable
private fun CacheDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups.coerceAtMost(3)])
}
