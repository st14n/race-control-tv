package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.DialogFragment
import fr.groggy.racecontrol.tv.R
import java.util.Locale

class CustomRadioSyncDialog(
    private val currentOffsetMs: Long,
    private val onOffsetSelected: (Long) -> Long,
    private val onUserInteraction: (() -> Unit)? = null
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        var dialogOffsetMs = currentOffsetMs.coerceIn(0L, 30_000L)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.custom_radio_sync_title, formatOffset(dialogOffsetMs)))
            .setNegativeButton(R.string.custom_radio_sync_decrease, null)
            .setPositiveButton(R.string.custom_radio_sync_increase, null)
            .setNeutralButton(R.string.custom_radio_sync_close, null)
            .create()

        dialog.setOnShowListener {
            onUserInteraction?.invoke()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialogOffsetMs = onOffsetSelected((dialogOffsetMs - 500L).coerceIn(0L, 30_000L))
                onUserInteraction?.invoke()
                dialog.setTitle(getString(R.string.custom_radio_sync_title, formatOffset(dialogOffsetMs)))
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialogOffsetMs = onOffsetSelected((dialogOffsetMs + 500L).coerceIn(0L, 30_000L))
                onUserInteraction?.invoke()
                dialog.setTitle(getString(R.string.custom_radio_sync_title, formatOffset(dialogOffsetMs)))
            }
        }
        dialog.setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                onUserInteraction?.invoke()
            }
            false
        }

        return dialog
    }

    private fun formatOffset(offsetMs: Long): String {
        val clampedMs = offsetMs.coerceIn(0L, 30_000L)
        return String.format(Locale.getDefault(), "%.1f s", clampedMs / 1000.0)
    }
}
