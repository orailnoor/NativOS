package com.nativOS.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.nativOS.launcher.KioskActivity

class SettingsActivity : Activity() {
    private lateinit var launcherStatus: TextView
    private lateinit var modeStatus: TextView
    private lateinit var deGoogleStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PremiumUi.dp(this@SettingsActivity, 20), PremiumUi.dp(this@SettingsActivity, 24),
                PremiumUi.dp(this@SettingsActivity, 20), PremiumUi.dp(this@SettingsActivity, 40))
        }

        content.addView(PremiumUi.badge(this, "NativOS control center", PremiumUi.primary))
        content.addView(PremiumUi.verticalSpace(this, 18))
        content.addView(PremiumUi.text(this, "Your phone,\nyour stack.", 36f, bold = true))
        content.addView(PremiumUi.verticalSpace(this, 10))
        content.addView(PremiumUi.text(this,
            "Choose how Linux integrates with Android. Every system-level change remains explicit and reversible.",
            16f, PremiumUi.muted))
        content.addView(PremiumUi.verticalSpace(this, 24))

        content.addView(PremiumUi.card(this, PremiumUi.primary).apply {
            addView(PremiumUi.cardContent(this@SettingsActivity).apply {
                addView(PremiumUi.badge(this@SettingsActivity, "Live configuration", PremiumUi.success))
                addView(PremiumUi.verticalSpace(this@SettingsActivity, 14))
                modeStatus = PremiumUi.text(this@SettingsActivity, "", 21f, bold = true)
                launcherStatus = PremiumUi.text(this@SettingsActivity, "", 14f, PremiumUi.muted)
                addView(modeStatus)
                addView(PremiumUi.verticalSpace(this@SettingsActivity, 6))
                addView(launcherStatus)
            })
        }, PremiumUi.matchWidth())
        content.addView(PremiumUi.verticalSpace(this, 26))

        content.addView(sectionTitle("Operating mode", "Decide when NativOS takes over the screen."))
        content.addView(PremiumUi.verticalSpace(this, 12))
        content.addView(PremiumUi.card(this).apply {
            addView(PremiumUi.cardContent(this@SettingsActivity).apply {
                addView(PremiumUi.button(this@SettingsActivity, "Linux desktop app").apply {
                    setOnClickListener {
                        NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.DESKTOP_APP)
                        if (HomeRoleManager.isDefaultHome(this@SettingsActivity))
                            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                        updateState()
                    }
                }, PremiumUi.matchWidth())
                addView(PremiumUi.button(this@SettingsActivity, "Make NativOS the Home launcher", true).apply {
                    setOnClickListener {
                        NativOSPreferences.setOperatingMode(this@SettingsActivity, NativOSPreferences.OperatingMode.HOME_LAUNCHER)
                        HomeRoleManager.requestDefaultHome(this@SettingsActivity)
                    }
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SettingsActivity, 10)))
                addView(PremiumUi.button(this@SettingsActivity, "De-Googled mode · in development").apply {
                    isEnabled = false
                    alpha = 0.55f
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SettingsActivity, 10)))
            })
        }, PremiumUi.matchWidth())
        content.addView(PremiumUi.verticalSpace(this, 26))

        content.addView(sectionTitle("Privacy & system apps", "See what is installed before changing anything."))
        content.addView(PremiumUi.verticalSpace(this, 12))
        content.addView(PremiumUi.card(this, PremiumUi.success).apply {
            addView(PremiumUi.cardContent(this@SettingsActivity).apply {
                addView(PremiumUi.badge(this@SettingsActivity, "Reversible by design", PremiumUi.success))
                addView(PremiumUi.verticalSpace(this@SettingsActivity, 14))
                addView(PremiumUi.text(this@SettingsActivity, "De-Google without guessing", 21f, bold = true))
                addView(PremiumUi.verticalSpace(this@SettingsActivity, 7))
                addView(PremiumUi.text(this@SettingsActivity,
                    "Review Google apps, core services, and phone-critical replacements. NativOS records every package it disables.",
                    14f, PremiumUi.muted))
                deGoogleStatus = PremiumUi.text(this@SettingsActivity, "No scan yet · nothing changed", 13f, PremiumUi.muted, true)
                deGoogleStatus.setPadding(0, PremiumUi.dp(this@SettingsActivity, 16), 0, 0)
                addView(deGoogleStatus)
                addView(PremiumUi.button(this@SettingsActivity, "Scan Google components").apply {
                    setOnClickListener {
                        isEnabled = false
                        deGoogleStatus.text = "Scanning the complete package set…"
                        Thread({
                            val result = runCatching { DeGoogleManager(this@SettingsActivity).scan() }
                            runOnUiThread {
                                isEnabled = true
                                deGoogleStatus.text = result.fold(
                                    onSuccess = { "${it.coreServices.size} core services · ${it.googleApps.size} Google packages · no changes" },
                                    onFailure = { "Scan failed · ${it.message}" })
                            }
                        }, "nativOS-degoogle-scan").start()
                    }
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SettingsActivity, 16)))
                addView(PremiumUi.button(this@SettingsActivity, "Open System App Remover", true).apply {
                    setOnClickListener { startActivity(Intent(this@SettingsActivity, SystemAppRemoverActivity::class.java)) }
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SettingsActivity, 10)))
            })
        }, PremiumUi.matchWidth())
        content.addView(PremiumUi.verticalSpace(this, 26))

        content.addView(sectionTitle("Android integration", "Control the layer underneath Linux."))
        content.addView(PremiumUi.verticalSpace(this, 12))
        content.addView(PremiumUi.card(this).apply {
            addView(PremiumUi.cardContent(this@SettingsActivity).apply {
                addView(SwitchMaterial(this@SettingsActivity).apply {
                    text = "Immersive Linux desktop"
                    textSize = 16f
                    setTextColor(PremiumUi.text)
                    isChecked = NativOSPreferences.hideSystemBars(this@SettingsActivity)
                    setOnCheckedChangeListener { _, checked ->
                        NativOSPreferences.setHideSystemBars(this@SettingsActivity, checked)
                    }
                }, PremiumUi.matchWidth())
                addView(PremiumUi.text(this@SettingsActivity,
                    "Hide Android navigation and status bars while NativOS is active.", 13f, PremiumUi.muted).apply {
                    setPadding(0, PremiumUi.dp(this@SettingsActivity, 5), 0, 0)
                })
                addView(PremiumUi.button(this@SettingsActivity, "Choose Android Home app").apply {
                    setOnClickListener { HomeRoleManager.requestDefaultHome(this@SettingsActivity) }
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SettingsActivity, 16)))
                addView(PremiumUi.button(this@SettingsActivity, "Open Android system settings").apply {
                    setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SettingsActivity, 10)))
            })
        }, PremiumUi.matchWidth())

        content.addView(PremiumUi.button(this, "Return to NativOS desktop", true).apply {
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            }
        }, PremiumUi.matchWidth(PremiumUi.dp(this, 28)))
        content.addView(PremiumUi.text(this, "NativOS · Linux-first, Android-compatible", 12f, PremiumUi.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, PremiumUi.dp(this@SettingsActivity, 18), 0, 0)
        })

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

    private fun sectionTitle(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(PremiumUi.text(this@SettingsActivity, title, 22f, bold = true))
        addView(PremiumUi.text(this@SettingsActivity, subtitle, 14f, PremiumUi.muted).apply {
            setPadding(0, PremiumUi.dp(this@SettingsActivity, 5), 0, 0)
        })
    }

    private fun updateState() {
        modeStatus.text = when (NativOSPreferences.operatingMode(this)) {
            NativOSPreferences.OperatingMode.DESKTOP_APP -> "Optional Linux desktop"
            NativOSPreferences.OperatingMode.HOME_LAUNCHER -> "Linux-first Home launcher"
            NativOSPreferences.OperatingMode.DEGOOGLED -> "De-Googled Android"
        }
        launcherStatus.text = if (HomeRoleManager.isDefaultHome(this))
            "NativOS currently owns the Android Home role"
        else "Android launcher currently owns the Home role"
    }
}
