package com.nativOS.x11

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class X11ServiceClient(
    context: Context,
    private val onConnected: (ParcelFileDescriptor, ParcelFileDescriptor?) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "X11BinderClient")
    }
    private val active = AtomicBoolean(false)
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IX11Service.Stub.asInterface(binder)
            executor.execute {
                try {
                    if (!active.get() || !service.startServer()) {
                        postError("The bundled X11 server could not start", null)
                        return@execute
                    }
                    val connectionFd = service.xConnection
                    if (connectionFd == null) {
                        postError("The bundled X11 server returned no renderer connection", null)
                        return@execute
                    }
                    val logcatFd = service.logcatOutput
                    mainHandler.post {
                        if (active.get()) onConnected(connectionFd, logcatFd)
                        else {
                            connectionFd.close()
                            logcatFd?.close()
                        }
                    }
                } catch (error: Throwable) {
                    postError("Failed to connect to the bundled X11 server", error)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) =
            postError("The bundled X11 server disconnected", null)
        override fun onBindingDied(name: ComponentName) =
            postError("The bundled X11 service binding died", null)
        override fun onNullBinding(name: ComponentName) =
            postError("The bundled X11 service returned a null binding", null)
    }

    fun connect() {
        if (!active.compareAndSet(false, true)) return
        val intent = Intent(appContext, X11ServerService::class.java)
        appContext.startService(intent)
        bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) postError("Android refused the bundled X11 service binding", null)
    }

    fun disconnect() {
        if (!active.getAndSet(false)) return
        if (bound) appContext.unbindService(connection)
        bound = false
        executor.shutdownNow()
    }

    private fun postError(message: String, error: Throwable?) {
        mainHandler.post { if (active.get()) onError(message, error) }
    }
}
