# Third-party software notices

This inventory covers the principal components copied into the source tree or
bundled in the NativOS APK. It must be updated whenever bundled binaries or the
root filesystem change.

## Android application and native libraries

| Component | Repository location | License / status | Upstream |
| --- | --- | --- | --- |
| Termux:X11 Android and input code | `android/app/src/main/java/com/termux/x11/` | GPL-3.0; individual source files may retain additional notices | <https://github.com/termux/termux-x11> |
| Xlorie native X server | `android/app/src/main/xlorie-upstream/arm64-v8a/libXlorie.so` | Termux:X11 GPL-3.0; NativOS applies a documented cursor patch during the build | <https://github.com/termux/termux-x11> |
| PRoot and loader | `android/app/src/main/jniLibs/arm64-v8a/libproot*.so` | Upstream PRoot is GPL-2.0-or-later | <https://github.com/termux/proot> |
| talloc | `android/app/src/main/jniLibs/arm64-v8a/libtalloc.so` | LGPL-3.0-or-later in current upstream; exact bundled build provenance should be recorded | <https://talloc.samba.org/> |
| Android shared-memory compatibility library | `libandroid-shmem.so` and `android/app/src/main/jniLibs/libandroid-shmem.c` | Preserve the applicable upstream/source notices; exact bundled build provenance should be recorded | <https://github.com/termux/android-shmem> |
| NativOS socket relocation hook | `libsocket_hook.so` and `android/socket_hook.c` | NativOS GPL-3.0 source is included; release builds should reproduce the binary from source | This repository |
| Mesa Turnip KGSL package | `android/app/src/main/assets/gpu/` | Mesa components retain their upstream licenses; package copyright files must be preserved | <https://mesa3d.org/> |

## Bundled Ubuntu root filesystem

The release APK contains an Ubuntu 24.04 ARM64 root filesystem with Phosh,
Phoc, GNOME applications, Mesa, Flatpak, and their dependencies. Each package
retains its own license. Debian-format copyright files are preserved inside
the image under `/usr/share/doc/*/copyright`, and the build process records the
installed package versions in `/usr/share/nativos/rootfs-packages.txt`.

Relevant upstream sources and license information include:

- Ubuntu package source: <https://packages.ubuntu.com/>
- Ubuntu legal information: <https://ubuntu.com/legal/intellectual-property-policy>
- Phosh: <https://gitlab.gnome.org/World/Phosh/phosh>
- Phoc: <https://gitlab.gnome.org/World/Phosh/phoc>
- GNOME: <https://www.gnome.org/>
- Flatpak: <https://github.com/flatpak/flatpak>
- Mesa: <https://mesa3d.org/>

The compressed production rootfs is distributed in the release APK rather
than committed to Git because it exceeds GitHub's normal file-size limit. Its
checksum and deterministic packaging script are in this repository.

## Android dependencies

The app uses AndroidX, Material Components for Android, Kotlin, and `org.json`.
They retain their respective licenses. Gradle dependency metadata in the
source tree identifies the requested versions.

## Release provenance work

This alpha still needs a fully automated source-and-license manifest for every
prebuilt native library and every rootfs package. See
[PRODUCTION_READINESS.md](PRODUCTION_READINESS.md). This notice documents the
current state; it does not replace any component's license terms.
