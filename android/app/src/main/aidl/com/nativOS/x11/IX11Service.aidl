package com.nativOS.x11;

import android.os.ParcelFileDescriptor;

interface IX11Service {
    boolean startServer();
    ParcelFileDescriptor getXConnection();
    ParcelFileDescriptor getLogcatOutput();
}
