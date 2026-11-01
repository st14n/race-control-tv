package fr.groggy.racecontrol.tv.utils

import android.app.Activity
import android.app.Application
import android.content.res.Resources
import android.os.Bundle
import androidx.preference.PreferenceManager
import fr.groggy.racecontrol.tv.BuildConfig
import java.util.Locale

/**
 * App language independent of the TV's system language: the "app_language" setting
 * (auto / nl / en), or the build's default until the user picks one. Applies from the next start.
 */
object LanguageOverride {
    const val KEY = "app_language"

    fun apply(app: Application) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val tag = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_LANGUAGE
        // "auto" in the setting means the system language, whatever the build default is
        if (tag == "auto") return
        val locale = Locale(tag)
        Locale.setDefault(locale)
        update(app.resources, locale)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                update(activity.resources, locale)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = update(activity.resources, locale)
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    @Suppress("DEPRECATION")
    private fun update(resources: Resources, locale: Locale) {
        val configuration = resources.configuration
        configuration.setLocale(locale)
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}
