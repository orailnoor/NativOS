package com.nativOS.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * NativOS Bridge Service.
 *
 * A foreground Android service that runs a Unix domain socket server.
 * The Linux chroot connects to this socket to access Android hardware APIs.
 *
 * The socket is at: /data/data/com.nativOS/files/bridge/bridge.sock
 * which is bind-mounted into the chroot at: /run/nativOS/bridge.sock
 *
 * Each API bridge (telephony, SMS, camera, etc.) registers as a handler
 * and processes requests routed by type.
 */
class BridgeService : Service() {

    companion object {
        private const val TAG = "NativOS.Bridge"
        private const val NOTIFICATION_CHANNEL = "nativOS_bridge"
        private const val NOTIFICATION_ID = 1001
    }

    private var serverSocket: LocalServerSocket? = null
    private var running = false

    // Bridge handlers — one per API category
    private lateinit var telephonyBridge: TelephonyBridge
    private lateinit var smsBridge: SmsBridge
    private lateinit var cameraBridge: CameraBridge
    private lateinit var audioBridge: AudioBridge
    private lateinit var sensorBridge: SensorBridge
    private lateinit var locationBridge: LocationBridge
    private lateinit var bluetoothBridge: BluetoothBridge
    private lateinit var hapticsBridge: HapticsBridge
    private lateinit var notificationBridge: NotificationBridge
    private lateinit var systemBridge: SystemBridge

    // Connected clients that need to receive events
    private val eventClients = mutableListOf<OutputStream>()
    private val eventClientsLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Bridge service created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initBridges()
        startSocketServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Restart if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        serverSocket?.close()
        cleanupBridges()
        Log.i(TAG, "Bridge service destroyed")
    }

    private fun initBridges() {
        telephonyBridge = TelephonyBridge(this)
        telephonyBridge.registerListeners()
        
        smsBridge = SmsBridge(this)
        smsBridge.registerReceivers()
        
        cameraBridge = CameraBridge(this)
        audioBridge = AudioBridge(this)
        sensorBridge = SensorBridge(this)
        locationBridge = LocationBridge(this)
        
        bluetoothBridge = BluetoothBridge(this)
        bluetoothBridge.registerReceivers()
        
        hapticsBridge = HapticsBridge(this)
        notificationBridge = NotificationBridge(this)
        systemBridge = SystemBridge(this)

        Log.i(TAG, "All bridge handlers initialized")
    }

    private fun cleanupBridges() {
        sensorBridge.cleanup()
        locationBridge.cleanup()
        cameraBridge.cleanup()
        audioBridge.cleanup()
    }

    private fun startSocketServer() {
        running = true
        thread(name = "bridge-socket-server") {
            try {
                // Create the socket file in the bridge directory
                val socketPath = "${filesDir.absolutePath}/bridge/${BridgeProtocol.SOCKET_NAME}"
                java.io.File(socketPath).parentFile?.mkdirs()
                java.io.File(socketPath).delete() // Remove stale socket

                // Use abstract namespace socket for reliability
                serverSocket = LocalServerSocket("nativOS_bridge")
                Log.i(TAG, "Bridge socket server listening on: nativOS_bridge")

                // Also create a filesystem socket via symlink for the chroot
                // The chroot accesses /run/nativOS/bridge.sock
                createFilesystemSocket(socketPath)

                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        Log.i(TAG, "Bridge client connected")
                        handleClient(client)
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Error accepting client: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket server failed: ${e.message}", e)
            }
        }
    }

    /**
     * Create a filesystem-accessible Unix socket that the chroot can connect to.
     * This is needed because LocalServerSocket uses Android's abstract namespace,
     * which isn't accessible from inside a chroot.
     */
    private fun createFilesystemSocket(socketPath: String) {
        thread(name = "bridge-fs-socket") {
            try {
                val file = java.io.File(socketPath)
                file.parentFile?.mkdirs()
                file.delete()

                // Use a real filesystem Unix domain socket
                val address = android.net.LocalSocketAddress(
                    socketPath,
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM
                )
                val fsServer = LocalServerSocket(address.name)
                Log.i(TAG, "Filesystem bridge socket at: $socketPath")

                while (running) {
                    try {
                        val client = fsServer.accept() ?: break
                        Log.i(TAG, "Bridge client connected via filesystem socket")
                        handleClient(client)
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "FS socket error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Filesystem socket failed: ${e.message}")
            }
        }
    }

    private fun handleClient(client: LocalSocket) {
        thread(name = "bridge-client-handler") {
            try {
                val input = BufferedReader(InputStreamReader(client.inputStream))
                val output = client.outputStream

                // Register for events
                synchronized(eventClientsLock) {
                    eventClients.add(output)
                }

                var line: String?
                while (input.readLine().also { line = it } != null) {
                    val request = BridgeProtocol.parseRequest(line!!)
                    if (request == null) {
                        Log.w(TAG, "Invalid request: $line")
                        continue
                    }

                    Log.d(TAG, "Request: type=${request.type} action=${request.action} id=${request.id}")
                    val response = routeRequest(request)
                    output.write(response.toByteArray())
                    output.flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Client disconnected: ${e.message}")
            } finally {
                synchronized(eventClientsLock) {
                    eventClients.remove(client.outputStream)
                }
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    /** Route a request to the appropriate bridge handler. */
    private fun routeRequest(request: BridgeRequest): String {
        return try {
            when (request.type) {
                BridgeProtocol.TYPE_TELEPHONY -> telephonyBridge.handle(request)
                BridgeProtocol.TYPE_SMS -> smsBridge.handle(request)
                BridgeProtocol.TYPE_CAMERA -> cameraBridge.handle(request)
                BridgeProtocol.TYPE_AUDIO -> audioBridge.handle(request)
                BridgeProtocol.TYPE_SENSOR -> sensorBridge.handle(request)
                BridgeProtocol.TYPE_LOCATION -> locationBridge.handle(request)
                BridgeProtocol.TYPE_BLUETOOTH -> bluetoothBridge.handle(request)
                BridgeProtocol.TYPE_HAPTICS -> hapticsBridge.handle(request)
                BridgeProtocol.TYPE_NOTIFICATION -> notificationBridge.handle(request)
                BridgeProtocol.TYPE_SYSTEM -> systemBridge.handle(request)
                else -> BridgeProtocol.response(
                    request.id,
                    BridgeProtocol.STATUS_ERROR,
                    JSONObject().put("message", "Unknown type: ${request.type}")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: ${e.message}", e)
            BridgeProtocol.response(
                request.id,
                BridgeProtocol.STATUS_ERROR,
                JSONObject().put("message", e.message ?: "Unknown error")
            )
        }
    }

    /** Broadcast an event to all connected clients. */
    fun broadcastEvent(type: String, eventName: String, data: JSONObject? = null) {
        val eventMsg = BridgeProtocol.event(type, eventName, data)
        val bytes = eventMsg.toByteArray()

        synchronized(eventClientsLock) {
            val disconnected = mutableListOf<OutputStream>()
            for (client in eventClients) {
                try {
                    client.write(bytes)
                    client.flush()
                } catch (e: Exception) {
                    disconnected.add(client)
                }
            }
            eventClients.removeAll(disconnected.toSet())
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "NativOS Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "NativOS hardware bridge service"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("NativOS")
            .setContentText("Linux bridge active")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}
