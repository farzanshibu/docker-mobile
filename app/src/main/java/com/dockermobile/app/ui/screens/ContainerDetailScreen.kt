package com.dockermobile.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.ui.components.ConfirmDialog
import com.dockermobile.app.ui.components.Hairline
import com.dockermobile.app.ui.components.MinTouchTarget
import com.dockermobile.app.ui.components.SegmentedControl
import com.dockermobile.app.ui.components.StatusBadge
import com.dockermobile.app.ui.components.rememberHaptics
import com.dockermobile.app.ui.screens.panes.ExecPane
import com.dockermobile.app.ui.screens.panes.InspectPane
import com.dockermobile.app.ui.screens.panes.LogsPane
import com.dockermobile.app.ui.screens.panes.StatsPane
import com.dockermobile.app.ui.theme.AppTheme

private val paneTitles = listOf("Logs", "Exec", "Stats", "Inspect")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailScreen(
    containerId: String,
    containerName: String,
    initialTab: Int,
    onBack: () -> Unit,
) {
    val graph = LocalGraph.current
    val repo = graph.repo
    val haptics = rememberHaptics()
    var selected by remember { mutableIntStateOf(initialTab.coerceIn(0, paneTitles.size - 1)) }
    var confirmRemove by remember { mutableStateOf(false) }

    val containers by repo.containers.collectAsState()
    val container = containers.firstOrNull { it.id == containerId }
    val name = container?.name ?: containerName
    val state = container?.state ?: "unknown"

    LaunchedEffect(Unit) { repo.startWatching() }

    Scaffold(
        containerColor = AppTheme.colors.base,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            StatusBadge(state)
                        }
                    },
                    navigationIcon = {
                        // Back sits where the platform puts it: a chevron on the
                        // leading edge, tinted with the accent.
                        IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBackIos,
                                contentDescription = "Back to containers",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppTheme.colors.base,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    actions = {
                        if (container?.isRunning == true) {
                            IconButton(onClick = {
                                haptics.selection()
                                repo.containerAction(containerId, "stop")
                            }) {
                                Icon(Icons.Filled.Pause, contentDescription = "Stop container")
                            }
                            IconButton(onClick = {
                                haptics.selection()
                                repo.containerAction(containerId, "restart")
                            }) {
                                Icon(Icons.Filled.RestartAlt, contentDescription = "Restart container")
                            }
                        } else {
                            IconButton(onClick = {
                                haptics.success()
                                repo.containerAction(containerId, "start")
                            }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Start container")
                            }
                        }
                        IconButton(onClick = { confirmRemove = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove container",
                                tint = AppTheme.colors.statusError,
                            )
                        }
                    },
                )
                Hairline(startIndent = 0.dp)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Spacer(Modifier.height(12.dp))
            SegmentedControl(
                items = paneTitles,
                selectedIndex = selected,
                onSelect = { selected = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))
            when (selected) {
                0 -> LogsPane(containerId)
                1 -> ExecPane(containerId)
                2 -> StatsPane(containerId)
                else -> InspectPane(containerId)
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove “$name”?",
            body = "The container and anything written inside it are deleted. Named volumes are kept.",
            confirmLabel = "Remove",
            onConfirm = {
                repo.removeContainer(containerId, name)
                onBack()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}
