package fr.groggy.racecontrol.tv.core.settings

data class Settings(
    val bypassChannelSelection: Boolean,
    val displayThumbnailsEnabled: Boolean,
    val openWithExternalPlayer: Boolean,
    /** Force SDR playback by disabling HDR manifest requests. */
    val disableUhdManifests: Boolean,
    /** Force SDR tone-mapping for HDR video (keeps 4K resolution but tonemaps to SDR). */
    val disableHdrOn4kStreams: Boolean,
    /** Merge the PRES/F1Live audio stream alongside the selected video channel. */
    val useExternalAudio: Boolean,
    /** Manual A/V sync offset in milliseconds (positive = audio ahead of video). */
    val audioOffsetMs: Long,
    /** Custom radio initial sync delay in milliseconds. */
    val customRadioDelayMs: Long,
    /** Optional custom radio stream URL. Empty string = use default candidates. */
    val customRadioUrl: String,
    /** Auto-switch to custom radio when a built-in or saved custom stream is available. */
    val autoSelectCustomRadio: Boolean,
    /** Restrict custom radio selection to sessions that are currently live. */
    val restrictCustomRadioToLiveSessions: Boolean,
    /** F1TV account email (stored locally for auto-fill). */
    val f1Username: String,
    /** F1TV account password (stored locally for auto-fill). */
    val f1Password: String
) {
    enum class StreamType(val rawName: String) {
        HLS("HLS"), DASH("DASH"), DASH_HLS("HLS")
    }

    /** Kept for telemetry emitted by the custom radio player. */
    enum class CustomRadioBackend(val preferenceValue: String) {
        EXOPLAYER("exoplayer")
    }

    companion object {
        val DEFAULT = Settings(
            bypassChannelSelection = false,
            displayThumbnailsEnabled = true,
            openWithExternalPlayer = false,
            disableUhdManifests = false,
            disableHdrOn4kStreams = false,
            useExternalAudio = false,
            audioOffsetMs = 0L,
            customRadioDelayMs = 20_000L,
            customRadioUrl = "",
            autoSelectCustomRadio = true,
            restrictCustomRadioToLiveSessions = false,
            f1Username = "",
            f1Password = ""
        )

        const val KEY_BYPASS_CHANNEL_SELECTION = "bypass_channel_selection"
        const val KEY_DISPLAY_THUMBNAILS_ENABLED = "display_thumbnails_enabled"
        const val KEY_OPEN_WITH_EXTERNAL_PLAYER = "open_with_external_player"
        const val KEY_DISABLE_HDR_PLAYBACK = "disable_hdr_playback"
        const val KEY_FORCE_SDR_TONE_MAPPING = "force_sdr_tone_mapping"
        const val KEY_USE_EXTERNAL_AUDIO = "use_external_audio"
        const val KEY_AUDIO_OFFSET_MS = "audio_offset_ms"
        const val KEY_CUSTOM_RADIO_DELAY_MS = "custom_radio_delay_ms"
        const val KEY_CUSTOM_RADIO_URL = "custom_radio_url"
        const val KEY_AUTO_SELECT_CUSTOM_RADIO = "auto_select_custom_radio"
        const val KEY_RESTRICT_CUSTOM_RADIO_TO_LIVE_SESSIONS = "restrict_custom_radio_to_live_sessions"
        const val KEY_OLD_CUSTOM_RADIO_DELAY_MS = "gp_radio_delay_ms"
        const val KEY_OLD_CUSTOM_RADIO_URL = "gp_radio_custom_url"
        const val KEY_F1_USERNAME = "f1_username"
        const val KEY_F1_PASSWORD = "f1_password"
    }
}