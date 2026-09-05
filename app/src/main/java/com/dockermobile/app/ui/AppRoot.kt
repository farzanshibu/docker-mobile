package com.dockermobile.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dockermobile.app.ui.components.Hairline
import com.dockermobile.app.ui.components.rememberHaptics
import com.dockermobile.app.ui.screens.ComposeScreen
import com.dockermobile.app.ui.screens.ContainerDetailScreen
import com.dockermobile.app.ui.screens.ContainersScreen
import com.dockermobile.app.ui.screens.ImagesScreen
import com.dockermobile.app.ui.screens.SettingsScreen
import com.dockermobile.app.ui.screens.VmScreen
import com.dockermobile.app.ui.theme.AppTheme
import com.dockermobile.app.ui.theme.reduceMotion

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
    val showTabBar = tabs.any { it.route == currentRoute }
    val still = reduceMotion()

    // A push reads as the new screen sliding over the old one, which itself
    // drifts a little and dims. Dropped entirely when the device asks for
    // reduced motion.
    val pushEnter = if (still) EnterTransition.None else
        slideInHorizontally(tween(320)) { it } + fadeIn(tween(180))
    val pushExitBack = if (still) ExitTransition.None else
        slideOutHorizontally(tween(320)) { it } + fadeOut(tween(240))
    val parallaxOut = if (still) ExitTransition.None else
        slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(320))
    val parallaxIn = if (still) EnterTransition.None else
        slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320))

    Surface(color = AppTheme.colors.base) {
        Scaffold(
            containerColor = AppTheme.colors.base,
            bottomBar = { if (showTabBar) TabBar(currentRoute) { route -> nav.switchTab(route) } },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = "containers",
                modifier = Modifier.padding(padding),
                enterTransition = { if (still) EnterTransition.None else fadeIn(tween(140)) },
                exitTransition = { if (still) ExitTransition.None else fadeOut(tween(140)) },
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
                    enterTransition = { pushEnter },
                    exitTransition = { parallaxOut },
                    popEnterTransition = { parallaxIn },
                    popExitTransition = { pushExitBack },
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
                composable(
                    route = "settings",
                    enterTransition = { pushEnter },
                    exitTransition = { parallaxOut },
                    popEnterTransition = { parallaxIn },
                    popExitTransition = { pushExitBack },
                ) {
                    SettingsScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

private fun androidx.navigation.NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Tab bar in the platform idiom: a hairline, a near-opaque bar that lets the
 * content colour bleed through, 24dp glyphs over an 11pt caption, and the
 * accent reserved for the selected tab. No pill indicator — selection is
 * carried by tint *and* weight.
 */
@Composable
private fun TabBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val haptics = rememberHaptics()
    Column {
        Hairline(startIndent = 0.dp)
        Surface(color = AppTheme.colors.elevated.copy(alpha = 0.94f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route
                    val tint = if (selected) MaterialTheme.colorScheme.primary
                    else AppTheme.colors.labelSecondary
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (!selected) {
                                    haptics.selection()
                                    onSelect(tab.route)
                                }
                            }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}
