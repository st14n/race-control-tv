package fr.groggy.racecontrol.tv.ui.settings

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.Keep
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.leanback.preference.LeanbackSettingsFragmentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsViewModel
import fr.groggy.racecontrol.tv.ui.signin.SignInActivity
import kotlinx.coroutines.launch

@Keep
@AndroidEntryPoint
class SettingsFragment: LeanbackSettingsFragmentCompat() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onDestroy() {
        viewModel.applySettings()

        super.onDestroy()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = childFragmentManager.fragmentFactory.instantiate(
            requireActivity().classLoader,
            pref.fragment
        ).also {
            it.arguments = pref.extras
        }
        startPreferenceFragment(fragment)

        return true
    }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        val fragment = PreferenceFragment().apply {
            arguments = Bundle().apply { putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.key) }
        }
        startPreferenceFragment(fragment)

        return true
    }

    override fun onPreferenceStartInitialScreen() {
        startPreferenceFragment(PreferenceFragment())
    }

    // Block Leanback's built-in plain-text EditText dialog for keys we handle
    // ourselves via setOnPreferenceClickListener in PreferenceFragment.
    // DialogPreference.onClick() calls showDialog() BEFORE the click listener fires,
    // so without this the Leanback dialog opens alongside our AlertDialog.
    override fun onPreferenceDisplayDialog(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        if (pref.key == Settings.KEY_F1_USERNAME ||
            pref.key == Settings.KEY_F1_PASSWORD ||
            pref.key == Settings.KEY_CUSTOM_RADIO_URL) {
            return true // suppressed — our AlertDialog handles it
        }
        return super.onPreferenceDisplayDialog(caller, pref)
    }

    @Keep
    @AndroidEntryPoint
    class PreferenceFragment: LeanbackPreferenceFragmentCompat() {
        private val viewModel: SettingsViewModel by viewModels({ requireParentFragment() })
        private var currentAccountPreference: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            currentAccountPreference = findPreference("current_account")
            updateCurrentAccountSummary()

            findPreference<Preference>(Settings.KEY_F1_USERNAME)?.setOnPreferenceChangeListener { _, _ ->
                updateCurrentAccountSummary()
                true
            }

            findPreference<EditTextPreference>(Settings.KEY_F1_PASSWORD)?.let { pref ->
                // Always show dots in the preference row — never expose the stored value
                pref.summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> { "●●●●●●●●" }
                pref.setOnPreferenceChangeListener { _, _ ->
                    updateCurrentAccountSummary()
                    true
                }
            }

            findPreference<Preference>("reset_settings")?.setOnPreferenceClickListener {
                viewModel.resetSettings()
                activity?.finish()
                true
            }

            findPreference<Preference>("donations")?.setOnPreferenceClickListener {
                DonationDialog.show(parentFragmentManager)
                true
            }

            findPreference<Preference>("logout")?.setOnPreferenceClickListener {
                viewModel.logout()
                CookieManager.getInstance().removeAllCookies {
                    startActivity(SignInActivity.intentClearTask(requireContext()))
                    activity?.finish()
                }

                true
            }

            configureTextPreference(
                key = Settings.KEY_F1_USERNAME,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            )
            configureTextPreference(
                key = Settings.KEY_F1_PASSWORD,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            configureTextPreference(
                key = Settings.KEY_CUSTOM_RADIO_URL,
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            )

            findPreference<ListPreference>("app_locale")?.setOnPreferenceChangeListener { _, newValue ->
                val tag = newValue as String
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val locales = if (tag == "system") {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList.forLanguageTags(tag)
                    }
                    requireContext().getSystemService(android.app.LocaleManager::class.java)
                        .applicationLocales = locales
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Language change takes effect after restarting the app",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }
        }

        private fun configureTextPreference(key: String, inputType: Int) {
            findPreference<EditTextPreference>(key)?.setOnPreferenceClickListener { preference ->
                showTextPreferenceDialog(preference as EditTextPreference, inputType)
                true
            }
        }

        private fun showTextPreferenceDialog(preference: EditTextPreference, inputType: Int) {
            val isPassword = inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD ==
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            val editText = EditText(requireContext()).apply {
                this.inputType = inputType
                if (!isPassword) {
                    // Pre-fill non-password fields with the current value
                    setText(preference.text.orEmpty())
                    setSelection(text.length)
                }
                // Password fields are left empty — type a new one to change it
            }
            AlertDialog.Builder(requireContext())
                .setTitle(preference.title)
                .setView(editText)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newValue = editText.text.toString()
                    // For password fields, only update if the user typed something new
                    if (newValue.isNotEmpty() || !isPassword) {
                        preference.text = newValue
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create().apply {
                    setOnShowListener {
                        val imm = requireContext().getSystemService(InputMethodManager::class.java)
                        val hideKeyboard = {
                            imm?.hideSoftInputFromWindow(editText.windowToken, 0)
                        }
                        getButton(AlertDialog.BUTTON_POSITIVE)?.onFocusChangeListener =
                            View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) hideKeyboard() }
                        getButton(AlertDialog.BUTTON_NEGATIVE)?.onFocusChangeListener =
                            View.OnFocusChangeListener { _, hasFocus -> if (hasFocus) hideKeyboard() }
                        editText.requestFocus()
                        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                        editText.post {
                            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                    setOnDismissListener {
                        requireContext().getSystemService(InputMethodManager::class.java)
                            ?.hideSoftInputFromWindow(editText.windowToken, 0)
                    }
                }
                .show()
        }

        private fun updateCurrentAccountSummary() {
            val preference = currentAccountPreference ?: return
            lifecycleScope.launch {
                val accountInfo = viewModel.getCurrentAccountInfo()
                preference.summary = when {
                    accountInfo.isLoggedIn && !accountInfo.email.isNullOrBlank() -> accountInfo.email
                    !accountInfo.email.isNullOrBlank() ->
                        getString(R.string.settings_current_account_saved_summary, accountInfo.email)
                    else -> getString(R.string.settings_current_account_none_summary)
                }
            }
        }
    }
}