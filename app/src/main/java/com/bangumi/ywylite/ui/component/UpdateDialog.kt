package com.bangumi.ywylite.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.data.UpdateInfo

/**
 * 发现新版本弹窗。
 *
 * 「立即更新」是点击即下载：直链失败自动换镜像，装好后直接唤起系统安装器，
 * 不再让用户去浏览器里自己找安装包。下载中显示进度并锁住其它选项
 * （避免关了弹窗留下一份无人接管的半截包）。
 *
 * 其余三个选项决定后续自动检查怎么对待这个版本：
 * - 稍后提醒：同一版本 24 小时内不再由自动检查弹出
 * - 跳过本次更新：记住该版本号，自动检查永不再提
 * - 永不检查：直接关掉自动检查开关（手动检查仍可用）
 */
@Composable
fun UpdateAvailableDialog(
    currentVersion: String,
    update: UpdateInfo,
    downloadState: DownloadState,
    onUpdateNow: () -> Unit,
    onDismissDownload: () -> Unit,
    onRemindLater: () -> Unit,
    onSkipVersion: () -> Unit,
    onNeverCheck: () -> Unit
) {
    val downloading = downloadState is DownloadState.Downloading
    AlertDialog(
        // 下载中不允许点外部关闭：进度条和取消按钮都已给出，误关只会留下疑问
        onDismissRequest = { if (!downloading) onRemindLater() },
        title = { Text(if (downloadState is DownloadState.Failed) "下载失败" else "发现新版本") },
        text = {
            Column {
                Text("当前版本 $currentVersion，最新版本 ${update.versionName}")
                Spacer(modifier = Modifier.height(12.dp))

                when (downloadState) {
                    is DownloadState.Downloading -> {
                        Text(
                            text = if (downloadState.percent >= 0) "正在下载 ${downloadState.percent}%" else "正在连接下载…",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // 长度未知时用不确定进度条，避免卡在 0% 像是没动静
                        if (downloadState.percent >= 0) {
                            LinearProgressIndicator(
                                progress = { downloadState.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        UpdateOptionRow(
                            title = "取消下载",
                            subtitle = "未完成的文件会被删除",
                            onClick = onDismissDownload
                        )
                    }

                    is DownloadState.Failed -> {
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        UpdateOptionRow(
                            title = "重试",
                            subtitle = "重新从 GitHub 下载（失败会自动换镜像）",
                            onClick = onUpdateNow,
                            emphasis = true
                        )
                        UpdateOptionRow(
                            title = "稍后提醒",
                            subtitle = "同一版本 24 小时内不再提醒",
                            onClick = onRemindLater
                        )
                        UpdateOptionRow(
                            title = "跳过本次更新",
                            subtitle = "不再提醒版本 ${update.versionName}",
                            onClick = onSkipVersion
                        )
                    }

                    else -> {
                        UpdateOptionRow(
                            title = "立即更新",
                            subtitle = "开始下载并安装（GitHub 直链，失败自动换镜像）",
                            onClick = onUpdateNow,
                            emphasis = true
                        )
                        UpdateOptionRow(
                            title = "稍后提醒",
                            subtitle = "同一版本 24 小时内不再提醒",
                            onClick = onRemindLater
                        )
                        UpdateOptionRow(
                            title = "跳过本次更新",
                            subtitle = "不再提醒版本 ${update.versionName}",
                            onClick = onSkipVersion
                        )
                        UpdateOptionRow(
                            title = "永不检查",
                            subtitle = "关闭自动检查更新，仍可在设置里手动检查",
                            onClick = onNeverCheck
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/** 更新包下载状态（弹窗内展示用） */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val percent: Int) : DownloadState
    data class Failed(val message: String) : DownloadState
}

@Composable
private fun UpdateOptionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    emphasis: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}