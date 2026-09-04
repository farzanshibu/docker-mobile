package com.dockermobile.app.vm

import java.io.File

/**
 * Which engine actually drives the guest CPU.
 *
 *  TCG         QEMU's software emulator. Works on every arm64 device, no
 *              privileges needed. Slow (~5-15% of native) but universal.
 *
 *  KVM         Same QEMU binary, but the guest runs directly on the CPU
 *              through /dev/kvm. Near-native speed. Stock Android SELinux
 *              policy neverallows untrusted_app to open /dev/kvm, so this
 *              only lights up on rooted devices or custom ROMs that expose
 *              the node to apps (a popular one-line sepolicy patch).
 *
 *  AVF_CROSVM  Android Virtualization Framework: the platform's own
 *              VirtualizationService + crosvm on pKVM. Fastest and most
 *              battery-friendly, but NOT reachable from a normal APK —
 *              see [AvfBackend] and docs/AVF_BACKEND.md for what it takes.
 */
enum class Accelerator(val label: String, val speedHint: String) {
    TCG("QEMU TCG (software emulation)", "5–15% of native — works everywhere"),
    KVM("QEMU KVM", "near-native — requires /dev/kvm access"),
    AVF_CROSVM("AVF crosvm (pKVM)", "near-native — requires platform privileges"),
}

/**
 * Snapshot of everything the app can find out about virtualization support on
 * this device without special permissions. Pure detection: nothing here is
 * required for the TCG path, and a failed probe never blocks a boot.
 */
data class HypervisorReport(
    val sdkInt: Int,
    val kvmDeviceAccessible: Boolean,
    val avfProperties: List<Pair<String, String>>,
    val avfLikely: Boolean,
    val chosen: Accelerator,
    val notes: List<String>,
) {
    /** One-line summary for the UI, e.g. "QEMU TCG — no /dev/kvm on this device". */
    val summary: String
        get() = when (chosen) {
            Accelerator.KVM -> "QEMU KVM — /dev/kvm is accessible, boots will be near-native"
            Accelerator.AVF_CROSVM -> "AVF crosvm — platform VM services detected"
            Accelerator.TCG ->
                if (sdkInt >= 33 && avfLikely) "QEMU TCG — device has AVF but apps cannot use it (see docs/AVF_BACKEND.md)"
                else "QEMU TCG — software emulation (no /dev/kvm, no app-accessible AVF)"
        }
}

/**
 * Best-effort device capability probing. Every check is wrapped so that odd
 * devices / OEM skins can never crash the app: any failure just means
 * "assume the slow-but-universal TCG path".
 */
object Hypervisor {

    private val AVF_PROP_KEYS = listOf(
        "ro.boot.hypervisor.version",          // e.g. "pkvm-5.15-android13-8" on AVF builds
        "ro.boot.hypervisor.protected_vm.supported",
        "ro.boot.hypervisor.vm.supported",
        "ro.hardware.virtualization",          // "pkvm" / "1" on some implementations
    )

    /** Can this process actually open /dev/kvm? (root or sepolicy-patched ROM) */
    fun kvmAccessible(): Boolean = runCatching {
        val dev = File("/dev/kvm")
        dev.exists() && dev.canRead() && dev.canWrite()
    }.getOrDefault(false)

    /** Reads a list of AVF-related system properties via the getprop binary. */
    fun avfProperties(): List<Pair<String, String>> = AVF_PROP_KEYS.mapNotNull { key ->
        runCatching {
            val p = ProcessBuilder("getprop", key)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor()
            if (out.isNotBlank() && out != "null" && out != "0") key to out else null
        }.getOrNull()
    }

    /** Full probe. Cheap enough to call once per VM screen visit. */
    fun probe(): HypervisorReport {
        val props = avfProperties()
        val kvm = kvmAccessible()
        val avfLikely = props.isNotEmpty()

        // AVF is the ideal end state, but a stock APK cannot bind to
        // VirtualizationService (SELinux + no SDK API), so we never
        // auto-select it — AvfBackend.report() spells out the situation.
        // Only engines this process can actually drive are chosen here.
        val chosen = when {
            kvm -> Accelerator.KVM
            else -> Accelerator.TCG
        }

        val notes = buildList {
            if (kvm) add("KVM detected — the same Alpine/Docker guest will run near-natively. Nothing else to configure.")
            if (!kvm) add("No app-accessible /dev/kvm: falling back to TCG. Rooted device or a custom ROM with a /dev/kvm allowlist unlocks near-native speed with the same assets.")
            if (avfLikely) add("AVF system properties found — this ROM ships crosvm/pKVM. Talking to it needs a platform-signed build (docs/AVF_BACKEND.md).")
            if (!avfLikely && !kvm) add("This device exposes neither KVM nor AVF to apps; TCG is the only engine available.")
        }

        return HypervisorReport(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            kvmDeviceAccessible = kvm,
            avfProperties = props,
            avfLikely = avfLikely,
            chosen = chosen,
            notes = notes,
        )
    }
}
