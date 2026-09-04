package com.dockermobile.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.ComposeEngine
import com.dockermobile.app.ui.components.ErrorBanner
import com.dockermobile.app.ui.components.MonoText
import com.dockermobile.app.ui.components.SectionCard
import kotlinx.coroutines.launch

private const val TEMPLATE_YAML = """services:
  web:
    image: nginx
    ports:
      - "8080:80"

  redis:
    image: redis
"""

/**
 * compose.yaml editor + mini stack orchestrator (up/down), mirroring the
 * classic `services: web/redis` example.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(onOpenSettings: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var projects by remember { mutableStateOf(graph.stacks.list()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var yaml by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<String?>(null) }
    val events = remember { mutableStateListOf<String>() }

    val error by graph.repo.lastError.collectAsState()

    fun loadProject(name: String?) {
        selected = name
        yaml = name?.let { graph.stacks.read(it) } ?: ""
    }

    fun runEvent(ev: ComposeEngine.Ev) {
        when (ev) {
            is ComposeEngine.Ev.Info -> events.add(ev.text)
            is ComposeEngine.Ev.Pull -> events.add(ev.text)
            is ComposeEngine.Ev.Done -> events.add(if (ev.ok) "OK — ${ev.text}" else "FAIL — ${ev.text}")
        }
        if (events.size > 300) events.removeAt(0)
    }

    LaunchedEffect(Unit) { graph.repo.startWatching() }
    LaunchedEffect(Unit) { graph.repo.messages.collect { snackbar.showSnackbar(it.take(140)) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(selected ?: "Compose stacks") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = { loadProject(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showNew = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New stack")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        if (selected == null) {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                ErrorBanner(error)
                Spacer(Modifier.height(8.dp))
                if (projects.isEmpty()) {
                    Text(
                        "No stacks yet. Tap + to create compose.yaml for your first stack.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 40.dp),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(projects) { p ->
                            SectionCard(Modifier) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            graph.stacks.composeFile(p).absolutePath,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    IconButton(onClick = { loadProject(p) }) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Open")
                                    }
                                    IconButton(onClick = { deleteCandidate = p }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    AssistChip(
                        onClick = {
                            busy = true
                            val project = selected ?: return@AssistChip
                            scope.launch {
                                graph.stacks.write(project, yaml)
                                graph.compose.up(project, yaml) { runEvent(it) }
                                busy = false
                                graph.appScope.launch { graph.repo.refresh() }
                            }
                        },
                        label = { Text("UP") },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    AssistChip(
                        onClick = {
                            busy = true
                            val project = selected ?: return@AssistChip
                            scope.launch {
                                graph.compose.down(project) { runEvent(it) }
                                busy = false
                                graph.appScope.launch { graph.repo.refresh() }
                            }
                        },
                        label = { Text("DOWN") },
                        leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    AssistChip(
                        onClick = {
                            selected?.let { graph.stacks.write(it, yaml) }
                            scope.launch { snackbar.showSnackbar("Saved ${selected ?: ""}") }
                        },
                        label = { Text("SAVE") },
                        leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    AssistChip(
                        onClick = { deleteCandidate = selected },
                        label = { Text("DELETE") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    )
                    if (busy) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.padding(6.dp).height(24.dp).width(24.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = yaml,
                    onValueChange = { yaml = it },
                    label = { Text("compose.yaml") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(10.dp))
                Text("Activity", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(events) { line -> MonoText(line) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New stack") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Stack name (e.g. myapp)") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").ifBlank { "stack" }
                    graph.stacks.write(normalized, TEMPLATE_YAML)
                    projects = graph.stacks.list()
                    loadProject(normalized)
                    showNew = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }

    deleteCandidate?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete stack '$p'?") },
            text = { Text("The compose file and its volumes folder will be removed. Containers keep running.") },
            confirmButton = {
                TextButton(onClick = {
                    graph.stacks.delete(p)
                    projects = graph.stacks.list()
                    if (selected == p) loadProject(null)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
        )
    }
}
