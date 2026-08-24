package com.nativOS.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMS bridge — exposes Android's SMS functionality to Linux.
 *
 * Actions: send, query_conversations, query_messages, mark_read
 * Events: incoming_sms
 */
class SmsBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.SMS"
    }

    init {
        Log.i(TAG, "SMS bridge initialized")
    }

    fun registerReceivers() {
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        service.registerReceiver(smsReceiver, filter)
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format") ?: "3gpp"

            for (pdu in pdus) {
                try {
                    val message = SmsMessage.createFromPdu(pdu as ByteArray, format)
                    val data = JSONObject().apply {
                        put("sender", message.displayOriginatingAddress ?: "")
                        put("body", message.displayMessageBody ?: "")
                        put("timestamp", message.timestampMillis)
                    }

                    Log.i(TAG, "Incoming SMS from: ${message.displayOriginatingAddress}")
                    service.broadcastEvent(
                        BridgeProtocol.TYPE_SMS,
                        BridgeProtocol.EVENT_INCOMING_SMS,
                        data
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing SMS: ${e.message}")
                }
            }
        }
    }

    fun handle(request: BridgeRequest): String {
        return when (request.action) {
            BridgeProtocol.ACTION_SEND_SMS -> handleSend(request)
            BridgeProtocol.ACTION_QUERY_CONVERSATIONS -> handleQueryConversations(request)
            BridgeProtocol.ACTION_QUERY_MESSAGES -> handleQueryMessages(request)
            BridgeProtocol.ACTION_MARK_READ -> handleMarkRead(request)
            else -> BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Unknown SMS action: ${request.action}")
            )
        }
    }

    private fun handleSend(request: BridgeRequest): String {
        val to = request.params.optString("to", "")
        val body = request.params.optString("body", "")

        if (to.isEmpty() || body.isEmpty()) {
            return BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Missing 'to' or 'body' parameter")
            )
        }

        return try {
            @Suppress("DEPRECATION")
            val smsManager = SmsManager.getDefault()
            // Handle multi-part messages
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(to, null, body, null, null)
            }
            Log.i(TAG, "SMS sent to: $to")
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
        } catch (e: SecurityException) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "SEND_SMS permission not granted")
            )
        } catch (e: Exception) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to send SMS: ${e.message}")
            )
        }
    }

    private fun handleQueryConversations(request: BridgeRequest): String {
        return try {
            val limit = request.params.optInt("limit", 50)
            val conversations = JSONArray()

            val cursor = service.contentResolver.query(
                android.net.Uri.parse("content://sms/conversations"),
                null, null, null, "date DESC"
            )

            cursor?.use {
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val conv = JSONObject().apply {
                        put("thread_id", it.getString(it.getColumnIndexOrThrow("thread_id")))
                        put("snippet", it.getString(it.getColumnIndexOrThrow("snippet")))
                        put("msg_count", it.getInt(it.getColumnIndexOrThrow("msg_count")))
                    }
                    conversations.put(conv)
                    count++
                }
            }

            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_OK,
                JSONObject().put("conversations", conversations)
            )
        } catch (e: Exception) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to query conversations: ${e.message}")
            )
        }
    }

    private fun handleQueryMessages(request: BridgeRequest): String {
        return try {
            val threadId = request.params.optString("thread_id", "")
            val limit = request.params.optInt("limit", 100)
            val messages = JSONArray()

            val selection = if (threadId.isNotEmpty()) "thread_id = ?" else null
            val selectionArgs = if (threadId.isNotEmpty()) arrayOf(threadId) else null

            val cursor = service.contentResolver.query(
                android.net.Uri.parse("content://sms"),
                arrayOf("_id", "thread_id", "address", "body", "date", "type", "read"),
                selection, selectionArgs, "date DESC LIMIT $limit"
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val msg = JSONObject().apply {
                        put("id", it.getString(it.getColumnIndexOrThrow("_id")))
                        put("thread_id", it.getString(it.getColumnIndexOrThrow("thread_id")))
                        put("address", it.getString(it.getColumnIndexOrThrow("address")))
                        put("body", it.getString(it.getColumnIndexOrThrow("body")))
                        put("date", it.getLong(it.getColumnIndexOrThrow("date")))
                        put("type", it.getInt(it.getColumnIndexOrThrow("type"))) // 1=inbox, 2=sent
                        put("read", it.getInt(it.getColumnIndexOrThrow("read")) == 1)
                    }
                    messages.put(msg)
                }
            }

            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_OK,
                JSONObject().put("messages", messages)
            )
        } catch (e: Exception) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to query messages: ${e.message}")
            )
        }
    }

    private fun handleMarkRead(request: BridgeRequest): String {
        val messageId = request.params.optString("message_id", "")
        if (messageId.isEmpty()) {
            return BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Missing 'message_id' parameter")
            )
        }

        return try {
            val values = android.content.ContentValues().apply {
                put("read", 1)
            }
            service.contentResolver.update(
                android.net.Uri.parse("content://sms/$messageId"),
                values, null, null
            )
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
        } catch (e: Exception) {
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Failed to mark read: ${e.message}")
            )
        }
    }
}
