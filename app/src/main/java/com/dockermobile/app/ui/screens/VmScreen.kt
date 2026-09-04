package com.dockermobile.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.VmPhase
import com.dockermobile.app.ui.components.MonoText
import com.dockermobile.app.ui.components.SectionCard
import com.dockermobile.app.ui.components.StatusDot
import com.dockermobile.app.vm.AssetKind
import com.dockermobile.app.vm.HypervisorReport
import com.dockermobile.app.vm.VmService
import kotlinx.coroutines.launch

@Composable
fun VmScreen(onOpenSettings: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val phase by graph.vm.phase.collectAsState()
    val accelerator by graph.vm.accelerator.collectAsState()
    val consoleLines by graph.vm.consoleLines.collectAsState()
    val bootLog by graph.vm.bootLog.collectAsState()
    val settings by graph.settings.settings.collectAsState(initial = null)

    var assetReport by remember { mutableStateOf(graph.assets.report()) }
    var hyp by remember { mutableStateOf<HypervisorReport?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var mirror by remember { mutableStateOf("") }
    var showQemuLog by remember { mutableStateOf(false) }
    var consoleInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        mirror = graph.assets.snapshotSettings().assetMirror
        assetReport = graph.assets.report()
        hyp = graph.vm.hypervisorReport()
    }

    val pickKernel = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busyMessage = "Importing kernel…"
            runCatching { graph.assets.import(uri, AssetKind.KERNEL) }
                .onSuccess { busyMessage = it }
                .onFailure { busyMessage = "Import failed: ${it.message}" }
            assetReport = graph.assets.report()
        }
    }
    val pickInitramfs = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busyMessage = "Importing initramfs…"
            runCatching { graph.assets.import(uri, AssetKind.INITRAMFS) }
                .onSuccess { busyMessage = it }
                .onFailure { busyMessage = "Import failed: ${it.message}" }
            assetReport = graph.assets.report()
        }
    }
    val pickRootfs = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busyMessage = "Importing root filesystem (this can take a minute)…"
            runCatching { graph.assets.import(uri, AssetKind.ROOTFS) }
                .onSuccess { busyMessage = it }
                .onFailure { busyMessage = "Import failed: ${it.message}" }
            assetReport = graph.assets.report()
        }
    }

    val requestNotifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) VmService.start(context)
    }

    fun startVm() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            VmService.start(context)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Embedded VM", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
        }
        Text(
            "A real Alpine Linux VM runs containerd + Docker inside your app sandbox. " +
                "No root required — the app picks the fastest engine it can: KVM when the " +
                "device exposes it, software emulation (TCG) otherwise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- runtime card
        SectionCard(title = "Runtime") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stateLabel = when (phase) {
                    is VmPhase.Idle -> "stopped"
                    is VmPhase.Booting -> "booting"
                    is VmPhase.Running -> "running"
                    is VmPhase.Stopping -> "stopping"
                    is VmPhase.Failed -> "failed"
                }
                StatusDot(
                    when (phase) {
                        is VmPhase.Running -> "running"
                        is VmPhase.Booting, is VmPhase.Stopping -> "paused"
                        else -> "exited"
                    }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stateLabel.uppercase() + " — Docker on 127.0.0.1:${settings?.daemonPort ?: 23750}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (phase is VmPhase.Booting) {
                    CircularProgressIndicator(Modifier.width(22.dp).height(22.dp))
                }
            }
            (phase as? VmPhase.Booting)?.let { boot ->
                Spacer(Modifier.height(6.dp))
                Text(boot.message, style = MaterialTheme.typography.bodySmall)
            }
            (phase as? VmPhase.Failed)?.let { failed ->
                Spacer(Modifier.height(6.dp))
                MonoText(failed.message.take(600), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Button(
                    onClick = { startVm() },
                    enabled = phase is VmPhase.Idle || phase is VmPhase.Failed,
                    modifier = Modifier.weight(1f),
                ) { Text("Start VM") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { VmService.stop(context) },
                    enabled = phase is VmPhase.Running || phase is VmPhase.Booting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text("Stop") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- resources card
        SectionCard(title = "Resources") {
            val s = settings
            if (s == null) {
                Text("Loading settings…")
            } else {
                Text("Memory: ${s.vmRamMb} MB", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = s.vmRamMb.toFloat(),
                    onValueChange = { scope.launch { graph.settings.setVmRamMb((it / 128).toInt() * 128) } },
                    valueRange = 512f..4096f,
                )
                Text("vCPUs: ${s.vmCpus} of ${Runtime.getRuntime().availableProcessors()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = s.vmCpus.toFloat(),
                    onValueChange = { scope.launch { graph.settings.setVmCpus(it.toInt()) } },
                    valueRange = 1f..Runtime.getRuntime().availableProcessors().toFloat().coerceAtLeast(1f),
                    steps = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(0),
                )
                Text(
                    "Changes apply on the next VM start.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- hypervisor card
        SectionCard(title = "Hypervisor") {
            val report = hyp
            if (report == null) {
                Text("Probing device capabilities…", style = MaterialTheme.typography.bodySmall)
            } else {
                HypRow("QEMU TCG — software emulation", true, "always available, every arm64 device")
                HypRow(
                    "QEMU KVM — /dev/kvm",
                    report.kvmDeviceAccessible,
                    if (report.kvmDeviceAccessible) "near-native speed, auto-selected at boot"
                    else "needs root or a ROM that exposes /dev/kvm to apps",
                )
                HypRow(
                    "AVF — crosvm / pKVM",
                    report.avfLikely,
                    if (report.avfLikely) "ROM ships AVF, but stock SELinux blocks app access — docs/AVF_BACKEND.md"
                    else "not exposed by this device/ROM",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This VM ${if (phase is VmPhase.Running) "is using" else "will use"}: ${accelerator.label}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    report.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- assets card
        SectionCard(title = "VM assets") {
            AssetRow("Alpine kernel (vmlinuz-virt)", assetReport.kernel != null, "~13 MB") { pickKernel.launch("*/*") }
            AssetRow("Alpine initramfs (initramfs-virt)", assetReport.initramfs != null, "~47 MB") { pickInitramfs.launch("*/*") }
            AssetRow("Root filesystem (rootfs.img)", assetReport.rootfs != null, "~1.5 GB (Docker preinstalled)") { pickRootfs.launch("*/*") }
            AssetRow("QEMU binary (libqemu_system_aarch64.so)", assetReport.qemuBinary != null, "bundled via jniLibs — see tools/", onImport = null)

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = mirror,
                onValueChange = { mirror = it },
                label = { Text("Asset mirror URL (directory with the 3 files)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = {
                        scope.launch {
                            busyMessage = "Downloading…"
                            runCatching {
                                graph.settings.setAssetMirror(mirror)
                                graph.assets.downloadAll(mirror) { msg, frac ->
                                    busyMessage = if (frac != null) {
                                        "$msg ${(frac * 100).toInt()}%"
                                    } else msg
                                }
                            }
                                .onSuccess { busyMessage = it }
                                .onFailure { busyMessage = "Download failed: ${it.message}" }
                            assetReport = graph.assets.report()
                        }
                    },
                    enabled = mirror.startsWith("http"),
                ) { Text("Download all") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { assetReport = graph.assets.report() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Text("Rescan")
                }
            }
            busyMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onOpenSettings) {
                Text("Where do these files come from? See docs/ — tools/build-vm-image.sh builds them.")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- console card
        SectionCard(title = "Serial console (ttyAMA0)") {
            val recent = consoleLines.takeLast(60).joinToString("\n")
            MonoText(
                recent.ifBlank { "Console will stream here while the VM boots." },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = consoleInput,
                    onValueChange = { consoleInput = it },
                    label = { Text("Send to console (login: root)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {
                        graph.vm.sendConsole(consoleInput)
                        consoleInput = ""
                    },
                    label = { Text("Send") },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- diagnostics
        SectionCard(title = "QEMU diagnostics") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { showQemuLog = !showQemuLog },
                    label = { Text(if (showQemuLog) "Hide qemu.log" else "Show qemu.log") },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    monitorNote(graph, assetReport),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showQemuLog) {
                Spacer(Modifier.height(8.dp))
                MonoText(
                    bootLog.joinToString("\n").ifBlank { "(no output yet)" },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun monitorNote(graph: com.dockermobile.app.core.AppGraph, report: com.dockermobile.app.vm.AssetReport): String =
    when {
        !report.allPresent -> "Assets incomplete — the VM cannot start yet."
        else -> "Monitor/serial sockets live under the app's private files dir."
    }

@Composable
private fun HypRow(label: String, ok: Boolean, hint: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = if (ok) "available" else "unavailable",
            tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AssetRow(label: String, present: Boolean, hint: String, onImport: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            if (present) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = if (present) "present" else "missing",
            tint = if (present) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!present && onImport != null) {
            TextButton(onClick = onImport) { Text("Import") }
        }
    }
}
