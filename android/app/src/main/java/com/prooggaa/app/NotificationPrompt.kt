package com.prooggaa.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * "Turn on notifications", in the website's style: says what the person
 * gets before Android's own permission question (or the phone's settings)
 * comes up.
 */
class NotificationPrompt(
    private val activity: Activity,
    private val onTurnOn: () -> Unit,
    private val onLater: () -> Unit,
) {
    private val dialog = Dialog(activity)

    init {
        val ink = color(R.color.proggaa_ink)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(26), dp(24))
            background = Comic.card(activity, color(R.color.proggaa_bg), radius = 24, shadow = 5)
        }

        content.addView(ImageView(activity).apply {
            setImageResource(R.drawable.proggaa_logo)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(88), dp(88)))

        content.addView(TextView(activity).apply {
            text = "🔔 STAY IN THE LOOP"
            setTextColor(ink)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(dp(12), dp(3), dp(14), dp(5))
            background = Comic.card(activity, color(R.color.proggaa_xp), radius = 999, shadow = 2)
            rotation = -2f
        }, wrap().apply { topMargin = dp(4) })

        content.addView(TextView(activity).apply {
            text = "Turn on notifications?"
            setTextColor(ink)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, wrap().apply { topMargin = dp(10) })

        content.addView(TextView(activity).apply {
            text = "Get new messages, replies and everything else from Proggaa on your phone, even when the app is closed."
            setTextColor(color(R.color.proggaa_muted_ink))
            textSize = 14f
            gravity = Gravity.CENTER
        }, matchWidth().apply { topMargin = dp(8) })

        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                Comic.button(activity, "Not now", fill = color(R.color.proggaa_surface), text = ink) {
                    dialog.dismiss()
                    onLater()
                },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) },
            )
            addView(
                Comic.button(activity, "Turn on", fill = color(R.color.proggaa_primary), text = Color.WHITE) {
                    dialog.dismiss()
                    onTurnOn()
                },
                LinearLayout.LayoutParams(0, dp(50), 1.6f),
            )
        }, matchWidth().apply { topMargin = dp(20) })

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { onLater() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (activity.resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(dp(420))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun show() {
        if (!activity.isFinishing) dialog.show()
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(v: Int) = Comic.dp(activity, v)
}
