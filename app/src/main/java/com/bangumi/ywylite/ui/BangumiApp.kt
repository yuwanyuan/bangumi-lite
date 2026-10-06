package com.bangumi.ywylite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bangumi.ywylite.App
import com.bangumi.ywylite.ui.navigation.Route
import com.bangumi.ywylite.ui.screen.*

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
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val app = App.INSTANCE
    val token by app.settings.accessToken.collectAsState(initial = null)
    val ready by app.appReady.collectAsState()

    val showBottomBar = bottomNavItems.any { it.route == currentDestination?.route }

    if (!ready) {
        // 配置恢复（token/域名/代理）完成前不渲染页面，避免带默认直连配置抢先发请求
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
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
            startDestination = Route.Collection.path,
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
                TagsScreen(
                    typePath = typePath,
                    api = app.api,
                    onBack = { navController.popBackStack() },
                    onNavigateToTagBrowse = { tagName, tagSlug, type ->
                        navController.navigate(Route.TagBrowse.create(tagName, tagSlug, type))
                    }
                )
            }
            composable(
                route = Route.TagBrowse.path,
                arguments = listOf(
                    navArgument("tagName") { type = NavType.StringType },
                    navArgument("tagSlug") { type = NavType.StringType },
                    navArgument("type") { type = NavType.IntType }
                )
            ) { backStackEntry ->
                val tagName = backStackEntry.arguments?.getString("tagName")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } ?: ""
                val tagSlug = backStackEntry.arguments?.getString("tagSlug")?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } ?: ""
                val type = backStackEntry.arguments?.getInt("type") ?: 2
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
