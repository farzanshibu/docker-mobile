#!/usr/bin/env bash
# =============================================================================
# tools/fetch-qemu-aarch64.sh
#
# Places an aarch64 build of qemu-system-aarch64 into the APK's jniLibs folder
# as a "fake shared library" so the Android package installer extracts it with
# the exec bit:
#
#   app/src/main/jniLibs/arm64-v8a/libqemu_system_aarch64.so
#
# Where to get a working binary:
#
#   Option A (recommended): build inside a docker cross-compilation image.
#     ./tools/fetch-qemu-aarch64.sh /path/to/qemu-system-aarch64
#     (just point the script at any Linux/AArch64-eligible ELF you already have)
#
#   Option B: extract from Termux (binaries are built against Android's bionic
#     libc and run fine from an app's nativeLibraryDir):
#       pkg install qemu-system-aarch64  (inside Termux)
#       then copy $PREFIX/bin/qemu-system-aarch64 here.
#       Copy its non-system .so dependencies too if missing on-device
#       (glib-2.0, pixman-1, etc.) as sibling lib<name>.so files.
#
#   Option C: build from source with the Android NDK:
#       git clone --depth 1 --branch v9.1.0 https://gitlab.com/qemu-project/qemu
#       # configure with --target-list=aarch64-softmmu --cross-prefix from NDK
#       # see docs/ARCHITECTURE.md for the full recipe
#
# Usage:
#   ./tools/fetch-qemu-aarch64.sh [path-to-binary]
# =============================================================================
set -euo pipefail

SRC="${1:-}"
DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
DEST="$DEST_DIR/libqemu_system_aarch64.so"

if [[ -z "$SRC" ]]; then
    echo "ERROR: pass the path to a qemu-system-aarch64 ELF binary."
    echo "  $0 /path/to/qemu-system-aarch64"
    echo
    echo "See the header of this script for where to obtain one."
    exit 1
fi

if ! file "$SRC" 2>/dev/null | grep -qi 'aarch64\|arm64\|arm aarch'; then
    echo "WARNING: '$SRC' does not look like an aarch64 ELF (file says:"
    echo "  $(file -b "$SRC" 2>/dev/null || echo 'file(1) unavailable')"
    echo "Continuing anyway — the app will fail gracefully if it cannot execute."
fi

mkdir -p "$DEST_DIR"
cp "$SRC" "$DEST"
chmod +x "$DEST"

echo "Installed: $DEST"
echo "Verify it is unstripped-enough to run on-device, then build the APK in Android Studio."
