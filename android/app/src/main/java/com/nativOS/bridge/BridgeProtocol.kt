package com.nativOS.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge protocol definitions.
 *
 * All communication between Android and Linux happens over a Unix domain socket
 * using JSON Lines (one JSON object per line, newline-delimited).
 *
 * Message types:
 * - Request:  Linux → Android  (has id, action, params)
 * - Response: Android → Linux  (has id, status, data)
 * - Event:    Android → Linux  (no id, has event name, data — unsolicited)
 *
 * Audio and camera frame streams use separate dedicated sockets for performance.
 */
object BridgeProtocol {

    // ── Message Types ──
    const val TYPE_TELEPHONY = "telephony"
    const val TYPE_SMS = "sms"
    const val TYPE_CAMERA = "camera"
    const val TYPE_AUDIO = "audio"
    const val TYPE_SENSOR = "sensor"
    const val TYPE_LOCATION = "location"
    const val TYPE_BLUETOOTH = "bluetooth"
    const val TYPE_HAPTICS = "haptics"
    const val TYPE_NOTIFICATION = "notification"
    const val TYPE_SYSTEM = "system"

    // ── Status Codes ──
    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"

    // ── Telephony Actions ──
    const val ACTION_DIAL = "dial"
    const val ACTION_ANSWER = "answer"
    const val ACTION_HANGUP = "hangup"
    const val ACTION_HOLD = "hold"
    const val ACTION_UNHOLD = "unhold"
    const val ACTION_DTMF = "dtmf"
    const val ACTION_GET_CALL_STATE = "get_call_state"

    // ── Telephony Events ──
    const val EVENT_INCOMING_CALL = "incoming_call"
    const val EVENT_CALL_STATE_CHANGED = "call_state_changed"
    const val EVENT_CALL_ENDED = "call_ended"

    // ── SMS Actions ──
    const val ACTION_SEND_SMS = "send"
    const val ACTION_QUERY_CONVERSATIONS = "query_conversations"
    const val ACTION_QUERY_MESSAGES = "query_messages"
    const val ACTION_MARK_READ = "mark_read"

    // ── SMS Events ──
    const val EVENT_INCOMING_SMS = "incoming_sms"

    // ── Camera Actions ──
    const val ACTION_START_PREVIEW = "start_preview"
    const val ACTION_STOP_PREVIEW = "stop_preview"
    const val ACTION_CAPTURE_PHOTO = "capture_photo"
    const val ACTION_LIST_CAMERAS = "list_cameras"
    const val ACTION_SWITCH_CAMERA = "switch_camera"

    // ── Sensor Actions ──
    const val ACTION_SUBSCRIBE = "subscribe"
    const val ACTION_UNSUBSCRIBE = "unsubscribe"
    const val ACTION_LIST_SENSORS = "list_sensors"

    // ── Sensor Events ──
    const val EVENT_ACCELEROMETER = "accelerometer"
    const val EVENT_GYROSCOPE = "gyroscope"
    const val EVENT_PROXIMITY = "proximity"
    const val EVENT_LIGHT = "light"
    const val EVENT_MAGNETOMETER = "magnetometer"

    // ── Location Actions ──
    const val ACTION_START_LOCATION = "start_updates"
    const val ACTION_STOP_LOCATION = "stop_updates"
    const val ACTION_GET_LAST_KNOWN = "get_last_known"

    // ── Location Events ──
    const val EVENT_LOCATION_UPDATE = "location_update"

    // ── Bluetooth Actions ──
    const val ACTION_BT_ENABLE = "enable"
    const val ACTION_BT_DISABLE = "disable"
    const val ACTION_BT_SCAN = "scan"
    const val ACTION_BT_PAIR = "pair"
    const val ACTION_BT_CONNECT = "connect"
    const val ACTION_BT_DISCONNECT = "disconnect"

    // ── Bluetooth Events ──
    const val EVENT_BT_DEVICE_FOUND = "device_found"
    const val EVENT_BT_CONNECTION_STATE = "connection_state"
    const val EVENT_BT_STATE = "bt_state"

    // ── Haptics Actions ──
    const val ACTION_VIBRATE = "vibrate"
    const val ACTION_VIBRATE_PATTERN = "vibrate_pattern"
    const val ACTION_VIBRATE_CANCEL = "cancel"

    // ── Notification Actions ──
    const val ACTION_POST_NOTIFICATION = "post"
    const val ACTION_CANCEL_NOTIFICATION = "cancel"
    const val EVENT_NOTIFICATION_RECEIVED = "notification_received"

    // ── System Actions ──
    const val ACTION_SET_BRIGHTNESS = "set_brightness"
    const val ACTION_GET_BATTERY = "get_battery"
    const val ACTION_TORCH_ON = "torch_on"
    const val ACTION_TORCH_OFF = "torch_off"
    const val ACTION_GET_SIGNAL = "get_signal"

    // ── System Events ──
    const val EVENT_BATTERY_CHANGED = "battery_changed"
    const val EVENT_SIGNAL_CHANGED = "signal_changed"

    // ── Socket Paths ──
    const val SOCKET_NAME = "bridge.sock"         // Main control socket (JSON Lines)
    const val AUDIO_SOCKET_NAME = "audio.sock"    // Raw PCM audio stream
    const val CAMERA_SOCKET_NAME = "camera.sock"  // Raw camera frames

    // ── Message Builders ──

    /** Build a response message. */
    fun response(id: Int, status: String, data: JSONObject? = null): String {
        val msg = JSONObject().apply {
            put("id", id)
            put("status", status)
            if (data != null) put("data", data)
        }
        return msg.toString() + "\n"
    }

    /** Build an event message (unsolicited, no id). */
    fun event(type: String, eventName: String, data: JSONObject? = null): String {
        val msg = JSONObject().apply {
            putOpt("id", null)
            put("type", type)
            put("event", eventName)
            if (data != null) put("data", data)
        }
        return msg.toString() + "\n"
    }

    /** Parse a request message from Linux side. */
    fun parseRequest(line: String): BridgeRequest? {
        return try {
            val json = JSONObject(line)
            BridgeRequest(
                id = json.getInt("id"),
                type = json.getString("type"),
                action = json.getString("action"),
                params = json.optJSONObject("params") ?: JSONObject()
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Parsed request from the Linux bridge client. */
data class BridgeRequest(
    val id: Int,
    val type: String,
    val action: String,
    val params: JSONObject
)
