package fr.groggy.racecontrol.tv.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager

/**
 * Glide for a card's view, or null once its screen is being destroyed: Glide refuses (crashes) to
 * start or clear a load for a destroyed activity, which is when Leanback unbinds the cards.
 */
internal fun glideFor(view: View): RequestManager? {
    val activity = view.context.findActivity()
    if (activity != null && (activity.isDestroyed || activity.isFinishing)) return null
    return runCatching { Glide.with(view) }.getOrNull()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
