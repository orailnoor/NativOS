package com.nativOS.x11

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import com.termux.x11.CmdEntryPoint
import java.io.File
import java.util.concurrent.CountDownLatch

/** Hosts the bundled X server in a process separate from LorieView. */
class X11ServerService : Service() {
    private lateinit var serverThread: HandlerThread
    private lateinit var serverHandler: Handler
    private val stateLock = Any()
    private var startLatch: CountDownLatch? = null
    @Volatile private var started = false
    @Volatile private var startSucceeded = false
    private val cmdEntryPoint = CmdEntryPoint()

    private val binder = object : IX11Service.Stub() {
        override fun startServer(): Boolean = ensureServerStarted()
        override fun getXConnection(): ParcelFileDescriptor? =
            if (ensureServerStarted()) cmdEntryPoint.xConnection else null
        override fun getLogcatOutput(): ParcelFileDescriptor? =
            if (ensureServerStarted()) cmdEntryPoint.logcatOutput else null
    }

    override fun onCreate() {
        super.onCreate()
        serverThread = HandlerThread("X11Server").apply { start() }
        serverHandler = Handler(serverThread.looper)
        Log.i(TAG, "X11 service created in pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        serverThread.quitSafely()
        super.onDestroy()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun ensureServerStarted(): Boolean {
        if (started) return startSucceeded
        val latch: CountDownLatch
        synchronized(stateLock) {
            if (started) return startSucceeded
            latch = startLatch ?: CountDownLatch(1).also { created ->
                startLatch = created
                serverHandler.post {
                    startSucceeded = try {
                        configureEnvironment()
                        Log.i(TAG, "Starting native X server in pid=${android.os.Process.myPid()}")
                        CmdEntryPoint.start(arrayOf(":0", "-nolock", "-legacy-drawing"))
                    } catch (error: Throwable) {
                        Log.e(TAG, "Native X server failed to start", error)
                        false
                    } finally {
                        started = true
                        created.countDown()
                    }
                    Log.i(TAG, "Native X server start result=$startSucceeded")
                }
            }
        }
        return try {
            latch.await()
            startSucceeded
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun configureEnvironment() {
        val appTmpDir = File(filesDir, "tmp").apply { mkdirs() }
        File(appTmpDir, ".X11-unix").mkdirs()
        Os.setenv("TMPDIR", appTmpDir.absolutePath, true)
        Os.setenv("XDG_RUNTIME_DIR", appTmpDir.absolutePath, true)
        Os.setenv("PREFIX", File(filesDir, "usr").absolutePath, true)
        Os.setenv("HOME", filesDir.absolutePath, true)

        val xkbRoot = File(filesDir, "rootfs/usr/share/X11/xkb")
        if (xkbRoot.exists()) Os.setenv("XKB_CONFIG_ROOT", xkbRoot.absolutePath, true)

        val staleSocket = File(appTmpDir, ".X11-unix/X0")
        if (staleSocket.exists() && !staleSocket.delete()) {
            Log.w(TAG, "Could not remove stale X11 socket ${staleSocket.absolutePath}")
        }
    }

    private companion object {
        const val TAG = "NativOS.X11Service"
    }
}
