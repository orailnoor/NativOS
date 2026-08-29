package com.nativOS.settings

import android.content.Context
import com.nativOS.runtime.RootShell

/** Read-only discovery for the future reversible De-Googled mode. */
class DeGoogleManager(context: Context) {
    data class ScanResult(
        val coreServices: List<String>,
        val googleApps: List<String>,
        val allPackages: List<String>
    )

    private val rootShell = RootShell(context.applicationContext)

    fun scan(): ScanResult {
        check(rootShell.hasRoot()) { "Root permission is required to inspect the complete package set." }
        val packages = rootShell.exec("pm list packages")
            .lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() && PACKAGE_NAME.matches(it) }
            .distinct()
            .sorted()
            .toList()

        val core = packages.filter { it in CORE_GOOGLE_COMPONENTS }
        val apps = packages.filter {
            it !in CORE_GOOGLE_COMPONENTS &&
                (it.startsWith("com.google.") || it == "com.android.vending")
        }
        return ScanResult(core, apps, packages)
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
        private val CORE_GOOGLE_COMPONENTS = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.syncadapters.contacts",
            "com.google.android.syncadapters.calendar",
            "com.google.android.ext.shared",
            "com.google.android.ext.services"
        )
    }
}
