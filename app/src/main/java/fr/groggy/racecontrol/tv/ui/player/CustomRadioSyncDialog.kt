package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.fragment.app.DialogFragment
import fr.groggy.racecontrol.tv.R

/**
 * Nudges the custom radio earlier/later. What a step changes (the manual delay or the auto-sync
 * correction) is up to the caller; the neutral button switches between auto-sync and manual.
 */
class CustomRadioSyncDialog(
    private val titleProvider: () -> String,
    private val onAdjust: (deltaMs: Long) -> Unit,
    private val modeToggleLabelProvider: () -> String,
    private val onToggleMode: () -> Unit,
    private val onUserInteraction: (() -> Unit)? = null,
    private val onDialogDismissed: (() -> Unit)? = null
) : DialogFragment() {

    companion object {
        private const val STEP_MS = 500L
        // Auto-sync keeps moving the delay, so keep the title current while the dialog is open
        private const val TITLE_REFRESH_MS = 1_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTitle = object : Runnable {
        override fun run() {
            dialog?.setTitle(titleProvider())
            handler.postDelayed(this, TITLE_REFRESH_MS)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(titleProvider())
            .setNegativeButton(R.string.custom_radio_sync_decrease, null)
            .setPositiveButton(R.string.custom_radio_sync_increase, null)
            .setNeutralButton(modeToggleLabelProvider(), null)
            .create()

        dialog.setOnShowListener {
            onUserInteraction?.invoke()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                onAdjust(-STEP_MS)
                onUserInteraction?.invoke()
                dialog.setTitle(titleProvider())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onAdjust(STEP_MS)
                onUserInteraction?.invoke()
                dialog.setTitle(titleProvider())
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { button ->
                onToggleMode()
                onUserInteraction?.invoke()
                dialog.setTitle(titleProvider())
                (button as? android.widget.Button)?.text = modeToggleLabelProvider()
            }
            handler.postDelayed(refreshTitle, TITLE_REFRESH_MS)
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

    override fun onDismiss(dialog: DialogInterface) {
        handler.removeCallbacks(refreshTitle)
        super.onDismiss(dialog)
        onDialogDismissed?.invoke()
    }
}
