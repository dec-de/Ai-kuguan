package com.kuguan.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kuguan.app.di.AppContainer
import com.kuguan.app.ui.detail.ItemDetailScreen
import com.kuguan.app.ui.history.HistoryScreen
import com.kuguan.app.ui.home.HomeScreen
import com.kuguan.app.ui.search.SearchScreen
import com.kuguan.app.ui.reminder.ReminderScreen
import com.kuguan.app.ui.backup.BackupScreen
import com.kuguan.app.ui.backup.BackupSection
import com.kuguan.app.ui.config.ConfigDirectoryScreen
import com.kuguan.app.ui.config.VoiceAiSettingsScreen

/** 底部五个入口和配置、物品详情二级路由。 */
object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val REMINDERS = "reminders"
    const val CONFIG = "config"
    const val VOICE_AI = "config/voice-ai"
    const val BACKUP_RESTORE = "config/backup"
    const val STORAGE_DIAGNOSTICS = "config/storage"
    const val ITEM_DETAIL = "item/{itemId}"

    fun itemDetail(itemId: String) = "item/$itemId"
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    TabItem(Routes.HOME, "说话", Icons.Filled.Mic),
    TabItem(Routes.SEARCH, "搜索", Icons.Filled.Search),
    TabItem(Routes.HISTORY, "历史", Icons.Filled.History),
    TabItem(Routes.REMINDERS, "提醒", Icons.Filled.Notifications),
    TabItem(Routes.CONFIG, "配置", Icons.Filled.Settings),
)

@Composable
fun KuguanNavHost(
    container: AppContainer,
    reminderRequest: Int = 0,
    navController: NavHostController = rememberNavController(),
) {
    var voiceDirty by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(reminderRequest) {
        if (reminderRequest > 0) navController.navigate(Routes.REMINDERS) { launchSingleTop = true }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route
    val configRoutes = setOf(Routes.CONFIG, Routes.VOICE_AI, Routes.BACKUP_RESTORE, Routes.STORAGE_DIAGNOSTICS)

    fun leaveOrConfirm(action: () -> Unit) {
        if (currentRoute == Routes.VOICE_AI && voiceDirty) pendingLeave = action else action()
    }
    BackHandler(enabled = currentRoute == Routes.VOICE_AI) {
        leaveOrConfirm { navController.popBackStack() }
    }

    // 所有「切到某个 Tab」的入口必须走这一条路径。
    //
    // 之前首页搜索图标用的是裸 navigate(SEARCH)，与底部 Tab 的
    // popUpTo + saveState 混用，导致 back stack 里出现两种形态共存：
    // 从图标进搜索页后按返回键会直接退出 App（实测 K70 真机）。
    // 抽成函数后「图标进入」与「Tab 进入」语义完全一致。
    val switchTab: (String) -> Unit = { route ->
        leaveOrConfirm {
            if (currentRoute in configRoutes && currentRoute != Routes.CONFIG) {
                navController.popBackStack(Routes.CONFIG, false)
            }
            if (route != Routes.CONFIG || currentRoute !in configRoutes) {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            val showBar = currentRoute in configRoutes || TABS.any { tab ->
                currentDestination?.hierarchy?.any { it.route == tab.route } == true
            }
            if (showBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = if (tab.route == Routes.CONFIG) currentRoute in configRoutes
                            else currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (reminderRequest > 0) Routes.REMINDERS else Routes.HOME,
            modifier = Modifier,
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    container = container,
                    bottomPadding = innerPadding,
                    onOpenSearch = { switchTab(Routes.SEARCH) },
                    // 反查结果里的「打开详情」走既有路由，不另起一套导航
                    onOpenItem = { id -> navController.navigate(Routes.itemDetail(id)) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    repository = container.repository,
                    bottomPadding = innerPadding,
                    onBack = { navController.popBackStack() },
                    onOpenItem = { id -> navController.navigate(Routes.itemDetail(id)) },
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    repository = container.repository,
                    bottomPadding = innerPadding,
                    onBack = { navController.popBackStack() },
                    onOpenItem = { id -> navController.navigate(Routes.itemDetail(id)) },
                )
            }
            composable(Routes.REMINDERS) {
                ReminderScreen(
                    bottomPadding = innerPadding,
                    onOpenItem = { id -> navController.navigate(Routes.itemDetail(id)) },
                )
            }
            composable(Routes.CONFIG) {
                ConfigDirectoryScreen(
                    bottomPadding = innerPadding,
                    onVoiceAi = { navController.navigate(Routes.VOICE_AI) },
                    onBackup = { navController.navigate(Routes.BACKUP_RESTORE) },
                    onStorage = { navController.navigate(Routes.STORAGE_DIAGNOSTICS) },
                )
            }
            composable(Routes.VOICE_AI) {
                VoiceAiSettingsScreen(
                    bottomPadding = innerPadding,
                    settings = container.voiceSettings,
                    tester = container.voiceConnectionTester,
                    onBack = { leaveOrConfirm { navController.popBackStack() } },
                    onDirtyChange = { voiceDirty = it },
                )
            }
            composable(Routes.BACKUP_RESTORE) {
                BackupScreen(bottomPadding = innerPadding, database = container.database,
                    section = BackupSection.BACKUP, onBack = { navController.popBackStack() })
            }
            composable(Routes.STORAGE_DIAGNOSTICS) {
                BackupScreen(bottomPadding = innerPadding, database = container.database,
                    section = BackupSection.STORAGE, onBack = { navController.popBackStack() })
            }
            composable(Routes.ITEM_DETAIL) { entry ->
                ItemDetailScreen(
                    repository = container.repository,
                    itemId = entry.arguments?.getString("itemId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
    if (pendingLeave != null) AlertDialog(
        onDismissRequest = { pendingLeave = null },
        title = { Text("放弃未保存的修改？") },
        text = { Text("语音与 AI 的修改尚未保存。离开后会丢失这些修改。") },
        confirmButton = { TextButton(onClick = {
            val action = pendingLeave
            pendingLeave = null
            voiceDirty = false
            action?.invoke()
        }) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { pendingLeave = null }) { Text("继续编辑") } },
    )
}
