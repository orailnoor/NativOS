#!/bin/sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: $0 PREPARED_ROOTFS OUTPUT_TAR_GZ" >&2
    exit 2
fi

rootfs=$1
output=$2
source_date_epoch=${SOURCE_DATE_EPOCH:-1704067200}

if [ -n "${TAR_BIN:-}" ]; then
    tar_bin=$TAR_BIN
elif command -v gtar >/dev/null 2>&1; then
    tar_bin=gtar
else
    tar_bin=tar
fi
if ! "$tar_bin" --version 2>/dev/null | grep -q 'GNU tar'; then
    echo "GNU tar is required for a reproducible rootfs (install gtar or set TAR_BIN)." >&2
    exit 2
fi

test -x "$rootfs/usr/bin/phosh"
test -x "$rootfs/usr/bin/phoc"
test -x "$rootfs/usr/bin/kgx"
test -f "$rootfs/usr/share/glib-2.0/schemas/org.gnome.settings-daemon.peripherals.gschema.xml"

staging=$(mktemp -d "${TMPDIR:-/tmp}/nativos-rootfs.XXXXXX")
trap 'rm -rf "$staging"' EXIT HUP INT TERM
cp -a "$rootfs"/. "$staging"/

# Never ship device identity, caches, runtime state, logs, or user-installed apps.
rm -rf "$staging/tmp"/* "$staging/run"/* "$staging/var/tmp"/*
rm -rf "$staging/var/cache/apt/archives"/* "$staging/var/lib/apt/lists"/*
rm -rf "$staging/root/.cache" "$staging/root/.local/share/flatpak"
rm -f "$staging/etc/machine-id" "$staging/var/lib/dbus/machine-id"
mkdir -p "$staging/tmp" "$staging/run" "$staging/var/tmp"
chmod 1777 "$staging/tmp" "$staging/var/tmp"

# Record the exact Debian package set included in this image. This file is part
# of the rootfs and can be compared between releases without booting it.
mkdir -p "$staging/usr/share/nativos"
awk '
    BEGIN { RS=""; FS="\n" }
    {
        package=""; version=""; architecture=""
        for (i = 1; i <= NF; i++) {
            if ($i ~ /^Package: /) package=substr($i, 10)
            else if ($i ~ /^Version: /) version=substr($i, 10)
            else if ($i ~ /^Architecture: /) architecture=substr($i, 15)
        }
        if (package != "" && version != "")
            print package "=" version " [" architecture "]"
    }
' "$staging/var/lib/dpkg/status" | LC_ALL=C sort > \
    "$staging/usr/share/nativos/rootfs-packages.txt"

cat > "$staging/usr/share/nativos/rootfs-build.txt" <<EOF
schema=1
architecture=arm64
source_date_epoch=$source_date_epoch
EOF

mkdir -p "$(dirname "$output")"
archive="$staging/rootfs.tar"
"$tar_bin" --sort=name --mtime="@$source_date_epoch" \
    --owner=0 --group=0 --numeric-owner --xattrs --acls \
    --pax-option=delete=atime,delete=ctime \
    --exclude='./dev/*' \
    --exclude='./proc/*' \
    --exclude='./sys/*' \
    --exclude='./rootfs.tar' \
    -C "$staging" -cf "$archive" .
gzip -n -9 -c "$archive" > "$output"

if command -v sha256sum >/dev/null 2>&1; then
    checksum=$(sha256sum "$output" | awk '{print $1}')
else
    checksum=$(shasum -a 256 "$output" | awk '{print $1}')
fi
printf '%s  %s\n' "$checksum" "$(basename "$output")" > "$output.sha256"

echo "Created $output"
echo "Created $output.sha256"
