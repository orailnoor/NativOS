package com.nativOS.settings

import android.content.Intent
import android.service.quicksettings.TileService

class SettingsTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
