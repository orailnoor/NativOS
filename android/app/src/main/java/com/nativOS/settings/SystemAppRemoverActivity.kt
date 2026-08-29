package com.nativOS.settings

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SystemAppRemoverActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var status: TextView
    private lateinit var selectedStatus: TextView
    private lateinit var disableButton: MaterialButton
    private val rows = linkedMapOf<SystemAppManager.SuggestedApp, MaterialCheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PremiumUi.background
        window.navigationBarColor = PremiumUi.background

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = PremiumUi.pageBackground()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        content.addView(PremiumUi.text(this, "‹  Settings", 16f, PremiumUi.primary).apply {
            setPadding(0, dp(8), 0, dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
        content.addView(PremiumUi.text(this, "System Apps", 34f, bold = true))
        content.addView(PremiumUi.text(
            this,
            "Disable preinstalled apps without deleting them.",
            15f,
            PremiumUi.muted
        ).apply { setPadding(0, dp(6), 0, 0) })

        content.addView(PremiumUi.verticalSpace(this, 26))
        content.addView(PremiumUi.sectionLabel(this, "Recovery"))
        content.addView(group().apply {
            addView(actionRow(
                "Restore NativOS changes",
                "Re-enable every app disabled here",
                PremiumUi.primary
            ) { restoreAll() })
        })
        content.addView(PremiumUi.text(
            this,
            "Only changes made by NativOS are restored.",
            12f,
            PremiumUi.muted
        ).apply { setPadding(dp(16), dp(8), dp(12), 0) })

        status = PremiumUi.text(this, "Scanning…", 13f, PremiumUi.muted).apply {
            setPadding(dp(16), dp(26), dp(8), dp(10))
        }
        content.addView(status)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer)

        scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            setBackgroundColor(PremiumUi.surface)
            selectedStatus = PremiumUi.text(this@SystemAppRemoverActivity, "None selected", 13f, PremiumUi.muted)
            addView(selectedStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            disableButton = PremiumUi.button(this@SystemAppRemoverActivity, "Disable", true).apply {
                isEnabled = false
                setOnClickListener { confirmDisable() }
            }
            addView(disableButton, LinearLayout.LayoutParams(dp(116), dp(44)))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        loadApps()
    }

    private fun loadApps() {
        rows.clear()
        listContainer.removeAllViews()
        status.text = "Scanning installed apps…"
        updateSelectedCount()
        Thread({
            val result = runCatching { SystemAppManager(this).scanSuggestions() }
            runOnUiThread {
                result.onSuccess(::showApps).onFailure { status.text = "Scan failed · ${it.message}" }
            }
        }, "nativOS-system-app-scan").start()
    }

    private fun showApps(apps: List<SystemAppManager.SuggestedApp>) {
        status.text = "${apps.size} suggestions · nothing is selected automatically"
        apps.groupBy { it.category }.forEach { (category, items) ->
            listContainer.addView(PremiumUi.sectionLabel(this, category), PremiumUi.matchWidth(dp(18)))
            listContainer.addView(group().apply {
                items.forEachIndexed { index, item ->
                    if (index > 0) addView(PremiumUi.separator(this@SystemAppRemoverActivity, 52))
                    addView(appRow(item))
                }
            }, PremiumUi.matchWidth())
        }
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_UP) }
    }

    private fun appRow(item: SystemAppManager.SuggestedApp): LinearLayout {
        val riskColor = when (item.risk) {
            SystemAppManager.Risk.RECOMMENDED -> PremiumUi.success
            SystemAppManager.Risk.DEGOOGLE_CORE -> PremiumUi.warning
            SystemAppManager.Risk.REPLACEMENT_REQUIRED -> PremiumUi.danger
        }
        val riskText = when (item.risk) {
            SystemAppManager.Risk.RECOMMENDED -> "Safe to review"
            SystemAppManager.Risk.DEGOOGLE_CORE -> "May affect Google-dependent apps"
            SystemAppManager.Risk.REPLACEMENT_REQUIRED -> "Replacement required first"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            minimumHeight = dp(72)
            setPadding(dp(8), dp(11), dp(14), dp(11))

            val checkbox = MaterialCheckBox(this@SystemAppRemoverActivity).apply {
                text = ""
                isEnabled = !item.disabled
                setOnCheckedChangeListener { _, _ -> updateSelectedCount() }
            }
            rows[item] = checkbox
            addView(checkbox, LinearLayout.LayoutParams(dp(44), dp(48)))

            addView(LinearLayout(this@SystemAppRemoverActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(PremiumUi.text(
                    this@SystemAppRemoverActivity,
                    item.title + if (item.disabled) " · Disabled" else "",
                    16f
                ))
                addView(PremiumUi.text(this@SystemAppRemoverActivity, item.packageName, 12f, PremiumUi.muted).apply {
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(4), 0, 0)
                })
                addView(PremiumUi.text(this@SystemAppRemoverActivity, riskText, 12f, riskColor).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun actionRow(title: String, subtitle: String, color: Int, action: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(16), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(LinearLayout(this@SystemAppRemoverActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(PremiumUi.text(this@SystemAppRemoverActivity, title, 16f, color))
                addView(PremiumUi.text(this@SystemAppRemoverActivity, subtitle, 13f, PremiumUi.muted).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(PremiumUi.text(this@SystemAppRemoverActivity, "›", 28f, PremiumUi.muted))
        }

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(PremiumUi.surface)
        }
        clipToOutline = true
    }

    private fun updateSelectedCount() {
        if (!::selectedStatus.isInitialized) return
        val selected = rows.count { it.value.isChecked }
        selectedStatus.text = if (selected == 0) "None selected" else "$selected selected"
        selectedStatus.setTextColor(if (selected == 0) PremiumUi.muted else PremiumUi.text)
        if (::disableButton.isInitialized) {
            disableButton.isEnabled = selected > 0
            disableButton.backgroundTintList = ColorStateList.valueOf(
                if (selected > 0) PremiumUi.primary else PremiumUi.surfaceHigh
            )
            disableButton.setTextColor(if (selected > 0) android.graphics.Color.WHITE else PremiumUi.muted)
        }
    }

    private fun confirmDisable() {
        val selected = rows.filterValues { it.isChecked }.keys
        if (selected.isEmpty()) return
        val highRisk = selected.count { it.risk != SystemAppManager.Risk.RECOMMENDED }
        val warning = if (highRisk > 0) "\n\n$highRisk selected app${if (highRisk == 1) " has" else "s have"} additional risk." else ""
        MaterialAlertDialogBuilder(this)
            .setTitle("Disable ${selected.size} app${if (selected.size == 1) "" else "s"}?")
            .setMessage("They will disappear from Android but can be restored here.$warning")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disable") { _, _ -> runAction(selected.map { it.packageName }, true) }
            .show()
    }

    private fun restoreAll() {
        val changed = SystemAppManager(this).changedPackages().size
        if (changed == 0) {
            status.text = "No NativOS changes to restore"
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore $changed app${if (changed == 1) "" else "s"}?")
            .setMessage("Every package disabled through NativOS will be re-enabled.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> runAction(emptyList(), false) }
            .show()
    }

    private fun runAction(packages: List<String>, disable: Boolean) {
        status.text = if (disable) "Applying changes…" else "Restoring apps…"
        Thread({
            val result = runCatching {
                val manager = SystemAppManager(this)
                if (disable) manager.disable(packages) else manager.restoreAllChangedByNativOS()
            }
            runOnUiThread {
                status.text = result.fold(
                    onSuccess = { "${it.succeeded.size} changed · ${it.failed.size} failed" },
                    onFailure = { "Operation failed · ${it.message}" }
                )
                loadApps()
            }
        }, "nativOS-system-app-action").start()
    }

    private fun dp(value: Int) = PremiumUi.dp(this, value)
}
