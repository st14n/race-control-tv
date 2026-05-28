package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.DialogFragment
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import fr.groggy.racecontrol.tv.R
import java.util.Locale

private data class AudioSelectionItem(
    val format: Format,
    val displayLabel: String
)

internal fun displayAudioTrackLabel(format: Format, displayLocale: Locale = Locale.getDefault()): String {
    val normalizedLanguage = format.language?.let(::normalizeAudioLanguageCode)
    val localizedLanguage = normalizedLanguage
        ?.let(Locale::forLanguageTag)
        ?.getDisplayLanguage(displayLocale)
        ?.takeIf { it.isNotBlank() && !it.equals(normalizedLanguage, ignoreCase = true) }
        ?.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(displayLocale) else char.toString()
        }

    return localizedLanguage
        ?: format.label?.takeIf { it.isNotBlank() }
        ?: format.language?.uppercase(displayLocale)
        ?: "Audio"
}

private fun normalizeAudioLanguageCode(language: String): String? {
    val candidate = language
        .substringBefore('-')
        .substringBefore('_')
        .trim()
        .lowercase(Locale.ROOT)
    if (candidate.isBlank()) {
        return null
    }
    if (candidate.length == 2) {
        return candidate
    }
    if (candidate.length == 3) {
        return Locale.getAvailableLocales().firstNotNullOfOrNull { locale ->
            val iso3Language = runCatching { locale.isO3Language.lowercase(Locale.ROOT) }.getOrNull()
            locale.language.takeIf { it.length == 2 && iso3Language == candidate }
        } ?: candidate
    }
    return candidate
}

private fun audioSelectionSortPriority(format: Format): Int {
    return when (format.language?.let(::normalizeAudioLanguageCode)) {
        "en" -> 0
        "nl" -> 1
        else -> 2
    }
}

class AudioSelectionDialogFragment(
    private val audioGroups: List<Tracks.Group>,
    private val includeCustomRadioOption: Boolean = true,
    private val onDialogDismissed: (() -> Unit)? = null
) : DialogFragment() {

    private var onAudioLanguageSelectedListener: ((String?) -> Unit)? = null
    private var onCustomRadioSelectedListener: (() -> Unit)? = null

    private val formats: List<Format> by lazy {
        audioGroups.flatMap { group ->
            (0 until group.length).map { group.getTrackFormat(it) }
        }
    }

    private val audioItems: List<AudioSelectionItem> by lazy {
        formats.map { format ->
            AudioSelectionItem(format = format, displayLabel = displayAudioTrackLabel(format))
        }.sortedWith(
            compareBy<AudioSelectionItem>(
                { audioSelectionSortPriority(it.format) },
                { it.displayLabel.lowercase(Locale.getDefault()) }
            )
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = buildList {
            if (includeCustomRadioOption) {
                add(getString(R.string.custom_radio_audio_label))
            }
            addAll(audioItems.map { it.displayLabel })
        }.toTypedArray()

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.audio_selection_dialog_title)
            .setItems(items) { _, i -> onItemSelected(i) }
            .setNeutralButton(R.string.audio_selection_close, null)
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

    private fun onItemSelected(index: Int) {
        val audioIndex = if (includeCustomRadioOption) index - 1 else index
        when {
            includeCustomRadioOption && index == 0 -> onCustomRadioSelectedListener?.invoke()
            audioIndex in audioItems.indices -> onAudioLanguageSelectedListener?.invoke(audioItems[audioIndex].format.language)
        }
        dismiss()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDialogDismissed?.invoke()
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
