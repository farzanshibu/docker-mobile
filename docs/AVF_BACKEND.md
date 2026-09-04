# AVF backend — running Docker on the platform hypervisor (crosvm / pKVM)

This document answers one question: *can the APK in this repo stop booting
Alpine through QEMU and instead use the Android Virtualization Framework —
the platform's own crosvm on pKVM — as the VM engine?*

```text
APK
 └── VM Controller            (this repo: VmController)
       └── Android AVF        (VirtualizationService — platform service)
              └── crosvm / pKVM            (platform VMM + protected hypervisor)
                     └── ARM64 Linux       (our same Alpine rootfs)
                            └── containerd / Docker
                                  ├── PostgreSQL
                                  ├── Redis
                                  └── Nginx
```

**TL;DR**

| Question | Answer |
|---|---|
| Is it technically possible? | **Yes** — crosvm can boot our Alpine/Docker rootfs with near-native speed. |
| Can a *normal* APK do it on a *stock* device? | **No.** AVF has no public SDK API, and SELinux blocks app access. |
| What is in this repo today? | Capability probing (`Hypervisor`, `AvfBackend`), a UI Hypervisor card, and a ready-to-use `vm_config.json` blueprint. |
| What is the fastest path to the diagram? | Path B below: a custom ROM that exposes `/dev/kvm` → the app switches to QEMU **KVM** automatically, same assets, near-native speed, zero code changes. |

---

## 1. Why a stock APK cannot call AVF

Three independent blockers, any one of which is fatal:

1. **No public API.** AVF's client interface is the AIDL service
   `android.system.virtualmachines.IVirtualizationService`, published by the
   `virtualizationservice` native daemon
   (AOSP: `packages/modules/Virtualization/`). None of these types exist in
   the public `android.jar` — they are platform-internal APIs, explicitly
   not for third-party apps.
2. **SELinux.** Only specific domains may talk to the service: `shell`
   (that is what the `adb shell vm run` tool uses), `system_server`, and a
   short allowlist of platform/privileged apps. `untrusted_app` — every
   app installed from a store or sideloaded — is denied by default.
3. **No fallback through crosvm itself.** You might think: "ship crosvm in
   my APK like I ship QEMU". That fails because crosvm is **KVM-only** (it
   has no TCG-style software fallback), and stock SELinux `neverallow`s
   `untrusted_app` from opening `/dev/kvm`.

Consequence: on a retail device with a retail ROM, the AVF step of the
diagram is reachable **only** by `adb shell vm run` (shell domain) — not by
our process. This is a deliberate platform security boundary, not something
a better workaround can bypass.

## 2. What the repo already does (works on stock devices)

- `vm/Hypervisor.kt` — permissionless probe:
  - `/dev/kvm` openable? (root / patched ROM)
  - AVF system properties present? (`ro.boot.hypervisor.version`,
    `ro.boot.hypervisor.protected_vm.supported`, `ro.hardware.virtualization`, …)
  - picks the engine the app can actually drive: **KVM → TCG**.
- `vm/AvfBackend.kt` — AVF-specific capability check
  (`ABSENT / PRESENT_BUT_INACCESSIBLE / ACCESSIBLE`, via a best-effort
  reflection lookup of the `android.system.virtualizationservice` binder)
  plus `writeBlueprint(...)` which generates the VM config of §5.
- VM tab → **Hypervisor card** — shows the probe result live on device.

None of this affects the TCG default; probing is wrapped and can never
break a boot.

## 3. Adoption path A — platform-privileged build (real AVF)

The faithful version of the diagram. Requirements:

- Device with **pKVM** (Tensor G2/G3/G4 Pixels, several Snapdragon 8 Gen 2/3
  flagships on Android 14+) — check with the commands in §6.
- A build you control: a **custom ROM** (e.g. LineageOS-based) where you can
  modify sepolicy, or an app that is **signed with the platform key** and
  preinstalled as privileged.

Integration checklist:

1. **Vendor the AIDL surface.** Copy the interface set from AOSP
   `packages/modules/Virtualization/virtualizationservice/aidl/`
   (`android/system/virtualmachines/*.aidl` — `IVirtualizationService`,
   `IVirtualMachine`, `IVirtualMachineCallback`, `VirtualMachineConfig`,
   `VirtualMachineState`, …) into `app/src/main/aidl/`. The build generates
   the stubs; because the parcel layout is defined by the AIDL, the app can
   then talk to the platform service even though the types are not in the
   SDK.
2. **Get the binder.** `ServiceManager.getService("android.system.virtualizationservice")`
   (reflection on stock builds; direct call in a platform build).
   `AvfBackend.capability()` already reports whether this binder is visible
   — `ACCESSIBLE` means the gate is open.
3. **Boot the VM.** Build a `VirtualMachineConfig` for an **unprotected**
   custom-VM (not Microdroid — see §5): our `vmlinuz-virt` kernel, our
   `initramfs-virt`, our `rootfs.img` as a writable virtio disk. This boots
   the *same Alpine + dockerd image* that QEMU boots today.
   `AvfBackend.writeBlueprint(...)` writes the equivalent standalone
   `vm_config.json`.
4. **Grant access in sepolicy** (custom ROM route). Something like:
   ```
   # <sepolicy>/private/untrusted_app.te  (or a dedicated app domain)
   allow untrusted_app virtualizationservice_service:service_manager find;
   binder_call(untrusted_app, virtualizationservice)
   ```
   The exact policy belongs to your ROM tree; the point is: one `find` +
   `binder_call` pair opens the service to the app domain.
5. **Console.** Map the VM console/log outputs to files (the config in §5
   does) and stream them the same way `SerialConsole` streams today.

## 4. Adoption path B — `/dev/kvm` ROM patch (the pragmatic 90 % win)

If you control the ROM (or the device is rooted), the cheapest way to make
the guest near-native is **not** AVF at all: expose KVM to apps, keep QEMU.

- Rooted device: QEMU under the app can open `/dev/kvm` when run via `su`
  context, or on many rooted setups the node is already chmod'd.
- Custom ROM: a one-line sepolicy change
  (`allow untrusted_app kvm_device:chr_file rw_file_perms;`) plus making
  `/dev/kvm` group-accessible is a widely used pattern.

The app needs **no changes**: `Hypervisor.kvmAccessible()` flips, the
Hypervisor card shows KVM, and `VmController` launches
`-accel kvm -cpu host` instead of `-accel tcg,thread=multi` on the next
start. Same kernel, same rootfs, same port-forwarding machinery. If the
exposed KVM turns out to be broken (half-patched ROM), the boot falls back
to TCG once and the failure note lands in `qemu.log` — no dead Start button.

## 5. The VM blueprint (same guest, platform hypervisor)

`AvfBackend.writeBlueprint(kernel, initrd, rootfs, outDir, ramMb, cpus)`
writes `<filesDir>/avf/vm_config.json`:

```json
{
  "name": "dockermobile",
  "protected": false,
  "memory_mib": 2048,
  "vcpus": 2,
  "kernel": "<filesDir>/vm/vmlinuz-virt",
  "initrd": "<filesDir>/vm/initramfs-virt",
  "params": "console=hvc0 root=/dev/vda rw modules=ext4 quiet",
  "disks": [
    { "image": { "path": "<filesDir>/vm/rootfs.img" }, "writable": true }
  ],
  "console": "<filesDir>/avf/avf-console.log",
  "log": "<filesDir>/avf/avf-crosvm.log"
}
```

Notes on the schema choices:

- `"protected": false` — an **unprotected** pVM. Protected VMs get memory
  isolation from the host but *lose virtio-net and direct networking*,
  which Docker cannot live without. Unprotected still runs under pKVM with
  near-native speed.
- No Microdroid, no payload APK — this is the "custom VM" configuration
  shape: kernel + initrd + params + raw disks.
- The serial console becomes `hvc0` (virtio console) instead of QEMU's
  `ttyAMA0`; the Alpine image already ships the needed console plumbing, so
  only the `console=` parameter changes.
- **The JSON schema tracks the AOSP release.** Field names above match
  recent `packages/modules/Virtualization`; diff against your target
  version's `VmConfig` before shipping.

### Testing it today, without any app changes

On a device where the shell domain can reach AVF (most AVF-shipping
devices: `adb shell vm list` should print an empty table rather than an
error), push the assets and run the VM from the shell:

```bash
adb push vm/vmlinuz-virt vm/initramfs-virt vm/rootfs.img /data/local/tmp/avf/
adb shell vm run /data/local/tmp/avf/vm_config.json   # generated by the app or by hand
adb shell vm list                                     # running CID
```

If the guest boots and `dockerd` comes up, the asset chain is proven for
the platform path — everything that remains is the privileged binder
plumbing of path A.

## 6. Device capability checklist

```bash
adb shell getprop ro.build.version.sdk          # AVF needs 33+ (Android 13)
adb shell getprop ro.boot.hypervisor.version    # e.g. "pkvm-5.15-..." on AVF builds
adb shell getprop ro.boot.hypervisor.protected_vm.supported
adb shell vm list                               # empty table = AVF reachable from shell
adb shell ls -l /dev/kvm                        # who owns the KVM node
```

In-app, the VM tab → Hypervisor card reports the same facts without adb.

## 7. Networking: the real porting cost

The QEMU backend leans on slirp `hostfwd`, and more importantly on
**runtime** `hostfwd_add` through the monitor socket — that is how
published container ports appear on `127.0.0.1` while the VM runs. There is
no crosvm equivalent of "add a port forward at runtime", so the AVF backend
needs a different data plane. Options, in rough order of preference:

1. **Static forward for the daemon only.** Forward a fixed guest port for
   dockerd at VM creation; container ports stay guest-internal and the UI's
   browser links are rewritten to go through an Engine-API-proxied URL or a
   fixed high-port range agreed at boot (e.g. guest publishes containers on
   `20000-20100`, which the config forwards statically).
2. **vsock.** crosvm/AVF expose vsock to guests; a tiny guest-side relay
   (`socat vsock-listen:2375,fork TCP:127.0.0.1:2375`) plus a host-side
   vsock→TCP bridge gives full connectivity without any slirp config.
   Most robust, slightly more code on the Android side.
3. **Tap networking** via a privileged helper — only worth it in a ROM you
   fully control.

The Docker-client half of the app (everything except the VM tab) is
network-agnostic already: it talks to `http://127.0.0.1:<port>` and would
work unchanged over any of the three transports.

## 8. Effort & payoff summary

| Path | Devices | Code left to write | Guest speed |
|---|---|---|---|
| C. QEMU TCG (today's default) | every arm64 | none | 5–15 % native |
| B. QEMU KVM (auto-detected, **implemented**) | rooted / ROM-exposed `/dev/kvm` | none | 80–95 % native |
| A. AVF crosvm/pKVM (blueprint + recipe ready) | AVF devices + platform-privileged build | AIDL vendoring, binder client, sepolicy, transport (§7) | 80–95 % native, best battery |

Recommendation: ship on B's rails, prototype A with `vm run` (§5) on a
Pixel-class device, and commit to A only when the ROM/signing story is
real — the UI and Docker layers never need to know which engine won.
