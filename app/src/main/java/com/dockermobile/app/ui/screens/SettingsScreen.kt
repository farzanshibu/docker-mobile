package com.dockermobile.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dockermobile.app.core.EndpointMode
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.ui.components.appleSwitchColors
import com.dockermobile.app.ui.components.FilledAction
import com.dockermobile.app.ui.components.GroupBody
import com.dockermobile.app.ui.components.GroupRow
import com.dockermobile.app.ui.components.Hairline
import com.dockermobile.app.ui.components.InsetGroup
import com.dockermobile.app.ui.components.KeyValueRow
import com.dockermobile.app.ui.components.MinTouchTarget
import com.dockermobile.app.ui.components.SegmentedControl
import com.dockermobile.app.ui.components.rememberHaptics
import com.dockermobile.app.ui.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = rememberHaptics()
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
        containerColor = AppTheme.colors.base,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBackIos,
                                contentDescription = "Back",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppTheme.colors.base,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.primary,
                        actionIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    actions = {
                        IconButton(onClick = { scope.launch { graph.repo.refresh() } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Test connection")
                        }
                    },
                )
                Hairline(startIndent = 0.dp)
            }
        },
    ) { padding ->
        val s = settings
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (s == null) {
                Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.labelSecondary,
                )
                return@Column
            }

            // ------------------------------------------------------- daemon
            InsetGroup(
                header = "Docker daemon",
                footer = "Embedded runs the bundled Alpine VM on this phone. Remote talks to an Engine API somewhere else on your network.",
            ) {
                GroupBody {
                    SegmentedControl(
                        items = listOf("Embedded VM", "Remote"),
                        selectedIndex = if (s.mode == EndpointMode.EMBEDDED_VM) 0 else 1,
                        onSelect = { index ->
                            scope.launch {
                                graph.settings.setMode(
                                    if (index == 0) EndpointMode.EMBEDDED_VM else EndpointMode.REMOTE_TCP
                                )
                            }
                        },
                    )
                    if (s.mode == EndpointMode.REMOTE_TCP) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = remoteUrl,
                            onValueChange = { remoteUrl = it },
                            label = { Text("Base URL") },
                            placeholder = { Text("http://192.168.1.10:2375") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        FilledAction(
                            label = "Save and test",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                haptics.selection()
                                scope.launch {
                                    graph.settings.setRemoteBaseUrl(remoteUrl)
                                    snackbar.showSnackbar("Saved — testing the connection")
                                    graph.repo.refresh()
                                }
                            },
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                        KeyValueRow("Daemon", "127.0.0.1:${s.daemonPort}")
                        KeyValueRow("SSH", "127.0.0.1:${s.sshPort}")
                    }
                }
            }

            // -------------------------------------------------- home server
            InsetGroup(
                header = "Home server",
                footer = "Only ports you publish are shared. The Docker API and the guest SSH stay on 127.0.0.1 either way — they are unauthenticated and must never go on the network. Anyone on this Wi-Fi can reach an exposed port.",
            ) {
                GroupRow(
                    title = "Expose ports on Wi-Fi",
                    subtitle = if (s.exposeOnLan) {
                        val lan = graph.vm.lanAddress()
                        if (lan != null) "Reachable at http://$lan:<port>"
                        else "Bound to 0.0.0.0 on every interface"
                    } else "Published ports stay on this phone",
                    showChevron = false,
                    trailing = {
                        Switch(
                            colors = appleSwitchColors(),
                            checked = s.exposeOnLan,
                            onCheckedChange = { on ->
                                haptics.selection()
                                scope.launch {
                                    graph.settings.setExposeOnLan(on)
                                    snackbar.showSnackbar(
                                        if (on) "Ports will bind 0.0.0.0 — restart the VM to apply"
                                        else "Ports will bind 127.0.0.1 — restart the VM to apply"
                                    )
                                }
                            },
                        )
                    },
                )
                Hairline()
                GroupRow(
                    title = "Start the VM at boot",
                    subtitle = if (s.startOnBoot) "The daemon comes back after a reboot"
                    else "Start the VM by hand after a reboot",
                    showChevron = false,
                    trailing = {
                        Switch(
                            colors = appleSwitchColors(),
                            checked = s.startOnBoot,
                            onCheckedChange = { on ->
                                haptics.selection()
                                scope.launch {
                                    graph.settings.setStartOnBoot(on)
                                    snackbar.showSnackbar(
                                        if (on) "The VM will start on boot — open the app once after installing"
                                        else "The VM will not start on boot"
                                    )
                                }
                            },
                        )
                    },
                )
            }

            // ---------------------------------------------- background duty
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            var unrestricted by remember {
                mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName))
            }
            InsetGroup(
                header = "Background",
                footer = if (unrestricted) null
                else "Doze suspends the VM once the screen has been off for a while, which drops every port you are serving.",
            ) {
                GroupBody {
                    KeyValueRow("Battery", if (unrestricted) "Unrestricted" else "Optimised")
                    KeyValueRow("Wi-Fi", "High-performance lock while running")
                    KeyValueRow("CPU", "Partial wake lock while running")
                    Spacer(Modifier.height(12.dp))
                    if (!unrestricted) {
                        FilledAction(
                            label = "Allow unrestricted battery use",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
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
                            },
                        )
                    } else {
                        FilledAction(
                            label = "Re-check",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
                            },
                        )
                    }
                }
            }

            // --------------------------------------------------------- ports
            InsetGroup(
                header = "Embedded VM ports",
                footer = "Host-side ports the app forwards into the guest. Restart the VM after changing them.",
            ) {
                GroupBody {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = daemonPort,
                            onValueChange = { daemonPort = it },
                            label = { Text("Daemon") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = sshPort,
                            onValueChange = { sshPort = it },
                            label = { Text("SSH") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    FilledAction(
                        label = "Save ports",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            haptics.selection()
                            scope.launch {
                                graph.settings.setDaemonPort(daemonPort.toIntOrNull() ?: 23750)
                                graph.settings.setSshPort(sshPort.toIntOrNull() ?: 2222)
                                snackbar.showSnackbar("Ports saved — restart the VM to apply")
                            }
                        },
                    )
                }
            }

            // --------------------------------------------------------- about
            InsetGroup(header = "About") {
                GroupBody {
                    KeyValueRow("Version", "0.1.0")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "An independent, community client for container runtimes. Not affiliated " +
                            "with Docker Inc.; “Docker” is a trademark of Docker Inc. The embedded " +
                            "mode runs an Alpine Linux guest through QEMU inside the app sandbox and " +
                            "speaks the standard Engine API over 127.0.0.1.",
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.colors.labelSecondary,
                    )
                }
            }
        }
    }
}
