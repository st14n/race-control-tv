package fr.groggy.racecontrol.tv.core.settings

import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(
    private val preferences: SharedPreferences
) {
    private var currentSettings: Settings? = null

    fun getCurrent(): Settings {
        if (currentSettings == null) {
            currentSettings = getFromStorage()
        }

        /* Less then ideal, but safe at least */
        return currentSettings ?: Settings.DEFAULT
    }

    fun resetSettings() {
        preferences.edit {
            clear()
        }

        applySettings()
    }

    fun applySettings() {
        currentSettings = getFromStorage()
    }

    private fun getFromStorage(): Settings {
        return with(preferences) {
            Settings(
                bypassChannelSelection = getBoolean(Settings.KEY_BYPASS_CHANNEL_SELECTION, Settings.DEFAULT.bypassChannelSelection),
                displayThumbnailsEnabled = getBoolean(Settings.KEY_DISPLAY_THUMBNAILS_ENABLED, Settings.DEFAULT.displayThumbnailsEnabled),
                openWithExternalPlayer = getBoolean(Settings.KEY_OPEN_WITH_EXTERNAL_PLAYER, Settings.DEFAULT.openWithExternalPlayer)
            )
        }
    }
}