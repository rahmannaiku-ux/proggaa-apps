package com.prooggaa.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * The update prompt, drawn in the website's style: cream card, ink outlines
 * with a hard offset shadow, a yellow "new version" sticker, the version jump,
 * what's new, and a progress bar while the update downloads.
 * Built in code (no layout XML) so it stays in one place with its logic.
 */
class UpdateDialog(
    private val activity: Activity,
    currentVersion: String,
    newVersion: String,
    notes: String,
    private val onUpdate: () -> Unit,
    private val onLater: () -> Unit,
    private val onCancelDownload: () -> Unit,
) {
    private val ink = color(R.color.proggaa_ink)
    private val mutedInk = color(R.color.proggaa_muted_ink)
    private val primary = color(R.color.proggaa_primary)
    private val accent = color(R.color.proggaa_accent)
    private val dialog = Dialog(activity)

    private val notesPanel: LinearLayout
    private val progressPanel: LinearLayout
    private val bar: ProgressBar
    private val status: TextView
    private val percent: TextView
    private val laterButton: Button
    private val updateButton: Button

    init {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(26), dp(24))
            background = comicCard(color(R.color.proggaa_bg), radius = 24, shadow = 5)
        }

        content.addView(ImageView(activity).apply {
            setImageResource(R.drawable.proggaa_logo)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(96), dp(96)))

        content.addView(TextView(activity).apply {
            text = "⚡ NEW VERSION"
            setTextColor(ink)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(dp(12), dp(3), dp(14), dp(5))
            background = comicCard(color(R.color.proggaa_xp), radius = 999, shadow = 2)
            rotation = -2f
        }, wrap().apply { topMargin = dp(4) })

        content.addView(TextView(activity).apply {
            text = "A fresh Proggaa is here!"
            setTextColor(ink)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, wrap().apply { topMargin = dp(10) })

        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chip(currentVersion, filled = false))
            addView(TextView(activity).apply {
                text = "→"
                setTextColor(accent)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(10), 0, dp(10), 0)
            })
            addView(chip(newVersion, filled = true))
        }, wrap().apply { topMargin = dp(10) })

        notesPanel = panel().apply {
            addView(TextView(activity).apply {
                text = "What's new"
                setTextColor(accent)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(activity).apply {
                text = notes.ifBlank { "Improvements and fixes." }
                setTextColor(mutedInk)
                textSize = 14f
                maxLines = 6
                movementMethod = ScrollingMovementMethod()
            })
        }
        content.addView(notesPanel, matchWidth().apply { topMargin = dp(16) })

        bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = ColorStateList.valueOf(primary)
            indeterminateTintList = ColorStateList.valueOf(primary)
            progressBackgroundTintList = ColorStateList.valueOf(color(R.color.proggaa_muted))
        }
        status = TextView(activity).apply { setTextColor(mutedInk); textSize = 13f; text = "Downloading…" }
        percent = TextView(activity).apply { setTextColor(ink); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        progressPanel = panel().apply {
            isVisible = false
            addView(bar, matchWidth())
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(percent)
            }, matchWidth().apply { topMargin = dp(4) })
        }
        content.addView(progressPanel, matchWidth().apply { topMargin = dp(16) })

        laterButton = comicButton("Later", fill = color(R.color.proggaa_surface), text = ink) {
            dialog.dismiss()
            onLater()
        }
        updateButton = comicButton("Update now", fill = primary, text = Color.WHITE) { onUpdate() }
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(laterButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) })
            addView(updateButton, LinearLayout.LayoutParams(0, dp(50), 1.6f))
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

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    /** Switches to the download view: progress bar, and Cancel instead of Later. */
    fun showDownloading() {
        notesPanel.isVisible = false
        progressPanel.isVisible = true
        status.setTextColor(mutedInk)
        status.text = "Downloading…"
        percent.text = ""
        bar.isIndeterminate = true
        dialog.setCancelable(false)
        updateButton.isEnabled = false
        updateButton.text = "Updating…"
        laterButton.text = "Cancel"
        laterButton.setOnClickListener {
            onCancelDownload()
            dialog.dismiss()
        }
    }

    /** -1 = size unknown. */
    fun setProgress(percentDone: Int) {
        bar.isIndeterminate = percentDone < 0
        if (percentDone >= 0) {
            bar.progress = percentDone
            percent.text = "$percentDone%"
        }
    }

    fun showError(message: String) {
        status.setTextColor(color(R.color.proggaa_danger))
        status.text = message
        percent.text = ""
        bar.isIndeterminate = false
        bar.progress = 0
        dialog.setCancelable(true)
        updateButton.isEnabled = true
        updateButton.text = "Try again"
        laterButton.text = "Later"
        laterButton.setOnClickListener {
            dialog.dismiss()
            onLater()
        }
    }

    // ---------------------------------------------------------------------

    private fun chip(label: String, filled: Boolean) = TextView(activity).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (filled) Color.WHITE else mutedInk)
        setPadding(dp(12), dp(3), dp(12), dp(4))
        background = comicCard(if (filled) primary else color(R.color.proggaa_surface), radius = 10, shadow = if (filled) 3 else 0)
    }

    private fun panel() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(18), dp(16))
        background = comicCard(color(R.color.proggaa_surface), radius = 16, shadow = 4)
    }

    private fun comicButton(label: String, fill: Int, text: Int, onClick: () -> Unit) = Button(activity).apply {
        this.text = label
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(text)
        stateListAnimator = null
        background = comicCard(fill, radius = 14, shadow = 3)
        setPadding(0, 0, dp(3), dp(3))
        setOnClickListener { onClick() }
    }

    /**
     * The site's "comic panel": a rounded fill with an ink outline and a hard
     * ink shadow offset down and to the right by `shadow` dp.
     */
    private fun comicCard(fill: Int, radius: Int, shadow: Int): Drawable {
        val r = dp(radius).toFloat()
        val face = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = r
            setStroke(dp(2), ink)
        }
        if (shadow == 0) return face
        val back = GradientDrawable().apply {
            setColor(ink)
            cornerRadius = r
        }
        val s = dp(shadow)
        return LayerDrawable(arrayOf(back, face)).apply {
            setLayerInset(0, s, s, 0, 0)
            setLayerInset(1, 0, 0, s, s)
        }
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun color(id: Int) = ContextCompat.getColor(activity, id)
    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

}
