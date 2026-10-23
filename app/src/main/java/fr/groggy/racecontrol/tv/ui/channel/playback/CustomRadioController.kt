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
        // Give a radio seek time to land before measuring again
        private const val CUSTOM_RADIO_AUTO_SYNC_SETTLE_MS = 2_000L
        private const val CUSTOM_RADIO_BUFFER_MARGIN_MS = 2_000L
        private const val CUSTOM_RADIO_EARLY_END_THRESHOLD_MS = 12_000L
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
        releasePlayer()
        dismissSyncDialog()
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
            radioPlayer!!.setVolume(100)
            host.refreshRadioOverlay()
            host.setMainAudioMuted(true, "custom_radio_unmute")
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
        autoSync = settings.customRadioAutoSync
        autoSyncCorrectionMs = settings.customRadioAutoSyncCorrectionMs ?: 0L
        needsCorrectionSeed = settings.customRadioAutoSyncCorrectionMs == null
        manualDelayMs = settings.customRadioDelayMs
        hasWarnedBufferExceeded = false
        offsetMs = (if (autoSync) autoSyncTargetMs() else null) ?: settings.customRadioDelayMs
        plan = radioPlan
        planIndex = 0
        isActive = true
        host.refreshRadioOverlay()
        // F1 audio keeps playing until the radio has buffered up to its delay (see onStarted)
        showWaitingMessage(offsetMs)
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
        host.refreshRadioOverlay()
        host.setMainAudioMuted(false, "custom_radio_stop")
    }

    private fun startPlayer() {
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
                onStarted = {
                    if (!isCurrentAttempt(currentAttemptId)) return@createCustomRadioEngine
                    startedAtElapsedMs = SystemClock.elapsedRealtime()
                    logTelemetry(
                        event = "started",
                        planEntry = planEntry,
                        detail = "attemptId=$currentAttemptId offsetMs=$offsetMs autoSync=$autoSync"
                    )
                    // Mute embedded audio on the main player — the radio takes over
                    host.setMainAudioMuted(true, "custom_radio_start")
                    applyOffset()
                    startAutoSync()
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
        autoSyncCorrectionMs = (autoSyncCorrectionMs + deltaMs)
            .coerceIn(-CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS, CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS)
        // Remembered so the radio's own latency only has to be calibrated once per station
        settingsRepository.updateCustomRadioAutoSyncCorrection(autoSyncCorrectionMs)
        logTelemetry("auto_sync_correction", detail = "correctionMs=$autoSyncCorrectionMs")
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
        }
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

    /** How far the video is behind live (wall clock of the frame on screen) plus the correction. */
    private fun autoSyncTargetMs(): Long? {
        val videoLagMs = host.mainPlayer?.currentLiveOffset?.takeIf { it != C.TIME_UNSET } ?: return null
        if (needsCorrectionSeed) {
            // First live auto-sync ever: start from the manual delay the user already tuned, so
            // switching to auto-sync sounds the same and only drift is corrected from here on
            needsCorrectionSeed = false
            autoSyncCorrectionMs = (manualDelayMs - videoLagMs)
                .coerceIn(-CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS, CUSTOM_RADIO_MAX_AUTO_SYNC_CORRECTION_MS)
            settingsRepository.updateCustomRadioAutoSyncCorrection(autoSyncCorrectionMs)
            logTelemetry(
                "auto_sync_correction_seeded",
                detail = "manualDelayMs=$manualDelayMs videoLagMs=$videoLagMs correctionMs=$autoSyncCorrectionMs"
            )
        }
        return videoLagMs + autoSyncCorrectionMs
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
        val reachableTargetMs = if (targetMs > bufferLimitMs) {
            if (!hasWarnedBufferExceeded) {
                hasWarnedBufferExceeded = true
                Toast.makeText(fragment.requireContext(), R.string.custom_radio_buffer_exceeded_message, Toast.LENGTH_LONG).show()
            }
            bufferLimitMs
        } else {
            hasWarnedBufferExceeded = false
            targetMs
        }

        val actualMs = engine.currentDelayMs()
        if (!force && actualMs != null && abs(actualMs - reachableTargetMs) < CUSTOM_RADIO_AUTO_SYNC_TOLERANCE_MS) return

        engine.setAudioDelay(reachableTargetMs)
        lastAutoSyncSeekAtElapsedMs = nowMs
        offsetMs = reachableTargetMs
        host.refreshRadioOverlay()
        logTelemetry(
            "auto_sync",
            detail = "videoLagMs=${mainPlayer.currentLiveOffset} correctionMs=$autoSyncCorrectionMs " +
                "targetMs=$targetMs appliedMs=$reachableTargetMs actualMs=$actualMs maxMs=${engine.maxDelayMs()}"
        )
    }

    // ── Dialog ───────────────────────────────────────────────────────────────

    fun showSyncDialog() {
        fragment.view?.post {
            if (!fragment.isAdded) return@post
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

    private fun showWaitingMessage(delayMs: Long) {
        if (delayMs <= 0L) return
        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(R.string.custom_radio_waiting_message, formatOffset(delayMs)),
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
    }
}
