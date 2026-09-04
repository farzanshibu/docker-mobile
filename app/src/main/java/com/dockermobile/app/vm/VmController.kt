package com.dockermobile.app.vm

import android.app.Application
import com.dockermobile.app.core.AppSettings
import com.dockermobile.app.core.SettingsRepository
import com.dockermobile.app.docker.VmPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Owns the VM process that boots the Alpine Linux guest with Docker
 * preinstalled, supervises its lifecycle, exposes the serial console and
 * manages runtime port-forwarding through the QEMU monitor.
 *
 * Backend selection is automatic:
 *  - /dev/kvm openable (rooted device or sepolicy-patched ROM)  -> QEMU KVM,
 *    near-native, same assets; if the KVM launch dies before the guest is
 *    up, one automatic TCG retry keeps the boot from bricking.
 *  - otherwise                                                  -> QEMU TCG,
 *    software emulation, works on every arm64 device unprivileged.
 *  - AVF (crosvm/pKVM) is probed and surfaced in the UI, but a stock APK
 *    cannot drive it — see [AvfBackend] and docs/AVF_BACKEND.md.
 *
 * Everything runs unprivileged:
 *  - QEMU executes from <nativeLibraryDir>/libqemu_system_aarch64.so
 *    (binaries cannot be exec'd from app data on modern Android; nativeLibraryDir
 *    is the sanctioned location).
 *  - Guest networking is QEMU user-mode (slirp): outbound NAT for `docker pull`,
 *    inbound via hostfwd rules added at runtime for published container ports.
 */
class VmController(
    private val app: Application,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    val assets = VmAssetManager(app, settings)
    val monitor = QemuMonitor()
    val serial = SerialConsole()
    val avf = AvfBackend(app)

    private val _phase = MutableStateFlow<VmPhase>(VmPhase.Idle)
    val phase: StateFlow<VmPhase> = _phase

    private val _accelerator = MutableStateFlow(Accelerator.TCG)
    val accelerator: StateFlow<Accelerator> = _accelerator

    /** Set when a KVM boot attempt failed, forcing TCG for later attempts. */
    @Volatile private var kvmBlocked = false

    private val _bootLog = MutableStateFlow<List<String>>(emptyList())
    val bootLog: StateFlow<List<String>> = _bootLog

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines

    /** guestPort -> androidPort mapping currently active in the QEMU monitor. */
    private val _hostForwards = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val hostForwards: StateFlow<Map<Int, Int>> = _hostForwards

    @Volatile private var process: Process? = null
    private var serialJob: Job? = null
    private var logPollJob: Job? = null

    private val workDir: File get() = assets.vmDir
    private val monitorSocket: File get() = File(workDir, "qemu-monitor.sock")
    private val serialSocket: File get() = File(workDir, "qemu-serial.sock")
    private val qemuLog: File get() = File(workDir, "qemu.log")

    // ------------------------------------------------------------------ start

    suspend fun start() = startMutex.withLock {
        if (_phase.value is VmPhase.Running || _phase.value is VmPhase.Booting) return@withLock

        val s = settings.snapshot()
        _bootLog.value = emptyList()
        _consoleLines.value = emptyList()

        val report = assets.report()
        if (!report.allPresent) {
            _phase.value = VmPhase.Failed(missingMessage(report))
            return@withLock
        }

        val wantKvm = Hypervisor.kvmAccessible() && !kvmBlocked
        val outcome = runBoot(s, wantKvm)
        if (outcome.ok) {
            _accelerator.value = if (wantKvm) Accelerator.KVM else Accelerator.TCG
            _hostForwards.value = emptyMap()
            _phase.value = VmPhase.Running
            return@withLock
        }

        // One automatic TCG retry when a KVM launch died before the guest came
        // up — cheap insurance for ROMs whose /dev/kvm is half-exposed.
        if (wantKvm && outcome.diedEarly) {
            kvmBlocked = true
            _accelerator.value = Accelerator.TCG
            _bootLog.value = _bootLog.value +
                "[dockermobile] KVM launch failed (${outcome.message.take(120)}) — retrying with TCG"
            val retry = runBoot(s, useKvm = false)
            if (retry.ok) {
                _accelerator.value = Accelerator.TCG
                _hostForwards.value = emptyMap()
                _phase.value = VmPhase.Running
                return@withLock
            }
            _phase.value = VmPhase.Failed(retry.message)
            return@withLock
        }

        _phase.value = VmPhase.Failed(outcome.message)
    }

    private data class BootOutcome(
        val ok: Boolean,
        val message: String = "",
        val diedEarly: Boolean = false,
    )

    private suspend fun runBoot(s: AppSettings, useKvm: Boolean): BootOutcome {
        try {
            monitorSocket.delete()
            serialSocket.delete()
            qemuLog.delete()

            val args = qemuArgs(s, useKvm)
            val pb = ProcessBuilder(args)
                .directory(workDir)
                .redirectErrorStream(false)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(qemuLog))
                .redirectError(ProcessBuilder.Redirect.appendTo(qemuLog))

            // QEMU is spawned as a plain child process, so it gets the system
            // linker rather than the app's classloader namespace: its bundled
            // .so siblings are only found if nativeLibraryDir is on the path.
            pb.environment()["LD_LIBRARY_PATH"] = app.applicationInfo.nativeLibraryDir

            val proc = pb.start()
            process = proc

            serial.reset()
            startSerialCollector()
            startLogPoller()

            _phase.value = VmPhase.Booting(
                if (useKvm) "KVM boot — waiting for the Docker daemon…"
                else "Waiting for the Docker daemon…"
            )
            val ready = awaitDaemon(s.daemonPort, proc)
            if (!ready) {
                val alive = proc.isAlive
                val tail = logTail()
                stopInternal()
                val msg = buildString {
                    append("Docker daemon did not come up within 3 minutes")
                    if (!alive) append(" — QEMU exited early")
                    append(".\n\nLast QEMU output:\n$tail")
                }
                return BootOutcome(ok = false, message = msg, diedEarly = !alive && useKvm)
            }
            return BootOutcome(ok = true)
        } catch (e: Exception) {
            stopInternal()
            return BootOutcome(
                ok = false,
                message = "Failed to start the VM: ${e.message}",
                diedEarly = useKvm,
            )
        }
    }

    private fun qemuArgs(s: AppSettings, useKvm: Boolean): List<String> {
        val qemu = assets.qemuBinary
            ?: throw IllegalStateException("QEMU binary missing — see the VM tab for setup instructions.")
        val accel = if (useKvm) {
            listOf("-machine", "virt", "-accel", "kvm", "-cpu", "host")
        } else {
            listOf("-machine", "virt", "-cpu", "cortex-a57", "-accel", "tcg,thread=multi")
        }
        // QEMU's compiled-in datadir does not exist on Android, so point -L at
        // the blobs shipped next to the VM assets (virtio PCI devices refuse to
        // start without their option ROM).
        val dataDir = File(workDir, "qemu-data")
        val dataArgs = if (dataDir.isDirectory) listOf("-L", dataDir.absolutePath) else emptyList()

        return listOf(
            qemu.absolutePath,
            "-name", "dockermobile",
        ) + dataArgs + accel + listOf(
            "-smp", s.vmCpus.coerceAtMost(Runtime.getRuntime().availableProcessors()).toString(),
            "-m", s.vmRamMb.toString(),
            "-device", "virtio-rng-pci",
            "-kernel", assets.kernelFile.absolutePath,
            "-initrd", assets.initramfsFile.absolutePath,
            "-append", "console=ttyAMA0 root=/dev/vda rw modules=ext4 quiet",
            "-drive", "if=none,id=hd0,format=raw,file=${assets.rootfsFile.absolutePath}",
            "-device", "virtio-blk-pci,drive=hd0",
            "-netdev", "user,id=n0,hostfwd=tcp:127.0.0.1:${s.daemonPort}-:2375,hostfwd=tcp:127.0.0.1:${s.sshPort}-:22",
            "-device", "virtio-net-pci,netdev=n0",
            // server=on,wait=off: QEMU creates and listens on these sockets
            // instead of trying to connect to something already there.
            "-monitor", "unix:${monitorSocket.absolutePath},server=on,wait=off",
            "-serial", "unix:${serialSocket.absolutePath},server=on,wait=off",
            "-display", "none",
            "-no-reboot",
        )
    }

    private suspend fun awaitDaemon(androidPort: Int, proc: Process): Boolean {
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        val deadline = System.currentTimeMillis() + 3 * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) return false
            try {
                val resp = client.newCall(
                    Request.Builder().url("http://127.0.0.1:$androidPort/_ping").build()
                ).execute()
                resp.use { if (it.isSuccessful) return true }
            } catch (_: Exception) {
                // not up yet
            }
            delay(1500)
        }
        return false
    }

    // ------------------------------------------------------------------- stop

    suspend fun stop() = startMutex.withLock {
        if (_phase.value is VmPhase.Idle) return@withLock
        _phase.value = VmPhase.Stopping
        stopInternal()
        _phase.value = VmPhase.Idle
    }

    private fun stopInternal() {
        serialJob?.cancel()
        logPollJob?.cancel()
        serial.close()
        process?.let { p ->
            p.destroy()
            val dead = runCatching { p.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!dead) p.destroyForcibly()
        }
        process = null
        _hostForwards.value = emptyMap()
        monitorSocket.delete()
        serialSocket.delete()
    }

    fun isRunning(): Boolean = _phase.value is VmPhase.Running

    /** Full device capability probe (KVM / AVF), safe to call from the UI. */
    suspend fun hypervisorReport(): HypervisorReport = withContextIO { Hypervisor.probe() }

    // ------------------------------------------------------------ background

    private fun startSerialCollector() {
        serialJob?.cancel()
        serialJob = scope.launch {
            val buffer = ArrayDeque<String>()
            serial.readLoop(serialSocket.absolutePath) { chunk ->
                synchronized(buffer) {
                    chunk.split('\n', '\r').forEach { line ->
                        if (line.isNotBlank()) {
                            buffer.addLast(line)
                            if (buffer.size > 300) buffer.removeFirst()
                        }
                    }
                }
                _consoleLines.value = synchronized(buffer) { buffer.toList() }
            }
        }
    }

    private fun startLogPoller() {
        logPollJob?.cancel()
        logPollJob = scope.launch {
            while (isActive) {
                _bootLog.value = try {
                    qemuLog.takeIf { it.exists() }
                        ?.readLines()
                        ?.takeLast(100)
                        ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                delay(1000)
            }
        }
    }

    fun sendConsole(line: String) {
        serial.send("$line\n")
    }

    fun logTail(lines: Int = 60): String =
        runCatching { qemuLog.takeIf { it.exists() }?.readLines()?.takeLast(lines)?.joinToString("\n") }
            .getOrDefault("").orEmpty()

    // --------------------------------------------------------- port forwards

    /**
     * Ensures every published guest port has a hostfwd rule. Port numbers are
     * kept 1:1 where possible; if the Android side is taken, the next free port
     * is picked and recorded in [hostForwards].
     */
    suspend fun syncHostForwards(guestPorts: Set<Int>) {
        if (_phase.value !is VmPhase.Running) return
        val current = _hostForwards.value.toMutableMap()

        // Loopback keeps a published port private to the phone; 0.0.0.0 serves
        // it to the Wi-Fi network. The daemon and ssh forwards built into the
        // QEMU command line are never widened by this — only container ports.
        val bind = if (settings.snapshot().exposeOnLan) {
            QemuMonitor.ALL_INTERFACES
        } else {
            QemuMonitor.LOOPBACK
        }

        // Drop forwards that no longer have a matching published port (best-effort).
        current.keys.filterNot { it in guestPorts }.forEach { guest ->
            monitor.removeHostForward(monitorSocket.absolutePath, current[guest] ?: guest, bind)
            current.remove(guest)
        }

        guestPorts.forEach { guest ->
            if (current.containsKey(guest)) return@forEach
            val host = findFreePort(guest)
            try {
                withContextIO { monitor.addHostForward(monitorSocket.absolutePath, host, guest, bind) }
                current[guest] = host
            } catch (_: Exception) {
                // Monitor hiccup: skip this port this round; next refresh retries.
            }
        }
        _hostForwards.value = current
    }

    /** The address published container ports are currently bound to. */
    suspend fun publishBindAddress(): String =
        if (settings.snapshot().exposeOnLan) QemuMonitor.ALL_INTERFACES else QemuMonitor.LOOPBACK

    /** This device's Wi-Fi/LAN address, for the "reachable at" hint in the UI. */
    fun lanAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun findFreePort(preferred: Int): Int {
        var candidate = preferred
        while (candidate < 65536) {
            try {
                ServerSocket(candidate).use { return candidate }
            } catch (_: Exception) {
                candidate++
            }
        }
        throw IllegalStateException("No free port available")
    }

    private suspend fun <T> withContextIO(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private fun missingMessage(r: AssetReport): String {
        val missing = buildList {
            if (r.kernel == null) add("Alpine kernel (vmlinuz-virt)")
            if (r.initramfs == null) add("Alpine initramfs (initramfs-virt)")
            if (r.rootfs == null) add("Root filesystem (rootfs.img)")
            if (r.qemuBinary == null) add("QEMU binary (libqemu_system_aarch64.so in jniLibs)")
        }
        return "Missing VM assets: ${missing.joinToString(", ")}.\nImport or download them from the VM tab."
    }
}
