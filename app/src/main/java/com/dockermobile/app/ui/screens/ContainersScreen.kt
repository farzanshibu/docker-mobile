package com.dockermobile.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.dockermobile.app.ui.components.appleSwitchColors
import com.dockermobile.app.ui.components.ConfirmDialog
import com.dockermobile.app.ui.components.EmptyState
import com.dockermobile.app.ui.components.ErrorBanner
import com.dockermobile.app.ui.components.FilledAction
import com.dockermobile.app.ui.components.GroupBody
import com.dockermobile.app.ui.components.GroupRow
import com.dockermobile.app.ui.components.Hairline
import com.dockermobile.app.ui.components.InsetGroup
import com.dockermobile.app.ui.components.LargeTitleScaffold
import com.dockermobile.app.ui.components.MinTouchTarget
import com.dockermobile.app.ui.components.PortChips
import com.dockermobile.app.ui.components.StatusBadge
import com.dockermobile.app.ui.components.openPortInBrowser
import com.dockermobile.app.ui.components.rememberHaptics
import com.dockermobile.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

@Composable
fun ContainersScreen(
    onOpenContainer: (String, String, Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val graph = LocalGraph.current
    val repo = graph.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    var showDeploy by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<UiContainer?>(null) }

    val containers by repo.containers.collectAsState()
    val error by repo.lastError.collectAsState()
    val settings by graph.settings.settings.collectAsState(initial = null)
    val hostForwards by graph.vm.hostForwards.collectAsState()

    LaunchedEffect(Unit) { repo.startWatching() }
    LaunchedEffect(Unit) {
        repo.messages.collect { snackbar.showSnackbar(it.take(140)) }
    }

    LargeTitleScaffold(
        title = "Containers",
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            IconButton(onClick = { scope.launch { repo.refresh() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
            IconButton(onClick = { haptics.selection(); showDeploy = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Deploy a container")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item { ErrorBanner(error) { repo.lastError.value = null } }
            }
            if (containers.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Inbox,
                        title = if (error != null) "Can't reach the daemon" else "No containers yet",
                        message = if (error != null)
                            "Start the embedded VM from the VM tab, or point the app at a remote daemon in Settings."
                        else "Deploy an image to get your first container running.",
                        actionLabel = if (error != null) null else "Deploy a container",
                        onAction = if (error != null) null else ({ showDeploy = true }),
                    )
                }
            }
            items(containers, key = { it.id }) { c ->
                ContainerCard(
                    container = c,
                    onOpen = { onOpenContainer(c.id, c.name, 0) },
                    onStart = { haptics.success(); repo.containerAction(c.id, "start") },
                    onStop = { haptics.selection(); repo.containerAction(c.id, "stop") },
                    onRestart = { haptics.selection(); repo.containerAction(c.id, "restart") },
                    onRemove = { removeTarget = c },
                    onPortTap = { port ->
                        val s = settings ?: return@ContainerCard
                        val mapped = hostForwards[port.publicPort] ?: port.publicPort
                        if (mapped != null) openPortInBrowser(context, s, mapped)
                    },
                )
            }
        }
    }

    if (showDeploy) {
        DeploySheet(
            onDismiss = { showDeploy = false },
            onQuickRun = { run, restartAlways ->
                haptics.success()
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
                haptics.success()
                repo.runImage(
                    image = image,
                    name = null,
                    portPairs = parsePortPairs(portText),
                    env = envText.split(',').map { it.trim() }.filter { it.contains('=') },
                    cmd = null,
                    restartAlways = restartAlways,
                )
                showDeploy = false
            },
        )
    }

    removeTarget?.let { target ->
        ConfirmDialog(
            title = "Remove “${target.name}”?",
            body = "The container and anything written inside it are deleted. Named volumes are kept.",
            confirmLabel = "Remove",
            onConfirm = { repo.removeContainer(target.id, target.name) },
            onDismiss = { removeTarget = null },
        )
    }
}

internal fun parsePortPairs(text: String): List<Pair<Int, Int>> =
    text.split(',').mapNotNull { entry ->
        val parts = entry.trim().split(':')
        if (parts.size == 2) {
            parts[0].toIntOrNull()?.let { h -> parts[1].toIntOrNull()?.let { c -> h to c } }
        } else null
    }

@Composable
private fun ContainerCard(
    container: UiContainer,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onRemove: () -> Unit,
    onPortTap: (UiPort) -> Unit,
) {
    val published = container.ports.any { it.publicPort != null }
    InsetGroup {
        Row(
            Modifier
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    container.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    container.image,
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(container.state)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        container.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.colors.labelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (container.isRunning) {
                IconButton(onClick = onStop, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(Icons.Filled.Pause, contentDescription = "Stop ${container.name}")
                }
                IconButton(onClick = onRestart, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = "Restart ${container.name}")
                }
            } else {
                IconButton(onClick = onStart, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Start ${container.name}")
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(MinTouchTarget)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove ${container.name}",
                        tint = AppTheme.colors.statusError,
                    )
                }
            }
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = AppTheme.colors.labelTertiary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (published) {
            Hairline()
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                PortChips(container.ports, onTap = onPortTap)
            }
        }
    }
}

/**
 * Deploying is a multi-field task, not a yes/no question, so it belongs in a
 * sheet rather than an alert — alerts are reserved for critical, actionable
 * interruptions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeploySheet(
    onDismiss: () -> Unit,
    onQuickRun: (QuickRun, Boolean) -> Unit,
    onCustomRun: (image: String, ports: String, env: String, restartAlways: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var image by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("8080:80") }
    var env by remember { mutableStateOf("") }
    // Without a restart policy a container stays down after the VM (or the
    // phone) restarts, which defeats leaving the phone up as a server.
    var restartAlways by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppTheme.colors.base,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Deploy a container", style = MaterialTheme.typography.headlineMedium)

            InsetGroup(header = "Quick start") {
                QuickRuns.all.forEachIndexed { index, run ->
                    if (index > 0) Hairline()
                    GroupRow(
                        title = run.label,
                        subtitle = "${run.image} · ${run.description}",
                        onClick = { onQuickRun(run, restartAlways) },
                    )
                }
            }

            InsetGroup(
                header = "Custom image",
                footer = "Ports map host to container, comma separated. Environment entries look like KEY=value.",
            ) {
                GroupBody {
                    OutlinedTextField(
                        value = image,
                        onValueChange = { image = it },
                        label = { Text("Image") },
                        placeholder = { Text("nginx") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = ports,
                        onValueChange = { ports = it },
                        label = { Text("Ports") },
                        placeholder = { Text("8080:80") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = env,
                        onValueChange = { env = it },
                        label = { Text("Environment") },
                        placeholder = { Text("KEY=value") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            InsetGroup(footer = "Restarted containers come back on their own after the VM or the phone reboots.") {
                GroupRow(
                    title = "Restart automatically",
                    showChevron = false,
                    trailing = {
                        Switch(
                            checked = restartAlways,
                            onCheckedChange = { restartAlways = it },
                            colors = appleSwitchColors(),
                        )
                    },
                )
            }

            FilledAction(
                label = "Run image",
                onClick = { if (image.isNotBlank()) onCustomRun(image.trim(), ports, env, restartAlways) },
                enabled = image.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
