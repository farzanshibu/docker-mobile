package com.dockermobile.app.ui.screens.panes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.ui.components.MonoText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString

/** Pretty-printed `docker inspect` (one-shot fetch with retry button via tab re-entry). */
@Composable
fun InspectPane(containerId: String) {
    val graph = LocalGraph.current
    var pretty by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(containerId) {
        try {
            val obj: JsonObject = graph.repo.withClient { it.inspectContainer(containerId) }
            val formatter = Json { prettyPrint = true; prettyPrintIndent = "  " }
            pretty = formatter.encodeToString(obj)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(10.dp))
        error?.let {
            Text("Failed to inspect: $it", color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val body = pretty ?: "Fetching inspect data…"
        MonoText(body, modifier = Modifier.fillMaxSize().padding(bottom = 24.dp))
    }
}
