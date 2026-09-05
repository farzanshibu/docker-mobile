package com.dockermobile.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.QuickRun
import com.dockermobile.app.docker.QuickRuns
import com.dockermobile.app.docker.UiContainer
import com.dockermobile.app.docker.UiPort
import com.dockermobile.app.ui.components.ErrorBanner
import com.dockermobile.app.ui.components.PortChips
import com.dockermobile.app.ui.components.SectionCard
import com.dockermobile.app.ui.components.StatusDot
import com.dockermobile.app.ui.components.openPortInBrowser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainersScreen(
    onOpenContainer: (String, String, Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val graph = LocalGraph.current
    val repo = graph.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showDeploy by remember { mutableStateOf(false) }

    val containers by repo.containers.collectAsState()
    val error by repo.lastError.collectAsState()
    val settings by graph.settings.settings.collectAsState(initial = null)
    val hostForwards by graph.vm.hostForwards.collectAsState()

    LaunchedEffect(Unit) { repo.startWatching() }
    LaunchedEffect(Unit) {
        repo.messages.collect { snackbar.showSnackbar(it.take(140)) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Docker Mobile") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { scope.launch { repo.refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showDeploy = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Deploy") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            ErrorBanner(error)
            Spacer(Modifier.height(8.dp))

            if (containers.isEmpty()) {
                EmptyContainers(error != null)
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(containers, key = { it.id }) { c ->
                        ContainerCard(
                            container = c,
                            hostForwards = hostForwards,
                            onOpen = { onOpenContainer(c.id, c.name, 0) },
                            onStart = { repo.containerAction(c.id, "start") },
                            onStop = { repo.containerAction(c.id, "stop") },
                            onRestart = { repo.containerAction(c.id, "restart") },
                            onRemove = { repo.removeContainer(c.id, c.name) },
                            onPortTap = { port ->
                                val s = settings ?: return@ContainerCard
                                val mapped = hostForwards[port.publicPort] ?: port.publicPort
                                if (mapped != null) openPortInBrowser(context, s, mapped)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    if (showDeploy) {
        DeployDialog(
            onDismiss = { showDeploy = false },
            onQuickRun = { run, restartAlways ->
                repo.runImage(
                    image = run.image,
                    name = null,
                    portPairs = run.ports,
                    env = run.env,
                    cmd = run.cmd,
                    restartAlways = restartAlways,
                )
                showDeploy = false
            },
            onCustomRun = { image, portText, envText, restartAlways ->
                val pairs = portText.split(',')
                    .mapNotNull { entry ->
                        val parts = entry.trim().split(':')
                        if (parts.size == 2) {
                            parts[0].toIntOrNull()?.let { h -> parts[1].toIntOrNull()?.let { c -> h to c } }
                        } else null
                    }
                repo.runImage(
                    image = image,
                    name = null,
                    portPairs = pairs,
                    env = envText.split(',').map { it.trim() }.filter { it.contains('=') },
                    cmd = null,
                    restartAlways = restartAlways,
                )
                showDeploy = false
            },
        )
    }
}

@Composable
private fun EmptyContainers(hasError: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (hasError) "Cannot reach the Docker daemon" else "No containers yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasError) "Start the embedded VM from the VM tab, or check Settings."
            else "Tap Deploy to run your first image.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContainerCard(
    container: UiContainer,
    hostForwards: Map<Int, Int>,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
    onPortTap: (UiPort) -> Unit,
) {
    SectionCard(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(container.state)
            Spacer(Modifier.padding(start = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    container.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${container.image} · ${container.status}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (container.state == "running") {
                IconButton(onClick = onStop) { Icon(Icons.Filled.Pause, "Stop") }
                IconButton(onClick = onRestart) { Icon(Icons.Filled.RestartAlt, "Restart") }
            } else {
                IconButton(onClick = onStart) { Icon(Icons.Filled.PlayArrow, "Start") }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "Remove") }
            }
        }
        Spacer(Modifier.height(8.dp))
        PortChips(container.ports, enabled = true, onTap = onPortTap)
    }
}

@Composable
private fun DeployDialog(
    onDismiss: () -> Unit,
    onQuickRun: (QuickRun, Boolean) -> Unit,
    onCustomRun: (image: String, ports: String, env: String, restartAlways: Boolean) -> Unit,
) {
    var image by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("8080:80") }
    var env by remember { mutableStateOf("") }
    // Without a restart policy a container stays down after the VM (or the
    // phone) restarts, which defeats leaving the phone up as a server.
    var restartAlways by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deploy a container") },
        text = {
            Column {
                Text("Quick start", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                QuickRuns.all.forEach { run ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onQuickRun(run, restartAlways) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${run.label} — ${run.image}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                run.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Custom", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = image, onValueChange = { image = it },
                    label = { Text("Image (e.g. nginx)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ports, onValueChange = { ports = it },
                    label = { Text("Ports (host:container, comma-sep)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = env, onValueChange = { env = it },
                    label = { Text("Env (KEY=VAL, comma-sep)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = restartAlways,
                        onCheckedChange = { restartAlways = it },
                    )
                    Column {
                        Text("Restart automatically", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Comes back after a VM or phone restart",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (image.isNotBlank()) onCustomRun(image.trim(), ports, env, restartAlways) },
            ) { Text("Run") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
