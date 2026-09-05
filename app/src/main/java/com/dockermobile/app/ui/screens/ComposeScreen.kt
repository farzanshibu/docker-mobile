package com.dockermobile.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.ComposeEngine
import com.dockermobile.app.ui.components.ConfirmDialog
import com.dockermobile.app.ui.components.EmptyState
import com.dockermobile.app.ui.components.ErrorBanner
import com.dockermobile.app.ui.components.GroupBody
import com.dockermobile.app.ui.components.GroupRow
import com.dockermobile.app.ui.components.Hairline
import com.dockermobile.app.ui.components.InsetGroup
import com.dockermobile.app.ui.components.LargeTitleScaffold
import com.dockermobile.app.ui.components.MinTouchTarget
import com.dockermobile.app.ui.components.MonoText
import com.dockermobile.app.ui.components.TapTag
import com.dockermobile.app.ui.components.rememberHaptics
import com.dockermobile.app.ui.theme.AppTheme
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
@Composable
fun ComposeScreen(onOpenSettings: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
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

    LargeTitleScaffold(
        title = selected ?: "Compose",
        snackbarHost = { SnackbarHost(snackbar) },
        navigationIcon = {
            if (selected != null) {
                IconButton(
                    onClick = { loadProject(null) },
                    modifier = Modifier.size(MinTouchTarget),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Back to stacks",
                    )
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
    ) { padding ->
        if (selected == null) {
            StackList(
                modifier = Modifier.padding(padding),
                projects = projects,
                error = error,
                onDismissError = { graph.repo.lastError.value = null },
                pathOf = { graph.stacks.composeFile(it).absolutePath },
                onOpen = { loadProject(it) },
                onDelete = { deleteCandidate = it },
                onCreate = { showNew = true },
            )
        } else {
            StackEditor(
                modifier = Modifier.padding(padding),
                yaml = yaml,
                onYamlChange = { yaml = it },
                busy = busy,
                events = events,
                onUp = {
                    val project = selected ?: return@StackEditor
                    busy = true
                    haptics.success()
                    scope.launch {
                        graph.stacks.write(project, yaml)
                        graph.compose.up(project, yaml) { runEvent(it) }
                        busy = false
                        graph.appScope.launch { graph.repo.refresh() }
                    }
                },
                onDown = {
                    val project = selected ?: return@StackEditor
                    busy = true
                    haptics.warning()
                    scope.launch {
                        graph.compose.down(project) { runEvent(it) }
                        busy = false
                        graph.appScope.launch { graph.repo.refresh() }
                    }
                },
                onSave = {
                    selected?.let { graph.stacks.write(it, yaml) }
                    haptics.selection()
                    scope.launch { snackbar.showSnackbar("Saved ${selected ?: ""}") }
                },
                onDelete = { deleteCandidate = selected },
            )
        }
    }

    if (showNew) {
        NewStackDialog(
            onDismiss = { showNew = false },
            onCreate = { raw ->
                val normalized = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").ifBlank { "stack" }
                graph.stacks.write(normalized, TEMPLATE_YAML)
                projects = graph.stacks.list()
                loadProject(normalized)
                showNew = false
            },
        )
    }

    deleteCandidate?.let { p ->
        ConfirmDialog(
            title = "Delete “$p”?",
            body = "The compose file and its volumes folder are removed. Containers the stack started keep running.",
            confirmLabel = "Delete",
            onConfirm = {
                graph.stacks.delete(p)
                projects = graph.stacks.list()
                if (selected == p) loadProject(null)
            },
            onDismiss = { deleteCandidate = null },
        )
    }
}

@Composable
private fun StackList(
    modifier: Modifier,
    projects: List<String>,
    error: String?,
    onDismissError: () -> Unit,
    pathOf: (String) -> String,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (error != null) item { ErrorBanner(error, onDismissError) }
        if (projects.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.Description,
                    title = "No stacks yet",
                    message = "A stack is a compose.yaml the app runs for you — several containers brought up together.",
                    actionLabel = "New stack",
                    onAction = onCreate,
                )
            }
        } else {
            item {
                InsetGroup(footer = "Files live in the app's private storage and travel with the app.") {
                    projects.forEachIndexed { index, p ->
                        if (index > 0) Hairline()
                        GroupRow(
                            title = p,
                            subtitle = pathOf(p),
                            onClick = { onOpen(p) },
                            trailing = {
                                IconButton(
                                    onClick = { onDelete(p) },
                                    modifier = Modifier.size(MinTouchTarget),
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete $p",
                                        tint = AppTheme.colors.statusError,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StackEditor(
    modifier: Modifier,
    yaml: String,
    onYamlChange: (String) -> Unit,
    busy: Boolean,
    events: List<String>,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TapTag(label = "Up", icon = Icons.Filled.PlayArrow, onClick = onUp)
            TapTag(label = "Down", icon = Icons.Filled.Stop, onClick = onDown)
            TapTag(label = "Save", icon = Icons.Filled.Save, onClick = onSave)
            TapTag(
                label = "Delete",
                icon = Icons.Filled.Delete,
                tint = AppTheme.colors.statusError,
                onClick = onDelete,
            )
            if (busy) {
                Spacer(Modifier.width(4.dp))
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = yaml,
            onValueChange = onYamlChange,
            label = { Text("compose.yaml") },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "Activity",
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.labelSecondary,
            modifier = Modifier.padding(bottom = 7.dp),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.colors.elevated, MaterialTheme.shapes.medium),
        ) {
            if (events.isEmpty()) {
                Text(
                    "Nothing yet — Up and Down report progress here.",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.labelSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(events) { line -> MonoText(line) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewStackDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = AppTheme.colors.elevated,
        title = { Text("New stack", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(
                    "The name becomes the compose project prefix on every container it starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.labelSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("myapp") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Create", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
