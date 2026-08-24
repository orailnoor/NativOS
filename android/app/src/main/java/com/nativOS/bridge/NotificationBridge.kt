package com.nativOS.bridge

import android.util.Log
import org.json.JSONObject

/**
 * Notification bridge — forwards notifications between Android and Linux.
 *
 * Actions: post (Linux → Android notification), cancel
 * Events: notification_received (Android → Linux)
 */
class NotificationBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.Notification"
    }

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_POST_NOTIFICATION -> handlePost(request)
            BridgeProtocol.ACTION_CANCEL_NOTIFICATION -> handleCancel(request)
            else -> BridgeProtocol.response(
                request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown notification action: ${request.action}")
            )
        }
    }

    private fun handlePost(request: BridgeRequest): String {
        val title = request.params.optString("title", "NativOS")
        val body = request.params.optString("body", "")
        val notifId = request.params.optInt("notif_id", System.currentTimeMillis().toInt())

        return try {
            val notification = android.app.Notification.Builder(service, "nativOS_bridge")
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()

            val manager = service.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            manager.notify(notifId, notification)

            Log.i(TAG, "Posted notification: $title")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                JSONObject().put("notif_id", notifId))
        } catch (e: Exception) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to post notification: ${e.message}"))
        }
    }

    private fun handleCancel(request: BridgeRequest): String {
        val notifId = request.params.optInt("notif_id", -1)
        if (notifId < 0) {
            return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Missing 'notif_id' parameter"))
        }

        val manager = service.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        manager.cancel(notifId)

        return BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
    }
}
