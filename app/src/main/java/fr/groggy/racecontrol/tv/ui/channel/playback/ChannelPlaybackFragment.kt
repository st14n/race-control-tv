package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.os.bundleOf
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

        private const val GP_RADIO_DEFAULT_OFFSET_MS = 20_000L
        private const val GP_RADIO_MAX_OFFSET_MS = 30_000L
        private const val GP_RADIO_EARLY_END_THRESHOLD_MS = 12_000L
        private const val GP_RADIO_RETRY_DELAY_MS = 500L
        private const val OVERLAY_AUTO_CLOSE_DELAY_MS = 10_000L
        private const val GP_RADIO_SYNC_DIALOG_TAG = "gp_radio_sync"
        private const val GP_RADIO_USER_AGENT =
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
            arguments = bundleOf(ARG_VIEWING to viewing)
        }
    }

    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory
    @Inject internal lateinit var settingsRepository: SettingsRepository

    private var gpRadioPlayer: GpRadioEngine? = null
    private var gpRadioInjected: Boolean = false
    private var gpRadioMuted: Boolean = false
    private var gpRadioOffsetMs: Long = GP_RADIO_DEFAULT_OFFSET_MS
    private var hasAutoInjectedGpRadio = false
    private var lastMainPlayerSeekAtElapsedMs: Long = 0L
    private var gpRadioPlan: List<GpRadioPlanEntry> = emptyList()
    private var gpRadioPlanIndex: Int = 0
    private var gpRadioAttemptId: Long = 0L
    private var gpRadioStartedAtElapsedMs: Long = 0L
    private var playbackGlue: ExoPlayerPlaybackTransportControlGlue? = null
    private var gpRadioDelayNudgeRunnable: Runnable? = null
    private val overlayAutoCloseHandler = Handler(Looper.getMainLooper())
    private val overlayAutoCloseRunnable = Runnable {
        if (!isAdded) return@Runnable
        dismissGpRadioSyncDialogIfShown()
        if (isControlsOverlayVisible()) {
            hideControlsOverlay(true)
        }
    }

    private val trackSelector: DefaultTrackSelector by lazy {
        DefaultTrackSelector(requireContext())
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
                onGrandPrixRadioRequested = ::injectGrandPrixRadio,
                onCustomRadioSyncRequested = ::showGpRadioSyncDialog,
                onAudioTrackSelected = ::stopGpRadio,
                isCustomRadioActive = { gpRadioInjected },
                currentAudioLabelOverride = {
                    if (gpRadioInjected) getString(R.string.custom_radio_audio_label) else null
                }
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
                    androidx.media3.exoplayer.source.ClippingMediaSource(
                        audioSource,
                        /* startPositionUs = */ if (offsetMs > 0) offsetMs * 1000L else 0L,
                        /* endPositionUs = */ androidx.media3.common.C.TIME_END_OF_SOURCE,
                        /* enableInitialDiscontinuity = */ false,
                        /* allowDynamicClippingUpdates = */ true,
                        /* relativeToDefaultPosition = */ false
                    )
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
        if (playbackState == Player.STATE_READY && !hasAutoInjectedGpRadio) {
            hasAutoInjectedGpRadio = true
            injectGrandPrixRadio()
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        cancelOverlayAutoCloseTimer()
        if (player.isPlaying) {
            player.pause()
            Log.d(TAG, "onPause: paused")
        }
        gpRadioPlayer?.pause()
    }

    override fun onResume() {
        super.onResume()
        val radio = gpRadioPlayer ?: return
        if (gpRadioMuted || gpRadioInjected) {
            // VLC keeps its audioDelay across pause/resume — just resume playback
            radio.play()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: releasing player")
        cancelOverlayAutoCloseTimer()
        playbackGlue = null
        releaseGpRadioPlayer()
        player.removeListener(this)
        player.release()
        super.onDestroy()
    }

    // ── Grand Prix Radio ──────────────────────────────────────────────────────

    internal fun injectGrandPrixRadio() {
        if (gpRadioInjected) return
        // If the radio is already streaming but muted, just unmute — no 20s wait needed
        if (gpRadioMuted && gpRadioPlayer != null) {
            gpRadioMuted = false
            gpRadioInjected = true
            gpRadioPlayer!!.setVolume(100)
            playbackGlue?.refreshSubtitle()
            player.setTrackSelectionParameters(
                player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(null)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            )
            logGpRadioTelemetry("unmuted", detail = "Unmuted existing stream, no delay")
            return
        }
        // VLC was muted but died while muted — reset muted flag before fresh start
        gpRadioMuted = false
        val settings = settingsRepository.getCurrent()
        val radioPlan = buildGpRadioPlan(settings)
        if (radioPlan.isEmpty()) {
            logGpRadioTelemetry("plan_empty", detail = "No GP Radio backend available")
            return
        }
        gpRadioOffsetMs = settings.customRadioDelayMs
        gpRadioPlan = radioPlan
        gpRadioPlanIndex = 0
        gpRadioInjected = true
        playbackGlue?.refreshSubtitle()
        // Mute embedded audio on the main player — GP Radio takes over
        player.setTrackSelectionParameters(
            player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        )
        showGpRadioWaitingMessage(gpRadioOffsetMs)
        startGpRadioPlayer()
    }

    internal fun stopGpRadio() {
        if (!gpRadioInjected) return
        // If VLC has connected and started, mute it and keep it alive so switching back is instant.
        // If it hasn't started yet (still connecting), do a full teardown.
        val radioStarted = gpRadioPlayer != null && gpRadioStartedAtElapsedMs > 0L
        if (radioStarted) {
            gpRadioPlayer!!.setVolume(0)
            gpRadioInjected = false
            gpRadioMuted = true
            logGpRadioTelemetry("muted", detail = "Kept stream alive but muted")
        } else {
            releaseGpRadioPlayer()
            gpRadioInjected = false
            gpRadioMuted = false
            gpRadioOffsetMs = settingsRepository.getCurrent().customRadioDelayMs
            gpRadioPlan = emptyList()
            gpRadioPlanIndex = 0
            gpRadioStartedAtElapsedMs = 0L
        }
        playbackGlue?.refreshSubtitle()
        player.setTrackSelectionParameters(
            player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(null)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        )
    }

    internal fun showGpRadioSyncDialog() {
        CustomRadioSyncDialog(
            currentOffsetMs = gpRadioOffsetMs,
            onOffsetSelected = { offsetMs -> setGpRadioOffset(offsetMs) },
            onUserInteraction = ::resetOverlayAutoCloseTimer
        ).show(childFragmentManager, GP_RADIO_SYNC_DIALOG_TAG)
        resetOverlayAutoCloseTimer()
    }

    private fun startGpRadioPlayer() {
        val planEntry = gpRadioPlan.getOrNull(gpRadioPlanIndex)
        if (planEntry == null) {
            logGpRadioTelemetry("plan_exhausted", detail = "No GP Radio plan entry at index $gpRadioPlanIndex")
            stopGpRadio()
            return
        }

        try {
            releaseGpRadioPlayer()
            val attemptId = ++gpRadioAttemptId
            gpRadioStartedAtElapsedMs = 0L
            logGpRadioTelemetry(
                event = "start_requested",
                planEntry = planEntry,
                detail = "planIndex=$gpRadioPlanIndex attemptId=$attemptId"
            )

            gpRadioPlayer = createGpRadioEngine(
                context = requireContext(),
                planEntry = planEntry,
                userAgent = GP_RADIO_USER_AGENT,
                initialAudioDelayMs = gpRadioOffsetMs,
                initialVolume = 100,
                onStarted = {
                    if (!isCurrentGpRadioAttempt(attemptId)) return@createGpRadioEngine
                    gpRadioStartedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                    logGpRadioTelemetry(
                        event = "started",
                        planEntry = planEntry,
                        detail = "attemptId=$attemptId offsetMs=$gpRadioOffsetMs"
                    )
                    applyGpRadioOffset()
                },
                onEnded = {
                    if (!isCurrentGpRadioAttempt(attemptId)) return@createGpRadioEngine
                    handleGpRadioAttemptEnded(planEntry, attemptId, "ended")
                },
                onError = { errorCode, throwable ->
                    if (!isCurrentGpRadioAttempt(attemptId)) return@createGpRadioEngine
                    handleGpRadioAttemptEnded(
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
            handleGpRadioAttemptEnded(
                planEntry = planEntry,
                attemptId = gpRadioAttemptId,
                outcome = "exception",
                detail = e::class.simpleName,
                throwable = e
            )
        }
    }

    private fun handleGpRadioAttemptEnded(
        planEntry: GpRadioPlanEntry,
        attemptId: Long,
        outcome: String,
        detail: String? = null,
        throwable: Throwable? = null
    ) {
        val elapsedMs = if (gpRadioStartedAtElapsedMs > 0L) {
            android.os.SystemClock.elapsedRealtime() - gpRadioStartedAtElapsedMs
        } else {
            0L
        }
        logGpRadioTelemetry(
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

        val failedBeforePlaybackStarted = gpRadioStartedAtElapsedMs == 0L
        val shouldAdvance = failedBeforePlaybackStarted || elapsedMs in 1 until GP_RADIO_EARLY_END_THRESHOLD_MS
        if (shouldAdvance && gpRadioPlanIndex < gpRadioPlan.lastIndex) {
            gpRadioPlanIndex += 1
            logGpRadioTelemetry(
                event = "backend_advanced",
                planEntry = gpRadioPlan[gpRadioPlanIndex],
                detail = "previousBackend=${planEntry.backend.preferenceValue} elapsedMs=$elapsedMs started=${!failedBeforePlaybackStarted}"
            )
        }

        releaseGpRadioPlayer()
        if (gpRadioInjected && isCurrentGpRadioAttempt(attemptId)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (gpRadioInjected) {
                    startGpRadioPlayer()
                }
            }, GP_RADIO_RETRY_DELAY_MS)
        }
    }

    private fun isCurrentGpRadioAttempt(attemptId: Long): Boolean {
        return gpRadioInjected && gpRadioAttemptId == attemptId
    }

    private fun releaseGpRadioPlayer() {
        cancelGpRadioDelayNudge(resume = false)
        gpRadioPlayer?.release()
        gpRadioPlayer = null
    }

    private fun buildGpRadioPlan(settings: fr.groggy.racecontrol.tv.core.settings.Settings): List<GpRadioPlanEntry> {
        val sources: List<GpRadioSource> = if (settings.customRadioUrl.isNotBlank()) {
            listOf(GpRadioSource(name = "custom", url = settings.customRadioUrl))
        } else {
            GpRadioSources.rawCandidates
        }
        return sources.map {
            GpRadioPlanEntry(fr.groggy.racecontrol.tv.core.settings.Settings.CustomRadioBackend.LIBVLC, it)
        }
    }

    private fun logGpRadioTelemetry(
        event: String,
        planEntry: GpRadioPlanEntry? = null,
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

    private fun setGpRadioOffset(offsetMs: Long): Long {
        val clampedOffsetMs = offsetMs.coerceIn(0L, GP_RADIO_MAX_OFFSET_MS)
        if (!gpRadioInjected) {
            gpRadioOffsetMs = clampedOffsetMs
            return gpRadioOffsetMs
        }
        val deltaMs = clampedOffsetMs - gpRadioOffsetMs
        if (deltaMs == 0L) return gpRadioOffsetMs
        if (applyGpRadioOffsetDelta(deltaMs, clampedOffsetMs)) {
            gpRadioOffsetMs = clampedOffsetMs
            logGpRadioTelemetry("offset_updated", detail = "offsetMs=$gpRadioOffsetMs")
        } else {
            logGpRadioTelemetry(
                "offset_update_rejected",
                detail = "requestedOffsetMs=$clampedOffsetMs currentOffsetMs=$gpRadioOffsetMs deltaMs=$deltaMs"
            )
        }
        return gpRadioOffsetMs
    }

    private fun applyGpRadioOffset() {
        // audioDelay-based approach: VLC buffers the stream and outputs it [offsetMs] late.
        // If the main player just seeked, the radio is still running — no re-sync needed.
        val seekedRecentlyMs = android.os.SystemClock.elapsedRealtime() - lastMainPlayerSeekAtElapsedMs
        val effectiveOffsetMs = if (lastMainPlayerSeekAtElapsedMs > 0L && seekedRecentlyMs < 5_000L) {
            Log.d(TAG, "Seek was ${seekedRecentlyMs}ms ago — using 0ms audio delay")
            0L
        } else {
            gpRadioOffsetMs
        }
        gpRadioPlayer?.setAudioDelay(effectiveOffsetMs)
        logGpRadioTelemetry("offset_applied", detail = "offsetMs=$effectiveOffsetMs")
    }

    private fun restartGpRadio() {
        releaseGpRadioPlayer()
        startGpRadioPlayer()
    }

    private fun applyGpRadioOffsetDelta(deltaMs: Long, targetOffsetMs: Long): Boolean {
        val secondary = gpRadioPlayer ?: return false
        if (deltaMs == 0L) return true

        if (deltaMs < 0L) {
            if (secondary.skipAhead(-deltaMs)) {
                logGpRadioTelemetry("offset_adjusted_skip", detail = "deltaMs=$deltaMs newOffsetMs=$targetOffsetMs")
                return true
            }
            logGpRadioTelemetry("offset_adjusted_skip_unavailable", detail = "deltaMs=$deltaMs currentOffsetMs=$gpRadioOffsetMs")
            return false
        }

        scheduleGpRadioDelayNudge(secondary, deltaMs, targetOffsetMs)
        return true
    }

    private fun scheduleGpRadioDelayNudge(engine: GpRadioEngine, delayMs: Long, targetOffsetMs: Long) {
        cancelGpRadioDelayNudge(resume = true)
        engine.pause()
        val resumeRunnable = Runnable {
            gpRadioDelayNudgeRunnable = null
            if (gpRadioInjected && engine === gpRadioPlayer) {
                engine.play()
                logGpRadioTelemetry(
                    "offset_adjusted_pause",
                    detail = "delayMs=$delayMs newOffsetMs=$targetOffsetMs"
                )
            }
        }
        gpRadioDelayNudgeRunnable = resumeRunnable
        overlayAutoCloseHandler.postDelayed(resumeRunnable, delayMs)
    }

    private fun cancelGpRadioDelayNudge(resume: Boolean) {
        val pending = gpRadioDelayNudgeRunnable ?: return
        overlayAutoCloseHandler.removeCallbacks(pending)
        gpRadioDelayNudgeRunnable = null
        if (resume) {
            gpRadioPlayer?.play()
        }
    }

    private fun showGpRadioWaitingMessage(delayMs: Long) {
        if (delayMs <= 0L) return
        Toast.makeText(
            requireContext(),
            getString(R.string.custom_radio_waiting_message, formatGpRadioOffset(delayMs)),
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

    private fun dismissGpRadioSyncDialogIfShown() {
        (childFragmentManager.findFragmentByTag(GP_RADIO_SYNC_DIALOG_TAG) as? DialogFragment)
            ?.dismissAllowingStateLoss()
    }

    private fun formatGpRadioOffset(delayMs: Long): String {
        return String.format(Locale.getDefault(), "%.1f", delayMs / 1000.0)
    }
}
