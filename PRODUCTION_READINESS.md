# NativOS Production Readiness

This is the sequential release checklist. Complete and verify one numbered step before starting the next.

## Verified baseline

- [x] Bundled X11 server; no external Termux:X11 dependency
- [x] Bundled Linux rootfs boots without downloading the desktop
- [x] Root permission can be granted and retried without restarting the app
- [x] Phosh renders on Galaxy S23, Poco F1, Moto G71, and Pixel 6a
- [x] Zink/Turnip selected on tested Adreno phones
- [x] LLVMpipe software fallback selected on Pixel 6a (Mali)
- [x] Display cutouts and Android navigation insets handled in portrait and landscape

## Sequential release work

1. **Display lifecycle and rotation** — COMPLETE ON GALAXY S23
   - [x] Resize Android's X11 surface during live portrait/landscape rotation
   - [x] Resize Phoc's nested X11 output without restarting Linux applications
   - [x] Verify repeated portrait-to-landscape switching on Galaxy S23
   - [x] Preserve cutout/navigation safe areas, touch geometry, and overlay placement
   - [x] Android app return and surface repaint recovery implemented
   - [ ] Re-run the rotation regression on Pixel 6a, Poco F1, and Moto G71 when reconnected

2. **Reproducible bundled rootfs** — IN PROGRESS
   - [x] Generate a sanitized deterministic archive with normalized metadata and a package manifest
   - [x] Verify the bundled rootfs SHA-256 checksum during every Android build
   - [ ] Pin the source repositories and package versions used to provision the rootfs
   - [x] Make builds fail if the required rootfs asset or checksum is absent
   - [ ] Produce licensing and source notices for redistributed components

3. **Rootfs versioning, upgrade, and repair**
   - [ ] Store rootfs schema/build version separately from app version
   - [ ] Perform atomic upgrades with sufficient-storage checks
   - [ ] Recover from interrupted extraction or migration
   - [ ] Add a user-controlled repair/reset flow

4. **Runtime recovery and diagnostics**
   - [ ] Restart X11, Phoc, or Phosh after a crash without leaving a black screen
   - [ ] Detect boot timeouts and show an actionable error
   - [ ] Add a privacy-reviewed diagnostic export
   - [ ] Verify calling, notifications, lock screen, suspend, and resume

5. **Flatpak production flow**
   - [ ] Reliable install/update/remove progress and cancellation
   - [ ] Recover cleanly from interrupted or failed installs
   - [ ] Verify portals, permissions, sandboxing, and representative apps
   - [ ] Clearly identify unsupported Flatpak/application limitations

6. **Root and security hardening**
   - [ ] Minimize and validate every root command and filesystem target
   - [ ] Audit Android bridge access and exported components
   - [ ] Add safeguards and recovery guidance to the system-app remover
   - [ ] Test Magisk, KernelSU, and APatch

7. **Device compatibility matrix**
   - [ ] Define supported Android versions, ARM64 requirement, and storage requirement
   - [ ] Test multiple vendors, display shapes, refresh rates, and Android versions
   - [ ] Verify Adreno hardware acceleration and non-Adreno software fallback
   - [ ] Fail gracefully on unsupported architecture/root configurations

8. **Release candidate**
   - [x] Add private release signing configuration
   - [ ] Add reproducible CI builds
   - [ ] Remove debug-only behavior and audit logs for sensitive information
   - [ ] Complete accessibility, keyboard, localization, battery, and thermal testing
   - [x] Run clean-install and uninstall tests on Galaxy S23 and Pixel 6a
   - [ ] Run signed upgrade tests
   - [x] Publish requirements, limitations, licenses, and recovery documentation

## Release gate

NativOS is production-ready only when all eight steps pass on the declared support matrix with no critical data-loss, security, boot, display, or recovery defects.
