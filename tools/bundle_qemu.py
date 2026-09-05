#!/usr/bin/env python3
"""
Assemble a Termux qemu-system-aarch64 + its whole shared-library closure into
an Android jniLibs/arm64-v8a folder.

Android's package installer only extracts files named lib*.so from lib/<abi>/,
so every versioned SONAME (libz.so.1, libgio-2.0.so.0, ...) has to be renamed
and every DT_NEEDED/DT_SONAME referring to it rewritten. The replacement name
is always shorter than the original ("libz.so.1" -> "libz1.so"), so the strings
are patched in place inside .dynstr and no offsets move.
"""
import os, re, sys, shutil, struct, subprocess, tarfile, urllib.request, urllib.parse

REPO = "https://packages.termux.dev/apt/termux-main"
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")
ROOT_PKG = "qemu-system-aarch64-headless"

# ---------------------------------------------------------------- apt index

INDEX_URL = f"{REPO}/dists/stable/main/binary-aarch64/Packages.gz"


def ensure_index():
    """Fetch the apt Packages index next to this script if it is not there."""
    path = os.path.join(HERE, "Packages")
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    import gzip
    print(f"fetching package index: {INDEX_URL}")
    raw = urllib.request.urlopen(INDEX_URL, timeout=120).read()
    with open(path, "wb") as f:
        f.write(gzip.decompress(raw))
    return path


def load_index():
    pkgs, cur = {}, {}
    with open(ensure_index(), encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                if cur.get("Package"):
                    pkgs[cur["Package"]] = cur
                cur = {}
                continue
            if line[0] in " \t":
                continue
            if ":" in line:
                k, v = line.split(":", 1)
                cur[k.strip()] = v.strip()
    if cur.get("Package"):
        pkgs[cur["Package"]] = cur
    return pkgs


def deps_of(entry):
    out = []
    for chunk in entry.get("Depends", "").split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        alt = chunk.split("|")[0].strip()
        name = re.split(r"[\s(]", alt)[0].strip()
        if name:
            out.append(name)
    return out


def closure(pkgs, roots):
    seen, stack = set(), list(roots)
    while stack:
        name = stack.pop()
        if name in seen or name not in pkgs:
            continue
        seen.add(name)
        stack.extend(deps_of(pkgs[name]))
    return sorted(seen)

# ---------------------------------------------------------------- download

def fetch(pkgs, name):
    fn = pkgs[name]["Filename"]
    url = f"{REPO}/{fn}".replace(":", "%3A", 1) if ":" in os.path.basename(fn) else f"{REPO}/{fn}"
    url = f"{REPO}/" + "/".join(urllib.parse.quote(p) for p in fn.split("/"))
    dest = os.path.join(WORK, "debs", name + ".deb")
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    urllib.request.urlretrieve(url, dest)
    return dest


def unpack(deb, into):
    """Extract a .deb: the outer `ar` container, then its data tarball."""
    os.makedirs(into, exist_ok=True)
    deb = os.path.abspath(deb)

    # A .deb is an `ar` archive. bsdtar (the default tar on macOS) reads those
    # directly; GNU tar on Linux does not, so fall back to ar(1) there.
    for cmd in (["tar", "xf", deb], ["bsdtar", "xf", deb], ["ar", "x", deb]):
        try:
            subprocess.run(cmd, cwd=into, check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            break
        except (FileNotFoundError, subprocess.CalledProcessError):
            continue
    else:
        raise RuntimeError(f"cannot unpack {deb}: need bsdtar or ar")

    data = next(
        (os.path.join(into, n) for n in
         ("data.tar.xz", "data.tar.gz", "data.tar.bz2", "data.tar", "data.tar.zst")
         if os.path.exists(os.path.join(into, n))),
        None,
    )
    if data is None:
        raise RuntimeError(f"no data tarball inside {deb}")

    # tarfile handles xz/gz/bz2 from the stdlib, so this works on hosts whose
    # tar has no xz support (and without shelling out at all).
    try:
        with tarfile.open(data, "r:*") as tf:
            tf.extractall(into, filter="tar")
    except tarfile.ReadError:
        # .zst is not in the stdlib on older Pythons; let an external tar try.
        subprocess.run(["tar", "xf", data], cwd=into, check=True)


# ------------------------------------------------------------------- ELF

SHT_DYNAMIC, DT_NEEDED, DT_SONAME, DT_NULL = 6, 1, 14, 0


def elf_dyn_strings(path):
    """Return (list of (tag, strtab_file_offset, name)) for NEEDED/SONAME."""
    with open(path, "rb") as f:
        buf = bytearray(f.read())
    if buf[:4] != b"\x7fELF" or buf[4] != 2 or buf[5] != 1:
        return None, None                      # not 64-bit little-endian ELF
    e_shoff, = struct.unpack_from("<Q", buf, 0x28)
    e_shentsize, e_shnum = struct.unpack_from("<HH", buf, 0x3A)
    if e_shoff == 0:
        return None, None
    dyn = None
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        sh_type, = struct.unpack_from("<I", buf, off + 4)
        sh_offset, sh_size = struct.unpack_from("<QQ", buf, off + 0x18)
        sh_link, = struct.unpack_from("<I", buf, off + 0x28)
        sh_entsize, = struct.unpack_from("<Q", buf, off + 0x38)
        sections.append((sh_type, sh_offset, sh_size, sh_link, sh_entsize))
        if sh_type == SHT_DYNAMIC:
            dyn = sections[-1]
    if dyn is None:
        return None, None
    _, dyn_off, dyn_size, dyn_link, _ = dyn
    str_off = sections[dyn_link][1]
    found = []
    for pos in range(dyn_off, dyn_off + dyn_size, 16):
        tag, val = struct.unpack_from("<qQ", buf, pos)
        if tag == DT_NULL:
            break
        if tag in (DT_NEEDED, DT_SONAME):
            at = str_off + val
            end = buf.index(b"\0", at)
            found.append((tag, at, buf[at:end].decode()))
    return buf, found

# Termux keeps everything under a flat lib dir, so only the basename matters.
VER_RE = re.compile(r"^(lib.+?)\.so((?:\.\d+)+)$")


def android_name(soname):
    """libz.so.1 -> libz1.so ; libbz2.so.1.0 -> libbz210.so ; keeps lib*.so."""
    m = VER_RE.match(soname)
    if not m:
        return soname
    stem, vers = m.group(1), m.group(2).replace(".", "")
    return f"{stem}{vers}.so"


def patch_elf(path, rename):
    buf, found = elf_dyn_strings(path)
    if buf is None:
        return 0
    changed = 0
    for tag, at, name in found:
        new = rename.get(name)
        if not new or new == name:
            continue
        assert len(new) <= len(name), (name, new)
        buf[at:at + len(name) + 1] = new.encode() + b"\0" * (len(name) + 1 - len(new))
        changed += 1
    if changed:
        with open(path, "wb") as f:
            f.write(buf)
    return changed


# ------------------------------------------------------------------- main

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    stage_only = "--stage-only" in sys.argv
    dest = args[0] if args else None
    pkgs = load_index()
    want = closure(pkgs, [ROOT_PKG])
    print(f"closure: {len(want)} packages")

    stage = os.path.join(WORK, "stage")
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(stage, exist_ok=True)
    for name in want:
        if name not in pkgs:
            print(f"  ! missing from index: {name}")
            continue
        deb = fetch(pkgs, name)
        unpack(deb, os.path.join(stage, name))

    prefix = os.path.join("data", "data", "com.termux", "files", "usr")
    libs, binary, sharedir = {}, None, None
    for name in os.listdir(stage):
        base = os.path.join(stage, name, prefix)
        libdir = os.path.join(base, "lib")
        if os.path.isdir(libdir):
            for fn in os.listdir(libdir):
                p = os.path.join(libdir, fn)
                if os.path.isfile(p) and not os.path.islink(p) and ".so" in fn:
                    libs[fn] = p
        b = os.path.join(base, "bin", "qemu-system-aarch64")
        if os.path.isfile(b):
            binary = b
        s = os.path.join(base, "share", "qemu")
        if os.path.isdir(s):
            sharedir = s

    rename = {fn: android_name(fn) for fn in libs}
    renamed = {k: v for k, v in rename.items() if k != v}
    print(f"libs: {len(libs)}  renamed: {len(renamed)}")

    # Staging is all select_qemu_libs.py needs; it computes the real runtime
    # closure itself instead of shipping every lib the apt graph drags in.
    if stage_only or dest is None:
        print("staged only — run select_qemu_libs.py to emit jniLibs")
        return

    os.makedirs(dest, exist_ok=True)
    for old, src in sorted(libs.items()):
        out = os.path.join(dest, rename[old])
        shutil.copyfile(src, out)
        os.chmod(out, 0o755)
    qemu_out = os.path.join(dest, "libqemu_system_aarch64.so")
    shutil.copyfile(binary, qemu_out)
    os.chmod(qemu_out, 0o755)

    total = 0
    for fn in os.listdir(dest):
        total += patch_elf(os.path.join(dest, fn), rename)
    print(f"patched {total} DT_NEEDED/DT_SONAME entries")

    if sharedir:
        out = os.path.join(os.path.dirname(dest.rstrip("/")), "qemu-data")
        shutil.rmtree(out, ignore_errors=True)
        shutil.copytree(sharedir, out)
        print(f"qemu datadir -> {out} ({len(os.listdir(out))} files)")


if __name__ == "__main__":
    import urllib.parse
    main()
