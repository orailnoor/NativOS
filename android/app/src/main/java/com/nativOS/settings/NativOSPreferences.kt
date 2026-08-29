package com.nativOS.settings

import android.content.Context

object NativOSPreferences {
    private const val FILE = "nativos_settings"
    private const val HIDE_SYSTEM_BARS = "hide_system_bars"
    private const val HOME_PROMPT_SHOWN = "home_prompt_shown"

    fun hideSystemBars(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(HIDE_SYSTEM_BARS, true)

    fun setHideSystemBars(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(HIDE_SYSTEM_BARS, enabled).apply()
    }

    fun homePromptShown(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(HOME_PROMPT_SHOWN, false)

    fun markHomePromptShown(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(HOME_PROMPT_SHOWN, true).apply()
    }
}
