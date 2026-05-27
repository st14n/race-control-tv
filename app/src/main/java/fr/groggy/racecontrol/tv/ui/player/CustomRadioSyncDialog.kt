package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.DialogFragment
import fr.groggy.racecontrol.tv.R
import java.util.Locale

class CustomRadioSyncDialog(
    private val currentOffsetMs: Long,
    private val onOffsetSelected: (Long) -> Long,
    private val onUserInteraction: (() -> Unit)? = null,
    private val onDialogDismissed: (() -> Unit)? = null
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
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
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
        dialog.setOnKeyListener { _, keyCode, event ->
            val isDismissKey = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
            if (!isDismissKey) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onUserInteraction?.invoke()
                }
                return@setOnKeyListener false
            }

            if (event.action == KeyEvent.ACTION_DOWN) {
                onUserInteraction?.invoke()
                dismiss()
            }
            true
        }

        return dialog
    }

    private fun formatOffset(offsetMs: Long): String {
        val clampedMs = offsetMs.coerceIn(0L, 30_000L)
        return String.format(Locale.getDefault(), "%.1f s", clampedMs / 1000.0)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDialogDismissed?.invoke()
    }
}
