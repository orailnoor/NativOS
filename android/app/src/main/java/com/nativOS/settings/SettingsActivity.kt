package com.nativOS.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.nativOS.launcher.KioskActivity

class SettingsActivity : Activity() {
    private lateinit var launcherStatus: TextView
    private lateinit var modeStatus: TextView
    private lateinit var deGoogleStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "NativOS Settings"

        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (28 * density).toInt(),
                (24 * density).toInt(), (32 * density).toInt())
        }

        content.addView(TextView(this).apply {
            text = "NativOS Settings"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(this).apply {
            text = "Android integration controls. More settings will be added here."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, (8 * density).toInt(), 0, (26 * density).toInt())
        })

        content.addView(TextView(this).apply {
            text = "Operating mode"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        modeStatus = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
        }
        content.addView(modeStatus)
        content.addView(Button(this).apply {
            text = "Use as an optional Linux app"
            setOnClickListener {
                NativOSPreferences.setOperatingMode(
                    this@SettingsActivity,
                    NativOSPreferences.OperatingMode.DESKTOP_APP
                )
                if (HomeRoleManager.isDefaultHome(this@SettingsActivity)) {
                    startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                }
                updateState()
            }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "Use as the Home launcher"
            setOnClickListener {
                NativOSPreferences.setOperatingMode(
                    this@SettingsActivity,
                    NativOSPreferences.OperatingMode.HOME_LAUNCHER
                )
                HomeRoleManager.requestDefaultHome(this@SettingsActivity)
            }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "De-Googled Android mode — not enabled yet"
            isEnabled = false
        }, matchWidth())
        content.addView(TextView(this).apply {
            text = "This mode will remove Google Play Services and Google apps while retaining Android's hardware and vendor layer. It will be a separate, reversible opt-in feature."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, (8 * density).toInt(), 0, (24 * density).toInt())
        })
        deGoogleStatus = TextView(this).apply {
            text = "Google component scan has not been run. No packages have been changed."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        content.addView(deGoogleStatus)
        content.addView(Button(this).apply {
            text = "Scan Google components (read-only)"
            setOnClickListener {
                isEnabled = false
                deGoogleStatus.text = "Scanning installed packages…"
                Thread({
                    val result = runCatching { DeGoogleManager(this@SettingsActivity).scan() }
                    runOnUiThread {
                        isEnabled = true
                        deGoogleStatus.text = result.fold(
                            onSuccess = {
                                "Found ${it.coreServices.size} Google core components and " +
                                    "${it.googleApps.size} additional Google packages. Nothing was changed."
                            },
                            onFailure = { "Scan failed: ${it.message}" }
                        )
                    }
                }, "nativOS-degoogle-scan").start()
            }
        }, matchWidth())

        launcherStatus = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
        }
        content.addView(launcherStatus)
        content.addView(Button(this).apply {
            text = "Set NativOS as Home launcher"
            setOnClickListener { HomeRoleManager.requestDefaultHome(this@SettingsActivity) }
        }, matchWidth())

        content.addView(Switch(this).apply {
            text = "Hide Android navigation and status bars"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = NativOSPreferences.hideSystemBars(this@SettingsActivity)
            setPadding(0, (20 * density).toInt(), 0, (12 * density).toInt())
            setOnCheckedChangeListener { _, checked ->
                NativOSPreferences.setHideSystemBars(this@SettingsActivity, checked)
            }
        }, matchWidth())

        content.addView(Button(this).apply {
            text = "Open Android system settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "Return to NativOS desktop"
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, KioskActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
                finish()
            }
        }, matchWidth())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 22))
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    private fun updateState() {
        modeStatus.text = when (NativOSPreferences.operatingMode(this)) {
            NativOSPreferences.OperatingMode.DESKTOP_APP ->
                "Current: optional Linux desktop app"
            NativOSPreferences.OperatingMode.HOME_LAUNCHER ->
                "Current: Linux-first Home launcher"
            NativOSPreferences.OperatingMode.DEGOOGLED ->
                "Current: De-Googled Android"
        }
        launcherStatus.text = if (HomeRoleManager.isDefaultHome(this))
            "Home launcher: NativOS is active"
        else
            "Home launcher: Android launcher is still active"
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
