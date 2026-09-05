package com.dockermobile.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.Format
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.PullEvent
import com.dockermobile.app.docker.UiImage
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
import com.dockermobile.app.ui.components.TapTag
import com.dockermobile.app.ui.components.rememberHaptics
import com.dockermobile.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

@Composable
fun ImagesScreen(onOpenSettings: () -> Unit) {
    val graph = LocalGraph.current
    val repo = graph.repo
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    var showPull by remember { mutableStateOf(false) }
    var runTarget by remember { mutableStateOf<UiImage?>(null) }
    var deleteTarget by remember { mutableStateOf<UiImage?>(null) }

    val images by repo.images.collectAsState()
    val error by repo.lastError.collectAsState()

    LaunchedEffect(Unit) { repo.startWatching() }
    LaunchedEffect(Unit) { repo.messages.collect { snackbar.showSnackbar(it.take(140)) } }

    LargeTitleScaffold(
        title = "Images",
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            IconButton(onClick = { scope.launch { repo.refresh() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
            IconButton(onClick = { haptics.selection(); showPull = true }) {
                Icon(Icons.Filled.Download, contentDescription = "Pull an image")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item { ErrorBanner(error) { repo.lastError.value = null } }
            }
            if (images.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Layers,
                        title = "No images",
                        message = "Pull an image from Docker Hub or any OCI registry to run it here.",
                        actionLabel = "Pull an image",
                        onAction = { showPull = true },
                    )
                }
            }
            items(images, key = { it.id }) { img ->
                InsetGroup {
                    Row(
                        Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                img.displayTag,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${Format.humanBytes(img.sizeBytes)} · ${Format.relativeAge(img.createdUnix.toString())} · ${Format.shortId(img.id)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = AppTheme.colors.labelSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { runTarget = img },
                            modifier = Modifier.size(MinTouchTarget),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Run ${img.displayTag}",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = { deleteTarget = img },
                            modifier = Modifier.size(MinTouchTarget),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete ${img.displayTag}",
                                tint = AppTheme.colors.statusError,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPull) {
        PullSheet(onDismiss = { showPull = false })
    }

    runTarget?.let { img ->
        RunFromImageSheet(
            image = img.displayTag.substringBefore('@'),
            onDismiss = { runTarget = null },
            onRun = { name, ports, env, cmd, restart ->
                haptics.success()
                repo.runImage(img.displayTag.substringBefore('@'), name, ports, env, cmd, restart)
                runTarget = null
            },
        )
    }

    deleteTarget?.let { img ->
        ConfirmDialog(
            title = "Delete ${img.displayTag}?",
            body = "The image is removed from the daemon. Containers already running from it keep working until they stop.",
            confirmLabel = "Delete",
            onConfirm = { repo.deleteImage(img.id, img.displayTag) },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * Pull shows determinate progress as soon as the registry reports layer sizes,
 * and keeps the sheet open so the download stays visible while it runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullSheet(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val haptics = rememberHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var ref by remember { mutableStateOf("nginx:latest") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }
    val layers = remember { mutableStateOf(mapOf<String, Pair<Long, Long>>()) }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
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
            Text("Pull image", style = MaterialTheme.typography.headlineMedium)

            InsetGroup(footer = "Any OCI registry works — prefix the reference with its host.") {
                GroupBody {
                    OutlinedTextField(
                        value = ref,
                        onValueChange = { ref = it },
                        label = { Text("Reference") },
                        placeholder = { Text("nginx:latest") },
                        singleLine = true,
                        enabled = !busy,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("nginx:latest", "redis:8", "postgres:17", "alpine:latest").forEach { preset ->
                            TapTag(label = preset, onClick = { if (!busy) ref = preset })
                        }
                    }
                }
            }

            if (busy) {
                InsetGroup(header = "Downloading") {
                    GroupBody {
                        val f = fraction
                        if (f != null) {
                            LinearProgressIndicator(
                                progress = { f },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            statusText ?: "Contacting the registry…",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTheme.colors.labelSecondary,
                        )
                    }
                }
            }

            if (busy) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
            } else {
                FilledAction(
                    label = "Pull",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ref.isNotBlank(),
                    onClick = {
                        busy = true
                        layers.value = emptyMap()
                        graph.repo.pullImage(
                            ref.trim(),
                            onEvent = { ev: PullEvent ->
                                if (ev.current != null && ev.total != null && ev.id != null) {
                                    layers.value = layers.value + (ev.id to (ev.current to ev.total))
                                    val done = layers.value.values.sumOf { it.first }
                                    val total = layers.value.values.sumOf { it.second }
                                    if (total > 0) fraction = (done.toFloat() / total).coerceIn(0f, 1f)
                                }
                                statusText = ev.status
                            },
                            onDone = {
                                busy = false
                                haptics.success()
                                onDismiss()
                            },
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunFromImageSheet(
    image: String,
    onDismiss: () -> Unit,
    onRun: (name: String?, ports: List<Pair<Int, Int>>, env: List<String>, cmd: List<String>?, restart: Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var restart by remember { mutableStateOf(false) }

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
            Text("Run $image", style = MaterialTheme.typography.headlineMedium)

            InsetGroup(footer = "Leave the name empty and the daemon picks one.") {
                GroupBody {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = ports, onValueChange = { ports = it },
                        label = { Text("Ports") }, placeholder = { Text("8080:80") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = env, onValueChange = { env = it },
                        label = { Text("Environment") }, placeholder = { Text("KEY=value") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = cmd, onValueChange = { cmd = it },
                        label = { Text("Command") }, singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            InsetGroup(footer = "Restarted containers come back on their own after the VM or the phone reboots.") {
                GroupRow(
                    title = "Restart automatically",
                    showChevron = false,
                    trailing = { Switch(
                            checked = restart,
                            onCheckedChange = { restart = it },
                            colors = appleSwitchColors(),
                        ) },
                )
            }

            FilledAction(
                label = "Run",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onRun(
                        name.trim().ifBlank { null },
                        parsePortPairs(ports),
                        env.split(',').map { it.trim() }.filter { it.contains('=') },
                        cmd.trim().split(' ').filter { it.isNotBlank() }.ifEmpty { null },
                        restart,
                    )
                },
            )
        }
    }
}
