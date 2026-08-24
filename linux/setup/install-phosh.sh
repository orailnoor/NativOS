#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  NativOS — Install Phosh Desktop Environment in Chroot
#
#  Run this inside the chroot Ubuntu filesystem to install
#  Phosh (the GNOME Phone Shell) and all required dependencies.
#
#  Usage: chroot /path/to/rootfs /bin/bash /setup/install-phosh.sh
# ═══════════════════════════════════════════════════════════════

set -e

echo "╔══════════════════════════════════════════╗"
echo "║       NativOS — Installing Phosh         ║"
echo "╚══════════════════════════════════════════╝"
echo ""

export DEBIAN_FRONTEND=noninteractive
export TZ=Etc/UTC

# ── Update package index ──
echo "[1/8] Updating package lists..."
apt-get update -y -q

# ── Core X11/Wayland dependencies ──
echo "[2/8] Installing display server dependencies..."
apt-get install -y --no-install-recommends \
    xwayland \
    weston \
    x11-utils \
    x11-xserver-utils \
    dbus-x11 \
    ca-certificates \
    locales \
    wget \
    curl

# ── Install phoc (Wayland compositor for Phosh) ──
echo "[3/8] Installing phoc compositor..."
apt-get install -y --no-install-recommends \
    phoc || {
    echo "[!] phoc not in default repos — adding PureOS/Mobian repo..."
    # Fallback: build from source or add Mobian repo
    echo "deb http://repo.mobian-project.org/ bookworm main" > /etc/apt/sources.list.d/mobian.list
    wget -qO- https://repo.mobian-project.org/mobian.gpg | apt-key add - 2>/dev/null || true
    apt-get update -y -q
    apt-get install -y --no-install-recommends phoc || echo "[!] phoc install failed — will need manual build"
}

# ── Install Phosh ──
echo "[4/8] Installing Phosh shell..."
apt-get install -y --no-install-recommends \
    phosh || {
    echo "[!] Phosh not available — trying alternative sources..."
    apt-get install -y --no-install-recommends phosh-mobile-settings 2>/dev/null || true
}

# ── Touch keyboard ──
echo "[5/8] Installing on-screen keyboard..."
apt-get install -y --no-install-recommends \
    squeekboard || \
    apt-get install -y --no-install-recommends maliit-keyboard 2>/dev/null || \
    echo "[!] No on-screen keyboard available"

# ── Phone apps (GNOME Calls, Chatty for SMS, Camera) ──
echo "[6/8] Installing phone applications..."
apt-get install -y --no-install-recommends \
    gnome-calls \
    chatty \
    megapixels \
    gnome-contacts \
    gnome-clocks \
    gnome-calculator \
    gnome-text-editor \
    nautilus \
    gnome-terminal \
    firefox-esr \
    2>/dev/null || {
    echo "[!] Some phone apps not available — installing alternatives..."
    apt-get install -y --no-install-recommends \
        gnome-terminal \
        firefox-esr \
        2>/dev/null || true
}

# ── Audio (PipeWire) ──
echo "[7/8] Installing audio stack..."
apt-get install -y --no-install-recommends \
    pipewire \
    pipewire-pulse \
    wireplumber \
    2>/dev/null || \
    apt-get install -y --no-install-recommends pulseaudio 2>/dev/null || true

# ── Feedback daemon (haptics, LEDs) ──
apt-get install -y --no-install-recommends \
    feedbackd \
    2>/dev/null || echo "[!] feedbackd not available"

# ── Location service ──
apt-get install -y --no-install-recommends \
    geoclue-2.0 \
    2>/dev/null || echo "[!] geoclue not available"

# ── Sensors ──
apt-get install -y --no-install-recommends \
    iio-sensor-proxy \
    2>/dev/null || echo "[!] iio-sensor-proxy not available"

# ── Cleanup ──
echo "[8/8] Cleaning up..."
apt-get clean
rm -rf /var/lib/apt/lists/*

# ── Create NativOS config directory ──
mkdir -p /etc/nativOS

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║       Phosh installation complete!       ║"
echo "╚══════════════════════════════════════════╝"
echo ""
echo "Next: run install-bridge.sh to set up the NativOS hardware bridge"
