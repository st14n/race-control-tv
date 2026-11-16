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
    /** What the menu shows: the name, with an arrow in front of the track that plays now. */
    val displayLabel: String,
    val group: Tracks.Group,
    val trackIndex: Int,
    val baseLabel: String = displayLabel,
    val selected: Boolean = false
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
    private val audioGroups: List<Tracks.Group> = emptyList(),
    private val includeCustomRadioOption: Boolean = true,
    /** When the other main feed's commentary is merged in: the names of the two feeds. */
    private val feedLabel: String? = null,
    private val companionFeedLabel: String? = null,
    private val onDialogDismissed: (() -> Unit)? = null
) : DialogFragment() {

    private var onAudioLanguageSelectedListener: ((String?) -> Unit)? = null
    private var onAudioTrackChosenListener: ((Tracks.Group, Int) -> Unit)? = null
    private var onCustomRadioSelectedListener: (() -> Unit)? = null

    private val audioItems: List<AudioSelectionItem> by lazy {
        audioGroups.flatMap { group ->
            (0 until group.length).map { index ->
                val format = group.getTrackFormat(index)
                // A merged second source has its group ids prefixed with its position: "1:..."
                val fromCompanion = companionFeedLabel != null && group.mediaTrackGroup.id.startsWith("1:")
                val feed = if (companionFeedLabel == null) null else if (fromCompanion) companionFeedLabel else feedLabel
                val label = displayAudioTrackLabel(format).let { if (feed != null) "$it ($feed)" else it }
                AudioSelectionItem(
                    format = format,
                    displayLabel = label,
                    group = group,
                    trackIndex = index,
                    baseLabel = label,
                    selected = group.isTrackSelected(index)
                )
            }
        }
            // The same track often shows up twice (stereo and surround of one language, or a rendition
            // next to the muxed audio): one line each, the one that plays now if it is among them
            .groupBy { it.baseLabel }
            .map { (_, same) -> same.firstOrNull { it.selected } ?: same.first() }
            .map { if (it.selected) it.copy(displayLabel = "▶ ${it.baseLabel}") else it }
            .sortedWith(
                compareBy<AudioSelectionItem>(
                    { audioSelectionSortPriority(it.format) },
                    { it.baseLabel.lowercase(Locale.getDefault()) }
                )
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated by the system (its tracks and callbacks are gone): close instead of crashing
        if (savedInstanceState != null) dismissAllowingStateLoss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val items = buildList {
            if (includeCustomRadioOption) {
                add(getString(R.string.custom_radio_audio_label))
            }
            addAll(audioItems.map { it.displayLabel })
        }.toTypedArray()

        return AlertDialog.Builder(requireContext(), SIDE_PANEL_THEME)
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
            audioIndex in audioItems.indices -> {
                val item = audioItems[audioIndex]
                onAudioLanguageSelectedListener?.invoke(item.format.language)
                onAudioTrackChosenListener?.invoke(item.group, item.trackIndex)
            }
        }
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.showAsSidePanel()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDialogDismissed?.invoke()
    }

    fun onAudioLanguageSelected(listener: (String?) -> Unit): AudioSelectionDialogFragment {
        onAudioLanguageSelectedListener = listener
        return this
    }

    fun onAudioTrackChosen(listener: (Tracks.Group, Int) -> Unit): AudioSelectionDialogFragment {
        onAudioTrackChosenListener = listener
        return this
    }

    fun onCustomRadioSelected(listener: () -> Unit): AudioSelectionDialogFragment {
        onCustomRadioSelectedListener = listener
        return this
    }
}
