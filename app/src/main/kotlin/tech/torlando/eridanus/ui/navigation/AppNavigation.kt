// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import tech.torlando.eridanus.ui.screens.ChatScreen
import tech.torlando.eridanus.ui.screens.HostScreen
import tech.torlando.eridanus.ui.screens.HubBrowserScreen
import tech.torlando.eridanus.ui.screens.OnboardingScreen
import tech.torlando.eridanus.ui.screens.RoomListScreen
import tech.torlando.eridanus.ui.screens.SettingsScreen
import tech.torlando.eridanus.viewmodel.EridanusViewModel

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
    Screen.Discover,
    Screen.Rooms,
    Screen.Host,
    Screen.Settings,
)

@Composable
fun AppNavigation(viewModel: EridanusViewModel) {
    val hasCompletedOnboarding by viewModel.hasCompletedOnboarding.collectAsState()

    if (!hasCompletedOnboarding) {
        OnboardingScreen(viewModel = viewModel)
        return
    }

    val connectedToSharedInstance by viewModel.connectedToSharedInstance.collectAsState()
    val reticulumStarted by viewModel.reticulumStarted.collectAsState()

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
        Column(modifier = Modifier.padding(outerPadding)) {
            if (reticulumStarted && !connectedToSharedInstance) {
                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.errorContainer)
                        .clickable { viewModel.retrySharedInstance() }
                        .padding(top = statusBarTop + 10.dp, bottom = 10.dp, start = 16.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "No shared instance \u2014 tap to retry",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            NavHost(
                navController = navController,
                startDestination = Screen.Discover.route,
                modifier = Modifier.weight(1f),
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
                    HostScreen(
                        viewModel = viewModel,
                        onNavigateToRooms = {
                            navController.navigate(Screen.Rooms.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
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
}
