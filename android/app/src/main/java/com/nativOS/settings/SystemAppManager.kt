package com.nativOS.settings

import android.content.Context
import com.nativOS.runtime.RootShell

class SystemAppManager(context: Context) {
    enum class Risk { RECOMMENDED, DEGOOGLE_CORE, REPLACEMENT_REQUIRED }

    data class SuggestedApp(
        val packageName: String,
        val title: String,
        val category: String,
        val risk: Risk,
        val disabled: Boolean
    )

    data class ActionResult(val succeeded: List<String>, val failed: Map<String, String>)

    private val appContext = context.applicationContext
    private val root = RootShell(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun scanSuggestions(): List<SuggestedApp> {
        check(root.hasRoot()) { "Root permission is required." }
        val installed = packageSet(root.exec("pm list packages"))
        val disabled = packageSet(root.exec("pm list packages -d"))
        return CATALOG.mapNotNull { item ->
            item.takeIf { it.packageName in installed }
                ?.copy(disabled = item.packageName in disabled)
        }
    }

    fun disable(packages: Collection<String>): ActionResult = change(packages, disable = true)

    fun restore(packages: Collection<String>): ActionResult = change(packages, disable = false)

    fun restoreAllChangedByNativOS(): ActionResult = restore(changedPackages())

    fun changedPackages(): Set<String> = prefs.getStringSet(CHANGED_PACKAGES, emptySet())?.toSet().orEmpty()

    private fun change(packages: Collection<String>, disable: Boolean): ActionResult {
        check(root.hasRoot()) { "Root permission is required." }
        val allowed = CATALOG.mapTo(hashSetOf()) { it.packageName }
        val selected = packages.distinct().filter { it in allowed && PACKAGE_NAME.matches(it) }
        val succeeded = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()

        selected.forEach { packageName ->
            val command = if (disable)
                "pm disable-user --user 0 $packageName"
            else
                "pm enable $packageName"
            val output = root.exec(command).trim()
            val success = output.contains("new state", ignoreCase = true) ||
                output.contains("enabled", ignoreCase = true) ||
                output.contains("disabled", ignoreCase = true)
            if (success) succeeded += packageName else failed[packageName] = output.ifBlank { "Unknown package-manager error" }
        }

        val changed = changedPackages().toMutableSet()
        if (disable) changed.addAll(succeeded) else changed.removeAll(succeeded.toSet())
        prefs.edit().putStringSet(CHANGED_PACKAGES, changed).apply()
        return ActionResult(succeeded, failed)
    }

    private fun packageSet(output: String): Set<String> = output.lineSequence()
        .map { it.removePrefix("package:").trim() }
        .filter { PACKAGE_NAME.matches(it) }
        .toSet()

    private companion object {
        const val PREFS = "nativos_system_app_remover"
        const val CHANGED_PACKAGES = "changed_packages"
        val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")

        fun app(pkg: String, title: String, category: String, risk: Risk) =
            SuggestedApp(pkg, title, category, risk, disabled = false)

        val CATALOG = listOf(
            app("com.google.android.youtube", "YouTube", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.apps.youtube.music", "YouTube Music", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.gm", "Gmail", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.apps.maps", "Google Maps", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.apps.photos", "Google Photos", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.apps.docs", "Google Drive", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.keep", "Google Keep", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.calendar", "Google Calendar", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.apps.tachyon", "Google Meet", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.videos", "Google TV", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.googlequicksearchbox", "Google Search and Assistant", "Google apps", Risk.RECOMMENDED),
            app("com.android.chrome", "Google Chrome", "Google apps", Risk.RECOMMENDED),
            app("com.google.android.apps.wellbeing", "Digital Wellbeing", "Optional services", Risk.RECOMMENDED),
            app("com.google.android.projection.gearhead", "Android Auto", "Optional services", Risk.RECOMMENDED),
            app("com.google.ar.core", "Google Play Services for AR", "Optional services", Risk.RECOMMENDED),
            app("com.google.android.apps.tips", "Device Tips", "Optional services", Risk.RECOMMENDED),
            app("com.google.android.feedback", "Google Feedback", "Telemetry", Risk.RECOMMENDED),
            app("com.google.android.partnersetup", "Google Partner Setup", "Telemetry", Risk.RECOMMENDED),
            app("com.google.android.adservices.api", "Google Ad Services", "Advertising", Risk.RECOMMENDED),
            app("com.google.mainline.adservices", "Mainline Ad Services", "Advertising", Risk.RECOMMENDED),
            app("com.google.android.gms", "Google Play Services", "De-Googled core", Risk.DEGOOGLE_CORE),
            app("com.google.android.gsf", "Google Services Framework", "De-Googled core", Risk.DEGOOGLE_CORE),
            app("com.android.vending", "Google Play Store", "De-Googled core", Risk.DEGOOGLE_CORE),
            app("com.google.android.syncadapters.contacts", "Google Contacts Sync", "De-Googled core", Risk.DEGOOGLE_CORE),
            app("com.google.android.syncadapters.calendar", "Google Calendar Sync", "De-Googled core", Risk.DEGOOGLE_CORE),
            app("com.google.android.apps.messaging", "Google Messages", "Needs Linux replacement", Risk.REPLACEMENT_REQUIRED),
            app("com.google.android.dialer", "Google Phone", "Needs Linux replacement", Risk.REPLACEMENT_REQUIRED),
            app("com.google.android.contacts", "Google Contacts", "Needs Linux replacement", Risk.REPLACEMENT_REQUIRED),
            app("com.google.android.GoogleCamera", "Google Camera", "Needs Linux replacement", Risk.REPLACEMENT_REQUIRED),
            app("com.google.android.inputmethod.latin", "Gboard", "Needs replacement keyboard", Risk.REPLACEMENT_REQUIRED)
        )
    }
}
