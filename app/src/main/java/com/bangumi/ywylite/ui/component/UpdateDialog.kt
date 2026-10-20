package com.bangumi.ywylite.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.data.UpdateInfo

/**
 * 发现新版本弹窗：四个选项一次说清各自的后果。
 *
 * - 立即更新：打开 GitHub 发布页（系统浏览器）
 * - 稍后提醒：同一版本 24 小时内不再由自动检查弹出
 * - 跳过本次更新：记住该版本，自动检查永不再提
 * - 永不检查：直接关掉自动检查开关（手动检查仍可用）
 *
 * 点外部 / 返回键按「稍后提醒」处理：不落任何状态的话，下次启动会立刻再弹一次。
 */
@Composable
fun UpdateAvailableDialog(
    currentVersion: String,
    update: UpdateInfo,
    onUpdateNow: () -> Unit,
    onRemindLater: () -> Unit,
    onSkipVersion: () -> Unit,
    onNeverCheck: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onRemindLater,
        title = { Text("发现新版本") },
        text = {
            Column {
                Text("当前版本 $currentVersion，最新版本 ${update.versionName}")
                Spacer(modifier = Modifier.height(12.dp))
                UpdateOptionRow(
                    title = "立即更新",
                    subtitle = "打开 GitHub 发布页下载安装包",
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
        },
        confirmButton = {}
    )
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