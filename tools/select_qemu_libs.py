#!/usr/bin/env python3
"""
Second pass: keep only the shared libraries qemu-system-aarch64 actually pulls
in, by walking DT_NEEDED transitively instead of trusting the apt dependency
graph (which drags in ~260 unrelated libs).

Emits the trimmed jniLibs/arm64-v8a folder with Android-legal lib*.so names and
every DT_NEEDED/DT_SONAME rewritten to match.
"""
import os, sys, shutil
from bundle_qemu import elf_dyn_strings, android_name, patch_elf, WORK, DT_NEEDED

# Provided by Android itself — never bundle these.
SYSTEM = {
    "libc.so", "libm.so", "libdl.so", "libstdc++.so", "liblog.so",
    "libandroid.so", "libz.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so",
    "libOpenSLES.so", "libjnigraphics.so", "libvulkan.so", "libnativewindow.so",
    "libmediandk.so", "libaaudio.so", "libcamera2ndk.so", "libneuralnetworks.so",
}
PREFIX = os.path.join("data", "data", "com.termux", "files", "usr")


def index_stage(stage):
    """basename -> real file path, following Termux's in-lib-dir symlinks."""
    files, qemu, share = {}, None, None
    for pkg in os.listdir(stage):
        base = os.path.join(stage, pkg, PREFIX)
        libdir = os.path.join(base, "lib")
        if os.path.isdir(libdir):
            for fn in os.listdir(libdir):
                p = os.path.join(libdir, fn)
                real = os.path.realpath(p)
                if os.path.isfile(real) and ".so" in fn:
                    files.setdefault(fn, real)
        b = os.path.join(base, "bin", "qemu-system-aarch64")
        if os.path.isfile(b):
            qemu = b
        s = os.path.join(base, "share", "qemu")
        if os.path.isdir(s):
            share = s
    return files, qemu, share


def needed_of(path):
    buf, found = elf_dyn_strings(path)
    if buf is None:
        return []
    return [name for tag, _at, name in found if tag == DT_NEEDED]


def main():
    dest = sys.argv[1]
    stage = os.path.join(WORK, "stage")
    files, qemu, share = index_stage(stage)
    print(f"staged libs available: {len(files)}")

    keep, missing, queue = {}, set(), list(needed_of(qemu))
    while queue:
        soname = queue.pop()
        if soname in SYSTEM or soname in keep:
            continue
        src = files.get(soname)
        if src is None:
            missing.add(soname)
            continue
        keep[soname] = src
        queue.extend(needed_of(src))

    print(f"runtime closure: {len(keep)} libs")
    if missing:
        print(f"resolved from Android system: {sorted(missing)}")

    rename = {so: android_name(so) for so in keep}
    rename.update({s: s for s in SYSTEM})

    shutil.rmtree(dest, ignore_errors=True)
    os.makedirs(dest, exist_ok=True)
    total_bytes = 0
    for soname, src in sorted(keep.items()):
        out = os.path.join(dest, rename[soname])
        shutil.copyfile(src, out)
        os.chmod(out, 0o755)
        total_bytes += os.path.getsize(out)
    qemu_out = os.path.join(dest, "libqemu_system_aarch64.so")
    shutil.copyfile(qemu, qemu_out)
    os.chmod(qemu_out, 0o755)
    total_bytes += os.path.getsize(qemu_out)

    patched = sum(patch_elf(os.path.join(dest, f), rename) for f in os.listdir(dest))
    print(f"patched {patched} DT_NEEDED/DT_SONAME entries")
    print(f"payload: {total_bytes / 1e6:.1f} MB across {len(os.listdir(dest))} files")

    # verify: every DT_NEEDED of every shipped file now resolves locally
    have = set(os.listdir(dest))
    bad = []
    for f in os.listdir(dest):
        for n in needed_of(os.path.join(dest, f)):
            if n not in have and n not in SYSTEM:
                bad.append((f, n))
    print("unresolved after patch:", bad if bad else "none")


if __name__ == "__main__":
    main()
