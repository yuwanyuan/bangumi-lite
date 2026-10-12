package com.bangumi.ywylite.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bangumi.ywylite.App
import com.bangumi.ywylite.ui.theme.BangumiTheme
import com.bangumi.ywylite.ui.theme.extractWallpaperPalette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = App.INSTANCE
            val context = LocalContext.current
            val darkMode by app.settings.darkMode.collectAsState(initial = "system")
            val dynamicColor by app.settings.dynamicColor.collectAsState(initial = false)
            val wallpaperUri by app.settings.backgroundCustomUri.collectAsState(initial = null)
            val darkTheme = when (darkMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            // 壁纸取色：有背景图时按它派生整套配色，界面与文字随壁纸变化保持清晰；
            // 没有背景图则保持原样（品牌粉 / 系统动态取色）
            var wallpaperSeed by remember { mutableStateOf<Color?>(null) }
            LaunchedEffect(wallpaperUri) {
                wallpaperSeed = wallpaperUri
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    ?.let { extractWallpaperPalette(context, it)?.seed }
            }

            BangumiTheme(
                darkTheme = darkTheme,
                dynamicColor = dynamicColor,
                wallpaperSeed = wallpaperSeed
            ) {
                BangumiApp()
            }
        }
    }
}
