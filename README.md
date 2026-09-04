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

And the screen you saw in the mockup is real:

```text
┌─────────────────────────────┐
│ Docker Mobile               │
├─────────────────────────────┤
│ ● nginx       :8080→80  ▶ ⏸ │
│ ● redis       :6379→6379    │
│ ○ postgres    :5432→5432    │
│                             │
│        [ + Deploy ]         │
└─────────────────────────────┘
```

- Tap a container → **Logs / Exec / Stats / Inspect** tabs
- Tap a port chip → opens `http://127.0.0.1:<port>` in the browser
- `+ Deploy` → one-tap presets (`nginx`, `redis`, `postgres`, `n8n`, …) or a
  fully custom `docker run`-style form

---

## Important: what this repository contains

This is the **complete Android Studio project** (full source). It is *not* a
prebuilt APK. You open it in Android Studio, press Run, and get an installable
APK. Two heavy artifacts are produced outside the repo:

1. **The QEMU binary** — see `tools/fetch-qemu-aarch64.sh` (Termux/NDK/static
   build options in the header comment).
2. **The Alpine VM assets** — `vmlinuz-virt`, `initramfs-virt`, `rootfs.img`
   (Docker preinstalled) — built by `tools/build-vm-image.sh` on any
   Linux/macOS host with Docker, then served to the phone or imported via the
   file picker inside the app (VM tab → *Import* / *Download all*).

> **Work without the VM at all:** flip Settings → *Remote daemon (TCP)* and the
> exact same UI manages any Docker host on your network (NAS, VPS, desktop) —
> point it at `http://<host>:2375`.

## Requirements

| Thing | Version |
|-------|---------|
| Android Studio | Ladybug 2024.2+ (AGP 8.7.3) |
| JDK | 17 (Studio-bundled is fine) |
| Min Android | 8.0 (API 26), **arm64-v8a device for the embedded VM** |
| Target Android | 15 (API 35) |

### Build & run

```bash
# 1. open the folder in Android Studio; sync Gradle.
#    (only gradle-wrapper.properties is committed — if Studio reports a missing
#     wrapper jar, accept its one-click "create wrapper" fix, or run:
#       gradle wrapper --gradle-version 8.9 )
#
# 2. build VM assets on a Linux/macOS machine with Docker:
./tools/build-vm-image.sh ./vm-assets
cd vm-assets && python3 -m http.server 8000

# 3. put a qemu-system-aarch64 binary in place (see tools/fetch-qemu-aarch64.sh)
./tools/fetch-qemu-aarch64.sh ~/Downloads/qemu-system-aarch64

# 4. install the app on an arm64 phone/emulator, open the VM tab:
#      - asset mirror: http://<build-host>:8000  → Download all
#      - Start VM
#    …wait for the daemon ping, then: Containers → Deploy → nginx
```

First boot under TCG takes a few minutes (Alpine is tiny); image pulls are the
slow part (e.g. nginx ≈ 1–3 min depending on device). Subsequent container
operations are snappy — the UI talks HTTP to the daemon, not the emulator.

## Feature matrix

| Feature | Status |
|---|---|
| Dashboard with status dots, ports, quick actions | ✅ |
| Image list / pull with per-layer progress / delete | ✅ |
| Live logs (TTY-aware stream demuxer, follow, filter) | ✅ |
| Interactive exec terminal (raw hijacked TCP, Ctrl+C/D, Tab) | ✅ |
| Per-container stats (CPU%, mem, net) | ✅ |
| compose.yaml editor + up/down (mini Compose engine) | ✅ |
| Quick-run presets + custom run form | ✅ |
| Runtime port publishing (QEMU monitor `hostfwd_add`) | ✅ |
| Foreground service + notification keeps VM alive | ✅ |
| Remote daemon mode (TCP) reuses the entire UI | ✅ |
| KVM acceleration — auto-detected on rooted / patched-ROM devices | ✅ |
| AVF backend (platform crosvm/pKVM) — blueprint + recipe ready | needs platform-privileged build, see [`docs/AVF_BACKEND.md`](docs/AVF_BACKEND.md) |
| TLS to remote daemon / SSH tunnels | roadmap |

## Architecture in one page

- **UI** — Kotlin + Jetpack Compose + Material 3, Docker-dark palette
  (`#0B1220` bg, `#1D63ED` primary, green/amber/red status dots). Single
  activity, bottom-nav with 4 tabs + container detail with 4 panes.
- **Docker client** — pure OkHttp against the Engine API; `JsonElement` parsing
  (no codegen, tolerant of daemon versions). Logs/events/pull are streamed via
  okio `BufferedSource` with an 8-byte frame demuxer that auto-detects TTY vs
  multiplexed streams. Exec is a hand-rolled HTTP-upgrade on `java.net.Socket`
  (`101 UPGRADED` → raw duplex).
- **VM subsystem** — `VmController` builds the QEMU command line, supervises
  the process, tails `qemu.log`, streams the serial console over a
  `LocalSocket`, and syncs published container ports by sending
  `hostfwd_add tcp:127.0.0.1:<H>-:<G>` to the monitor socket at runtime.
- **Mini-Compose** — SnakeYAML → dependency-ordered pull/create/start with
  `com.docker.compose.project` labels so `down` works by label.
- **State** — singleton managers (`DockerRepo`, `VmController`) expose
  `StateFlow`s; screens collect them. 5 s polling + Docker events watcher keep
  the dashboard fresh.

Full details: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Security notes

- The guest daemon binds `0.0.0.0:2375` **inside the VM**; on the phone it is
  only reachable via QEMU hostfwd on `127.0.0.1` — no other app or device can
  reach it.
- All VM state lives in the app's private storage (`filesDir/vm`); uninstalling
  wipes the "phone Docker".
- The rootfs is built by *your* `build-vm-image.sh` run — change
  `ROOT_PW` in the script before distributing images.
- Remote-TCP mode is plaintext v1; put a TLS proxy (or SSH tunnel) in front of
  an untrusted network.

## Limitations (honest ones)

- TCG software emulation ≈ 5–15 % native speed. Fine for nginx/redis/postgres
  demos; heavy builds belong on a real host (remote mode). Rooted devices or
  custom ROMs that expose `/dev/kvm` get near-native speed automatically —
  see the Hypervisor card on the VM tab.
- Android kills QEMU if you force-stop the app; the foreground service +
  wakelock keep it alive under normal use.
- `hostfwd_remove` support depends on the QEMU build; stopped containers may
  leave a dead forward until VM restart (harmless).
- The terminal is line-oriented with ANSI stripping — not a full VT100/xterm.
- AVF (Android Virtualization Framework) is detected and reported, but a
  normal APK cannot drive it on stock Android — the honest breakdown and the
  three adoption paths live in [`docs/AVF_BACKEND.md`](docs/AVF_BACKEND.md).

## License

MIT — see [LICENSE](LICENSE). Not affiliated with Docker Inc.; "Docker" is a
trademark of Docker Inc.
