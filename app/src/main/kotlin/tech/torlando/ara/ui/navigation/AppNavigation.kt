package tech.torlando.ara.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tech.torlando.ara.ui.screens.ChatScreen
import tech.torlando.ara.ui.screens.HostScreen
import tech.torlando.ara.ui.screens.HubBrowserScreen
import tech.torlando.ara.ui.screens.RoomListScreen
import tech.torlando.ara.ui.screens.SettingsScreen
import tech.torlando.ara.viewmodel.AraViewModel

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
) {
    data object Rooms : Screen("rooms", "Rooms", Icons.AutoMirrored.Filled.Chat)
    data object Discover : Screen("discover", "Discover", Icons.Filled.Search)
    data object Host : Screen("host", "Host", Icons.Filled.Hub)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
}

val mainNavigationItems = listOf(
    Screen.Rooms,
    Screen.Discover,
    Screen.Host,
    Screen.Settings,
)

@Composable
fun AppNavigation(viewModel: AraViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry = navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry.value?.destination

    val hideBottomNavScreens = listOf(Screen.Chat.route)
    val shouldShowBottomNav = currentDestination?.route !in hideBottomNavScreens

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (shouldShowBottomNav) {
                NavigationBar {
                    mainNavigationItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { outerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Rooms.route,
            modifier = Modifier.padding(outerPadding),
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(75)) },
            popEnterTransition = { fadeIn(tween(150)) },
            popExitTransition = { fadeOut(tween(75)) },
        ) {
            composable(Screen.Rooms.route) {
                RoomListScreen(
                    viewModel = viewModel,
                    onNavigateToChat = { room ->
                        viewModel.setCurrentRoom(room)
                        navController.navigate(Screen.Chat.route)
                    },
                )
            }
            composable(Screen.Discover.route) {
                HubBrowserScreen(
                    viewModel = viewModel,
                )
            }
            composable(Screen.Host.route) {
                HostScreen(viewModel = viewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = viewModel)
            }
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
