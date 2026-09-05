package com.nativOS.settings

import android.content.Context

object NativOSPreferences {
    enum class OperatingMode { DESKTOP_APP, HOME_LAUNCHER, DEGOOGLED }

    private const val FILE = "nativos_settings"
    private const val HIDE_SYSTEM_BARS = "hide_system_bars"
    private const val SHOW_ANDROID_APPS = "show_android_apps"
    private const val OPERATING_MODE = "operating_mode"

    fun hideSystemBars(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(HIDE_SYSTEM_BARS, false)

    fun setHideSystemBars(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(HIDE_SYSTEM_BARS, enabled).apply()
    }

    fun showAndroidApps(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(SHOW_ANDROID_APPS, true)

    fun setShowAndroidApps(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(SHOW_ANDROID_APPS, enabled).apply()
    }

    fun operatingMode(context: Context): OperatingMode {
        val stored = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(OPERATING_MODE, OperatingMode.DESKTOP_APP.name)
        val migrated = if (stored == "MINIMAL_ANDROID") OperatingMode.DEGOOGLED.name else stored
        return runCatching { OperatingMode.valueOf(migrated.orEmpty()) }
            .getOrDefault(OperatingMode.DESKTOP_APP)
    }

    fun setOperatingMode(context: Context, mode: OperatingMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(OPERATING_MODE, mode.name).apply()
    }

}
