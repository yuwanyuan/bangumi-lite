package com.bangumi.ywylite.ui.component

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 更新弹窗 + 下载流程的宿主：主界面（自动检查）与设置页（手动检查）共用。
 *
 * 下载绑定在本组合的协程作用域上：弹窗是模态的，作用域只会在弹窗关闭时结束，
 * 此时 [UpdateInstaller] 会删掉未完成的 `.part`，不留垃圾。
 */
@Composable
fun UpdateFlowHost(
    currentVersion: String,
    update: UpdateInfo?,
    onDismiss: () -> Unit
) {
    if (update == null) return

    val app = App.INSTANCE
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = app.updateInstaller
    var state by remember(update.versionName) { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var job by remember(update.versionName) { mutableStateOf<Job?>(null) }

    fun startDownload() {
        if (job?.isActive == true) return
        job = scope.launch {
            state = DownloadState.Downloading(percent = -1)
            try {
                val apk = installer.download(update) { percent ->
                    state = DownloadState.Downloading(maxOf(percent, 0))
                }
                state = DownloadState.Idle
                // 没有「安装未知应用」权限时会跳系统设置页，此时保留弹窗让用户再点一次
                if (!installer.install(apk)) {
                    Toast.makeText(context, "请允许安装未知应用后再点一次「立即更新」", Toast.LENGTH_LONG).show()
                } else {
                    onDismiss()
                }
            } catch (e: CancellationException) {
                state = DownloadState.Idle
                throw e
            } catch (_: Exception) {
                // 失败已在 installer 内删掉残包，这里只负责告诉用户
                state = DownloadState.Failed("下载失败，请检查网络后重试；未完成的文件已删除。")
            }
        }
    }

    UpdateAvailableDialog(
        currentVersion = currentVersion,
        update = update,
        downloadState = state,
        onUpdateNow = {
            if (state is DownloadState.Failed) state = DownloadState.Idle
            startDownload()
        },
        onDismissDownload = {
            job?.cancel()
            job = null
            installer.discardPartial()
            state = DownloadState.Idle
            onDismiss()
        },
        onRemindLater = {
            job?.cancel()
            scope.launch { app.updateChecker.snooze(update) }
            onDismiss()
        },
        onSkipVersion = {
            job?.cancel()
            scope.launch { app.updateChecker.skip(update) }
            onDismiss()
        },
        onNeverCheck = {
            job?.cancel()
            scope.launch { app.updateChecker.setAutoCheckEnabled(false) }
            onDismiss()
        }
    )
}