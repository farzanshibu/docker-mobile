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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dockermobile.app.core.LocalGraph
import com.dockermobile.app.docker.VmPhase
import com.dockermobile.app.ui.components.AppleSlider
import com.dockermobile.app.ui.components.FilledAction
import com.dockermobile.app.ui.components.GroupBody
import com.dockermobile.app.ui.components.Hairline
import com.dockermobile.app.ui.components.InsetGroup
import com.dockermobile.app.ui.components.LargeTitleScaffold
import com.dockermobile.app.ui.components.MinTouchTarget
import com.dockermobile.app.ui.components.MonoText
import com.dockermobile.app.ui.components.OutlineAction
import com.dockermobile.app.ui.components.StatusBadge
import com.dockermobile.app.ui.components.TapTag
import com.dockermobile.app.ui.components.rememberHaptics
import com.dockermobile.app.ui.theme.AppTheme
import com.dockermobile.app.vm.AssetKind
import com.dockermobile.app.vm.HypervisorReport
import com.dockermobile.app.vm.VmService
import kotlinx.coroutines.launch

@Composable
fun VmScreen(onOpenSettings: () -> Unit) {
    val graph = LocalGraph.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = rememberHaptics()

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
        haptics.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            VmService.start(context)
        }
    }

    LargeTitleScaffold(
        title = "Virtual machine",
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "An Alpine Linux guest runs the Docker daemon inside the app sandbox — no root. " +
                    "The fastest available engine is picked at boot: KVM where the device exposes it, " +
                    "software emulation otherwise.",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.labelSecondary,
            )

            // ------------------------------------------------------- runtime
            InsetGroup(header = "Runtime") {
                GroupBody {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusBadge(
                            when (phase) {
                                is VmPhase.Idle -> "stopped"
                                is VmPhase.Booting -> "booting"
                                is VmPhase.Running -> "running"
                                is VmPhase.Stopping -> "stopping"
                                is VmPhase.Failed -> "failed"
                            }
                        )
                        Spacer(Modifier.weight(1f))
                        if (phase is VmPhase.Booting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Docker on 127.0.0.1:${settings?.daemonPort ?: 23750}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    (phase as? VmPhase.Booting)?.let { boot ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            boot.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTheme.colors.labelSecondary,
                        )
                    }
                    (phase as? VmPhase.Failed)?.let { failed ->
                        Spacer(Modifier.height(8.dp))
                        MonoText(failed.message.take(600), color = AppTheme.colors.statusError)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledAction(
                            label = "Start",
                            onClick = { startVm() },
                            enabled = phase is VmPhase.Idle || phase is VmPhase.Failed,
                            modifier = Modifier.weight(1f),
                        )
                        OutlineAction(
                            label = "Stop",
                            onClick = { haptics.warning(); VmService.stop(context) },
                            enabled = phase is VmPhase.Running || phase is VmPhase.Booting,
                            destructive = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ----------------------------------------------------- resources
            InsetGroup(header = "Resources", footer = "Changes apply the next time the VM starts.") {
                GroupBody {
                    val s = settings
                    if (s == null) {
                        Text(
                            "Loading…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppTheme.colors.labelSecondary,
                        )
                    } else {
                        Row {
                            Text("Memory", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                "${s.vmRamMb} MB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.labelSecondary,
                            )
                        }
                        AppleSlider(
                            value = s.vmRamMb.toFloat(),
                            onValueChange = { scope.launch { graph.settings.setVmRamMb((it / 128).toInt() * 128) } },
                            valueRange = 512f..4096f,
                        )
                        Spacer(Modifier.height(4.dp))
                        val cores = Runtime.getRuntime().availableProcessors()
                        Row {
                            Text("Processors", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(
                                "${s.vmCpus} of $cores",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.labelSecondary,
                            )
                        }
                        AppleSlider(
                            value = s.vmCpus.toFloat(),
                            onValueChange = { scope.launch { graph.settings.setVmCpus(it.toInt()) } },
                            valueRange = 1f..cores.toFloat().coerceAtLeast(1f),
                            steps = (cores - 2).coerceAtLeast(0),
                        )
                    }
                }
            }

            // ---------------------------------------------------- hypervisor
            InsetGroup(
                header = "Hypervisor",
                footer = hyp?.summary,
            ) {
                val report = hyp
                if (report == null) {
                    GroupBody {
                        Text(
                            "Probing device capabilities…",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.labelSecondary,
                        )
                    }
                } else {
                    CapabilityRow(
                        "Software emulation (TCG)",
                        true,
                        "Always available, every arm64 device",
                    )
                    Hairline(startIndent = 52.dp)
                    CapabilityRow(
                        "KVM via /dev/kvm",
                        report.kvmDeviceAccessible,
                        if (report.kvmDeviceAccessible) "Near-native speed, selected automatically at boot"
                        else "Needs root, or a ROM that exposes /dev/kvm to apps",
                    )
                    Hairline(startIndent = 52.dp)
                    CapabilityRow(
                        "Android Virtualization Framework",
                        report.avfLikely,
                        if (report.avfLikely) "Present, but stock SELinux blocks app access — see docs/AVF_BACKEND.md"
                        else "Not exposed by this device",
                    )
                    Hairline(startIndent = 16.dp)
                    GroupBody {
                        Text(
                            "${if (phase is VmPhase.Running) "Using" else "Will use"}: ${accelerator.label}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // -------------------------------------------------------- assets
            InsetGroup(
                header = "VM assets",
                footer = if (!assetReport.allPresent) "The VM can't start until all three files are present."
                else "tools/build-vm-image.sh builds these — see docs/.",
            ) {
                AssetRow("Kernel", assetReport.kernel != null, "vmlinuz-virt · ~13 MB") { pickKernel.launch("*/*") }
                Hairline(startIndent = 52.dp)
                AssetRow("Initramfs", assetReport.initramfs != null, "initramfs-virt · ~47 MB") { pickInitramfs.launch("*/*") }
                Hairline(startIndent = 52.dp)
                AssetRow("Root filesystem", assetReport.rootfs != null, "rootfs.img · ~1.5 GB, Docker preinstalled") { pickRootfs.launch("*/*") }
                Hairline(startIndent = 52.dp)
                AssetRow("QEMU", assetReport.qemuBinary != null, "Bundled in the APK", onImport = null)
                Hairline(startIndent = 16.dp)
                GroupBody {
                    OutlinedTextField(
                        value = mirror,
                        onValueChange = { mirror = it },
                        label = { Text("Asset mirror URL") },
                        placeholder = { Text("https://…/releases/download/v1") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledAction(
                            label = "Download all",
                            enabled = mirror.startsWith("http"),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    busyMessage = "Downloading…"
                                    runCatching {
                                        graph.settings.setAssetMirror(mirror)
                                        graph.assets.downloadAll(mirror) { msg, frac ->
                                            busyMessage = if (frac != null) "$msg ${(frac * 100).toInt()}%" else msg
                                        }
                                    }
                                        .onSuccess { busyMessage = it }
                                        .onFailure { busyMessage = "Download failed: ${it.message}" }
                                    assetReport = graph.assets.report()
                                }
                            },
                        )
                        OutlineAction(
                            label = "Rescan",
                            icon = Icons.Filled.Refresh,
                            onClick = { assetReport = graph.assets.report() },
                        )
                    }
                    busyMessage?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppTheme.colors.labelSecondary,
                        )
                    }
                }
            }

            // ------------------------------------------------------- console
            InsetGroup(header = "Serial console", footer = "Guest tty on ttyAMA0 — log in as root for a recovery shell.") {
                GroupBody {
                    MonoText(
                        consoleLines.takeLast(60).joinToString("\n")
                            .ifBlank { "Output appears here while the VM boots." },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        color = AppTheme.colors.labelSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = consoleInput,
                            onValueChange = { consoleInput = it },
                            placeholder = { Text("Send a line") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TapTag(
                            label = "Send",
                            onClick = {
                                graph.vm.sendConsole(consoleInput)
                                consoleInput = ""
                            },
                        )
                    }
                }
            }

            // --------------------------------------------------- diagnostics
            InsetGroup(header = "Diagnostics") {
                GroupBody {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TapTag(
                            label = if (showQemuLog) "Hide qemu.log" else "Show qemu.log",
                            onClick = { showQemuLog = !showQemuLog },
                        )
                    }
                    if (showQemuLog) {
                        Spacer(Modifier.height(8.dp))
                        MonoText(
                            bootLog.joinToString("\n").ifBlank { "(no output yet)" },
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            color = AppTheme.colors.labelSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Capability rows pair the tint with a filled/hollow glyph, so availability
 * survives a colour-blind reading of the screen.
 */
@Composable
private fun CapabilityRow(label: String, ok: Boolean, hint: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (ok) "Available" else "Unavailable",
            tint = if (ok) AppTheme.colors.statusRunning else AppTheme.colors.labelTertiary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.labelSecondary)
        }
    }
}

@Composable
private fun AssetRow(label: String, present: Boolean, hint: String, onImport: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (present) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (present) "Present" else "Missing",
            tint = if (present) AppTheme.colors.statusRunning else AppTheme.colors.statusWarn,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.labelSecondary)
        }
        if (!present && onImport != null) {
            TextButton(onClick = onImport, modifier = Modifier.height(MinTouchTarget)) {
                Text("Import", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
