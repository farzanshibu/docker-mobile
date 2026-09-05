package com.dockermobile.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.EndpointMode
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.ui.components.KeyValueRow
import com.dockermobile.app.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val settings by graph.settings.settings.collectAsState(initial = null)
    var remoteUrl by remember { mutableStateOf("") }
    var daemonPort by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("") }
    var loadedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loadedOnce) {
            remoteUrl = s.remoteBaseUrl
            daemonPort = s.daemonPort.toString()
            sshPort = s.sshPort.toString()
            loadedOnce = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { scope.launch { graph.repo.refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Test connection")
                    }
                },
            )
        },
    ) { padding ->
        val s = settings
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (s == null) {
                Text("Loading…")
                return@Column
            }

            SectionCard(title = "Docker daemon") {
                Text(
                    "Choose where the app should look for a Docker Engine API endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    FilterChip(
                        selected = s.mode == EndpointMode.EMBEDDED_VM,
                        onClick = { scope.launch { graph.settings.setMode(EndpointMode.EMBEDDED_VM) } },
                        label = { Text("Embedded VM") },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    FilterChip(
                        selected = s.mode == EndpointMode.REMOTE_TCP,
                        onClick = { scope.launch { graph.settings.setMode(EndpointMode.REMOTE_TCP) } },
                        label = { Text("Remote daemon (TCP)") },
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (s.mode == EndpointMode.REMOTE_TCP) {
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it },
                        label = { Text("Remote base URL") },
                        placeholder = { Text("http://192.168.1.10:2375") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        scope.launch {
                            graph.settings.setRemoteBaseUrl(remoteUrl)
                            snackbar.showSnackbar("Saved. Testing…")
                            graph.repo.refresh()
                        }
                    }) { Text("Save & test") }
                } else {
                    KeyValueRow("Daemon", "127.0.0.1:${s.daemonPort} (hostfwd → guest :2375)")
                    KeyValueRow("SSH", "127.0.0.1:${s.sshPort} (guest :22)")
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Home server (Wi-Fi access)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Expose published ports on Wi-Fi")
                        Text(
                            if (s.exposeOnLan) {
                                val lan = graph.vm.lanAddress()
                                if (lan != null) "On — reachable at http://$lan:<port> from other devices"
                                else "On — bound to 0.0.0.0 on every interface"
                            } else {
                                "Off — published ports stay on 127.0.0.1, phone only"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = s.exposeOnLan,
                        onCheckedChange = { on ->
                            scope.launch {
                                graph.settings.setExposeOnLan(on)
                                snackbar.showSnackbar(
                                    if (on) "Published ports will bind 0.0.0.0 — restart the VM to apply"
                                    else "Published ports will bind 127.0.0.1 — restart the VM to apply"
                                )
                            }
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start the VM when the phone boots")
                        Text(
                            if (s.startOnBoot) "On — the daemon comes back after a reboot"
                            else "Off — the VM must be started by hand after a reboot",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = s.startOnBoot,
                        onCheckedChange = { on ->
                            scope.launch {
                                graph.settings.setStartOnBoot(on)
                                snackbar.showSnackbar(
                                    if (on) "The VM will start on boot (open the app once after installing)"
                                    else "The VM will not start on boot"
                                )
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Only container ports you publish are shared. The Docker API and the " +
                        "guest SSH stay on 127.0.0.1 either way — they are unauthenticated and " +
                        "must never be put on the network. Anyone on this Wi-Fi can reach an " +
                        "exposed port, so treat it like any other LAN service.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Background runner") {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                var unrestricted by remember {
                    mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
                }
                KeyValueRow(
                    "Battery",
                    if (unrestricted) "Unrestricted ✓" else "Optimised — Android may throttle the VM",
                )
                KeyValueRow("Wi-Fi", "High-performance lock held while the VM runs")
                KeyValueRow("CPU", "Partial wake lock held while the VM runs")
                Spacer(Modifier.height(10.dp))
                if (!unrestricted) {
                    Text(
                        "Doze will suspend the VM once the screen has been off for a while, " +
                            "which drops any port you are serving. Allow unrestricted battery " +
                            "use to run this phone as a server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + context.packageName),
                                )
                            )
                        }.onFailure {
                            // Some OEM builds hide the direct request; fall back to the list.
                            runCatching {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                        }
                    }) { Text("Allow unrestricted battery use") }
                } else {
                    Button(onClick = {
                        unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
                    }) { Text("Re-check") }
                }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "Embedded VM ports") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = daemonPort,
                        onValueChange = { daemonPort = it },
                        label = { Text("Daemon port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = sshPort,
                        onValueChange = { sshPort = it },
                        label = { Text("SSH port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        graph.settings.setDaemonPort(daemonPort.toIntOrNull() ?: 23750)
                        graph.settings.setSshPort(sshPort.toIntOrNull() ?: 2222)
                        snackbar.showSnackbar("Ports saved — restart the VM to apply")
                    }
                }) { Text("Save ports") }
            }

            Spacer(Modifier.height(12.dp))

            SectionCard(title = "About") {
                Text(
                    "Docker Mobile 0.1.0",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "An independent, community client for container runtimes. This app is not " +
                        "affiliated with Docker Inc.; “Docker” is a trademark of Docker Inc. " +
                        "The embedded mode runs an Alpine Linux guest through QEMU (KVM when the " +
                        "device exposes it, TCG otherwise) inside the app sandbox and talks the " +
                        "standard Docker Engine API over 127.0.0.1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
