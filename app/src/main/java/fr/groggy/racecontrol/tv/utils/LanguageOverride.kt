package fr.groggy.racecontrol.tv.utils

import android.app.Application
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.preference.PreferenceManager
import fr.groggy.racecontrol.tv.BuildConfig

/**
 * App language independent of the TV's system language: the "app_language" setting
 * (auto / nl / en), or the build's default until the user picks one. Uses the system's per-app
 * language (Android 13+), which applies it to every screen without touching resources; older
 * Android versions keep following the system language.
 */
object LanguageOverride {
    const val KEY = "app_language"

    fun apply(app: Application) {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val tag = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_LANGUAGE
        val manager = app.getSystemService(LocaleManager::class.java) ?: return
        val wanted = if (tag == "auto") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        // Only when it differs: setting it restarts the app's activities
        if (manager.applicationLocales.toLanguageTags() != wanted.toLanguageTags()) {
            manager.applicationLocales = wanted
        }
    }
}
