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

    /** How far a stream's live edge lags behind real time (learned from streams with a clock). */
    fun getLiveEdgeLatencyMs(): Long = preferences.getLong(KEY_LIVE_EDGE_LATENCY_MS, DEFAULT_LIVE_EDGE_LATENCY_MS)

    fun updateLiveEdgeLatencyMs(valueMs: Long) {
        preferences.edit { putLong(KEY_LIVE_EDGE_LATENCY_MS, valueMs) }
    }

    /** The radio correction the viewer last set by hand; the acoustic sync stays near it. Null: never set. */
    fun getCustomRadioCorrectionAnchorMs(): Long? =
        if (preferences.contains(KEY_CUSTOM_RADIO_CORRECTION_ANCHOR_MS)) {
            preferences.getLong(KEY_CUSTOM_RADIO_CORRECTION_ANCHOR_MS, 0L)
        } else {
            null
        }

    fun updateCustomRadioCorrectionAnchorMs(valueMs: Long) {
        preferences.edit { putLong(KEY_CUSTOM_RADIO_CORRECTION_ANCHOR_MS, valueMs) }
    }

    fun updateCustomRadioAutoSync(enabled: Boolean) {
        preferences.edit {
            putBoolean(Settings.KEY_CUSTOM_RADIO_AUTO_SYNC, enabled)
        }

        applySettings()
    }

    fun updateCustomRadioAutoSyncCorrection(correctionMs: Long) {
        preferences.edit {
            putString(Settings.KEY_CUSTOM_RADIO_AUTO_SYNC_CORRECTION_MS, correctionMs.toString())
        }

        applySettings()
    }

    fun updateAutoUpdateCheck(enabled: Boolean) {
        preferences.edit {
            putBoolean(Settings.KEY_AUTO_UPDATE_CHECK, enabled)
        }

        applySettings()
    }

    private fun getFromStorage(): Settings {
        return with(preferences) {
            Settings(
                bypassChannelSelection = getBoolean(Settings.KEY_BYPASS_CHANNEL_SELECTION, Settings.DEFAULT.bypassChannelSelection),
                displayThumbnailsEnabled = getBoolean(Settings.KEY_DISPLAY_THUMBNAILS_ENABLED, Settings.DEFAULT.displayThumbnailsEnabled),
                openWithExternalPlayer = getBoolean(Settings.KEY_OPEN_WITH_EXTERNAL_PLAYER, Settings.DEFAULT.openWithExternalPlayer),
                disableUhdManifests = getBoolean(Settings.KEY_DISABLE_UHD_MANIFEST, Settings.DEFAULT.disableUhdManifests),
                disableHdrOn4kStreams = getBoolean(Settings.KEY_FORCE_SDR_TONE_MAPPING, Settings.DEFAULT.disableHdrOn4kStreams),
                useExternalAudio = getBoolean(Settings.KEY_USE_EXTERNAL_AUDIO, Settings.DEFAULT.useExternalAudio),
                combineCommentary = getBoolean(Settings.KEY_COMBINE_COMMENTARY, Settings.DEFAULT.combineCommentary),
                audioOffsetMs = getString(Settings.KEY_AUDIO_OFFSET_MS, null)?.toLongOrNull()
                    ?: Settings.DEFAULT.audioOffsetMs,
                customRadioDelayMs = getString(Settings.KEY_CUSTOM_RADIO_DELAY_MS, null)?.toLongOrNull()
                    ?: getString(Settings.KEY_OLD_CUSTOM_RADIO_DELAY_MS, null)?.toLongOrNull()
                    ?: Settings.DEFAULT.customRadioDelayMs,
                customRadioAutoSync = getBoolean(
                    Settings.KEY_CUSTOM_RADIO_AUTO_SYNC,
                    Settings.DEFAULT.customRadioAutoSync
                ),
                customRadioAutoSyncCorrectionMs = getString(Settings.KEY_CUSTOM_RADIO_AUTO_SYNC_CORRECTION_MS, null)
                    ?.toLongOrNull(),
                customRadioAcousticSync = getBoolean(
                    Settings.KEY_CUSTOM_RADIO_ACOUSTIC_SYNC,
                    Settings.DEFAULT.customRadioAcousticSync
                ),
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
                f1Password = getString(Settings.KEY_F1_PASSWORD, "") ?: "",
                autoUpdateCheck = getBoolean(Settings.KEY_AUTO_UPDATE_CHECK, Settings.DEFAULT.autoUpdateCheck),
                defaultFeed = getString(Settings.KEY_DEFAULT_FEED, null) ?: Settings.DEFAULT.defaultFeed
            )
        }
    }
}

private const val KEY_LIVE_EDGE_LATENCY_MS = "live_edge_latency_ms"
private const val KEY_CUSTOM_RADIO_CORRECTION_ANCHOR_MS = "custom_radio_correction_anchor_ms"
private const val DEFAULT_LIVE_EDGE_LATENCY_MS = 10_000L
