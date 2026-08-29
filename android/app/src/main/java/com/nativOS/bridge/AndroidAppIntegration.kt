package com.nativOS.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Publishes Android launcher activities as applications in the Phosh drawer. */
object AndroidAppIntegration {
    private const val TAG = "NativOS.AndroidApps"
    const val LAUNCH_PIPE = "android-launch.fifo"

    fun sync(context: Context) {
        try {
            val integrationDir = File(context.filesDir, "bridge/android-apps")
            val applicationsDir = File(integrationDir, "share/applications")
            val iconsDir = File(integrationDir, "icons")
            applicationsDir.mkdirs()
            iconsDir.mkdirs()

            File(integrationDir, "nativos-launch-android").writeText(
                """#!/bin/sh
                |[ "${'$'}#" -eq 2 ] || exit 2
                |printf '%s\t%s\n' "${'$'}1" "${'$'}2" > /run/nativOS/$LAUNCH_PIPE
                |""".trimMargin()
            )

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val activities = context.packageManager.queryIntentActivities(launcherIntent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }

            val expectedDesktopFiles = mutableSetOf<String>()
            val expectedIconFiles = mutableSetOf<String>()
            for (resolved in activities) {
                val packageName = resolved.activityInfo.packageName
                val activityName = resolved.activityInfo.name
                val id = stableId("$packageName/$activityName")
                val desktopName = "android-$id.desktop"
                val iconName = "android-$id.png"
                expectedDesktopFiles += desktopName
                expectedIconFiles += iconName

                val iconFile = File(iconsDir, iconName)
                if (!iconFile.exists()) writeIcon(resolved.loadIcon(context.packageManager), iconFile)

                val label = escapeDesktopValue(resolved.loadLabel(context.packageManager).toString())
                val desktop = """
                    [Desktop Entry]
                    Type=Application
                    Name=$label
                    Comment=Android application
                    Exec=/bin/sh /run/nativOS/android-apps/nativos-launch-android $packageName $activityName
                    Icon=/run/nativOS/android-apps/icons/$iconName
                    Terminal=false
                    StartupNotify=false
                    Categories=Android;
                    X-Android-Package=$packageName
                    X-Android-Activity=$activityName
                    X-Purism-FormFactor=Workstation;Mobile;
                """.trimIndent() + "\n"
                val desktopFile = File(applicationsDir, desktopName)
                if (!desktopFile.exists() || desktopFile.readText() != desktop) desktopFile.writeText(desktop)
            }

            applicationsDir.listFiles()?.filter { it.name !in expectedDesktopFiles }?.forEach { it.delete() }
            iconsDir.listFiles()?.filter { it.name !in expectedIconFiles }?.forEach { it.delete() }
            Log.i(TAG, "Published ${activities.size} Android apps to Phosh")
        } catch (error: Throwable) {
            Log.e(TAG, "Could not publish Android apps", error)
        }
    }

    fun isLaunchable(context: Context, packageName: String, activityName: String): Boolean {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return context.packageManager.queryIntentActivities(launcherIntent, 0).any {
            it.activityInfo.packageName == packageName && it.activityInfo.name == activityName
        }
    }

    private fun writeIcon(drawable: android.graphics.drawable.Drawable, file: File) {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun stableId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun escapeDesktopValue(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", " ")
        .replace("\r", " ")
}
