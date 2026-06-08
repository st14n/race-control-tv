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

    fun clearAccountCredentials() {
        preferences.edit {
            remove(Settings.KEY_F1_USERNAME)
            remove(Settings.KEY_F1_PASSWORD)
        }

        applySettings()
    }

    fun updateSignInDefaults(
        username: String,
        password: String,
        customRadioUrl: String
    ) {
        preferences.edit {
            putString(Settings.KEY_F1_USERNAME, username)
            putString(Settings.KEY_F1_PASSWORD, password)
            putString(Settings.KEY_CUSTOM_RADIO_URL, customRadioUrl)
        }

        applySettings()
    }

    private fun getFromStorage(): Settings {
        return with(preferences) {
            Settings(
                bypassChannelSelection = getBoolean(Settings.KEY_BYPASS_CHANNEL_SELECTION, Settings.DEFAULT.bypassChannelSelection),
                displayThumbnailsEnabled = getBoolean(Settings.KEY_DISPLAY_THUMBNAILS_ENABLED, Settings.DEFAULT.displayThumbnailsEnabled),
                openWithExternalPlayer = getBoolean(Settings.KEY_OPEN_WITH_EXTERNAL_PLAYER, Settings.DEFAULT.openWithExternalPlayer),
                disableUhdManifests = getBoolean(Settings.KEY_DISABLE_HDR_PLAYBACK, Settings.DEFAULT.disableUhdManifests),
                disableHdrOn4kStreams = getBoolean(Settings.KEY_FORCE_SDR_TONE_MAPPING, Settings.DEFAULT.disableHdrOn4kStreams),
                useExternalAudio = getBoolean(Settings.KEY_USE_EXTERNAL_AUDIO, Settings.DEFAULT.useExternalAudio),
                audioOffsetMs = getString(Settings.KEY_AUDIO_OFFSET_MS, null)?.toLongOrNull()
                    ?: Settings.DEFAULT.audioOffsetMs,
                customRadioDelayMs = getString(Settings.KEY_CUSTOM_RADIO_DELAY_MS, null)?.toLongOrNull()
                    ?: getString(Settings.KEY_OLD_CUSTOM_RADIO_DELAY_MS, null)?.toLongOrNull()
                    ?: Settings.DEFAULT.customRadioDelayMs,
                customRadioUrl = getString(Settings.KEY_CUSTOM_RADIO_URL, null)
                    ?: getString(Settings.KEY_OLD_CUSTOM_RADIO_URL, null)
                    ?: "",
                autoSelectCustomRadio = getBoolean(
                    Settings.KEY_AUTO_SELECT_CUSTOM_RADIO,
                    Settings.DEFAULT.autoSelectCustomRadio
                ),
                restrictCustomRadioToLiveSessions = getBoolean(
                    Settings.KEY_RESTRICT_CUSTOM_RADIO_TO_LIVE_SESSIONS,
                    Settings.DEFAULT.restrictCustomRadioToLiveSessions
                ),
                f1Username = getString(Settings.KEY_F1_USERNAME, "") ?: "",
                f1Password = getString(Settings.KEY_F1_PASSWORD, "") ?: ""
            )
        }
    }
}