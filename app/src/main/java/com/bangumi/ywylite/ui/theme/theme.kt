package com.bangumi.ywylite.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Pink = androidx.compose.ui.graphics.Color(0xFFE91E63)
private val PinkDark = androidx.compose.ui.graphics.Color(0xFFF48FB1)
private val PinkLight = androidx.compose.ui.graphics.Color(0xFFAD1457)

private val LightColors = lightColorScheme(
    primary = Pink,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFFCE4EC),
    onPrimaryContainer = PinkLight,
    secondary = androidx.compose.ui.graphics.Color(0xFF7B1FA2),
    onSecondary = androidx.compose.ui.graphics.Color.White,
)

private val DarkColors = darkColorScheme(
    primary = PinkDark,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    primaryContainer = PinkLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFCE4EC),
    secondary = androidx.compose.ui.graphics.Color(0xFFCE93D8),
    onSecondary = androidx.compose.ui.graphics.Color.Black,
)

@Composable
fun BangumiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // 动态取色默认关闭：原先 Android 12+ 强制壁纸取色，品牌粉色主题被完全覆盖；
    // 改为设置页开关，显式开启才生效（12 以下无此能力）
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // statusBarColor 已废弃（API 35）：enableEdgeToEdge 下系统按深浅色自动处理状态栏
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
