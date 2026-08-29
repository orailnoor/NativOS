package com.nativOS.settings

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SystemAppRemoverActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var status: TextView
    private val rows = linkedMapOf<SystemAppManager.SuggestedApp, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "System App Remover"
        val density = resources.displayMetrics.density
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (24 * density).toInt(),
                (20 * density).toInt(), (30 * density).toInt())
        }
        content.addView(TextView(this).apply {
            text = "System App Remover"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(this).apply {
            text = "Selected apps are disabled for the main Android user, not erased from the system partition. NativOS records its changes so they can be restored."
            textSize = 14f
            setTextColor(Color.LTGRAY)
        })
        status = TextView(this).apply {
            text = "Scanning installed system apps…"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, (16 * density).toInt(), 0, (12 * density).toInt())
        }
        content.addView(status)
        scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 18, 22))
            addView(content)
        }
        setContentView(scrollView)
        loadApps()
    }

    private fun loadApps() {
        Thread({
            val result = runCatching { SystemAppManager(this).scanSuggestions() }
            runOnUiThread {
                result.onSuccess(::showApps).onFailure { status.text = "Scan failed: ${it.message}" }
            }
        }, "nativOS-system-app-scan").start()
    }

    private fun showApps(apps: List<SystemAppManager.SuggestedApp>) {
        status.text = "${apps.size} suggested removable apps found. Review every selection."
        rows.clear()
        apps.groupBy { it.category }.forEach { (category, items) ->
            content.addView(TextView(this).apply {
                text = category
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, 20, 0, 4)
            })
            items.forEach { item ->
                val warning = when (item.risk) {
                    SystemAppManager.Risk.RECOMMENDED -> "Recommended"
                    SystemAppManager.Risk.DEGOOGLE_CORE -> "Breaks apps requiring Google services"
                    SystemAppManager.Risk.REPLACEMENT_REQUIRED -> "Keep until its replacement works"
                }
                val box = CheckBox(this).apply {
                    text = "${item.title}${if (item.disabled) " — disabled" else ""}\n${item.packageName}\n$warning"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    isChecked = false
                    isEnabled = !item.disabled
                }
                rows[item] = box
                content.addView(box, matchWidth())
            }
        }

        content.addView(Button(this).apply {
            text = "Disable selected apps"
            setOnClickListener { confirmDisable() }
        }, matchWidth())
        content.addView(Button(this).apply {
            text = "Restore all changes made by NativOS"
            setOnClickListener { restoreAll() }
        }, matchWidth())
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_UP) }
    }

    private fun confirmDisable() {
        val selected = rows.filterValues { it.isChecked }.keys
        if (selected.isEmpty()) {
            status.text = "Select at least one app."
            return
        }
        val highRisk = selected.count { it.risk != SystemAppManager.Risk.RECOMMENDED }
        AlertDialog.Builder(this)
            .setTitle("Disable ${selected.size} apps?")
            .setMessage("$highRisk selections can break Google-dependent or essential phone features. You can restore changes from this screen.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disable") { _, _ -> runAction(selected.map { it.packageName }, true) }
            .show()
    }

    private fun restoreAll() {
        AlertDialog.Builder(this)
            .setTitle("Restore disabled apps?")
            .setMessage("This re-enables every package disabled through NativOS.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> runAction(emptyList(), false) }
            .show()
    }

    private fun runAction(packages: List<String>, disable: Boolean) {
        status.text = if (disable) "Disabling selected apps…" else "Restoring apps…"
        Thread({
            val result = runCatching {
                val manager = SystemAppManager(this)
                if (disable) manager.disable(packages) else manager.restoreAllChangedByNativOS()
            }
            runOnUiThread {
                status.text = result.fold(
                    onSuccess = { "Changed ${it.succeeded.size} apps; ${it.failed.size} failed." },
                    onFailure = { "Operation failed: ${it.message}" }
                )
                rows.values.forEach { it.isEnabled = false }
            }
        }, "nativOS-system-app-action").start()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
