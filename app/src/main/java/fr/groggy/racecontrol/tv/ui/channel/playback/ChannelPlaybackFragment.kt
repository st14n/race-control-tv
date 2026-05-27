package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.Keep
import androidx.fragment.app.DialogFragment
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.ui.player.CustomRadioSyncDialog
import fr.groggy.racecontrol.tv.ui.player.ExoPlayerPlaybackTransportControlGlue
import javax.inject.Inject
import java.util.Locale

@Keep
@AndroidEntryPoint
class ChannelPlaybackFragment : VideoSupportFragment(), Player.Listener {

    companion object {
        internal val TAG = ChannelPlaybackFragment::class.simpleName

        private const val CUSTOM_RADIO_DEFAULT_OFFSET_MS = 20_000L
        private const val CUSTOM_RADIO_MAX_OFFSET_MS = 30_000L
        private const val CUSTOM_RADIO_EARLY_END_THRESHOLD_MS = 12_000L
        private const val CUSTOM_RADIO_RETRY_DELAY_MS = 500L
        private const val OVERLAY_AUTO_CLOSE_DELAY_MS = 10_000L
        private const val CUSTOM_RADIO_SYNC_DIALOG_TAG = "custom_radio_sync"
        private const val CUSTOM_RADIO_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Google TV Streamer Build/UTT3.240625.001.K5; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36"

        private const val ARG_VIEWING = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_VIEWING"
        private const val ARG_SESSION_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SESSION_ID"
        private const val ARG_CONTENT_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CONTENT_ID"
        private const val ARG_CHANNEL_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CHANNEL_ID"

        fun putSessionId(intent: Intent, sessionId: String) = intent.putExtra(ARG_SESSION_ID, sessionId)
        fun putChannelId(intent: Intent, channelId: String?) = intent.putExtra(ARG_CHANNEL_ID, channelId)
        fun putContentId(intent: Intent, contentId: String) = intent.putExtra(ARG_CONTENT_ID, contentId)

        fun findChannelId(activity: Activity): String? = activity.intent.getStringExtra(ARG_CHANNEL_ID)
        fun findContentId(activity: Activity): String? = activity.intent.getStringExtra(ARG_CONTENT_ID)
        fun findSessionId(activity: Activity): String? = activity.intent.getStringExtra(ARG_SESSION_ID)

        fun findViewing(fragment: ChannelPlaybackFragment): F1TvViewing? {
            val args = fragment.arguments ?: return null
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getParcelable(ARG_VIEWING, F1TvViewing::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getParcelable(ARG_VIEWING)
            }
        }

        fun newInstance(viewing: F1TvViewing) = ChannelPlaybackFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_VIEWING, viewing)
            }
        }
    }

    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory
    @Inject internal lateinit var settingsRepository: SettingsRepository

    private var customRadioPlayer: CustomRadioEngine? = null
    private var customRadioInjected: Boolean = false
    private var customRadioMuted: Boolean = false
    private var customRadioOffsetMs: Long = CUSTOM_RADIO_DEFAULT_OFFSET_MS
    private var hasAutoInjectedCustomRadio = false
    private var lastMainPlayerSeekAtElapsedMs: Long = 0L
    private var customRadioPlan: List<CustomRadioPlanEntry> = emptyList()
    private var customRadioPlanIndex: Int = 0
    private var customRadioAttemptId: Long = 0L
    private var customRadioStartedAtElapsedMs: Long = 0L
    private var playbackGlue: ExoPlayerPlaybackTransportControlGlue? = null
    private var customRadioDelayNudgeRunnable: Runnable? = null
    private var suppressOverlayReopenUntilElapsedMs: Long = 0L
    private val overlayAutoCloseHandler = Handler(Looper.getMainLooper())
    private val overlayAutoCloseRunnable = Runnable {
        if (!isAdded) return@Runnable
        dismissCustomRadioSyncDialogIfShown()
        if (isControlsOverlayVisible()) {
            hideControlsOverlay(true)
        }
    }

    private val trackSelector: DefaultTrackSelector by lazy {
        DefaultTrackSelector(requireContext()).apply {
            // Remove the default viewport/display-size cap so 4K tracks are eligible
            // for auto-selection on a 4K display without extra configuration.
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
            )
        }
    }

    private val player: ExoPlayer by lazy {
        Log.d(TAG, "Initializing ExoPlayer (Media3)")
        ExoPlayer.Builder(requireContext())
            .setTrackSelector(trackSelector)
            .build().also { p ->
                p.playWhenReady = true
                p.addAnalyticsListener(EventLogger())
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onRenderedFirstFrame(
                        eventTime: AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        Log.i(TAG, "onRenderedFirstFrame: output=$output renderTimeMs=${renderTimeMs}ms")
                    }
                    override fun onPlayerError(
                        eventTime: AnalyticsListener.EventTime,
                        error: PlaybackException
                    ) {
                        Log.e(TAG, "Player error: ${error.errorCodeName} (${error.errorCode})", error)
                    }
                })
                p.addListener(this)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        setOnKeyInterceptListener { _, _, event ->
            if (shouldConsumeMenuDismissKey(event)) {
                return@setOnKeyInterceptListener true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                view?.post { resetOverlayAutoCloseTimer() }
            }
            false
        }
        startPlayer()
    }

    private fun startPlayer() {
        Log.d(TAG, "startPlayer")
        try {
            val glue = ExoPlayerPlaybackTransportControlGlue(
                requireActivity(), player, trackSelector,
                onCustomRadioRequested = ::injectCustomRadio,
                onCustomRadioSyncRequested = ::showCustomRadioSyncDialog,
                onAudioTrackSelected = ::stopCustomRadio,
                isCustomRadioActive = { customRadioInjected },
                currentAudioLabelOverride = {
                    if (customRadioInjected) customRadioOverlayLabel() else null
                },
                onCustomRadioOffsetAdjust = { deltaMs ->
                    setCustomRadioOffset(customRadioOffsetMs + deltaMs)
                },
                onControlsInteraction = ::resetOverlayAutoCloseTimer,
                onPlayerMenuDismissed = ::hidePlayerMenusAndControls
            )
            playbackGlue = glue
            glue.host = VideoSupportFragmentGlueHost(this)
            val viewing = findViewing(this) ?: run {
                Log.e(TAG, "Viewing is null — cannot start playback")
                (activity as? ChannelPlaybackActivity)?.playerError()
                return
            }
            preparePlayer(viewing)
        } catch (e: Exception) {
            Log.e(TAG, "Error in startPlayer", e)
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
    }

    private fun preparePlayer(viewing: F1TvViewing) {
        Log.i(TAG, "preparePlayer: url=${viewing.url} streamType=${viewing.streamType}")
        try {
            val settings = settingsRepository.getCurrent()
            val mainItem = MediaSourceItemFactory.newMediaItem(viewing)
            val mainSource = createMediaSource(viewing.url.toString(), viewing.streamType, mainItem)

            val useExternalAudio = settings.useExternalAudio && viewing.externalAudioUri != null
            val mediaSource = if (useExternalAudio) {
                Log.i(TAG, "External audio enabled — building MergingMediaSource")
                val audioItem = MediaSourceItemFactory.newExternalAudioMediaItem(viewing)
                val audioSource = createMediaSource(
                    viewing.externalAudioUri.toString(),
                    viewing.externalAudioStreamType,
                    audioItem
                )
                // adjustPeriodTimeOffsets=false, clipDurations=false keeps both streams in sync
                // The audioOffsetMs preference is applied via ClippingMediaSource if non-zero
                val offsetMs = settings.audioOffsetMs
                val finalAudioSource = if (offsetMs != 0L) {
                    Log.i(TAG, "Applying audioOffsetMs=$offsetMs to audio source")
                    ClippingMediaSource.Builder(audioSource)
                        .setStartPositionUs(if (offsetMs > 0) offsetMs * 1000L else 0L)
                        .setEndPositionUs(C.TIME_END_OF_SOURCE)
                        .setEnableInitialDiscontinuity(false)
                        .setAllowDynamicClippingUpdates(true)
                        .setRelativeToDefaultPosition(false)
                        .build()
                } else audioSource
                MergingMediaSource(false, false, mainSource, finalAudioSource)
            } else {
                mainSource
            }

            player.setMediaSource(mediaSource)
            player.prepare()
            Log.d(TAG, "Player prepared")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing player", e)
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
    }

    /**
     * Choose DASH or HLS factory based on stream type and URL extension.
     * Defaults to HLS for unrecognised types (safer for Live streams).
     */
    private fun createMediaSource(
        urlString: String,
        streamType: String?,
        mediaItem: androidx.media3.common.MediaItem
    ): androidx.media3.exoplayer.source.MediaSource {
        val isDash = streamType?.contains("DASH", ignoreCase = true) == true
            || urlString.endsWith(".mpd", ignoreCase = true)
        return if (isDash) {
            Log.i(TAG, "Using DashMediaSource for $urlString")
            DashMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        } else {
            Log.i(TAG, "Using HlsMediaSource for $urlString")
            HlsMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    // ── Player.Listener ──────────────────────────────────────────────────────

    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
        (activity as? ChannelPlaybackActivity)?.playerError()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val state = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($playbackState)"
        }
        Log.d(TAG, "Playback state → $state")
        if (playbackState == Player.STATE_READY && !hasAutoInjectedCustomRadio) {
            hasAutoInjectedCustomRadio = true
            val settings = settingsRepository.getCurrent()
            if (settings.autoSelectCustomRadio && buildCustomRadioPlan(settings).isNotEmpty()) {
                injectCustomRadio()
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.d(TAG, "isPlaying → $isPlaying")
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            lastMainPlayerSeekAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "Main player seek detected — suppressing next GP Radio sync delay")
        }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    override fun showControlsOverlay(runAnimation: Boolean) {
        // Block Leanback's automatic re-show that fires when a dialog dismisses
        // and focus returns to the transport row — for the duration of the suppress window.
        if (SystemClock.elapsedRealtime() < suppressOverlayReopenUntilElapsedMs) return
        super.showControlsOverlay(runAnimation)
        resetOverlayAutoCloseTimer()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        cancelOverlayAutoCloseTimer()
        if (player.isPlaying) {
            player.pause()
            Log.d(TAG, "onPause: paused")
        }
        customRadioPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        val radio = customRadioPlayer ?: return
        if (customRadioMuted || customRadioInjected) {
            // VLC keeps its audioDelay across pause/resume — just resume playback
            radio.play()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: releasing player")
        cancelOverlayAutoCloseTimer()
        playbackGlue = null
        releaseCustomRadioPlayer()
        player.removeListener(this)
        player.release()
        super.onDestroy()
    }

    // ── Grand Prix Radio ──────────────────────────────────────────────────────

    internal fun injectCustomRadio() {
        if (customRadioInjected) return
        // If the radio is already streaming but muted, just unmute — no 20s wait needed
        if (customRadioMuted && customRadioPlayer != null) {
            customRadioMuted = false
            customRadioInjected = true
            customRadioPlayer!!.setVolume(100)
            playbackGlue?.refreshSubtitle()
            player.setTrackSelectionParameters(
                player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(null)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            )
            logCustomRadioTelemetry("unmuted", detail = "Unmuted existing stream, no delay")
            return
        }
        // VLC was muted but died while muted — reset muted flag before fresh start
        customRadioMuted = false
        val settings = settingsRepository.getCurrent()
        val radioPlan = buildCustomRadioPlan(settings)
        if (radioPlan.isEmpty()) {
            logCustomRadioTelemetry("plan_empty", detail = "No custom radio backend available")
            return
        }
        customRadioOffsetMs = settings.customRadioDelayMs
        customRadioPlan = radioPlan
        customRadioPlanIndex = 0
        customRadioInjected = true
        playbackGlue?.refreshSubtitle()
        // Mute embedded audio on the main player — GP Radio takes over
        player.setTrackSelectionParameters(
            player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        )
        showCustomRadioWaitingMessage(customRadioOffsetMs)
        startCustomRadioPlayer()
    }

    internal fun stopCustomRadio() {
        if (!customRadioInjected) return
        // If VLC has connected and started, mute it and keep it alive so switching back is instant.
        // If it hasn't started yet (still connecting), do a full teardown.
        val radioStarted = customRadioPlayer != null && customRadioStartedAtElapsedMs > 0L
        if (radioStarted) {
            customRadioPlayer!!.setVolume(0)
            customRadioInjected = false
            customRadioMuted = true
            logCustomRadioTelemetry("muted", detail = "Kept stream alive but muted")
        } else {
            releaseCustomRadioPlayer()
            customRadioInjected = false
            customRadioMuted = false
            customRadioOffsetMs = settingsRepository.getCurrent().customRadioDelayMs
            customRadioPlan = emptyList()
            customRadioPlanIndex = 0
            customRadioStartedAtElapsedMs = 0L
        }
        playbackGlue?.refreshSubtitle()
        player.setTrackSelectionParameters(
            player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        )
    }

    internal fun showCustomRadioSyncDialog() {
        view?.post {
            CustomRadioSyncDialog(
                currentOffsetMs = customRadioOffsetMs,
                onOffsetSelected = { offsetMs -> setCustomRadioOffset(offsetMs) },
                onUserInteraction = ::resetOverlayAutoCloseTimer,
                onDialogDismissed = ::hidePlayerMenusAndControls
            ).show(childFragmentManager, CUSTOM_RADIO_SYNC_DIALOG_TAG)
        }
        resetOverlayAutoCloseTimer()
    }

    private fun hidePlayerMenusAndControls() {
        suppressOverlayReopenUntilElapsedMs = SystemClock.elapsedRealtime() + 350L
        view?.post { hideControlsOverlay(true) }
        view?.postDelayed({ hideControlsOverlay(false) }, 120L)
    }

    private fun shouldConsumeMenuDismissKey(event: KeyEvent): Boolean {
        val suppressUntil = suppressOverlayReopenUntilElapsedMs
        if (suppressUntil == 0L) return false
        if (SystemClock.elapsedRealtime() > suppressUntil) {
            suppressOverlayReopenUntilElapsedMs = 0L
            return false
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> true
            else -> false
        }
    }

    private fun startCustomRadioPlayer() {
        val planEntry = customRadioPlan.getOrNull(customRadioPlanIndex)
        if (planEntry == null) {
            logCustomRadioTelemetry("plan_exhausted", detail = "No custom radio plan entry at index $customRadioPlanIndex")
            stopCustomRadio()
            return
        }

        try {
            releaseCustomRadioPlayer()
            val attemptId = ++customRadioAttemptId
            customRadioStartedAtElapsedMs = 0L
            logCustomRadioTelemetry(
                event = "start_requested",
                planEntry = planEntry,
                detail = "planIndex=$customRadioPlanIndex attemptId=$attemptId"
            )

            customRadioPlayer = createCustomRadioEngine(
                context = requireContext(),
                planEntry = planEntry,
                userAgent = CUSTOM_RADIO_USER_AGENT,
                initialAudioDelayMs = customRadioOffsetMs,
                initialVolume = 100,
                onStarted = {
                    if (!isCurrentCustomRadioAttempt(attemptId)) return@createCustomRadioEngine
                    customRadioStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                    logCustomRadioTelemetry(
                        event = "started",
                        planEntry = planEntry,
                        detail = "attemptId=$attemptId offsetMs=$customRadioOffsetMs"
                    )
                    applyCustomRadioOffset()
                },
                onEnded = {
                    if (!isCurrentCustomRadioAttempt(attemptId)) return@createCustomRadioEngine
                    handleCustomRadioAttemptEnded(planEntry, attemptId, "ended")
                },
                onError = { errorCode, throwable ->
                    if (!isCurrentCustomRadioAttempt(attemptId)) return@createCustomRadioEngine
                    handleCustomRadioAttemptEnded(
                        planEntry = planEntry,
                        attemptId = attemptId,
                        outcome = "error",
                        detail = errorCode,
                        throwable = throwable
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GP Radio player", e)
            handleCustomRadioAttemptEnded(
                planEntry = planEntry,
                attemptId = customRadioAttemptId,
                outcome = "exception",
                detail = e::class.simpleName,
                throwable = e
            )
        }
    }

    private fun handleCustomRadioAttemptEnded(
        planEntry: CustomRadioPlanEntry,
        attemptId: Long,
        outcome: String,
        detail: String? = null,
        throwable: Throwable? = null
    ) {
        val elapsedMs = if (customRadioStartedAtElapsedMs > 0L) {
            android.os.SystemClock.elapsedRealtime() - customRadioStartedAtElapsedMs
        } else {
            0L
        }
        logCustomRadioTelemetry(
            event = outcome,
            planEntry = planEntry,
            detail = buildString {
                append("elapsedMs=")
                append(elapsedMs)
                append(" attemptId=")
                append(attemptId)
                detail?.let {
                    append(" detail=")
                    append(it)
                }
            },
            throwable = throwable
        )

        val failedBeforePlaybackStarted = customRadioStartedAtElapsedMs == 0L
        val shouldAdvance = failedBeforePlaybackStarted || elapsedMs in 1 until CUSTOM_RADIO_EARLY_END_THRESHOLD_MS
        if (shouldAdvance && customRadioPlanIndex < customRadioPlan.lastIndex) {
            customRadioPlanIndex += 1
            logCustomRadioTelemetry(
                event = "backend_advanced",
                planEntry = customRadioPlan[customRadioPlanIndex],
                detail = "previousBackend=${planEntry.backend.preferenceValue} elapsedMs=$elapsedMs started=${!failedBeforePlaybackStarted}"
            )
        }

        releaseCustomRadioPlayer()
        if (customRadioInjected && isCurrentCustomRadioAttempt(attemptId)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (customRadioInjected) {
                    startCustomRadioPlayer()
                }
            }, CUSTOM_RADIO_RETRY_DELAY_MS)
        }
    }

    private fun isCurrentCustomRadioAttempt(attemptId: Long): Boolean {
        return customRadioInjected && customRadioAttemptId == attemptId
    }

    private fun releaseCustomRadioPlayer() {
        cancelCustomRadioDelayNudge(resume = false)
        customRadioPlayer?.release()
        customRadioPlayer = null
    }

    private fun buildCustomRadioPlan(settings: fr.groggy.racecontrol.tv.core.settings.Settings): List<CustomRadioPlanEntry> {
        val customUrl = settings.customRadioUrl.trim()
        if (customUrl.isNotBlank()) {
            if (!isSupportedCustomRadioUrl(customUrl)) {
                Log.w(TAG, "Ignoring invalid custom radio URL: $customUrl")
                return emptyList()
            }
            return listOf(
                CustomRadioPlanEntry(
                    backend = fr.groggy.racecontrol.tv.core.settings.Settings.CustomRadioBackend.EXOPLAYER,
                    source = CustomRadioSource(
                        name = "custom",
                        url = customUrl,
                        normalizeWithInAppHls = true
                    )
                )
            )
        }

        return CustomRadioSources.defaultCandidate?.let {
            listOf(
                CustomRadioPlanEntry(
                    fr.groggy.racecontrol.tv.core.settings.Settings.CustomRadioBackend.EXOPLAYER,
                    it
                )
            )
        } ?: emptyList()
    }

    private fun isSupportedCustomRadioUrl(url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private fun logCustomRadioTelemetry(
        event: String,
        planEntry: CustomRadioPlanEntry? = null,
        detail: String? = null,
        throwable: Throwable? = null
    ) {
        val message = buildString {
            append("GP Radio telemetry event=")
            append(event)
            planEntry?.let {
                append(" backend=")
                append(it.backend.preferenceValue)
                append(" source=")
                append(it.source.name)
                append(" url=")
                append(it.source.url)
            }
            detail?.let {
                append(' ')
                append(it)
            }
        }
        if (throwable == null) {
            Log.i(TAG, message)
        } else {
            Log.e(TAG, message, throwable)
        }
    }

    private fun setCustomRadioOffset(offsetMs: Long): Long {
        val clampedOffsetMs = offsetMs.coerceIn(0L, CUSTOM_RADIO_MAX_OFFSET_MS)
        if (!customRadioInjected) {
            customRadioOffsetMs = clampedOffsetMs
            return customRadioOffsetMs
        }
        val deltaMs = clampedOffsetMs - customRadioOffsetMs
        if (deltaMs == 0L) return customRadioOffsetMs
        cancelCustomRadioDelayNudge(resume = false)
        customRadioOffsetMs = clampedOffsetMs
        customRadioPlayer?.setAudioDelay(customRadioOffsetMs)
        playbackGlue?.refreshSubtitle()
        logCustomRadioTelemetry("offset_updated", detail = "offsetMs=$customRadioOffsetMs deltaMs=$deltaMs")
        return customRadioOffsetMs
    }

    private fun applyCustomRadioOffset() {
        // audioDelay-based approach: VLC buffers the stream and outputs it [offsetMs] late.
        // If the main player just seeked, the radio is still running — no re-sync needed.
        val seekedRecentlyMs = android.os.SystemClock.elapsedRealtime() - lastMainPlayerSeekAtElapsedMs
        val effectiveOffsetMs = if (lastMainPlayerSeekAtElapsedMs > 0L && seekedRecentlyMs < 5_000L) {
            Log.d(TAG, "Seek was ${seekedRecentlyMs}ms ago — using 0ms audio delay")
            0L
        } else {
            customRadioOffsetMs
        }
        customRadioPlayer?.setAudioDelay(effectiveOffsetMs)
        logCustomRadioTelemetry("offset_applied", detail = "offsetMs=$effectiveOffsetMs")
    }

    private fun restartCustomRadio() {
        releaseCustomRadioPlayer()
        startCustomRadioPlayer()
    }

    private fun applyCustomRadioOffsetDelta(deltaMs: Long, targetOffsetMs: Long): Boolean {
        val secondary = customRadioPlayer ?: return false
        if (deltaMs == 0L) return true

        if (deltaMs < 0L) {
            if (secondary.skipAhead(-deltaMs)) {
                logCustomRadioTelemetry("offset_adjusted_skip", detail = "deltaMs=$deltaMs newOffsetMs=$targetOffsetMs")
                return true
            }
            logCustomRadioTelemetry("offset_adjusted_skip_unavailable", detail = "deltaMs=$deltaMs currentOffsetMs=$customRadioOffsetMs")
            return false
        }

        scheduleCustomRadioDelayNudge(secondary, deltaMs, targetOffsetMs)
        return true
    }

    private fun scheduleCustomRadioDelayNudge(engine: CustomRadioEngine, delayMs: Long, targetOffsetMs: Long) {
        cancelCustomRadioDelayNudge(resume = true)
        engine.pause()
        val resumeRunnable = Runnable {
            customRadioDelayNudgeRunnable = null
            if (customRadioInjected && engine === customRadioPlayer) {
                engine.play()
                logCustomRadioTelemetry(
                    "offset_adjusted_pause",
                    detail = "delayMs=$delayMs newOffsetMs=$targetOffsetMs"
                )
            }
        }
        customRadioDelayNudgeRunnable = resumeRunnable
        overlayAutoCloseHandler.postDelayed(resumeRunnable, delayMs)
    }

    private fun cancelCustomRadioDelayNudge(resume: Boolean) {
        val pending = customRadioDelayNudgeRunnable ?: return
        overlayAutoCloseHandler.removeCallbacks(pending)
        customRadioDelayNudgeRunnable = null
        if (resume) {
            customRadioPlayer?.play()
        }
    }

    private fun showCustomRadioWaitingMessage(delayMs: Long) {
        if (delayMs <= 0L) return
        Toast.makeText(
            requireContext(),
            getString(R.string.custom_radio_waiting_message, formatCustomRadioOffset(delayMs)),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun resetOverlayAutoCloseTimer() {
        cancelOverlayAutoCloseTimer()
        overlayAutoCloseHandler.postDelayed(overlayAutoCloseRunnable, OVERLAY_AUTO_CLOSE_DELAY_MS)
    }

    private fun cancelOverlayAutoCloseTimer() {
        overlayAutoCloseHandler.removeCallbacks(overlayAutoCloseRunnable)
    }

    private fun dismissCustomRadioSyncDialogIfShown() {
        (childFragmentManager.findFragmentByTag(CUSTOM_RADIO_SYNC_DIALOG_TAG) as? DialogFragment)
            ?.dismissAllowingStateLoss()
    }

    private fun formatCustomRadioOffset(delayMs: Long): String {
        return String.format(Locale.getDefault(), "%.1f", delayMs / 1000.0)
    }

    private fun customRadioOverlayLabel(): String {
        return getString(
            R.string.custom_radio_overlay_label,
            getString(R.string.custom_radio_audio_label),
            formatCustomRadioOffset(customRadioOffsetMs)
        )
    }
}
