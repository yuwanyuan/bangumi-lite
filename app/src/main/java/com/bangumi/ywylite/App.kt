package com.bangumi.ywylite

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.bangumi.ywylite.data.Settings
import com.bangumi.ywylite.data.api.BangumiApi
import com.bangumi.ywylite.data.api.ImageNetworkProxy
import com.bangumi.ywylite.data.api.OAuthTokenRejectedException
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress

class App : Application(), ImageLoaderFactory {

    val api: BangumiApi by lazy { BangumiApi(this) }
    val settings: Settings by lazy { Settings(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 启动门闸：域名/代理等配置从 DataStore 恢复完成前为 false，
     * UI 层等待它为 true 才渲染 NavHost，避免首页带着默认直连配置抢先发请求。
     */
    val appReady = MutableStateFlow(false)

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
                // token 临期（不足 3 天）或已过期时启动即静默续期。
                // 限时 8s：弱网下续期卡住不拖慢 appReady，失败留待 401 时再续
                val expiresAt = settings.tokenExpiresAt.first()
                if (!api.refreshToken.isNullOrBlank() && expiresAt in 1 until System.currentTimeMillis() + 3 * 24 * 3600 * 1000L) {
                    withTimeoutOrNull(8_000) { refreshTokenIfNeeded(token) }
                }
            }
            // 域名与代理配置只在设置页保存时生效过，重启后必须恢复，否则会一直走默认直连
            api.updateApiHost(settings.apiHost.first(), settings.webHost.first())
            val proxy = settings.proxySettings.first()
            if (proxy.enabled) {
                api.updateProxy(proxy.enabled, proxy.type, proxy.host, proxy.port, proxy.username, proxy.password)
            }
            appReady.value = true
        }
    }

    /**
     * Coil 全局 ImageLoader：封面等图片域名可能同样需要代理，
     * 用动态 ProxySelector 跟随 ImageNetworkProxy（与 API 客户端同步刷新）。
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            // 封面一次并发十几张，默认每主机 5 并发会让队列排很久
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 12 })
            // 与 API 客户端同一 UA：lain.bgm.tv 对 okhttp 默认 UA 可能区别对待
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("User-Agent") != null) {
                    chain.proceed(request)
                } else {
                    chain.proceed(request.newBuilder().header("User-Agent", BangumiApi.USER_AGENT).build())
                }
            }
            .proxySelector(object : ProxySelector() {
                override fun select(uri: URI?): List<java.net.Proxy> =
                    ImageNetworkProxy.proxy?.let { listOf(it) } ?: listOf(java.net.Proxy.NO_PROXY)

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
            })
            .proxyAuthenticator { route, response ->
                // 返回 null 表示不提供代理认证
                ImageNetworkProxy.authenticator?.authenticate(route, response)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
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
            clearLoginState()
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
        } catch (e: OAuthTokenRejectedException) {
            // 服务端明确拒绝（invalid_grant 等）：登录态不可恢复，清除并同步网页会话
            clearLoginState()
        } catch (e: ClientRequestException) {
            // refresh_token 本身已失效（400/401）才清除登录态；网络异常保留，下次再试
            if (e.response.status.value == 400 || e.response.status.value == 401) {
                clearLoginState()
            }
        } catch (_: Exception) {
        }
    }

    /** 清空本地登录态（DataStore + 内存 token + 网页会话 Cookie） */
    private suspend fun clearLoginState() {
        settings.clearToken()
        api.updateToken(null)
        api.refreshToken = null
        api.clearWebSession()
    }

    /**
     * 401 统一恢复入口：尝试用 refresh_token 续期。
     * @return true 表示已恢复（api.accessToken 可用），false 表示登录态已失效被清除。
     * 屏幕层捕获 401 后调用本方法，true 则重试原请求，false 则回到未登录态。
     */
    suspend fun recoverFromUnauthorized(failedToken: String?): Boolean {
        refreshTokenIfNeeded(failedToken)
        return !api.accessToken.isNullOrBlank()
    }

    companion object {
        lateinit var INSTANCE: App
            private set
    }
}
