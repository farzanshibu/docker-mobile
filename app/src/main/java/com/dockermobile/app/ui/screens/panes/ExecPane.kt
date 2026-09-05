package com.dockermobile.app.ui.screens.panes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.ExecSession
import com.dockermobile.app.ui.components.MinTouchTarget
import com.dockermobile.app.ui.components.MonoText
import com.dockermobile.app.ui.components.TapTag
import com.dockermobile.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

/**
 * Interactive `docker exec` shell over a hijacked TCP connection.
 * v1 keeps a simple line-oriented UX: output streams up, commands go down,
 * with Ctrl+C / Ctrl+D / Tab shortcuts.
 */
@Composable
fun ExecPane(containerId: String) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val session = remember(containerId) { graph.repo.newExecSession(containerId) }

    var input by remember { mutableStateOf("") }
    val text by session.text.collectAsState()
    val status by session.status.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(containerId) {
        session.start(scope)
        // Best-effort TTY resize for a typical phone width.
        session.resize(90, 30, scope)
    }
    DisposableEffect(containerId) {
        onDispose { session.close() }
    }

    val rendered = remember(text) { text.split('\n').takeLast(400) }

    LaunchedEffect(rendered.size, status) {
        if (rendered.isNotEmpty()) listState.scrollToItem(rendered.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(
            when (status) {
                com.dockermobile.app.docker.ExecStatus.IDLE -> "Idle"
                com.dockermobile.app.docker.ExecStatus.CONNECTING -> "Connecting…"
                com.dockermobile.app.docker.ExecStatus.CONNECTED -> "Connected"
                com.dockermobile.app.docker.ExecStatus.CLOSED -> "Closed — tap Reconnect"
                com.dockermobile.app.docker.ExecStatus.FAILED -> "Failed — tap Reconnect"
            },
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.labelSecondary,
        )
        Spacer(Modifier.height(6.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(rendered) { line -> MonoText(line.ifBlank { " " }) }
        }

        // Shortcut row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            listOf("Ctrl+C" to ExecSession.CTRL_C, "Ctrl+D" to ExecSession.CTRL_D, "Tab" to ExecSession.TAB)
                .forEach { (label, keys) ->
                    TapTag(label = label, onClick = { scope.launch { session.send(keys) } })
                }
            if (status != com.dockermobile.app.docker.ExecStatus.CONNECTED &&
                status != com.dockermobile.app.docker.ExecStatus.CONNECTING
            ) {
                TapTag(label = "Reconnect", onClick = { session.start(scope) })
            }
        }
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Type a command") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                modifier = Modifier.size(MinTouchTarget),
                onClick = {
                    val cmd = input
                    input = ""
                    scope.launch { session.send(cmd + "\n") }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
