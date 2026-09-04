# Architecture

## 0. The constraint that shapes everything

Android (untrusted app, API 26–35) gives us:

- **no root** → no direct access to `/dev/kvm`, cgroups, network namespaces;
- **no exec from app data** (W^X enforcement on API 29+) → binaries must live in
  `applicationInfo.nativeLibraryDir`, i.e. shipped in `jniLibs/` as `lib*.so`;
- **a private POSIX environment** → unix sockets, `LocalSocket`, child
  processes, threads — all available.

Therefore the only non-root way to run *real* containers (namespaces, cgroups,
overlayfs) is to bring our **own kernel**: boot a full Linux guest and run
Docker inside it. The Android app is then "just" a Docker client — which
conveniently is the same code path used for remote hosts.

The engine is chosen per device, automatically (see `vm/Hypervisor.kt`):

| Engine | When | Speed |
|---|---|---|
| QEMU KVM | `/dev/kvm` openable by the app (root, or custom ROM sepolicy patch) | near-native |
| QEMU TCG | everything else — the unprivileged default | 5–15 % native |
| AVF crosvm/pKVM | detected and reported, but **not drivable from a stock APK** — see [`AVF_BACKEND.md`](AVF_BACKEND.md) | (platform) |

## 1. Layer map

```text
┌──────────────────────────────────────────────────────────────────┐
│ UI (Compose)                                                     │
│   AppRoot → bottom-nav: containers | images | compose | vm       │
│            + container/{id} detail: Logs|Exec|Stats|Inspect      │
│            + settings                                            │
└──────────────┬───────────────────────────────────────────────────┘
               │ StateFlow / SharedFlow
┌──────────────▼───────────────────────────────────────────────────┐
│ State managers (singletons in AppGraph)                          │
│   DockerRepo          - containers/images flows, actions,        │
│                         event watcher, port-sync orchestrator    │
│   VmController        - VM process, accelerator, phase FSM,      │
│                         console, hostfwd registry                │
│   ComposeEngine       - compose.yaml → create/start pipelines    │
│   SettingsRepository  - DataStore-backed AppSettings             │
│   VmAssetManager      - import/download/extract VM assets        │
└──────────────┬───────────────────────────────────────────────────┘
               │
┌──────────────▼──────────────────┐   ┌────────────────────────────┐
│ DockerClient (OkHttp, Engine    │   │ VmService (foreground)     │
│ API over HTTP)                  │   │  - notification            │
│   REST: /containers /images ... │   │  - partial wakelock        │
│   Streams: logs/events/pull     │   │  - start/stop intents      │
│   HijackClient: exec (raw TCP)  │   └────────────────────────────┘
└──────────────┬──────────────────┘
               │ 127.0.0.1:23750 (hostfwd)
┌──────────────▼───────────────────────────────────────────────────┐
│ QEMU guest (Alpine aarch64, KVM or TCG)                          │
│   -machine virt -accel kvm|tcg -cpu host|cortex-a57              │
│   -kernel vmlinuz-virt -initrd initramfs-virt                    │
│   -drive rootfs.img (ext4, Docker+sshd preinstalled)             │
│   -netdev user: NAT out, hostfwd 23750→2375 / 2222→22            │
│   -monitor unix:qemu-monitor.sock   ← hostfwd_add at runtime     │
│   -serial unix:qemu-serial.sock     ← console viewer             │
│   dockerd: tcp://0.0.0.0:2375  →  runc → containers              │
└──────────────────────────────────────────────────────────────────┘
```

## 2. Boot sequence

```text
VmScreen "Start VM"
  → VmService.start(context)            (foreground + wakelock first)
  → VmController.start()
      1. assets.report() — all 4 pieces present?
      2. engine pick: Hypervisor.kvmAccessible() → -accel kvm -cpu host,
         else -accel tcg,thread=multi (same assets, same guest)
      3. build argv (machine virt, -monitor/-serial unix sockets,
         two static hostfwd rules)
      4. ProcessBuilder(qemu, ...).redirectOutput(qemu.log).start()
      5. start serial collector (LocalSocket reconnect loop → consoleLines)
      6. start qemu.log poller (1 s → bootLog)
      7. awaitDaemon(): GET http://127.0.0.1:23750/_ping every 1.5 s
         (slirp accepts the TCP connect even before the guest is up, so we
          require a 200 OK from dockerd, 3 min budget)
      8. phase = Running  → DockerRepo.refresh() picks it up in ≤5 s
  → DockerRepo.syncPortForwards() adds hostfwd rules for published ports
```

Failure handling: QEMU death or ping timeout → phase `Failed` with the last
60 lines of `qemu.log` inlined into the UI so the user can see *why* (missing
virtio module, bad image, OOM from too much `-m`, …). A KVM launch that dies
early triggers exactly one automatic TCG retry (the `kvmBlocked` latch), so a
half-exposed /dev/kvm can never leave the user with a dead Start button.

## 3. Runtime port publishing

Docker publishes ports on the guest's `0.0.0.0`; QEMU slirp only forwards
what was configured at launch. Fix without restarting the VM:

```text
Docker event (start/die/destroy)
  → DockerRepo.refresh() → collect published guest ports P = {80, 6379}
  → diff against VmController.hostForwards map
      new port 80 → findFreePort(80)          (bind probe on Android side)
                  → QemuMonitor.command(
                      "hostfwd_add tcp:127.0.0.1:80-:80")
      dead port → hostfwd_remove (best-effort; ignored if unsupported)
  → UI shows :80 chip; tap → browser → http://127.0.0.1:80
```

## 4. Streaming endpoints

| Feature | Endpoint | Mechanism |
|---|---|---|
| Logs | `GET /containers/{id}/logs?follow=1` | okio loop: `request(1)` → drain ≤8 KiB → `StreamDemuxer` (auto-detects TTY vs 8-byte-multiplexed frames) → line emission |
| Events | `GET /events?filters={"type":["container"]}` | newline-delimited JSON, triggers refresh + port sync |
| Pull | `POST /images/create?fromImage=…` | newline JSON; `progressDetail` per layer aggregated into one progress bar |
| Exec | `POST /containers/{id}/exec` then `POST /exec/{id}/start` with `Upgrade: tcp` | hand-rolled HTTP over `java.net.Socket`; on `101` the socket becomes a duplex pipe; input rows write bytes, reader loop appends ANSI-stripped text |
| Stats | `GET /containers/{id}/stats?stream=false` | one-shot JSON poll every 2 s; CPU% = Δcpu/Δsystem × online_cpus |

Containers are created with `Tty: true` by default so both logs and exec
streams are raw text; the demuxer still handles foreign (Tty=false)
containers correctly.

## 5. Mini-Compose engine

`ComposeEngine.up(project, yaml)`:

1. SnakeYAML → `Map<String, Map<String, Any>>` (no schema codegen).
2. Topological sort by `depends_on` (cycle → error).
3. Per service: ensure image (pull if missing, streaming events to the UI) →
   create with name `<project>-<service>`, label
   `com.docker.compose.project=<project>`, ports parsed from short syntax,
   env from map or list, bind volumes under
   `filesDir/stacks/<project>/volumes/<service>` → start.
4. `down(project)`: list all containers, filter by label, stop + `rm`.

Good enough for web + db stacks; not a full Compose implementation (no
networks, healthchecks, or scale — documented as v1 scope).

## 6. Threading & state

- `AppGraph.appScope` (SupervisorJob + IO) hosts: refresh loop (5 s), event
  watcher (reconnect backoff 4 s), port sync, pull/exec jobs.
- Screens never own long-lived coroutines; they collect flows. Exec/log
  streams survive rotation because their jobs live in the singletons
  (`ExecSession` is `remember{}`-created but socket-backed).
- All monitor/serial/localsocket work is on `Dispatchers.IO`; the UI is pure
  flow collection.

## 7. Security model

- Guest daemon reachable **only** via `127.0.0.1` hostfwd on the phone;
  slirp NAT provides no guest→LAN exposure beyond outbound connections.
- App-private storage for all VM state; `allowBackup=false`.
- Remote-TCP mode speaks plain HTTP v1 (Docker's TLS client-cert flow is
  roadmap); docs recommend an SSH/TLS front if remote mode leaves localhost.
- The bundled QEMU executes from `nativeLibraryDir` — no write+exec from data
  dirs, satisfying API 29+ W^X enforcement.

## 8. Roadmap

- **AVF backend (crosvm/pKVM)** — capability probe + vm_config.json blueprint
  already in-tree (`AvfBackend`, `Hypervisor`); the full adoption recipe
  (AIDL vendoring, sepolicy, networking) is
  [`AVF_BACKEND.md`](AVF_BACKEND.md). Blocked on a platform-privileged
  build, not on code.
- **QMP instead of HMP** for typed monitor control + live RAM/cpu hotplug.
- **TLS + SSH contexts** for remote mode (docker context parity).
- **Full xterm.js-style terminal** (grid model, SGR colors).
- **Bundle QEMU + assets in the APK** via Play "asset packs" for zero-setup
  installs (adds ~1 GB to the download).
