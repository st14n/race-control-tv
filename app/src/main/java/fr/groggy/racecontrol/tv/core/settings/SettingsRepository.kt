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
                openWithExternalPlayer = getBoolean(Settings.KEY_OPEN_WITH_EXTERNAL_PLAYER, Settings.DEFAULT.openWithExternalPlayer),
                useExternalAudio = getBoolean(Settings.KEY_USE_EXTERNAL_AUDIO, Settings.DEFAULT.useExternalAudio),
                audioOffsetMs = getString(Settings.KEY_AUDIO_OFFSET_MS, null)?.toLongOrNull()
                    ?: Settings.DEFAULT.audioOffsetMs,
                customRadioDelayMs = getString(Settings.KEY_CUSTOM_RADIO_DELAY_MS, null)?.toLongOrNull()
                    ?: getString(Settings.KEY_OLD_CUSTOM_RADIO_DELAY_MS, null)?.toLongOrNull()
                    ?: Settings.DEFAULT.customRadioDelayMs,
                customRadioUrl = getString(Settings.KEY_CUSTOM_RADIO_URL, null)
                    ?: getString(Settings.KEY_OLD_CUSTOM_RADIO_URL, null)
                    ?: "",
                f1Username = getString(Settings.KEY_F1_USERNAME, "") ?: "",
                f1Password = getString(Settings.KEY_F1_PASSWORD, "") ?: ""
            )
        }
    }
}