# Docker Mobile

**Docker Desktop for Android** — a standalone, unprivileged Android app that runs a
real Docker daemon inside an embedded Alpine Linux VM and gives you a
Docker-Dock-style UI to manage it.

```text
Android APK  (Kotlin + Jetpack Compose)
   │
   ├── Docker-like UI
   │     ├── Containers (dashboard, start/stop/restart/rm)
   │     ├── Images     (list, pull with progress, run, delete)
   │     ├── Compose    (compose.yaml editor + up/down)
   │     ├── Logs       (live follow + search)
   │     ├── Terminal   (interactive docker exec over hijacked TCP)
   │     └── Stats      (CPU / memory bars)
   │
   ├── VM controller (Kotlin)
   │     ├── QEMU aarch64 (KVM when the device exposes it, TCG otherwise)
   │     ├── runtime port-forwarding via the QEMU monitor
   │     ├── hypervisor capability probe (KVM / AVF detection)
   │     └── serial console viewer
   │
   ▼
Alpine Linux guest  (rootfs.img)
   ├── OpenRC boot, virtio disk/net
   ├── dockerd listening on tcp://0.0.0.0:2375
   └── sshd on :22 (recovery shell)
   │
   ▼
Docker Hub / any OCI registry  (nginx, redis, postgres, n8n, …)
```

Verified end to end on an arm64 Android 16 emulator: QEMU 11.0.3 (TCG) → Alpine
3.20 / kernel 6.6.142-0-virt → **Docker Engine 26.1.5** on `overlay2`, running
`hello-world` and `nginx` with a published port.

- Tap a container → **Logs / Exec / Stats / Inspect** tabs
- Tap a port chip → opens `http://127.0.0.1:<port>` in the browser
- `+ Deploy` → one-tap presets (`nginx`, `redis`, `postgres`, `n8n`, …) or a
  fully custom `docker run`-style form

---

## Install

Grab the signed APK from [Releases](https://github.com/Project-Xman/docker-mobile/releases)
(arm64-v8a, Android 8.0+), verify it if you like, and sideload it:

```bash
curl -LO https://github.com/Project-Xman/docker-mobile/releases/download/v0.1.0/docker-mobile-0.1.0.apk
curl -LO https://github.com/Project-Xman/docker-mobile/releases/download/v0.1.0/docker-mobile-0.1.0.apk.sha256
shasum -a 256 -c docker-mobile-0.1.0.apk.sha256
adb install docker-mobile-0.1.0.apk
```

Then open the app → **VM tab → Asset mirror URL** → paste the download base URL
of a VM-assets release, e.g.

```
https://github.com/Project-Xman/docker-mobile/releases/download/vm-assets-v1
```

→ **Download all** → **Start VM**. The APK ships QEMU; the Alpine image is a
separate ~146 MB download because it expands to a multi-GB disk image.

Both artifacts are built by GitHub Actions
(`.github/workflows/release-apk.yml` and `vm-assets.yml`); the APK is signed in
CI from repository secrets and `apksigner`-verified before publishing.

---

## Important: what this repository contains

This is the **complete Android Studio project** (full source). It is *not* a
prebuilt APK. Two heavy artifacts are produced outside the repo and are
`.gitignore`d, because one of them is 1.5 GB:

1. **The QEMU binary and its shared libraries** →
   `app/src/main/jniLibs/arm64-v8a/`, built by `tools/bundle_qemu.py`.
2. **The Alpine VM assets** — `vmlinuz-virt`, `initramfs-virt`, `rootfs.img`
   (Docker preinstalled) — built by `tools/build-vm-image.sh`, then served to
   the phone or imported via the file picker (VM tab → *Import* / *Download all*).

> **Work without the VM at all:** flip Settings → *Remote daemon (TCP)* and the
> exact same UI manages any Docker host on your network (NAS, VPS, desktop) —
> point it at `http://<host>:2375`.

## Requirements

| Thing | Version |
|-------|---------|
| Android Studio | Ladybug 2024.2+ (AGP 8.7.3, **Gradle 8.9** — AGP 8.7 does not accept Gradle 9) |
| JDK | 17 (Studio-bundled is fine) |
| Min Android | 8.0 (API 26), **arm64-v8a device for the embedded VM** |
| Target Android | 15 (API 35) |
| Build host | Linux or macOS with Docker, to build the VM image |

### 1. Build the VM assets

```bash
./tools/build-vm-image.sh ./vm-assets
# smaller image for an emulator or a low-storage phone:
ROOTFS_SIZE_MB=1536 ./tools/build-vm-image.sh ./vm-assets
```

This builds an Alpine rootfs with Docker preinstalled **and the matching
kernel + initramfs generated from that same rootfs**, so kernel, modules and
initramfs never drift apart. It needs Docker but not `--privileged`: the image
is populated with `mkfs.ext4 -d`, so it also works on Docker Desktop for Mac.

Change `ROOT_PW` before building anything you will actually put on a network —
the default guest root password is `dockermobile`.

**Don't want to build it?** Run the **Build VM assets** GitHub Action
(`.github/workflows/vm-assets.yml`). It builds on an arm64 runner and publishes
`vmlinuz-virt`, `initramfs-virt` and `rootfs.img.gz` to a Release, which you can
paste straight into the app as an asset mirror URL.

### 2. Get a QEMU binary into the APK

`qemu-system-aarch64` has to be an Android/bionic aarch64 build, and it has to
live in `jniLibs` named `lib*.so` so the installer unpacks it somewhere the app
is allowed to `exec` from.

```bash
# assemble QEMU + its full runtime library closure from the Termux packages
python3 tools/select_qemu_libs.py app/src/main/jniLibs/arm64-v8a
```

`bundle_qemu.py` downloads the Termux `qemu-system-aarch64-headless` package and
its dependencies; `select_qemu_libs.py` then walks `DT_NEEDED` from the QEMU
binary, keeps only what is genuinely reachable (~69 libraries, ~68 MB rather
than the ~260 the apt graph pulls in), and rewrites every versioned SONAME
(`libz.so.1`, `libgio-2.0.so.0`, …) to an Android-legal `lib*.so` name — patching
`DT_NEEDED`/`DT_SONAME` in place, since Android only extracts `lib*.so` from
`lib/<abi>/`.

You also need QEMU's option ROM for the virtio PCI devices. Put `efi-virtio.rom`
(from the `qemu-common` package, printed by `bundle_qemu.py`) in the app's
`files/vm/qemu-data/` and `VmController` will pass `-L` at it.

If you already have a working binary from elsewhere, `tools/fetch-qemu-aarch64.sh`
just copies it into place.

### 3. Build and run

```bash
./gradlew assembleDebug        # or press Run in Android Studio
```

Then open the VM tab:
- asset mirror: `http://<build-host>:8000` (or the GitHub Release URL) → **Download all**
- **Start VM**, wait for the daemon ping, then Containers → Deploy → nginx

First boot under TCG takes a couple of minutes. Image pulls are the slow part.
Subsequent container operations are snappy — the UI talks HTTP to the daemon,
not to the emulator.

## Running the phone as a home server

The VM tab boots a Docker host; these three settings turn it into something you
can actually leave running.

| Setting | What it does |
|---|---|
| **Expose published ports on Wi-Fi** | Binds published container ports on `0.0.0.0` instead of `127.0.0.1`, so other devices on the LAN can reach them. Off by default. |
| **Start the VM when the phone boots** | A `BOOT_COMPLETED` receiver restarts the VM after a reboot, so nobody has to open the app. Off by default. |
| **Allow unrestricted battery use** | Exempts the app from Doze, which otherwise suspends the VM once the screen has been off for a while and drops whatever you are serving. |

While the VM runs, `VmService` holds a foreground notification, a
`PARTIAL_WAKE_LOCK` (CPU) and a **high-performance Wi-Fi lock** — without the
latter the radio drops into power save with the screen off and connections to
your published ports stall.

Tick **Restart automatically** in the Deploy dialog (on by default) so
containers come back with `restart: always` after a VM or phone restart.

Practical notes:

- Android only delivers `BOOT_COMPLETED` to apps that have been launched at
  least once since install, and never to force-stopped ones. The first start
  is always manual.
- Give the phone a DHCP reservation on your router, or its address will move.
- Force-stopping the app kills QEMU, and so does reinstalling the APK.

## Feature matrix

| Feature | Status |
|---|---|
| Dashboard with status dots, ports, quick actions | ✅ |
| Image list / pull with per-layer progress / delete | ✅ |
| Live logs (TTY-aware stream demuxer, follow, filter) | ✅ |
| Interactive exec terminal (raw hijacked TCP, Ctrl+C/D, Tab) | ✅ |
| Per-container stats (CPU%, mem, net) | ✅ |
| compose.yaml editor + up/down (mini Compose engine) | ✅ |
| Quick-run presets + custom run form + restart policy | ✅ |
| Runtime port publishing (QEMU monitor `hostfwd_add`) | ✅ |
| LAN exposure for published ports (home-server mode) | ✅ |
| Auto-start the VM on boot | ✅ |
| Foreground service + wake lock + Wi-Fi lock | ✅ |
| Remote daemon mode (TCP) reuses the entire UI | ✅ |
| KVM acceleration — auto-detected on rooted / patched-ROM devices | ✅ |
| CI that builds and publishes VM assets | ✅ |
| AVF backend (platform crosvm/pKVM) — blueprint + recipe ready | needs platform-privileged build, see [`docs/AVF_BACKEND.md`](docs/AVF_BACKEND.md) |
| QMP instead of HMP, live RAM/CPU hotplug | roadmap |
| TLS to remote daemon / SSH tunnels | roadmap |

## Architecture in one page

- **UI** — Kotlin + Jetpack Compose on an Apple HIG-derived design system:
  semantic light/dark palette (`#1D63ED` accent, one colour per state), a
  17pt-based type scale, inset grouped lists and collapsing large titles.
  Single activity, tab bar with 4 tabs + container detail with 4 panes.
- **Docker client** — pure OkHttp against the Engine API; `JsonElement` parsing
  (no codegen, tolerant of daemon versions). Logs/events/pull are streamed via
  okio `BufferedSource` with an 8-byte frame demuxer that auto-detects TTY vs
  multiplexed streams. Exec is a hand-rolled HTTP-upgrade on `java.net.Socket`
  (`101 UPGRADED` → raw duplex).
- **VM subsystem** — `VmController` builds the QEMU command line, supervises
  the process, tails `qemu.log`, streams the serial console over a
  `LocalSocket`, and syncs published container ports by sending
  `hostfwd_add tcp:<bind>:<H>-:<G>` to the monitor socket at runtime. QEMU is
  spawned with `LD_LIBRARY_PATH` pointed at `nativeLibraryDir` (a child process
  gets the system linker, not the app's classloader namespace) and `-L` pointed
  at the option-ROM directory.
- **Mini-Compose** — SnakeYAML → dependency-ordered pull/create/start with
  `com.docker.compose.project` labels so `down` works by label.
- **State** — singleton managers (`DockerRepo`, `VmController`) expose
  `StateFlow`s; screens collect them. 5 s polling + Docker events watcher keep
  the dashboard fresh.

Full details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Security notes

- By default the guest daemon is reachable **only** on the phone's
  `127.0.0.1`, via a QEMU hostfwd rule. QEMU user-mode networking (slirp) is
  NAT-only and has no inbound path except the rules the app creates.
- **The Docker API and the guest sshd stay on `127.0.0.1` no matter what.**
  The home-server toggle widens *published container ports* only. An
  unauthenticated Engine API is root-equivalent inside the VM and must never be
  put on a network.
- Android does not isolate loopback per app, so any other app on the same phone
  can reach `127.0.0.1:23750`. That is inherent to this design.
- With LAN exposure on, a published port is reachable by anything on the same
  Wi-Fi, with no authentication in front of it. Treat it like any other service
  you put on your network.
- All VM state lives in the app's private storage (`filesDir/vm`); uninstalling
  wipes the "phone Docker".
- The rootfs is built by *your* `build-vm-image.sh` run — change `ROOT_PW`
  before distributing images.
- Remote-TCP mode is plaintext v1; put a TLS proxy (or SSH tunnel) in front of
  an untrusted network.

## Limitations (honest ones)

- TCG software emulation ≈ 5–15 % native speed. Fine for nginx/redis/postgres;
  heavy builds belong on a real host (remote mode). Rooted devices or custom
  ROMs that expose `/dev/kvm` get near-native speed automatically — see the
  Hypervisor card on the VM tab.
- The guest resolves DNS against public servers (1.1.1.1 / 8.8.8.8), not the
  slirp resolver at 10.0.2.3: slirp forwards to the host's `/etc/resolv.conf`,
  which does not exist on Android, so every lookup would time out and
  `docker pull` could never work.
- `hostfwd_remove` support depends on the QEMU build; stopped containers may
  leave a dead forward until VM restart (harmless).
- The terminal is line-oriented with ANSI stripping — not a full VT100/xterm.
- Android kills QEMU if you force-stop the app; the foreground service +
  wakelock keep it alive under normal use.
- AVF (Android Virtualization Framework) is detected and reported, but a
  normal APK cannot drive it on stock Android — the honest breakdown and the
  three adoption paths live in [`docs/AVF_BACKEND.md`](docs/AVF_BACKEND.md).
- An Android **emulator** cannot be reached from other devices on your Wi-Fi
  (it sits behind the emulator's own NAT). Home-server mode needs a real phone.

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Docker Inc.; "Docker" is a
trademark of Docker Inc. The QEMU binary and libraries assembled by
`tools/bundle_qemu.py` are GPL-licensed and are **not** redistributed by this
repository — they are fetched from Termux at build time.
