package com.bangumi.ywylite

import android.app.Application
import com.bangumi.ywylite.data.Settings
import com.bangumi.ywylite.data.api.BangumiApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application() {

    val api: BangumiApi by lazy { BangumiApi() }
    val settings: Settings by lazy { Settings(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        // access_token 失效（401）时先尝试用 refresh_token 续期，失败才清登录态
        api.onTokenInvalid = {
            appScope.launch {
                val refresh = settings.refreshToken.first()
                val newToken = if (refresh.isNotBlank()) {
                    runCatching { api.refreshAccessToken(refresh) }.getOrNull()
                } else null
                if (newToken != null && newToken.accessToken.isNotBlank()) {
                    settings.saveToken(newToken.accessToken)
                    settings.saveRefreshToken(newToken.refreshToken.ifBlank { refresh })
                    settings.saveTokenExpiresAt(System.currentTimeMillis() + newToken.expiresIn * 1000)
                    api.updateToken(newToken.accessToken)
                } else {
                    settings.clearToken()
                    api.updateToken(null)
                }
            }
        }
        appScope.launch {
            val token = settings.accessToken.first()
            if (token != null) {
                api.updateToken(token)
                // token 临期（不足 3 天）或已过期时启动即静默续期
                val refresh = settings.refreshToken.first()
                val expiresAt = settings.tokenExpiresAt.first()
                if (refresh.isNotBlank() && expiresAt in 1 until System.currentTimeMillis() + 3 * 24 * 3600 * 1000L) {
                    val newToken = runCatching { api.refreshAccessToken(refresh) }.getOrNull()
                    if (newToken != null && newToken.accessToken.isNotBlank()) {
                        settings.saveToken(newToken.accessToken)
                        settings.saveRefreshToken(newToken.refreshToken.ifBlank { refresh })
                        settings.saveTokenExpiresAt(System.currentTimeMillis() + newToken.expiresIn * 1000)
                        api.updateToken(newToken.accessToken)
                    }
                }
            }
            // 域名与代理配置只在设置页保存时生效过，重启后必须恢复，否则会一直走默认直连
            api.updateApiHost(settings.apiHost.first(), settings.webHost.first())
            val proxy = settings.proxySettings.first()
            if (proxy.enabled) {
                api.updateProxy(proxy.enabled, proxy.type, proxy.host, proxy.port, proxy.username, proxy.password)
            }
        }
    }

    companion object {
        lateinit var INSTANCE: App
            private set
    }
}
