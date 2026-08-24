package com.nativOS.bridge

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.json.JSONObject

/**
 * Haptics bridge — controls Android's vibration motor for Linux's feedbackd.
 *
 * Actions: vibrate, vibrate_pattern, cancel
 */
class HapticsBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.Haptics"
    }

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (service.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        service.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_VIBRATE -> handleVibrate(request)
            BridgeProtocol.ACTION_VIBRATE_PATTERN -> handleVibratePattern(request)
            BridgeProtocol.ACTION_VIBRATE_CANCEL -> handleCancel(request)
            else -> BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown haptics action: ${request.action}")
            )
        }
    }

    private fun handleVibrate(request: BridgeRequest): String {
        val durationMs = request.params.optLong("duration_ms", 200)
        val amplitude = request.params.optInt("amplitude", VibrationEffect.DEFAULT_AMPLITUDE)

        return try {
            val effect = VibrationEffect.createOneShot(durationMs, amplitude)
            vibrator.vibrate(effect)
            Log.d(TAG, "Vibrate: ${durationMs}ms, amplitude=$amplitude")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Vibration failed: ${e.message}"))
        }
    }

    private fun handleVibratePattern(request: BridgeRequest): String {
        val patternArray = request.params.optJSONArray("pattern")
        val repeat = request.params.optInt("repeat", -1) // -1 = no repeat

        if (patternArray == null || patternArray.length() == 0) {
            return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Missing 'pattern' array"))
        }

        return try {
            val pattern = LongArray(patternArray.length()) { patternArray.getLong(it) }
            val effect = VibrationEffect.createWaveform(pattern, repeat)
            vibrator.vibrate(effect)
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Pattern vibration failed: ${e.message}"))
        }
    }

    private fun handleCancel(request: BridgeRequest): String {
        vibrator.cancel()
        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }
}
