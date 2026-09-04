package com.dockermobile.app.vm

import android.app.Application
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * Everything around the Android Virtualization Framework (AVF) backend.
 *
 * AVF is the platform stack behind the "AVF → crosvm/pKVM" step of the
 * target architecture:
 *
 *     APK → VM Controller → AVF → crosvm / pKVM → ARM64 Linux → Docker
 *
 * AVF ships crosvm as the platform VMM, guarded by pKVM on supported SoCs
 * (Tensor G2/G3, some Snapdragon 8 Gen 2/3 builds, Android 13+). It is the
 * *right* long-term engine for this app — near-native, power-efficient, and
 * it removes the bundled QEMU binary entirely — but Android deliberately
 * does not expose it through the public SDK:
 *
 *  1. There is no `android.system.virtualmachines` API in the SDK. The
 *     IVirtualizationService AIDL interface is platform-internal.
 *  2. SELinux only lets specific domains (shell via the `vm` tool,
 *     system_server, a handful of privileged apps) talk to
 *     `android.system.virtualizationservice`.
 *  3. Even shipping crosvm yourself would not help: crosvm is KVM-only (no
 *     TCG fallback), and stock SELinux neverallows untrusted_app to open
 *     /dev/kvm.
 *
 * So on a stock device this backend can only *report*, which is exactly what
 * it does. On a custom ROM / platform-signed build it becomes the real
 * engine — docs/AVF_BACKEND.md has the complete integration recipe (which
 * AIDL files to vendor, the vm_config.json schema, the sepolicy allowlist,
 * and the networking story).
 */
class AvfBackend(@Suppress("unused") private val app: Application) {
    // app is retained for the platform-privileged binder client (path A in
    // docs/AVF_BACKEND.md); the probe itself deliberately needs no context.


    enum class Level { ABSENT, PRESENT_BUT_INACCESSIBLE, ACCESSIBLE }

    data class Capability(
        val level: Level,
        val sdkInt: Int,
        val properties: List<Pair<String, String>>,
        val binderReachable: Boolean,
    ) {
        val headline: String
            get() = when (level) {
                Level.ACCESSIBLE ->
                    "AVF reachable — this build may bind to VirtualizationService"
                Level.PRESENT_BUT_INACCESSIBLE ->
                    "AVF present on this ROM, but SELinux blocks app access on stock Android"
                Level.ABSENT ->
                    if (sdkInt >= 33) "No AVF system properties found on this device"
                    else "AVF requires Android 13+ (this device is API $sdkInt)"
            }
    }

    /** Name of the platform service, as registered with ServiceManager. */
    private val serviceName = "android.system.virtualizationservice"

    /**
     * Probes without touching anything: API level, AVF sysprops, and whether
     * the VirtualizationService binder is even visible from this process.
     * All reflection is best-effort — hidden-API enforcement itself blocks
     * ServiceManager on stock builds, which is a meaningful signal, so a
     * reflection failure maps to PRESENT_BUT_INACCESSIBLE rather than an
     * error.
     */
    fun capability(): Capability {
        val props = Hypervisor.avfProperties()
        val sdk = Build.VERSION.SDK_INT
        if (sdk < 33 && props.isEmpty()) {
            return Capability(Level.ABSENT, sdk, props, binderReachable = false)
        }
        val binder = binderReachable()
        val level = when {
            binder -> Level.ACCESSIBLE
            props.isNotEmpty() || sdk >= 33 -> Level.PRESENT_BUT_INACCESSIBLE
            else -> Level.ABSENT
        }
        return Capability(level, sdk, props, binder)
    }

    /**
     * Reflection check for the platform binder. Returns true when the app is
     * platform-privileged, when the ROM relaxed hidden-API enforcement
     * (`adb shell settings put global hidden_api_policy 1` on userdebug
     * builds), or when a custom ROM patched sepolicy for this app domain.
     */
    private fun binderReachable(): Boolean = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        val getService = sm.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, serviceName) as? IBinder
        binder != null && binder.pingBinder()
    }.getOrDefault(false)

    /**
     * Writes the AVF VM configuration that boots the *same* Alpine + Docker
     * assets as an unprotected pVM with a custom kernel — no Microdroid, no
     * payload APK, just our rootfs image. The file lands in
     * <filesDir>/avf/vm_config.json, ready for a privileged build, or for
     * manual testing today via `adb shell vm run <path>`.
     *
     * Field names track AOSP packages/modules/Virtualization (VmConfig JSON);
     * re-verify against your target Android release before shipping.
     */
    fun writeBlueprint(
        kernel: File,
        initrd: File,
        rootfs: File,
        outDir: File,
        ramMb: Int = 2048,
        cpus: Int = 2,
    ): File {
        outDir.mkdirs()
        val config = File(outDir, "vm_config.json")
        val json = """
            {
              "name": "dockermobile",
              "protected": false,
              "memory_mib": $ramMb,
              "vcpus": $cpus,
              "kernel": "${kernel.absolutePath}",
              "initrd": "${initrd.absolutePath}",
              "params": "console=hvc0 root=/dev/vda rw modules=ext4 quiet",
              "disks": [
                { "image": { "path": "${rootfs.absolutePath}" }, "writable": true }
              ],
              "console": "${File(outDir, "avf-console.log").absolutePath}",
              "log": "${File(outDir, "avf-crosvm.log").absolutePath}"
            }
        """.trimIndent()
        config.writeText(json)
        return config
    }

    /**
     * The explicit answer when someone forces the AVF path on a stock
     * build. Throwing (instead of silently degrading) makes the platform
     * constraint visible instead of mysterious.
     */
    fun requireCapable() {
        val cap = capability()
        if (cap.level != Level.ACCESSIBLE) {
            throw UnsupportedOperationException(
                """
                The AVF backend cannot be driven from a normal APK on this device.
                Status: ${cap.headline}

                Adoption paths (docs/AVF_BACKEND.md):
                  A) custom ROM / platform-signed privileged app  -> real crosvm+pKVM
                  B) custom ROM exposing /dev/kvm                 -> QEMU KVM (auto-detected)
                  C) stock device                                 -> QEMU TCG (default)
                """.trimIndent()
            )
        }
    }
}
