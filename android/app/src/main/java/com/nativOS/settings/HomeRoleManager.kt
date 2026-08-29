package com.nativOS.settings

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object HomeRoleManager {
    private const val REQUEST_HOME_ROLE = 4101

    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            return roles?.isRoleHeld(RoleManager.ROLE_HOME) == true
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName == context.packageName
    }

    fun requestDefaultHome(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = activity.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                // RoleController identifies the requesting package from the
                // activity result caller. A plain startActivity() leaves the
                // package null and Android silently rejects the request.
                @Suppress("DEPRECATION")
                activity.startActivityForResult(
                    roles.createRequestRoleIntent(RoleManager.ROLE_HOME),
                    REQUEST_HOME_ROLE
                )
                return
            }
        }
        activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

}
