package com.dockermobile.app.ui.screens.panes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
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
import com.dockermobile.app.core.Format
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.ContainerStats
import com.dockermobile.app.ui.components.ErrorBanner
import com.dockermobile.app.ui.components.KeyValueRow
import com.dockermobile.app.ui.components.SectionCard
import kotlinx.coroutines.delay

/** Polls /stats?stream=false every 2 s and renders CPU / memory bars. */
@Composable
fun StatsPane(containerId: String) {
    val graph = LocalGraph.current
    var stats by remember { mutableStateOf<ContainerStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(containerId) {
        while (true) {
            try {
                stats = graph.repo.withClient { it.containerStats(containerId) }
                error = null
            } catch (e: Exception) {
                error = e.message
            }
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(12.dp))
        ErrorBanner(error)

        val s = stats
        if (s == null) {
            Text(
                "Waiting for stats…",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        SectionCard(title = "CPU") {
            Text(
                String.format(java.util.Locale.US, "%.1f%%", s.cpuPercentSafe),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (s.cpuPercentSafe / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))

        SectionCard(title = "Memory") {
            Text(
                "${Format.humanBytes(s.memUsedBytes)} / ${Format.humanBytes(s.memLimitBytes)}" +
                    String.format(java.util.Locale.US, "  (%.1f%%)", s.memPercentSafe),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (s.memPercentSafe / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))

        SectionCard(title = "Network (cumulative)") {
            KeyValueRow("Received", Format.humanBytes(s.netRxBytes))
            KeyValueRow("Sent", Format.humanBytes(s.netTxBytes))
        }
    }
}
