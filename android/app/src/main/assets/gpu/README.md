# Bundled Turnip driver

`mesa-vulkan-drivers-kgsl-25.0.7-arm64.deb` is the Ubuntu 24.04 arm64
KGSL-enabled Mesa build published by `lfdevs/mesa-for-android-container`:

https://github.com/lfdevs/mesa-for-android-container/releases/tag/import%2F25.0.7-0ubuntu0.24.04.2-adreno

SHA-256:
`66b11a94835f66e80efc8556477334a14dc68456a76e31ada3cd2d440869c5d5`

Mesa is distributed under the licenses recorded in the package's
`usr/share/doc/mesa-vulkan-drivers/copyright` file. NativOS extracts this
package into `/opt/nativos-gpu` without replacing the distribution Mesa,
preserving the software-rendering fallback.
