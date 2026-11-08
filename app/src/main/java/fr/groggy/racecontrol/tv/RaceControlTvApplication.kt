package fr.groggy.racecontrol.tv

import android.app.Application
import com.jakewharton.threetenabp.AndroidThreeTen
import dagger.hilt.android.HiltAndroidApp
import fr.groggy.racecontrol.tv.update.UpdateManager
import javax.inject.Inject

@HiltAndroidApp
class RaceControlTvApplication: Application() {
    @Inject internal lateinit var updateManager: UpdateManager

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // Before anything (Hilt, network clients) reads the device identity
        val tag = androidx.preference.PreferenceManager.getDefaultSharedPreferences(base)
            .getString("device_profile", "auto")
        fr.groggy.racecontrol.tv.utils.DeviceInfo.applyProfile(
            when (tag) {
                "current" -> fr.groggy.racecontrol.tv.utils.DeviceInfo.Profile.CURRENT
                "legacy" -> fr.groggy.racecontrol.tv.utils.DeviceInfo.Profile.LEGACY
                else -> fr.groggy.racecontrol.tv.utils.DeviceInfo.Profile.AUTO
            }
        )
    }

    override fun onCreate() {
        super.onCreate()

        fr.groggy.racecontrol.tv.utils.LanguageOverride.apply(this)
        AndroidThreeTen.init(this)
        updateManager.register(this)
    }
}
