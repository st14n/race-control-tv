package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import fr.groggy.racecontrol.tv.R

class AudioSelectionDialogFragment(
    private val audioGroups: List<Tracks.Group>
) : DialogFragment() {

    private var onAudioLanguageSelectedListener: ((String?) -> Unit)? = null
    private var onCustomRadioSelectedListener: (() -> Unit)? = null

    private val formats: List<Format> by lazy {
        audioGroups.flatMap { group ->
            (0 until group.length).map { group.getTrackFormat(it) }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = buildList {
            add(getString(R.string.custom_radio_audio_label))
            addAll(formats.map { it.label ?: it.language ?: "Audio" })
        }.toTypedArray()

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.audio_selection_dialog_title)
            .setItems(items) { _, i -> onItemSelected(i) }
            .setNeutralButton(R.string.audio_selection_close, null)
            .create()
    }

    private fun onItemSelected(index: Int) {
        when {
            index == 0 -> onCustomRadioSelectedListener?.invoke()
            index - 1 in formats.indices -> onAudioLanguageSelectedListener?.invoke(formats[index - 1].language)
        }
    }

    fun onAudioLanguageSelected(listener: (String?) -> Unit): AudioSelectionDialogFragment {
        onAudioLanguageSelectedListener = listener
        return this
    }

    fun onCustomRadioSelected(listener: () -> Unit): AudioSelectionDialogFragment {
        onCustomRadioSelectedListener = listener
        return this
    }
}
