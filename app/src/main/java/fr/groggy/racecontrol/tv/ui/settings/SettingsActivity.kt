package fr.groggy.racecontrol.tv.ui.settings

import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R

@AndroidEntryPoint
class SettingsActivity: FragmentActivity(R.layout.activity_settings) {
    companion object {
        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }
}