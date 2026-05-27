package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.DialogFragment
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import fr.groggy.racecontrol.tv.R
import kotlin.math.roundToInt

class ResolutionSelectionDialog(
    private val tracks: Tracks,
    private val onDialogDismissed: (() -> Unit)? = null
) : DialogFragment() {

    private var onResolutionSelectedListener: ((Int, Int) -> Unit)? = null

    private val formats: List<Format> by lazy {
        tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    // allowExceedingCapabilities=true so 4K tracks are listed even if
                    // the track selector's current viewport constraint would skip them.
                    if (group.isTrackSupported(i, /* allowExceedingCapabilities= */ true)) {
                        val fmt = group.getTrackFormat(i)
                        if (fmt.frameRate > 1F) fmt else null
                    } else null
                }
            }
            .distinctBy { Pair(it.height, it.frameRate.roundToInt()) }
    }

    /** Human-readable label for a video format. 2160p is shown as "4K (2160p)". */
    private fun formatLabel(fmt: Format): String {
        val fps = fmt.frameRate.roundToInt()
        return if (fmt.height >= 2160) {
            requireContext().getString(R.string.video_quality_4k, fmt.height, fps)
        } else {
            requireContext().getString(R.string.video_quality, fmt.height, fps)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = listOf(getText(R.string.video_selection_quality_auto)) + formats.map { formatLabel(it) }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.video_selection_dialog_title)
            .setItems(items.toTypedArray()) { _, i -> selectVideo(i) }
            .create().apply {
                setOnShowListener {
                    listView?.let { list ->
                        list.post {
                            list.setSelection(0)
                            (list.getChildAt(0) ?: list).requestFocus()
                        }
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
                    ) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
            }
    }

    fun setResolutionSelectedListener(listener: (Int, Int) -> Unit): ResolutionSelectionDialog {
        onResolutionSelectedListener = listener
        return this
    }

    private fun selectVideo(index: Int) {
        if (index == 0) {
            onResolutionSelectedListener?.invoke(Int.MAX_VALUE, Int.MAX_VALUE)
            dismiss()
            return
        }
        val format = formats[index - 1]
        onResolutionSelectedListener?.invoke(format.width, format.height)
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDialogDismissed?.invoke()
    }
}
