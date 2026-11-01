package fr.groggy.racecontrol.tv

import android.app.Application
import com.jakewharton.threetenabp.AndroidThreeTen
import dagger.hilt.android.HiltAndroidApp
import fr.groggy.racecontrol.tv.update.UpdateManager
import javax.inject.Inject

@HiltAndroidApp
class RaceControlTvApplication: Application() {
    @Inject internal lateinit var updateManager: UpdateManager

    override fun onCreate() {
        super.onCreate()

        fr.groggy.racecontrol.tv.utils.LanguageOverride.apply(this)
        AndroidThreeTen.init(this)
        updateManager.register(this)
    }
}
