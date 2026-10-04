package com.bangumi.ywylite.ui.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.bangumi.ywylite.App
import com.bangumi.ywylite.data.model.User
import kotlinx.coroutines.launch

/**
 * 登录面板：Token 登录 / 直接登录 双模式。
 * - Token 登录：粘贴用户在官网生成的 Access Token（7 天有效，需手动更换）
 * - 直接登录：邮箱 + 密码 + 验证码走网页会话 → OAuth 授权码换 token，
 *   保存 refresh_token 后由应用每 7 天自动续期，长期免登录
 */
@Composable
fun BangumiLoginPanel(onLoginSuccess: (User) -> Unit) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(0) }
    var tokenInput by remember { mutableStateOf(TextFieldValue("")) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var captchaImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var captchaKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mode, captchaKey) {
        if (mode == 1) {
            captchaImage = null
            runCatching { app.api.getWebLoginCaptcha() }
                .onSuccess { bytes ->
                    captchaImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                .onFailure { error = "验证码加载失败，点击图片处重试" }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Token 登录") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("直接登录") })
        }

        if (mode == 0) {
            OutlinedButton(
                onClick = { openInBrowser(context, "https://next.bgm.tv/demo/access-token") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("前往 Bangumi 获取 Access Token")
            }
            Text(
                "在浏览器中生成 Token 后复制粘贴到下方；此方式登录最多可保持 365 天，过期后需重新生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Access Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        } else {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .height(72.dp)
                        .clickable { captchaKey++ },
                    contentAlignment = Alignment.Center
                ) {
                    val img = captchaImage
                    if (img != null) {
                        Image(
                            bitmap = img,
                            contentDescription = "验证码，点击刷新",
                            modifier = Modifier
                                .height(68.dp)
                                .fillMaxWidth(0.6f)
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
                Column {
                    Text(
                        "看不清？点击图片刷新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedTextField(
                value = captcha,
                onValueChange = { captcha = it },
                label = { Text("验证码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "登录一次保持 7 天，之后自动续期，无需重复登录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                scope.launch {
                    loading = true
                    error = null
                    try {
                        val user = if (mode == 0) {
                            val t = tokenInput.text.trim()
                            if (t.isBlank()) throw IllegalStateException("请先填写 Token")
                            app.api.updateToken(t)
                            app.settings.saveToken(t)
                            // 手动 Token 不带 refresh_token，清掉旧值避免误续期
                            app.settings.saveRefreshToken("")
                            app.settings.saveTokenExpiresAt(0L)
                            app.api.getMe()
                        } else {
                            val t = app.api.loginWithWebAccount(email.trim(), password, captcha.trim())
                            app.api.updateToken(t.accessToken)
                            app.settings.saveToken(t.accessToken)
                            app.settings.saveRefreshToken(t.refreshToken)
                            app.settings.saveTokenExpiresAt(System.currentTimeMillis() + t.expiresIn * 1000)
                            app.api.getMe()
                        }
                        app.settings.saveUsername(user.username)
                        onLoginSuccess(user)
                    } catch (e: Exception) {
                        app.api.updateToken(null)
                        error = "登录失败：${e.message ?: "未知错误"}"
                    }
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && when (mode) {
                0 -> tokenInput.text.isNotBlank()
                else -> email.isNotBlank() && password.isNotBlank() && captcha.isNotBlank()
            }
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("登录")
            }
        }
    }
}
