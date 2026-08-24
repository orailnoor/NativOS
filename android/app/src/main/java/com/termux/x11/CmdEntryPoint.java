package com.termux.x11;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

public class CmdEntryPoint {
    public static native boolean start(String[] args);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    private static native boolean connected();
    private native void listenForConnections();

    static {
        try {
            System.loadLibrary("Xlorie");
            Log.i("CmdEntryPoint", "libXlorie loaded natively!");
        } catch (Exception e) {
            Log.e("CmdEntryPoint", "Failed to load libXlorie", e);
        }
    }
}
