package com.nativOS

import android.app.Application
import android.util.Log

/**
 * NativOS Application class.
 *
 * Initializes the bridge service infrastructure and chroot runtime
 * at application startup.
 */
class NativOSApp : Application() {

    companion object {
        const val TAG = "NativOS"

        @Volatile
        lateinit var instance: NativOSApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "NativOS application initialized")
    }
}
