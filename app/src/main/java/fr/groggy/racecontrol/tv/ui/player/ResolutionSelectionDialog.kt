package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import fr.groggy.racecontrol.tv.R
import kotlin.math.roundToInt

class ResolutionSelectionDialog(
    private val tracks: Tracks
) : DialogFragment() {

    private var onResolutionSelectedListener: ((Int, Int) -> Unit)? = null

    private val formats: List<Format> by lazy {
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    if (group.isTrackSupported(i)) {
                        val fmt = group.getTrackFormat(i)
                        if (fmt.frameRate > 1F) fmt else null
                    } else null
                }
            }
            .distinctBy { Pair(it.height, it.frameRate.roundToInt()) }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = listOf(getText(R.string.video_selection_quality_auto)) + formats.map {
            requireContext().getString(R.string.video_quality, it.height, it.frameRate.roundToInt())
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.video_selection_dialog_title)
            .setItems(items.toTypedArray()) { _, i -> selectVideo(i) }
            .create()
    }

    fun setResolutionSelectedListener(listener: (Int, Int) -> Unit): ResolutionSelectionDialog {
        onResolutionSelectedListener = listener
        return this
    }

    private fun selectVideo(index: Int) {
        if (index == 0) {
            onResolutionSelectedListener?.invoke(Int.MAX_VALUE, Int.MAX_VALUE)
            return
        }
        val format = formats[index - 1]
        onResolutionSelectedListener?.invoke(format.width, format.height)
    }
}
