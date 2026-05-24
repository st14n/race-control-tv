package fr.groggy.racecontrol.tv.ui.settings

import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.webkit.CookieManager
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
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
            arguments = bundleOf(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT to pref.key)
        }
        startPreferenceFragment(fragment)

        return true
    }

    override fun onPreferenceStartInitialScreen() {
        startPreferenceFragment(PreferenceFragment())
    }

    @Keep
    @AndroidEntryPoint
    class PreferenceFragment: LeanbackPreferenceFragmentCompat() {
        private val viewModel: SettingsViewModel by viewModels({ requireParentFragment() })

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

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
    }
}