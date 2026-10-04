package com.bangumi.ywylite.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bangumi_settings")

/** 代理配置快照，供启动时一次性恢复 */
data class ProxySettings(
    val enabled: Boolean = false,
    val type: String = "HTTP",
    val host: String = "",
    val port: Int = 7890,
    val username: String = "",
    val password: String = ""
)

class Settings(private val context: Context) {

    val accessToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[ACCESS_TOKEN_KEY]
    }

    val username: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[USERNAME_KEY]
    }

    val proxyEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PROXY_ENABLED_KEY] ?: false
    }

    val proxyType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PROXY_TYPE_KEY] ?: "HTTP"
    }

    val proxyHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PROXY_HOST_KEY] ?: ""
    }

    val proxyPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[PROXY_PORT_KEY] ?: 7890
    }

    val proxyUsername: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PROXY_USERNAME_KEY] ?: ""
    }

    val proxyPassword: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PROXY_PASSWORD_KEY] ?: ""
    }

    /** API 域名（空白 = 默认 https://api.bgm.tv） */
    val apiHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[API_HOST_KEY] ?: ""
    }

    /** Web 端域名（空白 = 默认 https://bgm.tv） */
    val webHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[WEB_HOST_KEY] ?: ""
    }

    /** 直接登录获取的 refresh_token，用于 access_token 过期后续期 */
    val refreshToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[REFRESH_TOKEN_KEY] ?: ""
    }

    /** access_token 过期时间（epoch ms，0 = 未知） */
    val tokenExpiresAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[TOKEN_EXPIRES_AT_KEY] ?: 0L
    }

    val proxySettings: Flow<ProxySettings> = context.dataStore.data.map { prefs ->
        ProxySettings(
            enabled = prefs[PROXY_ENABLED_KEY] ?: false,
            type = prefs[PROXY_TYPE_KEY] ?: "HTTP",
            host = prefs[PROXY_HOST_KEY] ?: "",
            port = prefs[PROXY_PORT_KEY] ?: 7890,
            username = prefs[PROXY_USERNAME_KEY] ?: "",
            password = prefs[PROXY_PASSWORD_KEY] ?: ""
        )
    }

    val darkMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DARK_MODE_KEY] ?: "system"
    }

    suspend fun saveToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN_KEY] = token
        }
    }

    suspend fun saveUsername(name: String) {
        context.dataStore.edit { prefs ->
            prefs[USERNAME_KEY] = name
        }
    }

    suspend fun clearToken() {
        context.dataStore.edit { prefs ->
            prefs.remove(ACCESS_TOKEN_KEY)
            prefs.remove(USERNAME_KEY)
            prefs.remove(REFRESH_TOKEN_KEY)
            prefs.remove(TOKEN_EXPIRES_AT_KEY)
        }
    }

    suspend fun saveProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PROXY_ENABLED_KEY] = enabled
        }
    }

    suspend fun saveProxyType(type: String) {
        context.dataStore.edit { prefs ->
            prefs[PROXY_TYPE_KEY] = type
        }
    }

    suspend fun saveProxyHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[PROXY_HOST_KEY] = host
        }
    }

    suspend fun saveProxyPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[PROXY_PORT_KEY] = port
        }
    }

    suspend fun saveProxyUsername(username: String) {
        context.dataStore.edit { prefs ->
            prefs[PROXY_USERNAME_KEY] = username
        }
    }

    suspend fun saveProxyPassword(password: String) {
        context.dataStore.edit { prefs ->
            prefs[PROXY_PASSWORD_KEY] = password
        }
    }

    suspend fun saveApiHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[API_HOST_KEY] = host
        }
    }

    suspend fun saveWebHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[WEB_HOST_KEY] = host
        }
    }

    suspend fun saveRefreshToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[REFRESH_TOKEN_KEY] = token
        }
    }

    suspend fun saveTokenExpiresAt(expiresAt: Long) {
        context.dataStore.edit { prefs ->
            prefs[TOKEN_EXPIRES_AT_KEY] = expiresAt
        }
    }

    suspend fun saveDarkMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[DARK_MODE_KEY] = mode
        }
    }

    companion object {
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("access_token")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val PROXY_ENABLED_KEY = booleanPreferencesKey("proxy_enabled")
        private val PROXY_TYPE_KEY = stringPreferencesKey("proxy_type")
        private val PROXY_HOST_KEY = stringPreferencesKey("proxy_host")
        private val PROXY_PORT_KEY = intPreferencesKey("proxy_port")
    private val PROXY_USERNAME_KEY = stringPreferencesKey("proxy_username")
    private val PROXY_PASSWORD_KEY = stringPreferencesKey("proxy_password")
    private val API_HOST_KEY = stringPreferencesKey("api_host")
    private val WEB_HOST_KEY = stringPreferencesKey("web_host")
    private val REFRESH_TOKEN_KEY = stringPreferencesKey("refresh_token")
    private val TOKEN_EXPIRES_AT_KEY = longPreferencesKey("token_expires_at")
    private val DARK_MODE_KEY = stringPreferencesKey("dark_mode")
    }
}
