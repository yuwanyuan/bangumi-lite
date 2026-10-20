package com.bangumi.ywylite.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import com.bangumi.ywylite.App
import com.bangumi.ywylite.ui.component.openInBrowser
import com.bangumi.ywylite.ui.component.wallpaperGlassPanel
import com.bangumi.ywylite.ui.theme.LocalDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 字体大小档位：系统字号基础上的乘率 */
private val fontScaleOptions = listOf(
    0.85f to "小",
    1.0f to "标准",
    1.2f to "大",
    1.4f to "特大"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToAccountSettings: () -> Unit,
    onNavigateToCacheSettings: () -> Unit,
    onNavigateToProxySettings: () -> Unit
) {
    val app = App.INSTANCE
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val darkMode by app.settings.darkMode.collectAsState(initial = "system")
    var showDarkModeDialog by remember { mutableStateOf(false) }

    // 默认打开页与收藏页条目样式
    val defaultTab by app.settings.defaultTab.collectAsState(initial = "collection")
    val collectionWaterfall by app.settings.collectionWaterfall.collectAsState(initial = false)
    var showDefaultTabDialog by remember { mutableStateOf(false) }
    var showCollectionStyleDialog by remember { mutableStateOf(false) }

    // 字体大小（标签页豁免，不随缩放）
    val fontScale by app.settings.fontScale.collectAsState(initial = 1f)
    var showFontSizeDialog by remember { mutableStateOf(false) }

    // 检查更新：自动开关 + 手动检查（手动才展示当前/最新版本与失败提示）
    val autoCheckUpdate by app.settings.autoCheckUpdate.collectAsState(initial = true)
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var manualUpdate by remember { mutableStateOf<com.bangumi.ywylite.data.UpdateInfo?>(null) }
    val currentVersion = com.bangumi.ywylite.BuildConfig.VERSION_NAME

    // 主界面背景图：用户自选图片 + 蒙版透明度/模糊度（各为三级档位）
    val bgMaskAlpha by app.settings.backgroundMaskAlpha.collectAsState(initial = 60)
    val bgBlurAmount by app.settings.backgroundBlurAmount.collectAsState(initial = 30)
    val bgCustomUri by app.settings.backgroundCustomUri.collectAsState(initial = null)
    var bgMaskAlphaDrag by remember { mutableStateOf<Float?>(null) }
    var bgBlurDrag by remember { mutableStateOf<Float?>(null) }
    var showBgImageDialog by remember { mutableStateOf(false) }
    // 自选图：取持久化 URI 权限，避免重启后无法读取
    val pickCustomImage = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 权限没拿到就不能长期保存：图片本次能显示、重启后必然读不到。
            // 这种「过一会儿自己消失」要当场说清楚，不能静默存下 URI
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.isSuccess
            if (!granted) {
                Toast.makeText(context, "系统未授予长期读取权限，这张图重启后可能失效", Toast.LENGTH_LONG).show()
            }
            scope.launch { app.settings.saveBackgroundCustomUri(uri.toString()) }
        }
    }

    // 官方 API / Web 端域名切换
    val apiHosts = listOf(
        "https://api.bgm.tv" to "api.bgm.tv（默认）",
        "https://api.bgmapi.com" to "api.bgmapi.com（备用）"
    )
    val webHosts = listOf(
        "https://bgm.tv" to "bgm.tv（默认）",
        "https://bangumi.tv" to "bangumi.tv（备用）",
        "https://chii.in" to "chii.in（备用）"
    )
    val apiHost by app.settings.apiHost.collectAsState(initial = "")
    val webHost by app.settings.webHost.collectAsState(initial = "")
    var showApiHostDialog by remember { mutableStateOf(false) }
    var showWebHostDialog by remember { mutableStateOf(false) }
    // 地址连通性测试结果：null 测试中 / true 通（绿）/ false 不通（红）
    var pingResults by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }

    LaunchedEffect(showApiHostDialog, showWebHostDialog) {
        val targets = buildList {
            if (showApiHostDialog) addAll(apiHosts.map { it.first })
            if (showWebHostDialog) addAll(webHosts.map { it.first })
        }
        if (targets.isEmpty()) return@LaunchedEffect
        pingResults = targets.associateWith { null }
        targets.forEach { url ->
            // 用 effect 自己的作用域：关掉弹窗即取消未完成的探活，
            // 结果不会串进下一次打开弹窗的状态（原先挂在整页 scope 上，弹窗关了还在跑）
            launch {
                val ok = runCatching { app.api.pingHost(url) }.getOrDefault(false)
                pingResults = pingResults + (url to ok)
            }
        }
    }

    if (showDefaultTabDialog) {
        AlertDialog(
            onDismissRequest = { showDefaultTabDialog = false },
            title = { Text("默认打开") },
            text = {
                Column {
                    listOf("collection" to "收藏", "explore" to "浏览").forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settings.saveDefaultTab(value) }
                                    showDefaultTabDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = defaultTab == value,
                                onClick = {
                                    scope.launch { app.settings.saveDefaultTab(value) }
                                    showDefaultTabDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDefaultTabDialog = false }) { Text("取消") }
            }
        )
    }

    if (showCollectionStyleDialog) {
        AlertDialog(
            onDismissRequest = { showCollectionStyleDialog = false },
            title = { Text("收藏页样式") },
            text = {
                Column {
                    listOf(false to "列表（经典）", true to "瀑布流（与浏览页一致）").forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settings.saveCollectionWaterfall(value) }
                                    showCollectionStyleDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = collectionWaterfall == value,
                                onClick = {
                                    scope.launch { app.settings.saveCollectionWaterfall(value) }
                                    showCollectionStyleDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCollectionStyleDialog = false }) { Text("取消") }
            }
        )
    }

    if (showFontSizeDialog) {
        AlertDialog(
            onDismissRequest = { showFontSizeDialog = false },
            title = { Text("字体大小") },
            text = {
                Column {
                    fontScaleOptions.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settings.saveFontScale(value) }
                                    showFontSizeDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = fontScale == value,
                                onClick = {
                                    scope.launch { app.settings.saveFontScale(value) }
                                    showFontSizeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFontSizeDialog = false }) { Text("取消") }
            }
        )
    }

    if (showBgImageDialog) {
        AlertDialog(
            onDismissRequest = { showBgImageDialog = false },
            title = { Text("背景图") },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pickCustomImage.launch(arrayOf("image/*"))
                                showBgImageDialog = false
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("更换图片")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { app.settings.saveBackgroundCustomUri(null) }
                                showBgImageDialog = false
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("移除背景图", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBgImageDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDarkModeDialog) {
        AlertDialog(
            onDismissRequest = { showDarkModeDialog = false },
            title = { Text("深色模式") },
            text = {
                Column {
                    listOf("system" to "跟随系统", "light" to "浅色模式", "dark" to "深色模式").forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { app.settings.saveDarkMode(value) }
                                    showDarkModeDialog = false
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = darkMode == value,
                                onClick = {
                                    scope.launch { app.settings.saveDarkMode(value) }
                                    showDarkModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    if (bgCustomUri != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "已选择背景图，界面固定使用深色效果；移除背景图后这里的选择才会生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDarkModeDialog = false }) { Text("取消") }
            }
        )
    }

    if (showApiHostDialog) {
        AlertDialog(
            onDismissRequest = { showApiHostDialog = false },
            title = { Text("API 地址") },
            text = {
                Column {
                    apiHosts.forEach { (value, label) ->
                        HostOptionRow(
                            label = label,
                            selected = apiHost == value,
                            pingState = pingResults[value],
                            onSelect = {
                                scope.launch {
                                    // 落盘与切换必须成对完成：只落盘不改运行中的客户端会
                                    // 「设置显示已切换、请求还走旧地址」。NonCancellable 保证
                                    // 选完立刻返回上一页也不会中断
                                    withContext(NonCancellable) {
                                        app.settings.saveApiHost(value)
                                        app.api.updateApiHost(apiHost = value)
                                    }
                                }
                                showApiHostDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showApiHostDialog = false }) { Text("取消") }
            }
        )
    }

    if (showWebHostDialog) {
        AlertDialog(
            onDismissRequest = { showWebHostDialog = false },
            title = { Text("Web 端地址") },
            text = {
                Column {
                    webHosts.forEach { (value, label) ->
                        HostOptionRow(
                            label = label,
                            selected = webHost == value,
                            pingState = pingResults[value],
                            onSelect = {
                                scope.launch {
                                    withContext(NonCancellable) {
                                        app.settings.saveWebHost(value)
                                        app.api.updateApiHost(webHost = value)
                                    }
                                }
                                showWebHostDialog = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWebHostDialog = false }) { Text("取消") }
            }
        )
    }

    // 手动检查发现新版本：与自动检查共用同一个四选项弹窗
    manualUpdate?.let { update ->
        com.bangumi.ywylite.ui.component.UpdateAvailableDialog(
            currentVersion = currentVersion,
            update = update,
            onUpdateNow = {
                openInBrowser(context, update.releaseUrl)
                manualUpdate = null
            },
            onRemindLater = {
                scope.launch { app.updateChecker.snooze(update) }
                manualUpdate = null
            },
            onSkipVersion = {
                scope.launch { app.updateChecker.skip(update) }
                manualUpdate = null
            },
            onNeverCheck = {
                scope.launch { app.updateChecker.setAutoCheckEnabled(false) }
                manualUpdate = null
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                // 透明标题栏：让壁纸从顶部也透出来，整屏像一个平面
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        // 背景与其它页一致（透出 AppBackground 壁纸+蒙版，不额外整页加玻璃），
        // 只有展开的选项块套玻璃面板
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsSection(title = "界面") {
                SettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = "深色模式",
                    subtitle = if (bgCustomUri != null) {
                        "已选背景图，固定深色"
                    } else when (darkMode) {
                        "light" -> "浅色模式"
                        "dark" -> "深色模式"
                        else -> "跟随系统"
                    },
                    onClick = { showDarkModeDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Home,
                    title = "默认打开",
                    subtitle = if (defaultTab == "explore") "浏览" else "收藏",
                    onClick = { showDefaultTabDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.GridView,
                    title = "收藏页样式",
                    subtitle = if (collectionWaterfall) "瀑布流（与浏览页一致）" else "列表（经典）",
                    onClick = { showCollectionStyleDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.FormatSize,
                    title = "字体大小",
                    subtitle = fontScaleOptions.firstOrNull { it.first == fontScale }?.second ?: "标准",
                    onClick = { showFontSizeDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Wallpaper,
                    title = "背景图",
                    subtitle = if (bgCustomUri != null) "已选择图片（界面固定深色，点击更换或移除）" else "从相册选择一张图片",
                    onClick = {
                        if (bgCustomUri == null) {
                            pickCustomImage.launch(arrayOf("image/*"))
                        } else {
                            showBgImageDialog = true
                        }
                    }
                )
                // 已设置背景图时才显示蒙版透明度与模糊度
                if (bgCustomUri != null) {
                    // 透明度：连续滑动（0–100，无档位）
                    ListItem(
                        headlineContent = { Text("不透明度") },
                        supportingContent = {
                            Column {
                                val v = (bgMaskAlphaDrag ?: bgMaskAlpha.toFloat()).toInt()
                                Text("$v%", style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = bgMaskAlphaDrag ?: bgMaskAlpha.toFloat(),
                                    onValueChange = { bgMaskAlphaDrag = it },
                                    onValueChangeFinished = {
                                        val alpha = (bgMaskAlphaDrag ?: bgMaskAlpha.toFloat()).toInt()
                                        scope.launch {
                                            app.settings.saveBackgroundMaskAlpha(alpha)
                                            // 落盘后再交回本地值：否则这段时间滑块会先弹回旧值再跳回来
                                            bgMaskAlphaDrag = null
                                        }
                                    },
                                    valueRange = 0f..100f,
                                    steps = 0
                                )
                            }
                        },
                        leadingContent = { Icon(Icons.Default.Layers, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    // 模糊度：连续滑动，控制底栏/设置页玻璃强度
                    ListItem(
                        headlineContent = { Text("背景模糊度") },
                        supportingContent = {
                            Column {
                                val v = (bgBlurDrag ?: bgBlurAmount.toFloat()).toInt()
                                Text("$v%", style = MaterialTheme.typography.labelSmall)
                                Slider(
                                    value = bgBlurDrag ?: bgBlurAmount.toFloat(),
                                    onValueChange = { bgBlurDrag = it },
                                    onValueChangeFinished = {
                                        val blur = (bgBlurDrag ?: bgBlurAmount.toFloat()).toInt()
                                        scope.launch {
                                            app.settings.saveBackgroundBlurAmount(blur)
                                            // 落盘后再交回本地值，避免滑块回弹
                                            bgBlurDrag = null
                                        }
                                    },
                                    valueRange = 0f..100f,
                                    steps = 0
                                )
                            }
                        },
                        leadingContent = { Icon(Icons.Default.BlurOn, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
            }

            SettingsSection(title = "账号") {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = "账号设置",
                    subtitle = "管理登录状态和Token",
                    onClick = onNavigateToAccountSettings
                )
            }

            SettingsSection(title = "网络") {
                SettingsItem(
                    icon = Icons.Default.Dns,
                    title = "API 地址",
                    subtitle = apiHosts.firstOrNull { it.first == apiHost }?.second ?: "api.bgm.tv（默认）",
                    onClick = { showApiHostDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = "Web 端地址",
                    subtitle = webHosts.firstOrNull { it.first == webHost }?.second ?: "bgm.tv（默认）",
                    onClick = { showWebHostDialog = true }
                )
                SettingsItem(
                    icon = Icons.Default.VpnLock,
                    title = "代理设置",
                    subtitle = "配置HTTP/SOCKS代理",
                    onClick = onNavigateToProxySettings
                )
            }

            SettingsSection(title = "存储") {
                SettingsItem(
                    icon = Icons.Default.DeleteSweep,
                    title = "缓存管理",
                    subtitle = "查看和清理缓存",
                    onClick = onNavigateToCacheSettings
                )
            }

            SettingsSection(title = "关于") {
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "GitHub",
                    subtitle = "github.com/yuwanyuan/bangumi-lite",
                    onClick = { openInBrowser(context, "https://github.com/yuwanyuan/bangumi-lite") }
                )
                // 手动检查放在 GitHub 链接下面：显示当前版本，检查后补上最新版本。
                // 手动检查无视「跳过 / 24 小时静默期」，失败也只在手动路径提示
                SettingsItem(
                    icon = Icons.Default.SystemUpdate,
                    title = "检查更新",
                    subtitle = when {
                        checkingUpdate -> "正在检查…"
                        updateStatus != null -> updateStatus!!
                        else -> "当前版本 $currentVersion"
                    },
                    onClick = {
                        if (checkingUpdate) return@SettingsItem
                        scope.launch {
                            checkingUpdate = true
                            val outcome = try {
                                Result.success(app.updateChecker.checkLatest())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure<com.bangumi.ywylite.data.UpdateInfo?>(e)
                            }
                            checkingUpdate = false
                            outcome.onSuccess { update ->
                                if (update != null) {
                                    updateStatus = "当前版本 $currentVersion · 最新版本 ${update.versionName}"
                                    manualUpdate = update
                                } else {
                                    updateStatus = "当前版本 $currentVersion · 已是最新版本"
                                }
                            }.onFailure {
                                updateStatus = "当前版本 $currentVersion · 暂时没查到新版本"
                                Toast.makeText(context, "暂时没查到新版本", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                SettingsSwitchItem(
                    icon = Icons.Default.Update,
                    title = "自动检查更新",
                    subtitle = "启动时自动检查 GitHub 上的新版本",
                    checked = autoCheckUpdate,
                    onCheckedChange = { enabled ->
                        scope.launch { app.updateChecker.setAutoCheckEnabled(enabled) }
                    }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    // 区块默认折叠：点标题行展开/收起，带连贯的纵向缩放动画
    var expanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    Column(
        modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (!expanded) return@clickable
                    coroutineScope.launch {
                        delay(350)
                        bringIntoViewRequester.bringIntoView()
                    }
                }
                .padding(top = 8.dp, bottom = 4.dp)
        ) {
            Text(
                text = title,
                // 一级（折叠区块标题）文字放大一点点：16sp → 17sp，加粗加强层级；
                // 展开后的菜单项保持默认字号（bodyLarge 16sp），比标题小一档
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        // 展开的选项块：套一层更强的玻璃面板（圆角 + 半透明 + 强模糊），
        // 与选项外的壁纸区域明显区分，形成「浮起的一块」
        AnimatedVisibility(visible = expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .then(wallpaperGlassPanel(LocalDarkTheme.current))
                    .padding(vertical = 4.dp)
            ) {
                // AnimatedVisibility 不自带纵向布局，多个 SettingsItem 必须用 Column 包裹，
                // 否则会叠在同一位置只露出最后一个
                Column { content() }
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        // 展开后的菜单项保持默认字号（bodyLarge 16sp），不随一级标题放大
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** 开关型设置项：整行可点，右侧 Switch（点开关本身不会连带触发行点击） */
@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            Icon(icon, contentDescription = null)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** 地址选择行：单选 + 连通性状态（绿通 / 红不通 / 灰测试中） */
@Composable
private fun HostOptionRow(
    label: String,
    selected: Boolean,
    pingState: Boolean?,
    onSelect: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val (color, text) = when (pingState) {
                true -> Color(0xFF4CAF50) to "通"
                false -> Color(0xFFF44336) to "不通"
                null -> MaterialTheme.colorScheme.outline to "测试中"
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
