package fr.groggy.racecontrol.tv.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.preference.EditTextPreferenceDialogFragmentCompat

class TvEditTextPreferenceDialogFragment : EditTextPreferenceDialogFragmentCompat() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also { dialog ->
            dialog.setOnShowListener {
                val editText = dialog.findViewById<EditText>(android.R.id.edit) ?: return@setOnShowListener
                editText.requestFocus()
                editText.setSelection(editText.text?.length ?: 0)
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                editText.post {
                    requireContext().getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    override fun needInputMethod(): Boolean = true

    companion object {
        fun newInstance(key: String): TvEditTextPreferenceDialogFragment {
            return TvEditTextPreferenceDialogFragment().apply {
                arguments = Bundle(1).apply {
                    putString(ARG_KEY, key)
                }
            }
        }
    }
}