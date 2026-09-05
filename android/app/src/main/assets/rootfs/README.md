# Bundled NativOS rootfs

All builds require `nativos-rootfs-arm64.tgz` and its matching
`nativos-rootfs-arm64.tgz.sha256` in this directory. Gradle fails before
packaging if either is absent or the checksum does not match.

Generate the image from a clean, fully provisioned arm64 rootfs:

```sh
scripts/build-rootfs-asset.sh /path/to/prepared-rootfs \
  android/app/src/main/assets/rootfs/nativos-rootfs-arm64.tgz
```

The prepared image must already contain Phosh, Phoc, GNOME Console, schemas,
icons, and the NativOS compatibility libraries. Do not package a user's live
rootfs without reviewing it for accounts, tokens, history, and personal data.

The script requires GNU tar (`gtar` on macOS), normalizes ownership, ordering,
timestamps and gzip metadata, and records the exact Debian package versions in
`/usr/share/nativos/rootfs-packages.txt`. Set `SOURCE_DATE_EPOCH` when producing
an official release; otherwise the documented default epoch is used.
