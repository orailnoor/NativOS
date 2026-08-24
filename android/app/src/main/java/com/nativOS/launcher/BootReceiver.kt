package com.nativOS.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nativOS.bridge.BridgeService

/**
 * Boot receiver that auto-starts NativOS on device boot.
 *
 * Handles:
 * - BOOT_COMPLETED (normal boot)
 * - LOCKED_BOOT_COMPLETED (direct boot aware)
 * - QUICKBOOT_POWERON (HTC, Xiaomi quick boot)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NativOS.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Boot event received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Starting NativOS on boot...")

                // Start the bridge service first
                val serviceIntent = Intent(context, BridgeService::class.java)
                context.startForegroundService(serviceIntent)

                // Launch the kiosk activity
                val kioskIntent = Intent(context, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(kioskIntent)

                Log.i(TAG, "NativOS boot sequence initiated")
            }
        }
    }
}
