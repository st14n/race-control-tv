package fr.groggy.racecontrol.tv.ui.player

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import kotlin.math.min

/**
 * Player menus open as a panel on the right instead of a dialog in the middle (which some
 * devices, Fire TV among them, stretch over the whole picture): the video stays visible.
 * Call from DialogFragment.onStart, once the window exists.
 */
internal fun Dialog.showAsSidePanel(widthFraction: Float = 0.34f, maxWidthDp: Int = 440) {
    val window = window ?: return
    val metrics = context.resources.displayMetrics
    if (metrics.widthPixels / metrics.density < 600f) {
        // A phone (portrait): a panel from the bottom over the full width, the picture stays above it
        window.setGravity(Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (metrics.heightPixels * 0.7f).toInt())
    } else {
        val width = min((metrics.widthPixels * widthFraction).toInt(), (maxWidthDp * metrics.density).toInt())
        window.setGravity(Gravity.END or Gravity.FILL_VERTICAL)
        window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    window.setBackgroundDrawable(ColorDrawable(0xF20D0E11.toInt()))
    window.setDimAmount(0f)
    (this as? android.app.AlertDialog)?.highlightFocus()
}

/** The line and button with focus in red with a white edge: the default grey was hard to see on a TV. */
private fun android.app.AlertDialog.highlightFocus() {
    listView?.apply {
        selector = androidx.core.content.ContextCompat.getDrawable(context, fr.groggy.racecontrol.tv.R.drawable.menu_item_selected)
        setDrawSelectorOnTop(false)
    }
    val density = context.resources.displayMetrics.density
    fun pill(color: Int, stroke: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 22f * density
        setColor(color)
        if (stroke) setStroke((2 * density).toInt(), android.graphics.Color.WHITE)
    }
    for (which in intArrayOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE, android.app.AlertDialog.BUTTON_NEUTRAL)) {
        getButton(which)?.apply {
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), pill(0xFFE10600.toInt(), true))
                addState(intArrayOf(android.R.attr.state_pressed), pill(0xFFE10600.toInt(), false))
                addState(intArrayOf(), ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            setTextColor(android.graphics.Color.WHITE)
        }
    }
}

/** Dark dialog theme for the side panels, the same on every device. */
internal const val SIDE_PANEL_THEME = android.R.style.Theme_Material_Dialog_Alert
