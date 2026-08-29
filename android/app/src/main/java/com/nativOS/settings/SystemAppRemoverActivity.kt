package com.nativOS.settings

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SystemAppRemoverActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var status: TextView
    private lateinit var selectedStatus: TextView
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
            setPadding(PremiumUi.dp(this@SystemAppRemoverActivity, 20), PremiumUi.dp(this@SystemAppRemoverActivity, 24),
                PremiumUi.dp(this@SystemAppRemoverActivity, 20), PremiumUi.dp(this@SystemAppRemoverActivity, 28))
        }
        content.addView(PremiumUi.badge(this, "Root package control", PremiumUi.warning))
        content.addView(PremiumUi.verticalSpace(this, 18))
        content.addView(PremiumUi.text(this, "System App\nRemover", 36f, bold = true))
        content.addView(PremiumUi.verticalSpace(this, 10))
        content.addView(PremiumUi.text(this,
            "A curated, reversible way to remove bloat from your daily experience without erasing the system partition.",
            16f, PremiumUi.muted))
        content.addView(PremiumUi.verticalSpace(this, 20))

        content.addView(PremiumUi.card(this, PremiumUi.success).apply {
            addView(PremiumUi.cardContent(this@SystemAppRemoverActivity).apply {
                addView(PremiumUi.badge(this@SystemAppRemoverActivity, "Rollback protected", PremiumUi.success))
                addView(PremiumUi.verticalSpace(this@SystemAppRemoverActivity, 12))
                addView(PremiumUi.text(this@SystemAppRemoverActivity,
                    "Apps are disabled for Android user 0. NativOS records only its own changes and can restore them in one tap.",
                    14f, PremiumUi.muted))
            })
        }, PremiumUi.matchWidth())

        status = PremiumUi.text(this, "Scanning installed system apps…", 14f, PremiumUi.muted, true).apply {
            setPadding(0, PremiumUi.dp(this@SystemAppRemoverActivity, 20), 0, PremiumUi.dp(this@SystemAppRemoverActivity, 12))
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

        root.addView(PremiumUi.card(this, PremiumUi.primary).apply {
            radius = 0f
            addView(PremiumUi.cardContent(this@SystemAppRemoverActivity).apply {
                setPadding(PremiumUi.dp(this@SystemAppRemoverActivity, 20), PremiumUi.dp(this@SystemAppRemoverActivity, 14),
                    PremiumUi.dp(this@SystemAppRemoverActivity, 20), PremiumUi.dp(this@SystemAppRemoverActivity, 16))
                selectedStatus = PremiumUi.text(this@SystemAppRemoverActivity, "No apps selected", 13f, PremiumUi.muted, true)
                addView(selectedStatus)
                addView(PremiumUi.button(this@SystemAppRemoverActivity, "Disable selected apps", true).apply {
                    setOnClickListener { confirmDisable() }
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SystemAppRemoverActivity, 9)))
                addView(PremiumUi.button(this@SystemAppRemoverActivity, "Restore NativOS changes").apply {
                    setOnClickListener { restoreAll() }
                }, PremiumUi.matchWidth(PremiumUi.dp(this@SystemAppRemoverActivity, 8)))
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        loadApps()
    }

    private fun loadApps() {
        rows.clear()
        listContainer.removeAllViews()
        status.text = "Scanning installed system apps…"
        updateSelectedCount()
        Thread({
            val result = runCatching { SystemAppManager(this).scanSuggestions() }
            runOnUiThread {
                result.onSuccess(::showApps).onFailure { status.text = "Scan failed · ${it.message}" }
            }
        }, "nativOS-system-app-scan").start()
    }

    private fun showApps(apps: List<SystemAppManager.SuggestedApp>) {
        status.text = "${apps.size} curated suggestions found · review every selection"
        apps.groupBy { it.category }.forEach { (category, items) ->
            val accent = when {
                items.any { it.risk == SystemAppManager.Risk.REPLACEMENT_REQUIRED } -> PremiumUi.danger
                items.any { it.risk == SystemAppManager.Risk.DEGOOGLE_CORE } -> PremiumUi.warning
                else -> PremiumUi.success
            }
            listContainer.addView(PremiumUi.card(this, accent).apply {
                addView(PremiumUi.cardContent(this@SystemAppRemoverActivity).apply {
                    addView(PremiumUi.text(this@SystemAppRemoverActivity, category, 20f, bold = true))
                    addView(PremiumUi.verticalSpace(this@SystemAppRemoverActivity, 8))
                    items.forEachIndexed { index, item ->
                        if (index > 0) addView(PremiumUi.verticalSpace(this@SystemAppRemoverActivity, 10))
                        addView(appRow(item))
                    }
                })
            }, PremiumUi.matchWidth(PremiumUi.dp(this, 12)))
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
            SystemAppManager.Risk.RECOMMENDED -> "Recommended"
            SystemAppManager.Risk.DEGOOGLE_CORE -> "Google-dependent apps may stop working"
            SystemAppManager.Risk.REPLACEMENT_REQUIRED -> "Install and test a replacement first"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PremiumUi.dp(this@SystemAppRemoverActivity, 12), PremiumUi.dp(this@SystemAppRemoverActivity, 12),
                PremiumUi.dp(this@SystemAppRemoverActivity, 12), PremiumUi.dp(this@SystemAppRemoverActivity, 12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = PremiumUi.dp(this@SystemAppRemoverActivity, 16).toFloat()
                setColor(PremiumUi.surfaceHigh)
            }
            val checkbox = MaterialCheckBox(this@SystemAppRemoverActivity).apply {
                text = item.title + if (item.disabled) "  ·  Disabled" else ""
                textSize = 16f
                setTextColor(PremiumUi.text)
                typeface = Typeface.create("sans", Typeface.BOLD)
                isEnabled = !item.disabled
                setOnCheckedChangeListener { _, _ -> updateSelectedCount() }
            }
            rows[item] = checkbox
            addView(checkbox, PremiumUi.matchWidth())
            addView(PremiumUi.text(this@SystemAppRemoverActivity, item.packageName, 12f, PremiumUi.muted).apply {
                typeface = Typeface.MONOSPACE
                setPadding(PremiumUi.dp(this@SystemAppRemoverActivity, 48), 0, 0, 0)
            })
            addView(PremiumUi.text(this@SystemAppRemoverActivity, riskText, 12f, riskColor, true).apply {
                setPadding(PremiumUi.dp(this@SystemAppRemoverActivity, 48), PremiumUi.dp(this@SystemAppRemoverActivity, 5), 0, 0)
            })
        }
    }

    private fun updateSelectedCount() {
        if (!::selectedStatus.isInitialized) return
        val selected = rows.count { it.value.isChecked }
        selectedStatus.text = if (selected == 0) "No apps selected" else "$selected app${if (selected == 1) "" else "s"} selected"
        selectedStatus.setTextColor(if (selected == 0) PremiumUi.muted else PremiumUi.primary)
    }

    private fun confirmDisable() {
        val selected = rows.filterValues { it.isChecked }.keys
        if (selected.isEmpty()) {
            status.text = "Select at least one app to continue"
            scrollView.smoothScrollTo(0, 0)
            return
        }
        val highRisk = selected.count { it.risk != SystemAppManager.Risk.RECOMMENDED }
        MaterialAlertDialogBuilder(this)
            .setTitle("Disable ${selected.size} app${if (selected.size == 1) "" else "s"}?")
            .setMessage("$highRisk selections are high risk. Disabled apps disappear from Android but remain restorable through NativOS.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disable") { _, _ -> runAction(selected.map { it.packageName }, true) }
            .show()
    }

    private fun restoreAll() {
        val changed = SystemAppManager(this).changedPackages().size
        if (changed == 0) {
            status.text = "NativOS has no disabled packages to restore"
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore $changed app${if (changed == 1) "" else "s"}?")
            .setMessage("This re-enables every package disabled through NativOS.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> runAction(emptyList(), false) }
            .show()
    }

    private fun runAction(packages: List<String>, disable: Boolean) {
        status.text = if (disable) "Applying selected changes…" else "Restoring NativOS changes…"
        Thread({
            val result = runCatching {
                val manager = SystemAppManager(this)
                if (disable) manager.disable(packages) else manager.restoreAllChangedByNativOS()
            }
            runOnUiThread {
                status.text = result.fold(
                    onSuccess = { "${it.succeeded.size} changed · ${it.failed.size} failed" },
                    onFailure = { "Operation failed · ${it.message}" })
                loadApps()
            }
        }, "nativOS-system-app-action").start()
    }
}
