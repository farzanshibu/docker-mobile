package com.dockermobile.app.ui.screens.panes

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.ui.components.MonoText

/**
 * Live `docker logs -f`: streams via the Engine API, auto-scrolls while
 * "follow" is on, supports substring search.
 */
@Composable
fun LogsPane(containerId: String) {
    val graph = LocalGraph.current
    val repo = graph.repo

    val lines = remember(containerId) { mutableStateOf(listOf<String>()) }
    var query by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(containerId) {
        repo.withClient { it.followLogs(containerId, tail = 500) }
            .collect { chunk ->
                lines.value = (lines.value + chunk.split('\n').filter { it.isNotBlank() })
                    .takeLast(5000)
            }
    }

    val displayed = remember(lines.value, query) {
        if (query.isBlank()) lines.value
        else lines.value.filter { it.contains(query, ignoreCase = true) }
    }

    LaunchedEffect(displayed.size, follow) {
        if (follow && displayed.isNotEmpty()) {
            listState.scrollToItem((displayed.size - 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter logs…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { follow = !follow }) {
                Icon(
                    Icons.Filled.VerticalAlignBottom,
                    contentDescription = "Follow",
                    tint = if (follow) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${displayed.size} lines · follow ${if (follow) "on" else "off"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(displayed) { line ->
                MonoText(line, modifier = Modifier.fillMaxWidth())
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
