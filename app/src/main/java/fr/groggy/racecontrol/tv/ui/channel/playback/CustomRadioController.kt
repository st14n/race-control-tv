package fr.groggy.racecontrol.tv.ui.channel.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.ui.player.CustomRadioSyncDialog
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** What a playback fragment provides to run the Grand Prix / custom radio on top of its player. */
internal interface CustomRadioHost {
    val mainPlayer: ExoPlayer?
    /** Silence (or restore) the F1 audio while the radio carries the commentary. */
    fun setMainAudioMuted(muted: Boolean, reason: String)
    /** The audio label in the transport controls changed. */
    fun refreshRadioOverlay()
    fun onRadioDialogInteraction()
    fun onRadioDialogDismissed()
    /** Fingerprint of the F1 audio as it's decoded (keeps running while muted); null if not tapped. */
    val mainAudioFeatures: AcousticFeatureStream?
    /**
     * Prefer the F1 stream's ambient-only (FX) audio track while muted under the radio: without
     * commentary it matches the radio's background circuit sound better. Returns whether it changed.
     */
    fun setAmbientAudioPreferred(preferred: Boolean): Boolean
}

/**
 * Custom radio playback shared by the playback fragments: starting/stopping the radio, keeping it
 * in sync with the video, and the sync dialog.
 *
 * Auto-sync delays the radio by however far the video is behind live (the wall clock of the frame on
 * screen, from the player's live offset) plus a remembered correction for the radio's own latency.
 * With auto-sync off, or when the stream has no live timing, the manual delay is used instead.
 */
internal class CustomRadioController(
    private val fragment: Fragment,
    private val settingsRepository: SettingsRepository,
    private val host: CustomRadioHost,
    private val tag: String?
) {

    companion object {
        private const val CUSTOM_RADIO_DEFAULT_OFFSET_MS = 20_000L
        // Just under the ~180 s the in-app HLS radio buffer keeps
        private const val CUSTOM_RADIO_MAX_OFFSET_MS = 170_000L
        private const val CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS = 60_000L
        private const val CUSTOM_RADIO_AUTO_SYNC_INTERVAL_MS = 1_000L
        // Drift smaller than this isn't worth an audible seek
        private const val CUSTOM_RADIO_AUTO_SYNC_TOLERANCE_MS = 400L
        // Errors up to this are closed by playing the radio a little faster or slower (no audible jump,
        // and the acoustic measurement keeps its audio); only bigger ones are a seek
        private const val CUSTOM_RADIO_SLEW_MAX_ERROR_MS = 1_500L
        private const val CUSTOM_RADIO_SLEW_DEADBAND_MS = 100L
        private const val CUSTOM_RADIO_SLEW_GAIN_PER_SECOND = 0.1f
        private const val CUSTOM_RADIO_SLEW_MAX_SPEED_CHANGE = 0.03f
        // Give a radio seek time to land before measuring again
        private const val CUSTOM_RADIO_AUTO_SYNC_SETTLE_MS = 2_000L
        private const val CUSTOM_RADIO_BUFFER_MARGIN_MS = 2_000L
        private const val CUSTOM_RADIO_EARLY_END_THRESHOLD_MS = 12_000L
        private const val ACOUSTIC_SYNC_INTERVAL_MS = 5_000L
        private const val ACOUSTIC_MIN_APPLY_INTERVAL_MS = 60_000L
        // The correction is minus the station's own latency (studio, encoder, server): seconds. Without a
        // value the viewer set by hand the acoustic sync keeps it inside this range (and a stored value
        // outside it is discarded); with one it stays within CORRECTION_ANCHOR_RANGE_MS of that.
        private const val CORRECTION_PLAUSIBLE_MIN_MS = -20_000L
        private const val CORRECTION_PLAUSIBLE_MAX_MS = 5_000L
        private const val CORRECTION_ANCHOR_RANGE_MS = 8_000L
        // Where the correction starts when nobody has tuned anything yet (typical station latency)
        private const val CORRECTION_DEFAULT_MS = -8_000L
        // Smaller offsets are within what the measurement itself can resolve
        private const val ACOUSTIC_DEADBAND_MS = 150L
        private const val CUSTOM_RADIO_RETRY_DELAY_MS = 500L
        private const val CUSTOM_RADIO_SYNC_DIALOG_TAG = "custom_radio_sync"
        private const val CUSTOM_RADIO_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Google TV Streamer Build/UTT3.240625.001.K5; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36"
    }

    private var radioPlayer: CustomRadioEngine? = null
    /** The radio is what the viewer hears. */
    var isActive: Boolean = false
        private set
    /** The radio keeps streaming silently so switching back to it is instant. */
    private var isMuted: Boolean = false
    private var offsetMs: Long = CUSTOM_RADIO_DEFAULT_OFFSET_MS
    private var hasAutoInjected = false
    private var lastMainPlayerSeekAtElapsedMs: Long = 0L
    private var plan: List<CustomRadioPlanEntry> = emptyList()
    private var planIndex: Int = 0
    private var attemptId: Long = 0L
    private var startedAtElapsedMs: Long = 0L
    private var autoSync: Boolean = true
    private var autoSyncCorrectionMs: Long = 0L
    private var needsCorrectionSeed = false
    private var manualDelayMs: Long = CUSTOM_RADIO_DEFAULT_OFFSET_MS
    private var lastAutoSyncSeekAtElapsedMs: Long = 0L
    private var hasWarnedBufferExceeded = false
    /** Auto-sync started but the radio isn't at its target delay yet; F1 audio plays until it is. */
    private var awaitingAutoSyncLock = false
    private val radioAudioFeatures = AcousticFeatureStream(withSide = true)
    private var acousticSync = true
    /** The user nudged the sync by hand this session; acoustic sync then leaves the correction alone. */
    private var acousticSuspendedByUser = false
    private var ambientAudioPreferred = false
    /** Last time the radio alignment changed; acoustic estimates only use audio after it. */
    private var lastAlignmentChangeAtMs = 0L
    private var acousticEstimateInFlight = false
    private val acousticExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "radio-acoustic-sync").apply { isDaemon = true }
    }
    private val acousticSyncRunnable = object : Runnable {
        override fun run() {
            requestAcousticEstimate()
            handler.postDelayed(this, ACOUSTIC_SYNC_INTERVAL_MS)
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val autoSyncRunnable = object : Runnable {
        override fun run() {
            tickAutoSync(force = false)
            handler.postDelayed(this, CUSTOM_RADIO_AUTO_SYNC_INTERVAL_MS)
        }
    }

    // ── Hooks for the host fragment ──────────────────────────────────────────

    /** Call when the main player becomes ready; switches to the radio once per playback if configured. */
    fun onMainPlayerReady() {
        if (hasAutoInjected) return
        hasAutoInjected = true
        if (shouldAutoInject(settingsRepository.getCurrent())) {
            inject()
        }
    }

    fun onMainPlayerSeek() {
        lastMainPlayerSeekAtElapsedMs = SystemClock.elapsedRealtime()
        Log.d(tag, "Main player seek detected — suppressing next GP Radio sync delay")
    }

    fun pause() {
        radioPlayer?.pause()
    }

    fun resume() {
        if (isMuted || isActive) {
            radioPlayer?.play()
        }
    }

    fun release() {
        // Nothing may restart after this: a retry posted in the last half second would otherwise call
        // startPlayer() on a detached fragment, fail, and post the next retry, forever
        isActive = false
        isMuted = false
        attemptId++
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        dismissSyncDialog()
        acousticExecutor.shutdownNow()
    }

    fun isSelectable(settings: Settings = settingsRepository.getCurrent()): Boolean {
        if (!settings.restrictCustomRadioToLiveSessions) return true
        return ChannelPlaybackFragment.findIsLiveSession(fragment.requireActivity())
    }

    fun overlayLabel(): String {
        val label = fragment.getString(R.string.custom_radio_audio_label)
        return if (autoSync) {
            fragment.getString(R.string.custom_radio_overlay_label_auto, label, formatOffset(offsetMs))
        } else {
            fragment.getString(R.string.custom_radio_overlay_label, label, formatOffset(offsetMs))
        }
    }

    // ── Start / stop ─────────────────────────────────────────────────────────

    fun inject() {
        if (isActive) return
        val settings = settingsRepository.getCurrent()
        if (!isSelectable(settings)) {
            Toast.makeText(fragment.requireContext(), R.string.custom_radio_live_only_message, Toast.LENGTH_LONG).show()
            return
        }
        // If the radio is already streaming but muted, just unmute — no wait needed
        if (isMuted && radioPlayer != null) {
            isMuted = false
            isActive = true
            host.refreshRadioOverlay()
            if (awaitingAutoSyncLock) {
                // Still filling up to its target delay; tickAutoSync switches over once it's there
                logTelemetry("unmuted", detail = "Waiting for auto-sync lock before switching audio")
                return
            }
            radioPlayer!!.setVolume(100)
            host.setMainAudioMuted(true, "custom_radio_unmute")
            preferAmbientMainAudio()
            logTelemetry("unmuted", detail = "Unmuted existing stream, no delay")
            return
        }
        // The radio was muted but died while muted — reset muted flag before fresh start
        isMuted = false
        val radioPlan = buildPlan(settings)
        if (radioPlan.isEmpty()) {
            logTelemetry("plan_empty", detail = "No custom radio backend available")
            return
        }
        autoSync = true // the radio always follows the picture; the dialog can turn it off for this session
        acousticSync = settings.customRadioAcousticSync
        acousticSuspendedByUser = false
        decider.reset()
        var storedCorrection = settings.customRadioAutoSyncCorrectionMs
        if (storedCorrection != null && settingsRepository.getCustomRadioCorrectionAnchorMs() == null &&
            storedCorrection !in CORRECTION_PLAUSIBLE_MIN_MS..CORRECTION_PLAUSIBLE_MAX_MS
        ) {
            // E.g. left behind by an early acoustic sync that jumped the wrong way
            logTelemetry("correction_discarded", detail = "stored correctionMs=$storedCorrection is not a plausible radio latency")
            storedCorrection = null
        }
        autoSyncCorrectionMs = storedCorrection ?: 0L
        needsCorrectionSeed = storedCorrection == null
        manualDelayMs = settings.customRadioDelayMs
        hasWarnedBufferExceeded = false
        offsetMs = (if (autoSync) autoSyncTargetMs() else null) ?: settings.customRadioDelayMs
        plan = radioPlan
        planIndex = 0
        isActive = true
        host.refreshRadioOverlay()
        // F1 audio keeps playing until the radio has buffered up to its delay (see onStarted)
        showWaitingMessage(offsetMs, followsPicture = autoSync && videoLagMs() != null)
        startPlayer()
    }

    fun stop() {
        if (!isActive) return
        // If the radio has connected and started, mute it and keep it alive so switching back is instant.
        // If it hasn't started yet (still connecting), do a full teardown.
        val radioStarted = radioPlayer != null && startedAtElapsedMs > 0L
        if (radioStarted) {
            radioPlayer!!.setVolume(0)
            isActive = false
            isMuted = true
            logTelemetry("muted", detail = "Kept stream alive but muted")
        } else {
            releasePlayer()
            isActive = false
            isMuted = false
            offsetMs = settingsRepository.getCurrent().customRadioDelayMs
            plan = emptyList()
            planIndex = 0
            startedAtElapsedMs = 0L
        }
        restoreMainAudioTrack()
        host.refreshRadioOverlay()
        host.setMainAudioMuted(false, "custom_radio_stop")
    }

    private fun startPlayer() {
        if (!fragment.isAdded) return
        val planEntry = plan.getOrNull(planIndex)
        if (planEntry == null) {
            logTelemetry("plan_exhausted", detail = "No custom radio plan entry at index $planIndex")
            stop()
            return
        }

        try {
            releasePlayer()
            val currentAttemptId = ++attemptId
            startedAtElapsedMs = 0L
            logTelemetry(
                event = "start_requested",
                planEntry = planEntry,
                detail = "planIndex=$planIndex attemptId=$currentAttemptId"
            )

            radioPlayer = createCustomRadioEngine(
                context = fragment.requireContext(),
                planEntry = planEntry,
                userAgent = CUSTOM_RADIO_USER_AGENT,
                initialAudioDelayMs = offsetMs,
                initialVolume = 100,
                audioBufferSink = radioAudioFeatures,
                onStarted = {
                    if (!isCurrentAttempt(currentAttemptId)) return@createCustomRadioEngine
                    startedAtElapsedMs = SystemClock.elapsedRealtime()
                    logTelemetry(
                        event = "started",
                        planEntry = planEntry,
                        detail = "attemptId=$currentAttemptId offsetMs=$offsetMs autoSync=$autoSync"
                    )
                    if (autoSync && videoLagMs() != null) {
                        // Keep F1 audio until the radio has buffered enough to sit at its target delay;
                        // switching now would mean hearing it jump towards the target as the buffer fills
                        awaitingAutoSyncLock = true
                        radioPlayer?.setVolume(0)
                    } else {
                        // Mute embedded audio on the main player — the radio takes over
                        host.setMainAudioMuted(true, "custom_radio_start")
                    }
                    applyOffset()
                    startAutoSync()
                    startAcousticSync()
                },
                onEnded = {
                    if (!isCurrentAttempt(currentAttemptId)) return@createCustomRadioEngine
                    handleAttemptEnded(planEntry, currentAttemptId, "ended")
                },
                onError = { errorCode, throwable ->
                    if (!isCurrentAttempt(currentAttemptId)) return@createCustomRadioEngine
                    handleAttemptEnded(
                        planEntry = planEntry,
                        attemptId = currentAttemptId,
                        outcome = "error",
                        detail = errorCode,
                        throwable = throwable
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to start GP Radio player", e)
            handleAttemptEnded(
                planEntry = planEntry,
                attemptId = attemptId,
                outcome = "exception",
                detail = e::class.simpleName,
                throwable = e
            )
        }
    }

    private fun handleAttemptEnded(
        planEntry: CustomRadioPlanEntry,
        attemptId: Long,
        outcome: String,
        detail: String? = null,
        throwable: Throwable? = null
    ) {
        val elapsedMs = if (startedAtElapsedMs > 0L) {
            SystemClock.elapsedRealtime() - startedAtElapsedMs
        } else {
            0L
        }
        logTelemetry(
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

        val failedBeforePlaybackStarted = startedAtElapsedMs == 0L
        val shouldAdvance = failedBeforePlaybackStarted || elapsedMs in 1 until CUSTOM_RADIO_EARLY_END_THRESHOLD_MS
        if (shouldAdvance && planIndex < plan.lastIndex) {
            planIndex += 1
            logTelemetry(
                event = "backend_advanced",
                planEntry = plan[planIndex],
                detail = "previousBackend=${planEntry.backend.preferenceValue} elapsedMs=$elapsedMs started=${!failedBeforePlaybackStarted}"
            )
        }

        releasePlayer()
        if (isActive && isCurrentAttempt(attemptId)) {
            handler.postDelayed({
                if (isActive) {
                    startPlayer()
                }
            }, CUSTOM_RADIO_RETRY_DELAY_MS)
        }
    }

    private fun isCurrentAttempt(attemptId: Long): Boolean {
        return isActive && this.attemptId == attemptId
    }

    private fun releasePlayer() {
        stopAutoSync()
        stopAcousticSync()
        awaitingAutoSyncLock = false
        radioPlayer?.release()
        radioPlayer = null
    }

    private fun shouldAutoInject(settings: Settings): Boolean {
        if (!settings.autoSelectCustomRadio) return false
        if (!isSelectable(settings)) return false
        return buildPlan(settings).isNotEmpty()
    }

    private fun buildPlan(settings: Settings): List<CustomRadioPlanEntry> {
        val customUrl = settings.customRadioUrl.trim()
        if (customUrl.isNotBlank()) {
            if (!isSupportedUrl(customUrl)) {
                Log.w(tag, "Ignoring invalid custom radio URL: $customUrl")
                return emptyList()
            }
            return listOf(
                CustomRadioPlanEntry(
                    backend = Settings.CustomRadioBackend.EXOPLAYER,
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
                    Settings.CustomRadioBackend.EXOPLAYER,
                    it
                )
            )
        } ?: emptyList()
    }

    private fun isSupportedUrl(url: String): Boolean {
        val uri = android.net.Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    /** Remote/overlay/dialog nudge: moves the auto-sync correction, or the manual delay when auto-sync is off. */
    fun adjust(deltaMs: Long) {
        if (!autoSync) {
            setOffset(offsetMs + deltaMs)
            return
        }
        if (acousticSync && !acousticSuspendedByUser) {
            acousticSuspendedByUser = true
            logTelemetry("acoustic_sync_suspended", detail = "manual nudge deltaMs=$deltaMs")
        }
        autoSyncCorrectionMs = (autoSyncCorrectionMs + deltaMs)
            .coerceIn(-CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS, CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS)
        // Remembered so the radio's own latency only has to be calibrated once per station
        settingsRepository.updateCustomRadioAutoSyncCorrection(autoSyncCorrectionMs)
        settingsRepository.updateCustomRadioCorrectionAnchorMs(autoSyncCorrectionMs)
        logTelemetry("auto_sync_correction", detail = "correctionMs=$autoSyncCorrectionMs (set by hand)")
        tickAutoSync(force = true)
        host.refreshRadioOverlay()
    }

    private fun toggleAutoSync() {
        autoSync = !autoSync
        settingsRepository.updateCustomRadioAutoSync(autoSync)
        logTelemetry("auto_sync_toggled", detail = "autoSync=$autoSync offsetMs=$offsetMs")
        // Switching to manual keeps the current delay, so nothing jumps; switching to auto re-syncs now
        if (autoSync) {
            hasWarnedBufferExceeded = false
            tickAutoSync(force = true)
        } else if (awaitingAutoSyncLock) {
            switchAudioToRadio("auto_sync_off")
        }
        host.refreshRadioOverlay()
    }

    private fun switchAudioToRadio(reason: String) {
        awaitingAutoSyncLock = false
        if (!isActive) return
        radioPlayer?.setVolume(100)
        host.setMainAudioMuted(true, "custom_radio_$reason")
        preferAmbientMainAudio()
        logTelemetry("audio_switched_to_radio", detail = "reason=$reason offsetMs=$offsetMs")
    }

    // ── Acoustic sync ────────────────────────────────────────────────────────

    private fun preferAmbientMainAudio() {
        if (!acousticSync || !autoSync || ambientAudioPreferred) return
        ambientAudioPreferred = host.setAmbientAudioPreferred(true)
        logTelemetry("ambient_audio_track", detail = "selected=$ambientAudioPreferred")
    }

    private fun restoreMainAudioTrack() {
        if (!ambientAudioPreferred) return
        ambientAudioPreferred = false
        host.setAmbientAudioPreferred(false)
    }

    private fun startAcousticSync() {
        stopAcousticSync()
        if (!acousticSync || host.mainAudioFeatures == null) return
        handler.postDelayed(acousticSyncRunnable, ACOUSTIC_SYNC_INTERVAL_MS)
    }

    private fun stopAcousticSync() {
        handler.removeCallbacks(acousticSyncRunnable)
        decider.reset()
    }

    private var nextAcousticLogAtMs = 0L

    /** Why no acoustic estimate is being made, at most every 20 s. */
    private fun logAcousticIdle(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < nextAcousticLogAtMs) return
        nextAcousticLogAtMs = now + 20_000L
        logTelemetry("acoustic_idle", detail = reason)
    }

    private fun requestAcousticEstimate() {
        val mainFeatures = host.mainAudioFeatures ?: run { logAcousticIdle("no F1 audio tap"); return }
        val mainPlayer = host.mainPlayer ?: return
        if (acousticSuspendedByUser) logAcousticIdle("paused after a manual change")
        if (!autoSync || !acousticSync || acousticSuspendedByUser || !isActive || awaitingAutoSyncLock) return
        if (!mainPlayer.isPlaying || acousticEstimateInFlight) return
        // Without a live picture to follow (a replay) there is nothing to fine-tune
        if (videoLagMs() == null) return
        val notBeforeMs = lastAlignmentChangeAtMs.toDouble()
        acousticEstimateInFlight = true
        acousticExecutor.execute {
            // Left minus right (commentary cancelled) and the mono mix, each against its radio twin
            val mainSide = mainFeatures.sideStream
            val radioSide = radioAudioFeatures.sideStream
            val sideEstimate = if (mainSide != null && radioSide != null) runCatching {
                RadioAcousticCorrelator.estimate(mainSide, radioSide, notBeforeMs)
            }.onFailure { Log.w(tag, "Acoustic side estimate failed", it) }.getOrNull() else null
            val monoEstimate = runCatching {
                RadioAcousticCorrelator.estimate(mainFeatures, radioAudioFeatures, notBeforeMs)
            }.onFailure { Log.w(tag, "Acoustic estimate failed", it) }.getOrNull()
            handler.post {
                acousticEstimateInFlight = false
                if (sideEstimate == null && monoEstimate == null) {
                    logAcousticIdle(RadioAcousticCorrelator.lastSkip)
                } else {
                    onAcousticEstimate(sideEstimate, monoEstimate, notBeforeMs)
                }
            }
        }
    }

    private val decider = RadioAcousticDecider()
    private var lastAcousticApplyAtMs = 0L

    private fun onAcousticEstimate(
        sideEstimate: RadioAcousticCorrelator.Estimate?,
        monoEstimate: RadioAcousticCorrelator.Estimate?,
        notBeforeMs: Double
    ) {
        // The alignment changed while this was computing: it describes audio that no longer applies
        if (lastAlignmentChangeAtMs.toDouble() != notBeforeMs || !isActive || acousticSuspendedByUser) return
        val now = SystemClock.elapsedRealtime()
        val decision = decider.add(sideEstimate, monoEstimate)
        logTelemetry("acoustic_estimate", detail = decider.lastSummary)
        if (decision == null) return
        val lagMs = decision.lagMs
        if (abs(lagMs) < ACOUSTIC_DEADBAND_MS) {
            decider.reset()
            logTelemetry("acoustic_in_sync", detail = "source=${decision.source} lagMs=$lagMs")
            return
        }
        if (now - lastAcousticApplyAtMs < ACOUSTIC_MIN_APPLY_INTERVAL_MS) return
        // Radio behind the F1 audio by lagMs: take that much off the radio's delay
        val corrected = autoSyncCorrectionMs - lagMs
        val anchor = settingsRepository.getCustomRadioCorrectionAnchorMs()
        val plausible = if (anchor != null) {
            abs(corrected - anchor) <= CORRECTION_ANCHOR_RANGE_MS
        } else {
            corrected in CORRECTION_PLAUSIBLE_MIN_MS..CORRECTION_PLAUSIBLE_MAX_MS
        }
        if (!plausible) {
            logTelemetry(
                "acoustic_correction_refused",
                detail = "lagMs=$lagMs would make correctionMs=$corrected " +
                    if (anchor != null) "(too far from the hand-set $anchor)" else "(not a plausible radio latency)"
            )
            decider.reset()
            return
        }
        lastAcousticApplyAtMs = now
        decider.reset()
        autoSyncCorrectionMs = corrected
        settingsRepository.updateCustomRadioAutoSyncCorrection(autoSyncCorrectionMs)
        logTelemetry(
            "acoustic_correction",
            detail = "lagMs=$lagMs correctionMs=$autoSyncCorrectionMs source=${decision.source} curves=${decision.curves} " +
                "score=${String.format(Locale.ROOT, "%.3f", decision.score)} ratio=${String.format(Locale.ROOT, "%.2f", decision.ratio)}"
        )
        if (abs(lagMs) >= 500L && fragment.isAdded) {
            Toast.makeText(
                fragment.requireContext(),
                fragment.getString(
                    R.string.custom_radio_acoustic_corrected,
                    String.format(Locale.getDefault(), "%+.1f", -lagMs / 1000.0)
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
        tickAutoSync(force = true)
        host.refreshRadioOverlay()
    }

    private fun setOffset(newOffsetMs: Long): Long {
        val clampedOffsetMs = newOffsetMs.coerceIn(0L, CUSTOM_RADIO_MAX_OFFSET_MS)
        if (!isActive) {
            offsetMs = clampedOffsetMs
            return offsetMs
        }
        val deltaMs = clampedOffsetMs - offsetMs
        if (deltaMs == 0L) return offsetMs
        offsetMs = clampedOffsetMs
        radioPlayer?.setAudioDelay(offsetMs)
        host.refreshRadioOverlay()
        logTelemetry("offset_updated", detail = "offsetMs=$offsetMs deltaMs=$deltaMs")
        return offsetMs
    }

    private fun applyOffset() {
        if (autoSync && autoSyncTargetMs() != null) {
            tickAutoSync(force = true)
            return
        }
        // If the main player just seeked, the radio is still running — no re-sync needed.
        val seekedRecentlyMs = SystemClock.elapsedRealtime() - lastMainPlayerSeekAtElapsedMs
        val effectiveOffsetMs = if (lastMainPlayerSeekAtElapsedMs > 0L && seekedRecentlyMs < 5_000L) {
            Log.d(tag, "Seek was ${seekedRecentlyMs}ms ago — using 0ms audio delay")
            0L
        } else {
            offsetMs
        }
        radioPlayer?.setAudioDelay(effectiveOffsetMs)
        logTelemetry("offset_applied", detail = "offsetMs=$effectiveOffsetMs")
    }

    /** How far the video is behind live: the wall clock of the frame on screen vs now. */
    private fun videoLagMs(): Long? {
        val player = host.mainPlayer ?: return null
        val offset = player.currentLiveOffset
        // Streams with a wall clock (HLS program date time, DASH availability start): exact
        if (offset != C.TIME_UNSET) {
            // A replay of a finished session reports the time since the session started (days): there
            // is no live picture to follow, and no radio buffer reaches back that far
            if (offset > CUSTOM_RADIO_MAX_OFFSET_MS) return null
            rememberLiveEdgeLatency(player, offset)
            return offset
        }
        // No wall clock (some feeds and stream types): how far behind the live edge of the stream the
        // viewer is, plus the edge's own latency, remembered from the last stream that had a clock.
        // It follows pauses and seeks; the constant part is what the correction (and acoustic
        // matching) absorbs. Before this, such streams just kept the fixed delay.
        if (!player.isCurrentMediaItemLive) return null
        val behindEdgeMs = behindLiveEdgeMs(player) ?: return null
        return (behindEdgeMs + settingsRepository.getLiveEdgeLatencyMs()).takeIf { it <= CUSTOM_RADIO_MAX_OFFSET_MS }
    }

    private fun behindLiveEdgeMs(player: androidx.media3.exoplayer.ExoPlayer): Long? {
        val duration = player.contentDuration
        val position = player.contentPosition
        if (duration == C.TIME_UNSET || position == C.TIME_UNSET || duration <= 0L) return null
        return (duration - position).coerceAtLeast(0L)
    }

    /** With a clock available, learn how far the stream's live edge itself lags (a slow average). */
    private fun rememberLiveEdgeLatency(player: androidx.media3.exoplayer.ExoPlayer, liveOffsetMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEdgeLatencySavedAtMs < 60_000L) return
        val behindEdge = behindLiveEdgeMs(player) ?: return
        val edgeLatency = liveOffsetMs - behindEdge
        if (edgeLatency !in 0L..60_000L) return
        lastEdgeLatencySavedAtMs = now
        val previous = settingsRepository.getLiveEdgeLatencyMs()
        settingsRepository.updateLiveEdgeLatencyMs((previous * 3 + edgeLatency) / 4)
    }

    private var lastEdgeLatencySavedAtMs = 0L

    /**
     * The radio delay (as measured by [CustomRadioEngine.currentDelayMs], i.e. since the audio was
     * recorded locally) that makes the radio's real age equal the video's lag:
     * videoLag - connect backlog + correction. The backlog changes with every connection and is
     * measured; the correction only covers what can't be measured (encoder/commentary latency, a
     * second or two), so it stays put between sessions.
     */
    private fun autoSyncTargetMs(): Long? {
        val videoLagMs = videoLagMs() ?: return null
        val backlogMs = radioPlayer?.ingestBacklogMs() ?: return null
        if (needsCorrectionSeed) {
            // First auto-sync with this correction model: start from the manual delay the user already
            // tuned, so switching to auto-sync sounds the same and only drift is corrected from here on
            needsCorrectionSeed = false
            // ...unless that delay was never touched (still the default): then a typical station latency
            autoSyncCorrectionMs = if (manualDelayMs == CUSTOM_RADIO_DEFAULT_OFFSET_MS) {
                CORRECTION_DEFAULT_MS
            } else {
                (manualDelayMs - (videoLagMs - backlogMs))
                    .coerceIn(-CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS, CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS)
            }
            settingsRepository.updateCustomRadioAutoSyncCorrection(autoSyncCorrectionMs)
            logTelemetry(
                "auto_sync_correction_seeded",
                detail = "manualDelayMs=$manualDelayMs videoLagMs=$videoLagMs backlogMs=$backlogMs " +
                    "correctionMs=$autoSyncCorrectionMs"
            )
        }
        return videoLagMs - backlogMs + autoSyncCorrectionMs
    }

    private fun startAutoSync() {
        stopAutoSync()
        handler.postDelayed(autoSyncRunnable, CUSTOM_RADIO_AUTO_SYNC_INTERVAL_MS)
    }

    private fun stopAutoSync() {
        handler.removeCallbacks(autoSyncRunnable)
    }

    private fun tickAutoSync(force: Boolean) {
        if (!autoSync || !fragment.isAdded) return
        val engine = radioPlayer ?: return
        if (startedAtElapsedMs == 0L) return
        val mainPlayer = host.mainPlayer ?: return
        // Paused or buffering: both streams stand still, so correct once playback resumes
        if (!force && !mainPlayer.isPlaying) return
        val nowMs = SystemClock.elapsedRealtime()
        if (!force && nowMs - lastAutoSyncSeekAtElapsedMs < CUSTOM_RADIO_AUTO_SYNC_SETTLE_MS) return
        // Not live (or no live timing in the stream): keep whatever delay is set
        val targetMs = autoSyncTargetMs()?.coerceAtLeast(0L) ?: return

        val bufferLimitMs = minOf(
            CUSTOM_RADIO_MAX_OFFSET_MS,
            engine.maxDelayMs()?.minus(CUSTOM_RADIO_BUFFER_MARGIN_MS) ?: CUSTOM_RADIO_MAX_OFFSET_MS
        ).coerceAtLeast(0L)
        val reachableTargetMs: Long
        if (targetMs > bufferLimitMs) {
            // Not enough radio buffered for the target. Don't chase the growing buffer with a seek every
            // tick (audible as stutter): while starting, F1 audio keeps playing until the buffer suffices;
            // later (e.g. after a long pause) jump to the oldest audio once and warn.
            if (awaitingAutoSyncLock || hasWarnedBufferExceeded) return
            hasWarnedBufferExceeded = true
            Toast.makeText(fragment.requireContext(), R.string.custom_radio_buffer_exceeded_message, Toast.LENGTH_LONG).show()
            reachableTargetMs = bufferLimitMs
        } else {
            hasWarnedBufferExceeded = false
            reachableTargetMs = targetMs
        }

        val actualMs = engine.currentDelayMs()
        // > 0: the radio plays audio that is older than it should be (too late)
        val errorMs = actualMs?.let { it - reachableTargetMs }
        val inSync = errorMs != null && abs(errorMs) < CUSTOM_RADIO_AUTO_SYNC_TOLERANCE_MS
        if (inSync && awaitingAutoSyncLock) {
            switchAudioToRadio("auto_sync_locked")
            return
        }
        if (!force && !awaitingAutoSyncLock && errorMs != null && abs(errorMs) < CUSTOM_RADIO_SLEW_MAX_ERROR_MS) {
            // Follow the picture smoothly: the video's lag creeps (ExoPlayer nudges its own speed to
            // hold the live offset), and a seek for every 0.4 s of it is audible and throws away what
            // the acoustic sync has measured so far
            val speed = if (abs(errorMs) < CUSTOM_RADIO_SLEW_DEADBAND_MS) {
                1f
            } else {
                1f + (errorMs / 1000f * CUSTOM_RADIO_SLEW_GAIN_PER_SECOND)
                    .coerceIn(-CUSTOM_RADIO_SLEW_MAX_SPEED_CHANGE, CUSTOM_RADIO_SLEW_MAX_SPEED_CHANGE)
            }
            engine.setPlaybackSpeed(speed)
            logSyncStatus(nowMs, targetMs, actualMs, speed)
            if (!inSync) {
                // Still converging: the acoustic sync should only use audio from after this
                lastAlignmentChangeAtMs = nowMs
                decider.reset()
            }
            return
        }

        engine.setPlaybackSpeed(1f)
        engine.setAudioDelay(reachableTargetMs)
        lastAutoSyncSeekAtElapsedMs = nowMs
        lastAlignmentChangeAtMs = nowMs
        decider.reset()
        offsetMs = reachableTargetMs
        host.refreshRadioOverlay()
        logTelemetry(
            "auto_sync",
            detail = "videoLagMs=${mainPlayer.currentLiveOffset} backlogMs=${engine.ingestBacklogMs()} " +
                "correctionMs=$autoSyncCorrectionMs " +
                "targetMs=$targetMs appliedMs=$reachableTargetMs actualMs=$actualMs maxMs=${engine.maxDelayMs()}"
        )
    }

    private var nextSyncStatusLogAtMs = 0L

    /** Every 10 s while following: what the sync is based on, for tuning from the log afterwards. */
    private fun logSyncStatus(nowMs: Long, targetMs: Long, actualMs: Long?, speed: Float) {
        if (nowMs < nextSyncStatusLogAtMs) return
        nextSyncStatusLogAtMs = nowMs + 10_000L
        val player = host.mainPlayer
        logTelemetry(
            "sync_status",
            detail = "videoLagMs=${videoLagMs()} liveOffsetMs=${player?.currentLiveOffset} " +
                "backlogMs=${radioPlayer?.ingestBacklogMs()} correctionMs=$autoSyncCorrectionMs " +
                "targetMs=$targetMs actualMs=$actualMs speed=$speed radioHeard=${isActive && !awaitingAutoSyncLock} " +
                "acoustic=$acousticSync suspended=$acousticSuspendedByUser ambientTrack=$ambientAudioPreferred " +
                "curves=${decider.count}"
        )
    }

    // ── Dialog ───────────────────────────────────────────────────────────────

    fun showSyncDialog() {
        fragment.view?.post {
            if (!fragment.isAdded || fragment.childFragmentManager.isStateSaved) return@post
            if (fragment.childFragmentManager.findFragmentByTag(CUSTOM_RADIO_SYNC_DIALOG_TAG) != null) {
                return@post
            }
            CustomRadioSyncDialog(
                titleProvider = ::syncDialogTitle,
                onAdjust = ::adjust,
                modeToggleLabelProvider = {
                    fragment.getString(
                        if (autoSync) R.string.custom_radio_sync_switch_to_manual
                        else R.string.custom_radio_sync_switch_to_auto
                    )
                },
                onToggleMode = ::toggleAutoSync,
                onUserInteraction = host::onRadioDialogInteraction,
                onDialogDismissed = host::onRadioDialogDismissed
            ).show(fragment.childFragmentManager, CUSTOM_RADIO_SYNC_DIALOG_TAG)
        }
        host.onRadioDialogInteraction()
    }

    fun dismissSyncDialog() {
        if (!fragment.isAdded) return
        (fragment.childFragmentManager.findFragmentByTag(CUSTOM_RADIO_SYNC_DIALOG_TAG) as? DialogFragment)
            ?.dismissAllowingStateLoss()
    }

    /** What the quick radio buttons in the player show: the delay and, with auto-sync, the correction. */
    fun syncStatus(): String = syncDialogTitle()

    private fun syncDialogTitle(): String {
        if (!autoSync) {
            return fragment.getString(R.string.custom_radio_sync_title, formatOffset(offsetMs) + " s")
        }
        val correction = String.format(Locale.getDefault(), "%+.1f s", autoSyncCorrectionMs / 1000.0)
        return if (autoSyncTargetMs() == null) {
            fragment.getString(R.string.custom_radio_auto_sync_unavailable_title, correction)
        } else {
            fragment.getString(
                R.string.custom_radio_auto_sync_title,
                correction,
                formatOffset(radioPlayer?.currentDelayMs() ?: offsetMs) + " s"
            )
        }
    }

    private fun showWaitingMessage(delayMs: Long, followsPicture: Boolean) {
        if (delayMs <= 0L) return
        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(
                if (followsPicture) R.string.custom_radio_waiting_message else R.string.custom_radio_waiting_message_replay,
                formatOffset(delayMs)
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun formatOffset(delayMs: Long): String {
        return String.format(Locale.getDefault(), "%.1f", delayMs / 1000.0)
    }

    private fun logTelemetry(
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
            Log.i(tag, message)
        } else {
            Log.e(tag, message, throwable)
        }
        RadioSyncLog.append(fragment.context, message, throwable)
    }
}
