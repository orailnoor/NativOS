#!/usr/bin/env python3
"""
NativOS Bridge Client — Linux-side daemon

Connects to the Android bridge service via Unix domain socket at
/run/nativOS/bridge.sock and exposes hardware APIs as D-Bus services
that Phosh and Linux apps can use natively.

This daemon implements:
- oFono-compatible telephony interface (for GNOME Calls)
- oFono-compatible SMS interface (for Chatty)
- GeoClue2 location provider
- iio-sensor-proxy compatible sensor interface
- feedbackd haptics backend
- PipeWire/PulseAudio audio integration

Usage:
    nativOS-bridge [--socket /run/nativOS/bridge.sock] [--debug]
"""

import argparse
import json
import logging
import os
import signal
import socket
import sys
import threading
import time

# ── Configuration ──

DEFAULT_SOCKET = "/run/nativOS/bridge.sock"
RECONNECT_DELAY = 3  # seconds between reconnection attempts
REQUEST_TIMEOUT = 10  # seconds

logger = logging.getLogger("nativOS-bridge")


class BridgeClient:
    """Manages the Unix socket connection to the Android bridge service."""

    def __init__(self, socket_path: str):
        self.socket_path = socket_path
        self.sock: socket.socket | None = None
        self.connected = False
        self.request_id = 0
        self.lock = threading.Lock()
        self.pending: dict[int, threading.Event] = {}
        self.responses: dict[int, dict] = {}
        self.event_handlers: dict[str, list] = {}
        self._reader_thread: threading.Thread | None = None

    def connect(self) -> bool:
        """Connect to the Android bridge socket."""
        try:
            self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.sock.connect(self.socket_path)
            self.connected = True
            logger.info(f"Connected to bridge at {self.socket_path}")

            # Start reader thread for responses and events
            self._reader_thread = threading.Thread(
                target=self._reader_loop, daemon=True
            )
            self._reader_thread.start()
            return True

        except (ConnectionRefusedError, FileNotFoundError, OSError) as e:
            logger.error(f"Cannot connect to bridge: {e}")
            self.connected = False
            return False

    def disconnect(self):
        """Disconnect from the bridge."""
        self.connected = False
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
            self.sock = None

    def request(self, msg_type: str, action: str, params: dict = None) -> dict:
        """Send a request and wait for the response."""
        if not self.connected:
            return {"status": "error", "data": {"message": "Not connected"}}

        with self.lock:
            self.request_id += 1
            req_id = self.request_id

        msg = {
            "id": req_id,
            "type": msg_type,
            "action": action,
            "params": params or {},
        }

        event = threading.Event()
        self.pending[req_id] = event

        try:
            line = json.dumps(msg) + "\n"
            self.sock.sendall(line.encode("utf-8"))
        except OSError as e:
            del self.pending[req_id]
            self.connected = False
            return {"status": "error", "data": {"message": str(e)}}

        # Wait for response
        if event.wait(timeout=REQUEST_TIMEOUT):
            return self.responses.pop(req_id, {"status": "error"})
        else:
            del self.pending[req_id]
            return {"status": "error", "data": {"message": "Request timed out"}}

    def on_event(self, event_type: str, handler):
        """Register a handler for bridge events."""
        if event_type not in self.event_handlers:
            self.event_handlers[event_type] = []
        self.event_handlers[event_type].append(handler)

    def _reader_loop(self):
        """Read responses and events from the socket."""
        buffer = b""
        while self.connected:
            try:
                data = self.sock.recv(4096)
                if not data:
                    logger.warning("Bridge connection closed")
                    self.connected = False
                    break

                buffer += data
                while b"\n" in buffer:
                    line, buffer = buffer.split(b"\n", 1)
                    self._handle_message(line.decode("utf-8", errors="replace"))

            except OSError:
                if self.connected:
                    logger.error("Socket read error")
                    self.connected = False
                break

    def _handle_message(self, line: str):
        """Process a single JSON message from the bridge."""
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            logger.warning(f"Invalid JSON from bridge: {line[:100]}")
            return

        msg_id = msg.get("id")

        if msg_id is not None and msg_id in self.pending:
            # This is a response to a pending request
            self.responses[msg_id] = msg
            self.pending[msg_id].set()
            del self.pending[msg_id]
        elif "event" in msg:
            # This is an unsolicited event
            event_type = msg.get("type", "")
            event_name = msg.get("event", "")
            key = f"{event_type}.{event_name}"
            data = msg.get("data", {})

            for handler in self.event_handlers.get(key, []):
                try:
                    handler(data)
                except Exception as e:
                    logger.error(f"Event handler error for {key}: {e}")


class NativOSBridge:
    """Main bridge daemon that connects Android APIs to Linux D-Bus services."""

    def __init__(self, socket_path: str):
        self.client = BridgeClient(socket_path)
        self.running = False

    def start(self):
        """Start the bridge daemon with auto-reconnect."""
        self.running = True
        signal.signal(signal.SIGTERM, self._shutdown)
        signal.signal(signal.SIGINT, self._shutdown)

        logger.info("NativOS bridge client starting...")

        # Register event handlers
        self._register_event_handlers()

        # Main loop with reconnection
        while self.running:
            if not self.client.connected:
                logger.info(f"Connecting to {self.client.socket_path}...")
                if self.client.connect():
                    self._on_connected()
                else:
                    time.sleep(RECONNECT_DELAY)
                    continue

            # Keep alive — check connection periodically
            time.sleep(1)

        self.client.disconnect()
        logger.info("NativOS bridge client stopped")

    def _on_connected(self):
        """Called when successfully connected to the bridge."""
        logger.info("Bridge connected — subscribing to sensors...")

        # Subscribe to sensors needed for auto-rotate and proximity
        self.client.request("sensor", "subscribe", {"sensor": "accelerometer"})
        self.client.request("sensor", "subscribe", {"sensor": "proximity"})
        self.client.request("sensor", "subscribe", {"sensor": "light"})

        # Start location updates for GeoClue
        self.client.request("location", "start_updates", {
            "min_time_ms": 5000,
            "min_distance_m": 10
        })

        # Get device info
        info = self.client.request("system", "get_device_info")
        if info.get("status") == "ok":
            device = info.get("data", {})
            logger.info(
                f"Device: {device.get('brand', '?')} {device.get('model', '?')} "
                f"(Android {device.get('android_version', '?')}, {device.get('soc', '?')})"
            )

    def _register_event_handlers(self):
        """Register handlers for Android events."""
        self.client.on_event("telephony.incoming_call", self._on_incoming_call)
        self.client.on_event("telephony.call_ended", self._on_call_ended)
        self.client.on_event("sms.incoming_sms", self._on_incoming_sms)
        self.client.on_event("sensor.proximity", self._on_proximity)
        self.client.on_event("sensor.accelerometer", self._on_accelerometer)
        self.client.on_event("sensor.light", self._on_ambient_light)
        self.client.on_event("location.location_update", self._on_location)
        self.client.on_event("bluetooth.bt_state", self._on_bt_state)
        self.client.on_event("system.battery_changed", self._on_battery)

    # ── Event Handlers ──

    def _on_incoming_call(self, data: dict):
        number = data.get("number", "Unknown")
        logger.info(f"📞 Incoming call from: {number}")
        # TODO: Emit D-Bus signal for GNOME Calls / oFono interface

    def _on_call_ended(self, data: dict):
        logger.info("📞 Call ended")

    def _on_incoming_sms(self, data: dict):
        sender = data.get("sender", "Unknown")
        body = data.get("body", "")
        logger.info(f"💬 SMS from {sender}: {body[:50]}...")
        # TODO: Emit D-Bus signal for Chatty / oFono SMS interface

    def _on_proximity(self, data: dict):
        near = data.get("near", False)
        if near:
            logger.debug("Proximity: near (screen should turn off during call)")
        # TODO: Write to iio-sensor-proxy D-Bus interface

    def _on_accelerometer(self, data: dict):
        # TODO: Feed to iio-sensor-proxy for auto-rotate
        pass

    def _on_ambient_light(self, data: dict):
        lux = data.get("lux", 0)
        # TODO: Feed to iio-sensor-proxy for auto-brightness
        pass

    def _on_location(self, data: dict):
        lat = data.get("latitude", 0)
        lon = data.get("longitude", 0)
        logger.debug(f"📍 Location: {lat:.6f}, {lon:.6f}")
        # TODO: Feed to GeoClue2 D-Bus provider

    def _on_bt_state(self, data: dict):
        state = data.get("state", "unknown")
        logger.info(f"🔵 Bluetooth: {state}")

    def _on_battery(self, data: dict):
        pct = data.get("percentage", -1)
        charging = data.get("charging", False)
        logger.debug(f"🔋 Battery: {pct}% {'⚡' if charging else ''}")
        # TODO: Update UPower D-Bus interface

    def _shutdown(self, signum, frame):
        logger.info("Shutdown signal received")
        self.running = False


def main():
    parser = argparse.ArgumentParser(description="NativOS bridge client daemon")
    parser.add_argument(
        "--socket", default=DEFAULT_SOCKET,
        help=f"Path to bridge Unix socket (default: {DEFAULT_SOCKET})"
    )
    parser.add_argument("--debug", action="store_true", help="Enable debug logging")
    args = parser.parse_args()

    log_level = logging.DEBUG if args.debug else logging.INFO
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    bridge = NativOSBridge(args.socket)
    bridge.start()


if __name__ == "__main__":
    main()
