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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.Format
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.DockerRepo
import com.dockermobile.app.docker.PullEvent
import com.dockermobile.app.docker.UiImage
import com.dockermobile.app.ui.components.ErrorBanner
import com.dockermobile.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen(onOpenSettings: () -> Unit) {
    val graph = LocalGraph.current
    val repo = graph.repo
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showPull by remember { mutableStateOf(false) }
    var runTarget by remember { mutableStateOf<UiImage?>(null) }
    var deleteTarget by remember { mutableStateOf<UiImage?>(null) }

    val images by repo.images.collectAsState()
    val error by repo.lastError.collectAsState()

    LaunchedEffect(Unit) { repo.startWatching() }
    LaunchedEffect(Unit) { repo.messages.collect { snackbar.showSnackbar(it.take(140)) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Images") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { showPull = true }) {
                        Icon(Icons.Filled.Download, contentDescription = "Pull")
                    }
                    IconButton(onClick = { scope.launch { repo.refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(images, key = { it.id }) { img ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { runTarget = img }) {
                                Text(
                                    img.displayTag,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${Format.shortId(img.id)} · ${Format.humanBytes(img.sizeBytes)} · ${Format.relativeAge(img.createdUnix.toString())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { runTarget = img }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Run")
                            }
                            IconButton(onClick = { deleteTarget = img }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showPull) {
        PullDialog(
            onDismiss = { showPull = false },
            onPull = { ref -> repo.pullImage(ref, onEvent = {}, onDone = {}) ; showPull = false },
        )
    }

    runTarget?.let { img ->
        RunFromImageDialog(
            image = img.displayTag.substringBefore('@'),
            onDismiss = { runTarget = null },
            onRun = { name, ports, env, cmd, restart ->
                repo.runImage(img.displayTag.substringBefore('@'), name, ports, env, cmd, restart)
                runTarget = null
            },
        )
    }

    deleteTarget?.let { img ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete image?") },
            text = { Text("This removes ${img.displayTag} from the daemon.") },
            confirmButton = {
                TextButton(onClick = { repo.deleteImage(img.id, img.displayTag); deleteTarget = null }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PullDialog(onDismiss: () -> Unit, onPull: (String) -> Unit) {
    val graph = LocalGraph.current
    var ref by remember { mutableStateOf("nginx:latest") }
    var statusText by remember { mutableStateOf<String?>(null) }
    var fraction by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Pull image") },
        text = {
            Column {
                OutlinedTextField(
                    value = ref,
                    onValueChange = { ref = it },
                    label = { Text("Reference (name:tag)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("nginx:latest", "redis:8", "postgres:17", "alpine:latest", "busybox:latest")
                        .forEach { preset ->
                            AssistChip(onClick = { ref = preset }, label = { Text(preset) })
                        }
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { fraction ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        statusText ?: "Pulling…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            } else {
                TextButton(onClick = {
                    busy = true
                    var layersDone = mutableStateOf(mapOf<String, Pair<Long, Long>>())
                    graph.repo.pullImage(
                        ref.trim(),
                        onEvent = { ev: PullEvent ->
                            if (ev.current != null && ev.total != null && ev.id != null) {
                                layersDone.value = layersDone.value + (ev.id to (ev.current to ev.total))
                                val sum = layersDone.value.values.fold(0L) { a, b -> a + b.first }
                                val tot = layersDone.value.values.fold(0L) { a, b -> a + b.second }
                                if (tot > 0) fraction = (sum.toFloat() / tot).coerceIn(0f, 1f)
                            }
                            statusText = ev.status
                        },
                        onDone = {
                            busy = false
                            onDismiss()
                        },
                    )
                }) { Text("Pull") }
            }
        },
        dismissButton = {
            if (!busy) TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun RunFromImageDialog(
    image: String,
    onDismiss: () -> Unit,
    onRun: (name: String?, ports: List<Pair<Int, Int>>, env: List<String>, cmd: List<String>?, restart: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var ports by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var restart by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run $image") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = ports, onValueChange = { ports = it },
                    label = { Text("Ports (8080:80, …)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = env, onValueChange = { env = it },
                    label = { Text("Env (KEY=VAL, comma-sep)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it },
                    label = { Text("Command (optional, space-sep)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = restart, onCheckedChange = { restart = it })
                    Text("Restart policy: always", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pairs = ports.split(',').mapNotNull { entry ->
                    val parts = entry.trim().split(':')
                    if (parts.size == 2) {
                        parts[0].toIntOrNull()?.let { h -> parts[1].toIntOrNull()?.let { c -> h to c } }
                    } else null
                }
                onRun(
                    name.trim().ifBlank { null },
                    pairs,
                    env.split(',').map { it.trim() }.filter { it.contains('=') },
                    cmd.trim().split(' ').filter { it.isNotBlank() }.ifEmpty { null },
                    restart,
                )
            }) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
