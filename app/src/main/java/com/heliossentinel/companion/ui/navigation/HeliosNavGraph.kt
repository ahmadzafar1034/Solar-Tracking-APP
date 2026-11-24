package com.heliossentinel.companion.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import com.heliossentinel.companion.data.repository.TrackerRepository
import com.heliossentinel.companion.ui.control.ControlScreen
import com.heliossentinel.companion.ui.dashboard.DashboardScreen
import com.heliossentinel.companion.ui.history.HistoryScreen
import com.heliossentinel.companion.ui.settings.SettingsScreen

sealed class Screen(val route: String, val label: String) {
    data object Dashboard : Screen("dashboard", "Dashboard")
    data object Control : Screen("control", "Control")
    data object History : Screen("history", "History")
    data object Settings : Screen("settings", "Settings")
}

private val bottomNavItems = listOf(Screen.Dashboard, Screen.Control, Screen.History, Screen.Settings)

@Composable
fun HeliosNavGraph(repository: TrackerRepository) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination

                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (screen) {
                                    Screen.Dashboard -> Icons.Filled.Dashboard
                                    Screen.Control -> Icons.Filled.Tune
                                    Screen.History -> Icons.AutoMirrored.Filled.ShowChart
                                    Screen.Settings -> Icons.Filled.Settings
                                },
                                contentDescription = screen.label
                            )
                        },
                        label = { Text(screen.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(repository = repository) }
            composable(Screen.Control.route) { ControlScreen(repository = repository) }
            composable(Screen.History.route) { HistoryScreen(repository = repository) }
            composable(Screen.Settings.route) { SettingsScreen(repository = repository) }
        }
    }
}
