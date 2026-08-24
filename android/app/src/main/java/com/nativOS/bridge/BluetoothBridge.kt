package com.nativOS.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bluetooth bridge — controls Android's Bluetooth via BluetoothAdapter for Linux.
 *
 * Actions: enable, disable, scan, pair, connect, disconnect
 * Events: device_found, connection_state, bt_state
 */
class BluetoothBridge(private val service: BridgeService) {

    companion object {
        private const val TAG = "NativOS.Bluetooth"
    }

    private val bluetoothManager =
        service.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    init {
        Log.i(TAG, "Bluetooth bridge initialized")
    }

    fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        service.registerReceiver(btReceiver, filter)
    }

    private val btReceiver = object : BroadcastReceiver() {
        @Suppress("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        try {
                            service.broadcastEvent(
                                BridgeProtocol.TYPE_BLUETOOTH,
                                BridgeProtocol.EVENT_BT_DEVICE_FOUND,
                                JSONObject().apply {
                                    put("name", it.name ?: "Unknown")
                                    put("address", it.address)
                                    put("type", it.type)
                                    put("bonded", it.bondState == BluetoothDevice.BOND_BONDED)
                                }
                            )
                        } catch (e: SecurityException) {
                            Log.w(TAG, "BT permission issue in discovery: ${e.message}")
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val stateName = when (state) {
                        BluetoothAdapter.STATE_ON -> "on"
                        BluetoothAdapter.STATE_OFF -> "off"
                        BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
                        BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
                        else -> "unknown"
                    }
                    service.broadcastEvent(
                        BridgeProtocol.TYPE_BLUETOOTH,
                        BridgeProtocol.EVENT_BT_STATE,
                        JSONObject().put("state", stateName)
                    )
                }
            }
        }
    }

    @Suppress("MissingPermission")
    fun handle(request: BridgeRequest): String {
        return try {
            when (request.action) {
                BridgeProtocol.ACTION_BT_ENABLE -> {
                    bluetoothAdapter?.enable()
                    BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
                }
                BridgeProtocol.ACTION_BT_DISABLE -> {
                    bluetoothAdapter?.disable()
                    BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK)
                }
                BridgeProtocol.ACTION_BT_SCAN -> {
                    bluetoothAdapter?.startDiscovery()
                    BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                        JSONObject().put("message", "Scan started — listen for device_found events"))
                }
                "get_paired" -> {
                    val paired = JSONArray()
                    bluetoothAdapter?.bondedDevices?.forEach { device ->
                        paired.put(JSONObject().apply {
                            put("name", device.name ?: "Unknown")
                            put("address", device.address)
                            put("type", device.type)
                        })
                    }
                    BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                        JSONObject().put("devices", paired))
                }
                "get_state" -> {
                    val state = if (bluetoothAdapter?.isEnabled == true) "on" else "off"
                    BridgeProtocol.response(request.id, BridgeProtocol.STATUS_OK,
                        JSONObject().put("state", state))
                }
                else -> BridgeProtocol.response(
                    request.id, BridgeProtocol.STATUS_ERROR,
                    JSONObject().put("message", "Unknown BT action: ${request.action}")
                )
            }
        } catch (e: SecurityException) {
            BridgeProtocol.response(request.id, BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", "Bluetooth permission not granted: ${e.message}"))
        }
    }
}
