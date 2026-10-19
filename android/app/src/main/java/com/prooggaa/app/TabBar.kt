package com.prooggaa.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * The app's own tab bar along the bottom, drawn natively in the site's style:
 * an ink line on top, and the selected tab in a yellow pill (like the site's
 * bottom bar), with unread badges. Tapping a tab switches page stacks;
 * tapping the open one goes back to its first page.
 */
@SuppressLint("ViewConstructor")
class TabBar(
    context: Context,
    private val onSelect: (index: Int, reselected: Boolean) -> Unit,
) : LinearLayout(context) {

    private val icons = listOf(R.drawable.ic_tab_home, R.drawable.ic_tab_learn, R.drawable.ic_tab_exams, R.drawable.ic_tab_community, R.drawable.ic_tab_chats)
    private val items = mutableListOf<Item>()
    private var selected = 0
    private var ink = ContextCompat.getColor(context, R.color.proggaa_ink)
    private var muted = ContextCompat.getColor(context, R.color.proggaa_muted_ink)
    private val topLine = GradientDrawable().apply { setColor(ink) }

    private class Item(val root: FrameLayout, val pill: View, val icon: ImageView, val label: TextView, val badge: TextView)

    init {
        orientation = HORIZONTAL
        setPadding(0, dp(2), 0, 0)
        Site.TABS.forEachIndexed { i, tab -> addView(buildItem(i, tab.label), LayoutParams(0, dp(62), 1f)) }
        setPageColor(ContextCompat.getColor(context, R.color.proggaa_surface))
        select(0, notify = false)
    }

    private fun buildItem(index: Int, title: String): View {
        val root = FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val again = index == selected
                select(index, notify = false)
                onSelect(index, again)
            }
        }
        val pill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(ContextCompat.getColor(context, R.color.proggaa_xp))
                setStroke(dp(2), ink)
            }
            alpha = 0f
        }
        root.addView(pill, FrameLayout.LayoutParams(dp(56), dp(32), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(6) })
        val icon = ImageView(context).apply { setImageResource(icons[index]) }
        root.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(11) })
        val label = TextView(context).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(6) })
        val badge = TextView(context).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, dp(4), 0)
            minWidth = dp(18)
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(ContextCompat.getColor(context, R.color.proggaa_danger))
            }
            visibility = GONE
        }
        root.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(2)
            leftMargin = dp(22)
        })
        items += Item(root, pill, icon, label, badge)
        return root
    }

    /** Highlights a tab (with a little pop), without telling the activity. */
    fun select(index: Int, notify: Boolean = false) {
        selected = index
        items.forEachIndexed { i, item ->
            val on = i == index
            item.pill.animate().alpha(if (on) 1f else 0f).scaleX(if (on) 1f else 0.6f).setDuration(180).start()
            item.icon.imageTintList = ColorStateList.valueOf(if (on) ContextCompat.getColor(context, R.color.proggaa_ink) else muted)
            item.label.setTextColor(if (on) ink else muted)
            item.label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (notify) onSelect(index, false)
    }

    /** The red count on a tab (0 hides it). */
    fun setBadge(index: Int, count: Int) {
        val badge = items.getOrNull(index)?.badge ?: return
        badge.visibility = if (count > 0) VISIBLE else GONE
        badge.text = if (count > 99) "99+" else count.toString()
    }

    /** Follows the page's colours, so the bar matches light and dark mode. */
    fun setPageColor(color: Int) {
        setBackgroundColor(color)
        val dark = ColorUtils.calculateLuminance(color) < 0.4
        ink = if (dark) Color.WHITE else ContextCompat.getColor(context, R.color.proggaa_ink)
        muted = if (dark) Color.argb(170, 255, 255, 255) else ContextCompat.getColor(context, R.color.proggaa_muted_ink)
        topLine.setColor(ink)
        items.forEach { (it.pill.background as GradientDrawable).setStroke(dp(2), ContextCompat.getColor(context, R.color.proggaa_ink)) }
        select(selected)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        topLine.setBounds(0, 0, width, dp(2))
        topLine.draw(canvas)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
