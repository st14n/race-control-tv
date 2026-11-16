package fr.groggy.racecontrol.tv.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.R

@AndroidEntryPoint
class SettingsActivity: FragmentActivity(R.layout.activity_settings) {
    companion object {
        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only in the TV layout (layout-w720dp)
        findViewById<TextView>(R.id.settings_version)?.text = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
    }
}
