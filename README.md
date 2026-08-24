# NativOS

**Turn any rooted Android phone into a near-native Linux phone.**

Android becomes invisible — just a hardware service provider. You see and use Linux.

> [!IMPORTANT]
> NativOS is in early development. It currently targets ARM64 rooted devices running Android 9+.

## What This Is

NativOS runs a full Linux desktop (Phosh/GNOME Phone Shell) inside a chroot on your rooted Android phone. Unlike regular chroot-in-Termux setups, NativOS:

- **Hides Android completely** — no launcher, no status bar, no lockscreen
- **Boots directly into Linux** — custom boot animation → Phosh desktop
- **Bridges Android hardware** to Linux via a Unix socket service:
  - 📞 Phone calls (via oFono-compatible D-Bus interface)
  - 💬 SMS (via oFono messaging)
  - 📷 Camera (via GStreamer/PipeWire virtual camera)
  - 🔊 Audio/Microphone (via PipeWire bridge)
  - 📍 GPS (via GeoClue2)
  - 🔄 Sensors (accelerometer, gyroscope, proximity, light)
  - 🔵 Bluetooth
  - 📳 Haptics/Vibration
  - 🔔 Notifications
  - 🔦 Flashlight, brightness, battery, signal
- **Works on every rooted phone** — no kernel mods, no reflashing, no per-device porting

## Architecture

```
┌─────────────────────────────────────────┐
│  What the user sees: Linux (Phosh)      │
│  ┌─────────────────────────────────┐    │
│  │ Phosh (GNOME Phone Shell)       │    │
│  │ GNOME Calls · Chatty · Camera   │    │
│  │ Terminal · Firefox · Any app    │    │
│  └──────────┬──────────────────────┘    │
│             │ Wayland                    │
│  ┌──────────▼──────────────────────┐    │
│  │ phoc (wlroots compositor)       │    │
│  │ Nested on X11 (LorieView)       │    │
│  └──────────┬──────────────────────┘    │
│             │                            │
│  ┌──────────▼──────────────────────┐    │
│  │ NativOS Bridge Client (Python)  │    │
│  │ ↕ Unix socket ↕                 │    │
│  └──────────┬──────────────────────┘    │
│             │ /run/nativOS/bridge.sock   │
├─────────────┼───────────────────────────┤
│  Android    │ (invisible)               │
│  ┌──────────▼──────────────────────┐    │
│  │ NativOS Bridge Service (Kotlin) │    │
│  │ Telephony · SMS · Camera · GPS  │    │
│  │ Sensors · BT · Audio · Haptics  │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

## Requirements

- ARM64 Android phone (Snapdragon, MediaTek, or Tensor)
- Android 9+ (API 28+)
- Root access (Magisk, KernelSU, or any su binary)
- 4GB+ RAM recommended
- 8GB+ free storage

## Project Structure

```
NativOS/
├── android/          # Android app (Kotlin/Gradle)
│   └── app/src/main/kotlin/com/nativOS/
│       ├── launcher/     # Kiosk mode, boot receiver, device admin
│       ├── bridge/       # Unix socket service + 10 API bridge handlers
│       ├── runtime/      # Chroot manager, root shell
│       └── compositor/   # LorieView / Wayland display
├── linux/            # Scripts and daemons for the chroot
│   ├── bridge-client/    # Python bridge daemon
│   ├── setup/            # Phosh and bridge installation scripts
│   └── config/           # phoc.ini, systemd service
├── boot/             # Boot animation Magisk module
└── docs/             # Architecture, protocol, device support
```

## Development Status

- [x] Project architecture and bridge protocol design
- [x] Android bridge service with 10 API handlers
- [x] Linux bridge client daemon
- [x] Kiosk launcher with lockscreen bypass
- [x] Boot receiver for auto-start
- [ ] LorieView integration (Wayland/X11 compositor)
- [ ] Phosh running in chroot
- [ ] End-to-end call/SMS test
- [ ] Pre-built rootfs with Phosh
- [ ] Boot animation assets

## How It Differs from Halium/Droidian

| | Halium/Droidian | NativOS |
|---|---|---|
| **Approach** | Replace PID 1, Linux boots natively | Android boots, Linux in chroot |
| **Requires reflashing** | Yes | No |
| **Per-device porting** | Yes (months per device) | No (works on any rooted phone) |
| **Kernel mods** | Yes | No |
| **Hardware access** | Via libhybris + Android HAL | Via Unix socket bridge to Android APIs |
| **Supported devices** | ~50 after 8+ years | Every rooted ARM64 phone |

## License

GPL-3.0 — see [LICENSE](LICENSE)

## Credits

Created by [orailnoor](https://youtube.com/@orailnoor)

Built on the foundation of [DroidDesk](https://github.com/orailnoor/DroidDesk).
# NativOS
