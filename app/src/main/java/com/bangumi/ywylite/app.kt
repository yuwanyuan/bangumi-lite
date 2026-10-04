package com.bangumi.ywylite

import android.app.Application
import com.bangumi.ywylite.data.Settings
import com.bangumi.ywylite.data.api.BangumiApi
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class App : Application() {

    val api: BangumiApi by lazy { BangumiApi() }
    val settings: Settings by lazy { Settings(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 续期必须串行：refresh_token 每次刷新都会轮换，并发续期会让后到的请求用到已作废的旧值
    private val refreshMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        // access_token 失效（401）时先尝试用 refresh_token 续期，失败才清登录态
        api.onTokenInvalid = { failedToken ->
            appScope.launch { refreshTokenIfNeeded(failedToken) }
        }
        // 刷新成功后持久化新 token（refresh_token 会轮换）
        api.onTokensRefreshed = { t ->
            appScope.launch {
                settings.saveToken(t.accessToken)
                if (t.refreshToken.isNotBlank()) settings.saveRefreshToken(t.refreshToken)
                if (t.expiresIn > 0) settings.saveTokenExpiresAt(System.currentTimeMillis() + t.expiresIn * 1000)
            }
        }
        appScope.launch {
            val token = settings.accessToken.first()
            if (token != null) {
                api.updateToken(token)
                api.refreshToken = settings.refreshToken.first()
                // token 临期（不足 3 天）或已过期时启动即静默续期
                val expiresAt = settings.tokenExpiresAt.first()
                if (!api.refreshToken.isNullOrBlank() && expiresAt in 1 until System.currentTimeMillis() + 3 * 24 * 3600 * 1000L) {
                    refreshTokenIfNeeded(token)
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

    /**
     * 用 refresh_token 续期。
     * @param failedToken 触发 401 的请求所携带的 token；为 null 表示主动续期（启动临期检查）。
     * 若等锁期间其它请求已完成续期，直接沿用新 token，不再重复刷新。
     */
    private suspend fun refreshTokenIfNeeded(failedToken: String?) = refreshMutex.withLock {
        val stored = settings.accessToken.first()
        if (failedToken != null && stored != null && stored != failedToken) return@withLock
        val refresh = settings.refreshToken.first()
        if (refresh.isBlank()) {
            // 手动 Token 没有续期能力，401 即失效
            settings.clearToken()
            api.updateToken(null)
            api.refreshToken = null
            return@withLock
        }
        try {
            val newToken = api.refreshAccessToken(refresh)
            if (newToken.accessToken.isNotBlank()) {
                settings.saveToken(newToken.accessToken)
                settings.saveRefreshToken(newToken.refreshToken.ifBlank { refresh })
                settings.saveTokenExpiresAt(System.currentTimeMillis() + newToken.expiresIn * 1000)
                api.updateToken(newToken.accessToken)
            }
        } catch (e: ClientRequestException) {
            // refresh_token 本身已失效（400/401）才清除登录态；网络异常保留，下次再试
            if (e.response.status.value == 400 || e.response.status.value == 401) {
                settings.clearToken()
                api.updateToken(null)
                api.refreshToken = null
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        lateinit var INSTANCE: App
            private set
    }
}
