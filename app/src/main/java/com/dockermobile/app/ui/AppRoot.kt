package com.dockermobile.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dockermobile.app.ui.screens.ComposeScreen
import com.dockermobile.app.ui.screens.ContainerDetailScreen
import com.dockermobile.app.ui.screens.ContainersScreen
import com.dockermobile.app.ui.screens.ImagesScreen
import com.dockermobile.app.ui.screens.SettingsScreen
import com.dockermobile.app.ui.screens.VmScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("containers", "Containers", Icons.Filled.Dashboard),
    Tab("images", "Images", Icons.Filled.Layers),
    Tab("compose", "Compose", Icons.Filled.Description),
    Tab("vm", "VM", Icons.Filled.Memory),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    Surface(color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "containers",
                modifier = Modifier.padding(padding),
            ) {
                composable("containers") {
                    ContainersScreen(
                        onOpenContainer = { id, name, tab -> nav.navigate("container/$id/$name/$tab") },
                        onOpenSettings = { nav.navigate("settings") },
                    )
                }
                composable("images") {
                    ImagesScreen(onOpenSettings = { nav.navigate("settings") })
                }
                composable("compose") {
                    ComposeScreen(onOpenSettings = { nav.navigate("settings") })
                }
                composable("vm") {
                    VmScreen(onOpenSettings = { nav.navigate("settings") })
                }
                composable(
                    route = "container/{id}/{name}/{tab}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType },
                        navArgument("tab") { type = NavType.IntType; defaultValue = 0 },
                    ),
                ) { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    val name = entry.arguments?.getString("name").orEmpty()
                    val tab = entry.arguments?.getInt("tab") ?: 0
                    ContainerDetailScreen(
                        containerId = id,
                        containerName = name,
                        initialTab = tab,
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("settings") {
                    SettingsScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
