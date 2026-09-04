package com.dockermobile.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.ui.screens.panes.ExecPane
import com.dockermobile.app.ui.screens.panes.InspectPane
import com.dockermobile.app.ui.screens.panes.LogsPane
import com.dockermobile.app.ui.screens.panes.StatsPane

private val tabTitles = listOf("Logs", "Exec", "Stats", "Inspect")

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
    var selected by remember { mutableIntStateOf(initialTab.coerceIn(0, tabTitles.size - 1)) }

    val containers by repo.containers.collectAsState()
    val container = containers.firstOrNull { it.id == containerId }
    val name = container?.name ?: containerName

    LaunchedEffect(Unit) { repo.startWatching() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    val running = container?.isRunning == true
                    if (running) {
                        IconButton(onClick = { repo.containerAction(containerId, "stop") }) {
                            Icon(Icons.Filled.Pause, contentDescription = "Stop")
                        }
                        IconButton(onClick = { repo.containerAction(containerId, "restart") }) {
                            Icon(Icons.Filled.RestartAlt, contentDescription = "Restart")
                        }
                    } else {
                        IconButton(onClick = { repo.containerAction(containerId, "start") }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Start")
                        }
                    }
                    IconButton(onClick = {
                        repo.removeContainer(containerId, name)
                        onBack()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(
                selectedTabIndex = selected,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                tabTitles.forEachIndexed { i, title ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = { Text(title) },
                    )
                }
            }
            Row(Modifier.padding(horizontal = 12.dp)) { Spacer(Modifier.width(0.dp)) }
            when (selected) {
                0 -> LogsPane(containerId)
                1 -> ExecPane(containerId)
                2 -> StatsPane(containerId)
                else -> InspectPane(containerId)
            }
        }
    }
}
