package com.nativOS.settings

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nativOS.bridge.AndroidAppIntegration
import com.nativOS.launcher.KioskActivity

class SettingsActivity : Activity() {
    private lateinit var summary: TextView
    private lateinit var desktopCheck: TextView
    private lateinit var homeCheck: TextView
    private lateinit var deGoogleStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }

        content.addView(PremiumUi.text(this, "Settings", 34f, bold = true))
        summary = PremiumUi.text(this, "", 15f, PremiumUi.muted).apply {
            setPadding(0, dp(6), 0, 0)
        }
        content.addView(summary)
        content.addView(PremiumUi.verticalSpace(this, 28))

        content.addView(PremiumUi.sectionLabel(this, "NativOS mode"))
        content.addView(group().apply {
            desktopCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Linux desktop",
                subtitle = "Open NativOS like a normal app",
                trailing = desktopCheck
            ) {
                NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.DESKTOP_APP)
                if (HomeRoleManager.isDefaultHome(this@SettingsActivity)) {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }
                updateState()
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            homeCheck = PremiumUi.text(this@SettingsActivity, "", 20f, PremiumUi.primary)
            addView(row(
                title = "Home launcher",
                subtitle = "Use NativOS as your phone's Home screen",
                trailing = homeCheck
            ) {
                NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.HOME_LAUNCHER)
                HomeRoleManager.requestDefaultHome(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "De-Googled mode",
                subtitle = "Coming later",
                trailingText = ""
            ).apply {
                isEnabled = false
                alpha = 0.42f
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Privacy"))
        content.addView(group().apply {
            deGoogleStatus = PremiumUi.text(this@SettingsActivity, "Not scanned", 13f, PremiumUi.muted)
            addView(row(
                title = "Google components",
                subtitleView = deGoogleStatus,
                trailingText = "Scan"
            ) { scanGoogleComponents() })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row(
                title = "System App Remover",
                subtitle = "Review and safely disable preinstalled apps",
                trailingText = "›"
            ) {
                startActivity(Intent(this@SettingsActivity, SystemAppRemoverActivity::class.java))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Android integration"))
        content.addView(group().apply {
            addView(switchRow(
                "Immersive desktop",
                "Hide Android system bars while Linux is active",
                NativOSPreferences.hideSystemBars(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setHideSystemBars(this@SettingsActivity, checked)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(switchRow(
                "Show Android apps",
                "Include installed Android apps in the Linux drawer",
                NativOSPreferences.showAndroidApps(this@SettingsActivity)
            ) { checked ->
                NativOSPreferences.setShowAndroidApps(this@SettingsActivity, checked)
                AndroidAppIntegration.sync(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("Default Home app", "Choose which launcher Android uses", trailingText = "›") {
                HomeRoleManager.requestDefaultHome(this@SettingsActivity)
            })
            addView(PremiumUi.separator(this@SettingsActivity))
            addView(row("Android settings", "Open the underlying system settings", trailingText = "›") {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(group().apply {
            addView(row("Return to desktop", trailingText = "›") {
                startActivity(Intent(this@SettingsActivity, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.text(this, "System changes are explicit and reversible.", 12f, PremiumUi.muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), 0)
        }, PremiumUi.matchWidth())

        setContentView(ScrollView(this).apply {
            background = PremiumUi.pageBackground()
            isFillViewport = true
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(PremiumUi.surface)
        }
        clipToOutline = true
    }

    private fun row(
        title: String,
        subtitle: String? = null,
        subtitleView: TextView? = null,
        trailingText: String = "",
        trailing: TextView? = null,
        destructive: Boolean = false,
        action: (() -> Unit)? = null
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(if (subtitle != null || subtitleView != null) 66 else 54)
        setPadding(dp(16), dp(10), dp(14), dp(10))
        if (action != null) {
            isClickable = true
            isFocusable = true
            background = selectableBackground()
            setOnClickListener { action() }
        }

        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(PremiumUi.text(
                this@SettingsActivity,
                title,
                16f,
                if (destructive) PremiumUi.danger else PremiumUi.text
            ))
            if (subtitleView != null) {
                subtitleView.setPadding(0, dp(4), 0, 0)
                addView(subtitleView)
            } else if (subtitle != null) {
                addView(PremiumUi.text(this@SettingsActivity, subtitle, 13f, PremiumUi.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(trailing ?: PremiumUi.text(
            this@SettingsActivity,
            trailingText,
            if (trailingText == "›") 28f else 15f,
            if (trailingText == "›") PremiumUi.muted else PremiumUi.primary
        ).apply { gravity = Gravity.CENTER })
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(66)
        setPadding(dp(16), dp(10), dp(10), dp(10))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(PremiumUi.text(this@SettingsActivity, title, 16f))
            addView(PremiumUi.text(this@SettingsActivity, subtitle, 13f, PremiumUi.muted).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(SwitchMaterial(this@SettingsActivity).apply {
            text = ""
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
    }

    private fun scanGoogleComponents() {
        deGoogleStatus.text = "Scanning…"
        Thread({
            val result = runCatching { DeGoogleManager(this).scan() }
            runOnUiThread {
                deGoogleStatus.text = result.fold(
                    onSuccess = { "${it.coreServices.size} core services · ${it.googleApps.size} Google apps" },
                    onFailure = { "Scan failed" }
                )
            }
        }, "nativOS-degoogle-scan").start()
    }

    private fun updateState() {
        if (!::summary.isInitialized) return
        val mode = NativOSPreferences.operatingMode(this)
        desktopCheck.text = if (mode == NativOSPreferences.OperatingMode.DESKTOP_APP) "✓" else ""
        homeCheck.text = if (mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER) "✓" else ""
        summary.text = when {
            mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER && HomeRoleManager.isDefaultHome(this) ->
                "NativOS is your Home launcher"
            mode == NativOSPreferences.OperatingMode.HOME_LAUNCHER ->
                "Home launcher selected · Android approval needed"
            else -> "Linux desktop with Android underneath"
        }
    }

    private fun selectableBackground() = android.util.TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId)
    }

    private fun dp(value: Int) = PremiumUi.dp(this, value)
}
