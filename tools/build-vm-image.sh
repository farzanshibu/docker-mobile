#!/usr/bin/env bash
# =============================================================================
# tools/build-vm-image.sh
#
# Builds the three VM assets that Docker Mobile loads at runtime:
#
#   vmlinuz-virt    - Alpine Linux "virt" kernel (aarch64)
#   initramfs-virt  - matching initramfs (ext4 + virtio capable)
#   rootfs.img      - ext4 rootfs with Docker + OpenSSH preinstalled
#
# Needs Docker on your Linux/macOS build host (rootfs is built inside an
# alpine:3.20 container). No --privileged and no loop devices: the image is
# populated with `mkfs.ext4 -d`, which works on Docker Desktop for Mac too.
# Kernel/initramfs are fetched from the Alpine CDN netboot artefacts.
#
# Usage:
#   ./tools/build-vm-image.sh [output-dir]
#
# Environment:
#   ROOTFS_SIZE_MB   rootfs image size in MB (default 4096; use ~1536 for
#                    memory/storage-constrained targets such as an emulator)
#   ROOT_PW          guest root password (default "dockermobile")
#
# Output: <output-dir>/{vmlinuz-virt,initramfs-virt,rootfs.img,rootfs.img.gz}
# Drop those onto a web server (asset mirror) or import them from the app's
# VM tab.
# =============================================================================
set -euo pipefail

OUT="${1:-$(pwd)/vm-assets}"
ALPINE_VERSION="3.20"
ALPINE_MIRROR="https://dl-cdn.alpinelinux.org/alpine"
DOCKER_TCP_PORT=2375
ROOT_PW="${ROOT_PW:-dockermobile}"       # console login: root / <ROOT_PW>
ROOTFS_SIZE_MB="${ROOTFS_SIZE_MB:-4096}"

mkdir -p "$OUT"

# Kernel and initramfs are NOT taken from the CDN netboot artefacts: that
# initramfs is built for network/modloop boot, ships no ext4 module (so
# "mounting /dev/vda on /sysroot failed: Invalid argument") and its module
# version drifts from whatever linux-virt the rootfs installs. Both are
# generated from the rootfs itself instead, which keeps kernel, modules and
# initramfs on exactly the same version.
echo "==> [1/2] Building ${ROOTFS_SIZE_MB} MB rootfs + matching kernel inside an alpine:${ALPINE_VERSION} container"
cat > /tmp/dockermobile-rootfs-setup.sh <<'EOS'
set -euo pipefail

OUT=/out
IMG="$OUT/rootfs.img"

# docker/docker-cli-compose live in community, which the base image does not
# enable by default.
cat > /etc/apk/repositories <<EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION}/community
EOF

apk update
apk add --no-cache e2fsprogs e2fsprogs-extra coreutils

# --- install Alpine userland into a directory -------------------------------
# `--initdb` starts an empty apk database: the new root has neither a
# repository list nor the signing keys, so seed both before installing.
ROOT=/rootfs
mkdir -p "$ROOT/etc/apk/keys"
cp /etc/apk/repositories "$ROOT/etc/apk/repositories"
cp -a /etc/apk/keys/. "$ROOT/etc/apk/keys/"

apk add --root "$ROOT" --initdb --no-cache \
    --repositories-file /etc/apk/repositories \
    alpine-base openrc linux-virt docker docker-cli-compose openssh-server bash \
    ca-certificates iptables ip6tables tzdata

# --- network + services ------------------------------------------------------
mkdir -p "$ROOT/etc/network"
cat > "$ROOT/etc/network/interfaces" <<EOF
auto lo
iface lo inet loopback

auto eth0
iface eth0 inet dhcp
EOF

# DNS: QEMU's user-mode network hands the guest 10.0.2.3, and slirp forwards
# those queries to whatever the *host* lists in /etc/resolv.conf. Android has
# no /etc/resolv.conf, so every lookup times out ("lookup registry-1.docker.io
# on 10.0.2.3:53: i/o timeout") and `docker pull` cannot work. Resolve against
# public servers instead — slirp NATs that out fine — and keep udhcpc from
# putting 10.0.2.3 back on every DHCP renewal.
mkdir -p "$ROOT/etc/udhcpc"
printf 'RESOLV_CONF="no"\n' > "$ROOT/etc/udhcpc/udhcpc.conf"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 10.0.2.3\n' > "$ROOT/etc/resolv.conf"

# Docker daemon: TCP listener for the phone app + default unix socket.
mkdir -p "$ROOT/etc/docker"
cat > "$ROOT/etc/docker/daemon.json" <<EOF
{
  "log-driver": "json-file",
  "iptables": true
}
EOF

# OpenRC passes DOCKER_OPTS to dockerd; keep the listeners here only, so they
# are never declared twice (daemon.json "hosts" + argv is a fatal conflict).
cat > "$ROOT/etc/conf.d/docker" <<EOF
DOCKER_OPTS="--host=unix:///var/run/docker.sock --host=tcp://0.0.0.0:${DOCKER_TCP_PORT}"
EOF

chroot "$ROOT" rc-update add networking boot || true
chroot "$ROOT" rc-update add docker boot || true
chroot "$ROOT" rc-update add sshd boot || true

# sshd: allow root login for recovery (password set below).
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin yes/' "$ROOT/etc/ssh/sshd_config" || true

# A getty on the virt machine's serial console, so the app's console viewer
# gives a real login shell (aarch64 "virt" uses ttyAMA0, not ttyS0).
if ! grep -q ttyAMA0 "$ROOT/etc/inittab"; then
    echo 'ttyAMA0::respawn:/sbin/getty -L 0 ttyAMA0 vt100' >> "$ROOT/etc/inittab"
fi

# root password (console + ssh)
chroot "$ROOT" sh -c "echo 'root:${ROOT_PW}' | chpasswd"

# Boot hygiene: mount standard filesystems early.
cat > "$ROOT/etc/fstab" <<EOF
/dev/vda / ext4 rw,noatime 0 1
EOF

# --- kernel + initramfs, generated against this exact rootfs ------------------
KVER="$(ls "$ROOT/lib/modules" | head -n1)"
echo "kernel in rootfs: $KVER"
chroot "$ROOT" mkinitfs -F "base virtio ext4 scsi" -o /boot/initramfs-virt "$KVER"
cp "$ROOT/boot/vmlinuz-virt"   "$OUT/vmlinuz-virt"
cp "$ROOT/boot/initramfs-virt" "$OUT/initramfs-virt"

# --- pack the rootfs ----------------------------------------------------------
# mkfs.ext4 -d populates the filesystem straight from a directory: no loop
# device, no mount, no --privileged (and it works on Docker Desktop for Mac).
rm -f "$IMG"
truncate -s "${SIZE_MB}M" "$IMG"
# Trim ext4 features that older/leaner kernels reject at mount time
# ("mounting /dev/vda on /sysroot failed: Invalid argument").
mkfs.ext4 -F -q -O ^metadata_csum_seed,^orphan_file -d "$ROOT" "$IMG"

# The container runs as root with a restrictive umask, so everything it drops
# in the bind mount comes out mode 0600 root-owned. On Linux (CI included) the
# calling user then cannot even read its own build output.
chmod 0644 "$OUT/vmlinuz-virt" "$OUT/initramfs-virt" "$IMG"

echo "rootfs ready: $IMG"
EOS

docker run --rm \
    -e SIZE_MB="$ROOTFS_SIZE_MB" \
    -e ALPINE_VERSION="$ALPINE_VERSION" \
    -e ROOT_PW="$ROOT_PW" \
    -e DOCKER_TCP_PORT="$DOCKER_TCP_PORT" \
    -v "$OUT:/out" \
    -v /tmp/dockermobile-rootfs-setup.sh:/setup.sh \
    "alpine:${ALPINE_VERSION}" \
    sh /setup.sh

echo "==> [2/2] Compressing rootfs for distribution"
gzip -kf "$OUT/rootfs.img"

echo
echo "Done. Files in $OUT:"
ls -lh "$OUT"
echo
echo "Serve them (e.g. 'python3 -m http.server' in $OUT) and point the app's"
echo "asset mirror at http://<your-host>:8000/ — or import them via the VM tab."
