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
    val width = min((metrics.widthPixels * widthFraction).toInt(), (maxWidthDp * metrics.density).toInt())
    window.setGravity(Gravity.END or Gravity.FILL_VERTICAL)
    window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
    window.setBackgroundDrawable(ColorDrawable(0xF20D0E11.toInt()))
    window.setDimAmount(0f)
}

/** Dark dialog theme for the side panels, the same on every device. */
internal const val SIDE_PANEL_THEME = android.R.style.Theme_Material_Dialog_Alert
