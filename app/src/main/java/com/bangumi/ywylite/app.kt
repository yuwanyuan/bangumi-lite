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
        api.onTokenInvalid = {
            appScope.launch {
                settings.clearToken()
                api.updateToken(null)
            }
        }
        appScope.launch {
            val token = settings.accessToken.first()
            if (token != null) {
                api.updateToken(token)
            }
        }
    }

    companion object {
        lateinit var INSTANCE: App
            private set
    }
}
