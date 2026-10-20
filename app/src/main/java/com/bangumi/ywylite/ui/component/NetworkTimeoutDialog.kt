package com.bangumi.ywylite.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 加载迟迟没有结果时的网络提示。
 *
 * 连不上时 OkHttp 的默认连接/读取超时较长，界面会一直停在转圈或旧列表上，
 * 用户看不出是网络问题。这里以 [timeoutMillis] 为界：超过仍没有数据就弹窗提示，
 * 正常结束（loading=false）会取消计时。
 *
 * 只负责“提示”，不改变请求本身的重试策略；页面自己的错误态仍优先展示。
 */
@Composable
fun NetworkTimeoutDialog(
    loading: Boolean,
    timeoutMillis: Long = 10_000L
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(loading) {
        if (loading) {
            delay(timeoutMillis)
            visible = true
        } else {
            visible = false
        }
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("网络连接异常") },
        text = { Text("超过 ${timeoutMillis / 1000} 秒没有收到数据，请检查网络连接后重试。") },
        confirmButton = {
            TextButton(onClick = { visible = false }) { Text("知道了") }
        }
    )
}