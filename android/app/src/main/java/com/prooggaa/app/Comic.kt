package com.prooggaa.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.Button
import androidx.core.content.ContextCompat

/** The website's comic look for the app's own dialogs. */
object Comic {
    /**
     * The site's "comic panel": a rounded fill with an ink outline and a hard
     * ink shadow offset down and to the right by `shadow` dp.
     */
    fun card(context: Context, fill: Int, radius: Int, shadow: Int): Drawable {
        val ink = ContextCompat.getColor(context, R.color.proggaa_ink)
        val r = dp(context, radius).toFloat()
        val face = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = r
            setStroke(dp(context, 2), ink)
        }
        if (shadow == 0) return face
        val back = GradientDrawable().apply {
            setColor(ink)
            cornerRadius = r
        }
        val s = dp(context, shadow)
        return LayerDrawable(arrayOf(back, face)).apply {
            setLayerInset(0, s, s, 0, 0)
            setLayerInset(1, 0, 0, s, s)
        }
    }

    fun button(context: Context, label: String, fill: Int, text: Int, onClick: () -> Unit) = Button(context).apply {
        this.text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(text)
        stateListAnimator = null
        background = card(context, fill, radius = 14, shadow = 3)
        setPadding(0, 0, dp(context, 3), dp(context, 3))
        setOnClickListener { onClick() }
    }

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
