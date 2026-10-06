package com.bangumi.ywylite.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bangumi.ywylite.App
import com.bangumi.ywylite.ui.theme.BangumiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = App.INSTANCE
            val darkMode by app.settings.darkMode.collectAsState(initial = "system")
            val dynamicColor by app.settings.dynamicColor.collectAsState(initial = false)
            val darkTheme = when (darkMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            BangumiTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                BangumiApp()
            }
        }
    }
}
