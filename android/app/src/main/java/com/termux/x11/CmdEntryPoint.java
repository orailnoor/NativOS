package com.termux.x11;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class CmdEntryPoint {
    public native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    private static native boolean connected();

    // Called by the native server when a client knocks on the X11 socket. NativOS
    // obtains the connection through its bound service, so no broadcast is needed.
    @SuppressWarnings("unused")
    private void sendBroadcast() {}

    @SuppressWarnings("unused")
    private void sendBroadcastDelayed() {}

    static {
        try {
            System.loadLibrary("Xlorie");
            Log.i("CmdEntryPoint", "libXlorie loaded natively!");
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Failed to load libXlorie", e);
        }
    }
}
