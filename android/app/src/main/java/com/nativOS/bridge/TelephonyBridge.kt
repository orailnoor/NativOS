package com.nativOS.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject

/**
 * Telephony bridge — exposes Android's call functionality to Linux.
 *
 * Actions: dial, answer, hangup, hold, unhold, dtmf, get_call_state
 * Events: incoming_call, call_state_changed, call_ended
 */
class TelephonyBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.Telephony"
    }

    private val telephonyManager =
        service.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    init {
        Log.i(TAG, "Telephony bridge initialized")
    }

    fun registerListeners() {
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            Log.i(TAG, "Listening for call state changes")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot listen for call state — permission not granted yet")
        }
    }

    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val stateName = when (state) {
                TelephonyManager.CALL_STATE_IDLE -> "idle"
                TelephonyManager.CALL_STATE_RINGING -> "ringing"
                TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
                else -> "unknown"
            }

            Log.d(TAG, "Call state: $stateName, number: $phoneNumber")

            val data = JSONObject().apply {
                put("state", stateName)
                put("number", phoneNumber ?: "")
            }

            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    service.broadcastEvent(
                        BridgeProtocol.TYPE_TELEPHONY,
                        BridgeProtocol.EVENT_INCOMING_CALL,
                        data
                    )
                }
                TelephonyManager.CALL_STATE_IDLE -> {
                    service.broadcastEvent(
                        BridgeProtocol.TYPE_TELEPHONY,
                        BridgeProtocol.EVENT_CALL_ENDED,
                        data
                    )
                }
                else -> {
                    service.broadcastEvent(
                        BridgeProtocol.TYPE_TELEPHONY,
                        BridgeProtocol.EVENT_CALL_STATE_CHANGED,
                        data
                    )
                }
            }
        }
    }

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_DIAL -> handleDial(request)
            BridgeProtocol.ACTION_ANSWER -> handleAnswer(request)
            BridgeProtocol.ACTION_HANGUP -> handleHangup(request)
            BridgeProtocol.ACTION_GET_CALL_STATE -> handleGetCallState(request)
            BridgeProtocol.ACTION_DTMF -> handleDtmf(request)
            else -> BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown telephony action: ${request.action}")
            )
        }
    }

    private fun handleDial(request: BridgeRequest): String {
        val number = request.params.optString("number", "")
        if (number.isEmpty()) {
            return BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Missing 'number' parameter")
            )
        }

        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            Log.i(TAG, "Dialing: $number")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
        } catch (e: SecurityException) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "CALL_PHONE permission not granted")
            )
        }
    }

    private fun handleAnswer(request: BridgeRequest): String {
        return try {
            // Use TelecomManager to answer on Android 8+
            val telecomManager = service.getSystemService(Context.TELECOM_SERVICE)
                as android.telecom.TelecomManager
            telecomManager.acceptRingingCall()
            Log.i(TAG, "Call answered")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
        } catch (e: SecurityException) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "ANSWER_PHONE_CALLS permission not granted")
            )
        }
    }

    private fun handleHangup(request: BridgeRequest): String {
        return try {
            val telecomManager = service.getSystemService(Context.TELECOM_SERVICE)
                as android.telecom.TelecomManager
            telecomManager.endCall()
            Log.i(TAG, "Call ended")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
        } catch (e: SecurityException) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Permission not granted")
            )
        }
    }

    private fun handleGetCallState(request: BridgeRequest): String {
        val state = when (telephonyManager.callState) {
            TelephonyManager.CALL_STATE_IDLE -> "idle"
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "unknown"
        }
        return BridgeProtocol.response(
            request.id,
            BridgeProtocol.STATUS_OK,
            JSONObject().put("state", state)
        )
    }

    private fun handleDtmf(request: BridgeRequest): String {
        // DTMF tones during active calls require TelecomManager InCallService
        // This is a placeholder — full implementation requires InCallService binding
        val tone = request.params.optString("tone", "")
        Log.i(TAG, "DTMF tone requested: $tone (not yet implemented)")
        return BridgeProtocol.response(
            request.id,
            BridgeProtocol.STATUS_ERROR,
            JSONObject().put("message", "DTMF not yet implemented — requires InCallService")
        )
    }
}
