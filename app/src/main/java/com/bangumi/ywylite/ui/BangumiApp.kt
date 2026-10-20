package com.bangumi.ywylite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bangumi.ywylite.App
import com.bangumi.ywylite.BuildConfig
import com.bangumi.ywylite.data.UpdateChecker
import com.bangumi.ywylite.data.UpdateInfo
import com.bangumi.ywylite.ui.component.AppBackground
import com.bangumi.ywylite.ui.component.LocalWallpaperBlur
import com.bangumi.ywylite.ui.component.UpdateFlowHost
import com.bangumi.ywylite.ui.component.WallpaperBlurScope
import com.bangumi.ywylite.ui.component.wallpaperGlass
import com.bangumi.ywylite.ui.component.hazeWallpaperSource
import com.bangumi.ywylite.ui.component.rememberWallpaperHazeState
import com.bangumi.ywylite.ui.navigation.Route
import com.bangumi.ywylite.ui.screen.*
import com.bangumi.ywylite.ui.theme.LocalDarkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Route.Explore.path, "浏览", Icons.Default.Explore),
    BottomNavItem(Route.Collection.path, "收藏", Icons.Default.Star),
    BottomNavItem(Route.Profile.path, "我的", Icons.Default.Person),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BangumiApp() {
    val app = App.INSTANCE
    val fontScale by app.settings.fontScale.collectAsState(initial = 1f)
    // 全局字体缩放：在系统字号基础上乘以应用内倍率（sp 生效、dp 不动）。
    // 标签页要求字体不随设置缩放，其组合处用 baseDensity 恢复原始密度
    val systemDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(systemDensity.density, systemDensity.fontScale * fontScale)
    ) {
        BangumiAppContent(baseDensity = Density(systemDensity.density, systemDensity.fontScale))
    }
}

@Composable
private fun BangumiAppContent(baseDensity: Density) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val app = App.INSTANCE
    val token by app.settings.accessToken.collectAsState(initial = null)
    val ready by app.appReady.collectAsState()

    // 自动检查更新：配置就绪后查一次 GitHub。失败静默（只有手动检查才提示），
    // 命中的版本还要过「自动开关 / 跳过 / 24 小时静默期」三道门槛
    var pendingUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        // 让首页请求先走，避免启动瞬间和壁纸/列表抢网络
        delay(3_000)
        val update = try {
            // 整体限时：检查会在 API/镜像/网页兜底之间串行尝试，不能无限占着
            withTimeoutOrNull(UpdateChecker.CHECK_TIMEOUT_MILLIS) { app.updateChecker.checkLatest() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        if (app.updateChecker.shouldAutoPrompt(update)) pendingUpdate = update
    }

    // 主界面背景图设置（用户自选图；除详情页外所有页面透出）
    val bgMaskAlpha by app.settings.backgroundMaskAlpha.collectAsState(initial = 60)
    val bgBlurAmount by app.settings.backgroundBlurAmount.collectAsState(initial = 30)
    val bgCustomUri by app.settings.backgroundCustomUri.collectAsState(initial = null)

    val showBottomBar = bottomNavItems.any { it.route == currentDestination?.route }
    // 详情页自带封面背景，不透出主界面背景图
    val showAppBackground = currentDestination?.route != Route.SubjectDetail.path
    // Haze 共享状态：AppBackground 标为 source，底栏/设置类页面标为 child
    val hazeState = rememberWallpaperHazeState()
    // 壁纸模糊上下文：有壁纸且非详情页时启用，底栏与控件页面据此叠加毛玻璃
    val wallpaperBlur = WallpaperBlurScope(
        blurAmount = bgBlurAmount,
        hasWallpaper = bgCustomUri != null && showAppBackground,
        hazeState = hazeState
    )

    if (!ready) {
        // 配置恢复（token/域名/代理）完成前不渲染页面，避免带默认直连配置抢先发请求
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 基底主题色：各页 Scaffold 已改为透明以透出背景图，必须由这里兜底涂背景，
            // 否则深色模式下会漏出 Activity 窗口的浅色底（深色模式失效）
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (showAppBackground && bgCustomUri != null) {
            AppBackground(
                customUri = bgCustomUri,
                maskAlpha = bgMaskAlpha,
                modifier = Modifier.hazeWallpaperSource(wallpaperBlur)
            )
        }
    CompositionLocalProvider(LocalWallpaperBlur provides wallpaperBlur) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                // 有壁纸时底栏采样背后壁纸做毛玻璃；无壁纸时走默认 NavigationBar。
                // 罩色用主题实际深浅（含背景图强制的深色），不能用系统深浅
                val darkTheme = LocalDarkTheme.current
                NavigationBar(
                    containerColor = if (wallpaperBlur.hasWallpaper) Color.Transparent else NavigationBarDefaults.containerColor,
                    modifier = Modifier.then(wallpaperGlass(darkTheme))
                ) {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            // 默认打开页由设置决定（explore/collection）；appReady 放行前该值已恢复完成
            startDestination = if (app.defaultTab.value == "explore") {
                Route.Explore.path
            } else {
                Route.Collection.path
            },
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Route.Explore.path) {
                ExploreScreen(
                    onSubjectClick = { id ->
                        navController.navigate(Route.SubjectDetail.create(id))
                    },
                    onSearchClick = {
                        navController.navigate(Route.Search.path)
                    },
                    onNavigateToTags = { typePath ->
                        navController.navigate(Route.Tags.create(typePath))
                    },
                    onNavigateToCalendar = {
                        navController.navigate(Route.Calendar.path)
                    }
                )
            }
            composable(Route.Calendar.path) {
                CalendarScreen(
                    onSubjectClick = { id ->
                        navController.navigate(Route.SubjectDetail.create(id))
                    },
                    onSearchClick = {
                        navController.navigate(Route.Search.path)
                    }
                )
            }
            composable(Route.Search.path) {
                SearchScreen(
                    onSubjectClick = { id ->
                        navController.navigate(Route.SubjectDetail.create(id))
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Route.Collection.path) {
                CollectionScreen(
                    isLoggedIn = token != null,
                    onSubjectClick = { id ->
                        navController.navigate(Route.SubjectDetail.create(id))
                    },
                    onSearchClick = {
                        navController.navigate(Route.Search.path)
                    }
                )
            }
            composable(Route.Profile.path) {
                ProfileScreen(
                    onNavigateToSettings = {
                        navController.navigate(Route.Settings.path)
                    },
                    onSubjectClick = { id ->
                        navController.navigate(Route.SubjectDetail.create(id))
                    }
                )
            }
            composable(
                route = Route.SubjectDetail.path,
                arguments = listOf(navArgument("id") { type = NavType.IntType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getInt("id") ?: return@composable
                SubjectDetailScreen(
                    subjectId = id,
                    onBack = { navController.popBackStack() },
                    onTagClick = { tag, type ->
                        navController.navigate(Route.TagBrowse.create(tag, tag, type))
                    },
                    onSubjectClick = { sid ->
                        navController.navigate(Route.SubjectDetail.create(sid))
                    }
                )
            }
            composable(Route.Settings.path) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToAccountSettings = {
                        navController.navigate(Route.AccountSettings.path)
                    },
                    onNavigateToCacheSettings = {
                        navController.navigate(Route.CacheSettings.path)
                    },
                    onNavigateToProxySettings = {
                        navController.navigate(Route.ProxySettings.path)
                    }
                )
            }
            composable(Route.AccountSettings.path) {
                AccountSettingsScreen(
                    settings = app.settings,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Route.CacheSettings.path) {
                CacheSettingsScreen(
                    cacheManager = com.bangumi.ywylite.data.CacheManager(app),
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Route.ProxySettings.path) {
                ProxySettingsScreen(
                    settings = app.settings,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Route.Tags.path,
                arguments = listOf(navArgument("typePath") { type = NavType.StringType })
            ) { backStackEntry ->
                val typePath = backStackEntry.arguments?.getString("typePath") ?: "anime"
                // 标签页字体不随应用内缩放：恢复系统原始密度
                CompositionLocalProvider(LocalDensity provides baseDensity) {
                    TagsScreen(
                        typePath = typePath,
                        api = app.api,
                        onBack = { navController.popBackStack() },
                        onNavigateToTagBrowse = { tagName, tagSlug, type ->
                            navController.navigate(Route.TagBrowse.create(tagName, tagSlug, type))
                        }
                    )
                }
            }
            composable(
                route = Route.TagBrowse.path,
                arguments = listOf(
                    navArgument("tagName") { type = NavType.StringType },
                    navArgument("tagSlug") { type = NavType.StringType },
                    navArgument("type") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                // 取参时 Navigation 已经做过百分号解码（内部走 Uri.getQueryParameters），
                // 这里不能再解一次：含 '%' 的标签会抛 IllegalArgumentException 崩溃，
                // 含 '+' 的标签会被解成空格后请求到错误的标签
                val tagName = backStackEntry.arguments?.getString("tagName") ?: ""
                val tagSlug = backStackEntry.arguments?.getString("tagSlug") ?: ""
                val type = backStackEntry.arguments?.getInt("type") ?: 2
                // 标签浏览页与标签页同属标签流，同样豁免字体缩放
                CompositionLocalProvider(LocalDensity provides baseDensity) {
                    TagBrowseScreen(
                        tagName = tagName,
                        tagSlug = tagSlug,
                        type = type,
                        api = app.api,
                        onBack = { navController.popBackStack() },
                        onSubjectClick = { id ->
                            navController.navigate(Route.SubjectDetail.create(id))
                        }
                    )
                }
            }
        }
    }
    }

    // 自动检查发现新版本：点击即下载安装（直链失败自动换镜像）
    UpdateFlowHost(
        currentVersion = BuildConfig.VERSION_NAME,
        update = pendingUpdate,
        onDismiss = { pendingUpdate = null }
    )
    }
}
