# tools/ — VM asset pipeline

The app ships as source; the heavy VM binaries are produced/fetched at build
time and are never committed. Two things are needed before the embedded VM can
start:

| Asset | Built by | Size |
|-------|----------|------|
| `vmlinuz-virt` + `initramfs-virt` | `build-vm-image.sh` (downloads from Alpine CDN) | ~60 MB |
| `rootfs.img` (Docker preinstalled) | `build-vm-image.sh` (alpine container build) | ~1.5 GB compressed |
| `qemu-system-aarch64` | `fetch-qemu-aarch64.sh` (see header for sources) | ~15–40 MB |

## Quick start (Linux or macOS with Docker)

```bash
./tools/build-vm-image.sh ./vm-assets
# ... serve or copy ./vm-assets somewhere the phone can reach:
cd vm-assets && python3 -m http.server 8000
```

Then in the app: **VM tab → asset mirror URL → `http://<build-host-ip>:8000` →
Download all**. Alternatively, import each file with the per-row *Import*
buttons (SAF file picker) — no network needed.

## QEMU binary

Follow the options in the header of `fetch-qemu-aarch64.sh`. The recommended
path is Termux's bionic build of `qemu-system-aarch64`, which runs cleanly from
an app's `nativeLibraryDir`:

```bash
# on-device, in Termux
pkg install qemu-system-aarch64
# pull it off the device via adb or scp, then on your build host:
./tools/fetch-qemu-aarch64.sh ./qemu-system-aarch64
```

If QEMU pulls in shared libraries (glib, pixman), copy them next to it as
`libglib-2.0.so`, `libpixman-1.so`, … inside the same `jniLibs/arm64-v8a/`
folder; Android's linker will resolve them from there. Fully static builds are
preferred when available — musl-based cross builds (e.g. from
`musl.cc` toolchains compiling QEMU 8/9) produce a single-file binary that
"just works".

Without a QEMU binary the rest of the app still functions in **Remote daemon
mode** (Settings → Remote daemon TCP) against any Docker host on your network.
