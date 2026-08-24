#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  NativOS — Install Bridge Client in Chroot
#
#  Installs the Python bridge daemon and systemd service
#  that connects Linux apps to Android hardware APIs.
# ═══════════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR/../bridge-client"
CONFIG_DIR="$SCRIPT_DIR/../config"

echo "╔══════════════════════════════════════════╗"
echo "║    NativOS — Installing Bridge Client    ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── Install Python dependencies ──
echo "[1/4] Installing Python 3..."
export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends python3 python3-pip python3-dbus 2>/dev/null || true

# ── Copy bridge daemon ──
echo "[2/4] Installing bridge daemon..."
cp "$BRIDGE_DIR/nativOS-bridge.py" /usr/local/bin/nativOS-bridge
chmod +x /usr/local/bin/nativOS-bridge

# ── Copy phoc config ──
echo "[3/4] Installing phoc configuration..."
mkdir -p /etc/nativOS
cp "$CONFIG_DIR/phoc.ini" /etc/nativOS/phoc.ini

# ── Install systemd service ──
echo "[4/4] Installing systemd service..."
cp "$CONFIG_DIR/nativOS-bridge.service" /etc/systemd/system/
systemctl daemon-reload 2>/dev/null || true
systemctl enable nativOS-bridge.service 2>/dev/null || true

# ── Create bridge socket directory ──
mkdir -p /run/nativOS

echo ""
echo "✅ Bridge client installed!"
echo "   Daemon: /usr/local/bin/nativOS-bridge"
echo "   Service: nativOS-bridge.service"
echo "   Socket: /run/nativOS/bridge.sock (bind-mounted from Android)"
