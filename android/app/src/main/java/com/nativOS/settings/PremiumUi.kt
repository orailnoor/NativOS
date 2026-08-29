package com.nativOS.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

object PremiumUi {
    val background = Color.BLACK
    val surface = Color.rgb(28, 28, 30)
    val surfaceHigh = Color.rgb(44, 44, 46)
    val primary = Color.rgb(10, 132, 255)
    val text = Color.rgb(242, 242, 247)
    val muted = Color.rgb(142, 142, 147)
    val success = Color.rgb(48, 209, 88)
    val warning = Color.rgb(255, 159, 10)
    val danger = Color.rgb(255, 69, 58)
    val border = Color.rgb(58, 58, 60)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun pageBackground() = ColorDrawable(background)

    fun card(context: Context): MaterialCardView = MaterialCardView(context).apply {
        radius = dp(context, 14).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(surface)
        strokeWidth = 0
        useCompatPadding = false
    }

    fun cardContent(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
    }

    fun text(context: Context, value: String, size: Float, color: Int = text, bold: Boolean = false) =
        TextView(context).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans", Typeface.BOLD)
            includeFontPadding = false
        }

    fun button(context: Context, label: String, primaryAction: Boolean = false): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create("sans", Typeface.BOLD)
            cornerRadius = dp(context, 12)
            minHeight = dp(context, 48)
            insetTop = 0
            insetBottom = 0
            setTextColor(if (primaryAction) Color.WHITE else primary)
            backgroundTintList = ColorStateList.valueOf(if (primaryAction) primary else surface)
            strokeWidth = 0
        }

    fun sectionLabel(context: Context, label: String): TextView =
        text(context, label.uppercase(), 12f, muted).apply {
            letterSpacing = 0.04f
            setPadding(dp(context, 16), 0, 0, dp(context, 8))
        }

    fun separator(context: Context, startInset: Int = 16): View = View(context).apply {
        setBackgroundColor(border)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
        ).apply { marginStart = dp(context, startInset) }
    }

    fun verticalSpace(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, height))
    }

    fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }
}
