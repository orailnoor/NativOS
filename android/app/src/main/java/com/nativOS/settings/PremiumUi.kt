package com.nativOS.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

object PremiumUi {
    val background = Color.rgb(9, 11, 18)
    val surface = Color.rgb(20, 24, 36)
    val surfaceHigh = Color.rgb(28, 34, 49)
    val primary = Color.rgb(117, 107, 255)
    val primarySoft = Color.rgb(41, 36, 80)
    val text = Color.rgb(247, 247, 252)
    val muted = Color.rgb(167, 174, 192)
    val success = Color.rgb(66, 211, 162)
    val warning = Color.rgb(255, 190, 85)
    val danger = Color.rgb(255, 107, 122)
    val border = Color.rgb(44, 51, 70)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun pageBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(background, Color.rgb(13, 17, 29), background)
    )

    fun card(context: Context, accent: Int? = null): MaterialCardView = MaterialCardView(context).apply {
        radius = dp(context, 22).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(surface)
        strokeWidth = dp(context, 1)
        strokeColor = accent ?: border
        useCompatPadding = false
    }

    fun cardContent(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
    }

    fun text(context: Context, value: String, size: Float, color: Int = text, bold: Boolean = false) =
        TextView(context).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans", Typeface.BOLD)
            includeFontPadding = false
        }

    fun badge(context: Context, value: String, color: Int): TextView =
        text(context, value.uppercase(), 11f, color, true).apply {
            letterSpacing = 0.12f
            setPadding(dp(context, 11), dp(context, 7), dp(context, 11), dp(context, 7))
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 20).toFloat()
                setColor(withAlpha(color, 30))
                setStroke(dp(context, 1), withAlpha(color, 90))
            }
        }

    fun button(context: Context, label: String, primaryAction: Boolean = false): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create("sans", Typeface.BOLD)
            cornerRadius = dp(context, 15)
            minHeight = dp(context, 52)
            insetTop = 0
            insetBottom = 0
            setTextColor(if (primaryAction) Color.WHITE else PremiumUi.text)
            backgroundTintList = ColorStateList.valueOf(if (primaryAction) primary else surfaceHigh)
            strokeWidth = if (primaryAction) 0 else dp(context, 1)
            strokeColor = ColorStateList.valueOf(border)
        }

    fun verticalSpace(context: Context, height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, height))
    }

    fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
