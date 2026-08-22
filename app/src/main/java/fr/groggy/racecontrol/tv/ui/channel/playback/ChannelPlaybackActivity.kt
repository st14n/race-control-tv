package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.ViewingService
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvBasicChannel
import fr.groggy.racecontrol.tv.f1tv.F1TvBasicChannelType
import fr.groggy.racecontrol.tv.f1tv.F1TvClient
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHdrRendererRouter
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHdrStreamClassifier
import fr.groggy.racecontrol.tv.ui.player.ChannelSelectionDialog
import fr.groggy.racecontrol.tv.ui.session.browse.Channel
import fr.groggy.racecontrol.tv.ui.signin.SignInActivity
import fr.groggy.racecontrol.tv.utils.DeviceInfo
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChannelPlaybackActivity : FragmentActivity(R.layout.activity_channel_playback),
    ChannelSelectionDialog.ChannelManagerListener {

    @Inject internal lateinit var viewingService: ViewingService
    @Inject internal lateinit var settingsRepository: SettingsRepository
    @Inject internal lateinit var f1TvClient: F1TvClient

    /** The [F1TvViewing] currently loaded into the player, used for 4K fallback detection. */
    private var currentViewing: F1TvViewing? = null
    private var currentPlaybackAttempt: PlaybackAttempt = PlaybackAttempt.Standard
    private var hasTriedToneMappedHdr: Boolean = false
    private var isSwappingPlaybackFragment: Boolean = false

    private val playbackTouchGestureDetector by lazy(LazyThreadSafetyMode.NONE) {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return activePlaybackFragment()?.shouldHandleTouchOverlayGesture() == true
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    return activePlaybackFragment()?.showControlsOverlayFromTouch() == true
                }
            }
        )
    }

    private val preferHdrManifestForDevice: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        val preferHdrManifest = !settingsRepository.getCurrent().disableUhdManifests &&
            DeviceInfo.shouldRequestHdrManifest(this) &&
            allowsUhdPlaybackForSeason()
        Log.i(TAG, "preferHdrManifestForDevice=$preferHdrManifest")
        preferHdrManifest
    }

    companion object {
        private val TAG = ChannelPlaybackActivity::class.simpleName

        // DIAGNOSTIC (2026-08-22): make exactly one CONTENT/PLAY call per
        // playback session, like the official app. Disables companion audio
        // (and therefore custom radio) while enabled.
        private const val SKIP_COMPANION_AUDIO_DIAGNOSTIC = false

        // DIAGNOSTIC (2026-08-22): cycle every UHD/HDR stream variant F1 will
        // serve, playing each for a few seconds with an on-screen label, so a
        // working combination (if any exists) can be found empirically instead
        // of one manual test at a time.
        private const val STREAM_VARIANT_PROBE = false
        private const val PROBE_SECONDS_PER_VARIANT = 12_000L

        fun intent(
            context: Context,
            sessionId: String,
            channelId: String?,
            contentId: String,
            isLiveSession: Boolean,
            seasonYear: Int
        ): Intent {
            val intent = Intent(context, ChannelPlaybackActivity::class.java)
            // Pass IDs needed to *fetch* viewing details initially
            ChannelPlaybackFragment.putChannelId(intent, channelId)
            ChannelPlaybackFragment.putContentId(intent, contentId)
            ChannelPlaybackFragment.putSessionId(intent, sessionId)
            ChannelPlaybackFragment.putIsLiveSession(intent, isLiveSession)
            ChannelPlaybackFragment.putSeasonYear(intent, seasonYear)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // CRITICAL FIX: The Android TV UI runs at 1080p by default. If the physical HDMI output
        // is 1080p, the MediaTek secure hardware composer cannot scale the 4K secure DRM buffer
        // down to 1080p, and instead outputs a green screen. 
        // We MUST force the display mode to 4K (or the maximum available) to match the official app.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val displayManager = getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            if (display != null) {
                val modes = display.supportedModes
            var bestMode = display.mode
            for (mode in modes) {
                // Prefer higher resolution, then higher refresh rate
                if (mode.physicalWidth > bestMode.physicalWidth || 
                   (mode.physicalWidth == bestMode.physicalWidth && mode.refreshRate > bestMode.refreshRate)) {
                    bestMode = mode
                }
            }
            if (bestMode.modeId != display.mode.modeId) {
                val layoutParams = window.attributes
                layoutParams.preferredDisplayModeId = bestMode.modeId
                window.attributes = layoutParams
                android.util.Log.i("ChannelPlaybackActivity", "Forcing preferred display mode to ${bestMode.physicalWidth}x${bestMode.physicalHeight}@${bestMode.refreshRate}Hz (modeId=${bestMode.modeId})")
            }
        }
    }

        lifecycleScope.launch {
            if (STREAM_VARIANT_PROBE) {
                runStreamVariantProbe()
            } else {
                attachViewingIfNeeded(Settings.StreamType.DASH, preferHdrManifest = preferHdrManifestForDevice)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG) as? ChannelPlaybackFragment)
            ?.onHostConfigurationChanged()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val playbackFragment = activePlaybackFragment()
        if (playbackFragment?.shouldHandleTouchOverlayGesture() == true &&
            playbackTouchGestureDetector.onTouchEvent(event)
        ) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onSwitchChannel(channel: Channel) {
        val sessionId = ChannelPlaybackFragment.findSessionId(this) ?: return
        startActivity(
            intent(
                this,
                sessionId,
                channel.id?.value,
                channel.contentId,
                ChannelPlaybackFragment.findIsLiveSession(this),
                ChannelPlaybackFragment.findSeasonYear(this)
            )
        )
        finish()
    }

    private fun allowsUhdPlaybackForSeason(): Boolean {
        return ChannelPlaybackFragment.findSeasonYear(this) >= 2026
    }

    private suspend fun attachViewingIfNeeded(
        streamType: Settings.StreamType,
        preferHdrManifest: Boolean = true
    ) {
        if (supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG) != null) {
            Log.d(TAG, "Playback fragment already exists — skipping fetch")
            return
        }
        val contentId = ChannelPlaybackFragment.findContentId(this) ?: return finish()
        val channelId = resolvePreferredChannelId(
            contentId = contentId,
            requestedChannelId = ChannelPlaybackFragment.findChannelId(this)
        )
        Log.i(
            TAG,
            "Fetching viewing for contentId=$contentId channelId=$channelId " +
                "sessionId=${ChannelPlaybackFragment.findSessionId(this)} " +
                "isLiveSession=${ChannelPlaybackFragment.findIsLiveSession(this)} " +
                "seasonYear=${ChannelPlaybackFragment.findSeasonYear(this)} " +
                "preferHdrManifest=$preferHdrManifest"
        )
        try {
            var viewing = viewingService.getViewing(channelId, contentId, streamType, preferHdrManifest)
            Log.i(
                TAG,
                "Main viewing: url=${viewing.url} platform=${viewing.platform} " +
                    "type=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType}"
            )

            // Fetch PRES / F1Live channel for external audio if the user wants it
            // and they are not already watching the PRES channel itself
            val settings = settingsRepository.getCurrent()
            if (needsHdrEmbeddedAudioWorkaround(viewing)) {
                viewing = tryAttachStandardAudioCompanion(viewing, contentId, channelId, streamType)
            }
            if (settings.useExternalAudio) {
                val externalAudioPreferHdr = preferHdrManifest && !viewing.externalAudioRequired
                viewing = tryAttachExternalAudio(viewing, contentId, channelId, streamType, externalAudioPreferHdr)
            }

            onViewingCreated(viewing)
        } catch (e: ViewingService.TokenExpiredException) {
            Log.e(TAG, "Token expired", e)
            handleError(R.string.unable_to_play_video_session_expired) {
                startActivity(SignInActivity.intentClearTask(this))
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching viewing", e)
            handleError(R.string.unable_to_play_video_message, ::finish)
        }
    }

    private suspend fun resolvePreferredChannelId(contentId: String, requestedChannelId: String?): String? {
        if (requestedChannelId != null) {
            return requestedChannelId
        }
        return runCatching {
            f1TvClient.getChannels(contentId)
                .filterIsInstance<F1TvBasicChannel>()
                .firstOrNull { it.type == F1TvBasicChannelType.Wif }
                ?.channelId
        }.onFailure {
            Log.w(TAG, "Failed to resolve preferred International channel: ${it.message}")
        }.getOrNull()
    }

    /**
     * Non-fatal: attempts to find the PRES/F1Live channel and populate [F1TvViewing]
     * with external audio info. Returns the original [viewing] unchanged on any failure.
     */
    private suspend fun tryAttachExternalAudio(
        viewing: F1TvViewing,
        contentId: String,
        currentChannelId: String?,
        streamType: Settings.StreamType,
        preferHdrManifest: Boolean
    ): F1TvViewing = try {
        val channels = f1TvClient.getChannels(contentId)
        val presChannel = channels
            .filterIsInstance<F1TvBasicChannel>()
            .firstOrNull { it.type == F1TvBasicChannelType.F1Live }
        if (presChannel == null) {
            Log.d(TAG, "No PRES/F1Live channel found for contentId=$contentId")
            return viewing
        }
        if (presChannel.channelId == currentChannelId) {
            Log.d(TAG, "Already watching PRES channel — skipping external audio")
            return viewing
        }
        Log.i(TAG, "Fetching PRES audio from channelId=${presChannel.channelId}")
        val presViewing = viewingService.getViewing(
            presChannel.channelId,
            presChannel.contentId,
            streamType,
            preferHdrManifest
        )
        viewing.copy(
            externalAudioUri = presViewing.url,
            externalAudioStreamType = presViewing.streamType,
            externalAudioLaURL = presViewing.laURL,
            externalAudioPlayApiVersion = presViewing.playApiVersion,
            externalAudioEntitlementtoken = presViewing.entitlementtoken,
            externalAudioContentId = presViewing.contentId,
            externalAudioChannelId = presViewing.channelId,
            externalAudioRequired = viewing.externalAudioRequired
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch PRES audio (non-fatal): ${e.message}")
        viewing
    }

    /**
     * F1's current UHD/HDR embedded audio can be unreliable in Media3 on Android TV.
     * Keep the UHD/HDR video and pair it with a same-channel standard audio companion.
     */
    private suspend fun tryAttachStandardAudioCompanion(
        viewing: F1TvViewing,
        contentId: String,
        currentChannelId: String?,
        streamType: Settings.StreamType
    ): F1TvViewing = try {
        // EXPERIMENT (2026-08-22): skip the companion-audio CONTENT/PLAY call.
        //
        // This second PLAY has happened on EVERY test so far -- the earlier
        // "single DRM session" test only changed the media source, not the API
        // calls. F1 issues per-play session tokens, so a second PLAY for the
        // same subscriber can supersede the first server-side, leaving the
        // video's licence scoped to a stale session and yielding a key that
        // decrypts to garbage (= a uniformly zero-filled, green frame).
        // Everything else is now eliminated: our rendering path and the
        // MediaTek decoder play synthetic 4K 10-bit HLG (hvc1 AND hev1)
        // correctly, vanilla Media3 is green too, and L3/non-secure decode is
        // green, so decryption output is the only candidate left.
        if (SKIP_COMPANION_AUDIO_DIAGNOSTIC) {
            Log.i(TAG, "DIAGNOSTIC: skipping companion-audio PLAY call (single CONTENT/PLAY per session)")
            return viewing
        }
        Log.i(
            TAG,
            "Fetching standard same-channel audio companion for UHD/HDR playback " +
                "contentId=$contentId channelId=$currentChannelId"
        )
        val audioViewing = viewingService.getViewing(
            currentChannelId,
            contentId,
            Settings.StreamType.HLS,
            preferHdrManifest = false
        )
        Log.i(
            TAG,
            "Attached standard audio companion for UHD/HDR video-only playback " +
                "url=${audioViewing.url} type=${audioViewing.streamType} laUrl=${audioViewing.laURL} " +
                "dash=${looksLikeDash(audioViewing.streamType, audioViewing.url.toString())}"
        )
        viewing.copy(
            externalAudioUri = audioViewing.url,
            externalAudioStreamType = audioViewing.streamType,
            externalAudioLaURL = audioViewing.laURL,
            externalAudioPlayApiVersion = audioViewing.playApiVersion,
            externalAudioEntitlementtoken = audioViewing.entitlementtoken,
            externalAudioContentId = audioViewing.contentId,
            externalAudioChannelId = audioViewing.channelId,
            externalAudioRequired = true
        )
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch standard audio companion (non-fatal): ${e.message}")
        viewing
    }

    private fun needsHdrEmbeddedAudioWorkaround(viewing: F1TvViewing): Boolean {
        return ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing)
    }

    private fun onViewingCreated(viewing: F1TvViewing) {
        currentViewing = viewing
        currentPlaybackAttempt = PlaybackAttempt.Standard
        hasTriedToneMappedHdr = false
        Log.d(
            TAG,
            "Proceeding to create player with " +
                "contentId=${viewing.contentId} channelId=${viewing.channelId} " +
                "platform=${viewing.platform} playApiVersion=${viewing.playApiVersion} " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                "url=${viewing.url}"
        )
        if (settingsRepository.getCurrent().openWithExternalPlayer) {
            Log.d(TAG, "Opening with external player.")
            openWithExternalPlayer(viewing)
        } else {
            val settings = settingsRepository.getCurrent()
            val isHdrUhdWidevine = ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing)
            if (isHdrUhdWidevine && shouldUseToneMappedHdrFirst(settings)) {
                Log.i(
                    TAG,
                    "Opening UHD/HDR Widevine stream with HDR-to-SDR tone mapping " +
                        "disableHdrOn4kStreams=${settings.disableHdrOn4kStreams}"
                )
                openToneMappedHdrPlayer(viewing, "initial_device_or_setting_policy")
                return
            }
            if (isHdrUhdWidevine) {
                Log.i(
                    TAG,
                    "Opening UHD/HDR Widevine stream with official-like bare secure SurfaceView path"
                )
                openWithOfficialLikeHdrPlayer(viewing)
                return
            }

            val protectedHdrDecision = ProtectedHdrRendererRouter.decide(viewing)
            if (protectedHdrDecision.shouldUseProtectedRenderer) {
                Log.i(TAG, "Opening with protected HDR renderer reason=${protectedHdrDecision.reason}")
                openWithProtectedHdrRenderer(viewing)
                return
            }
            Log.i(
                TAG,
                "Protected HDR renderer unavailable; falling back to Media3 internal player " +
                    "reason=${protectedHdrDecision.reason}"
            )
            Log.d(TAG, "Opening with internal player.")
            openWithInternalPlayer(viewing)
        }
    }

    private fun shouldUseToneMappedHdrFirst(settings: Settings): Boolean {
        return settings.disableHdrOn4kStreams || DeviceInfo.shouldToneMapHdrToSdr(this)
    }

    private enum class PlaybackAttempt {
        Standard,
        NativeHdr,
        ToneMappedHdr,
        ProtectedHlgGraph,
        DirectMedia3Hdr
    }

    private data class ProbeVariant(
        val label: String,
        val api: String,
        val platform: String,
        val player: String,
        val override: String?
    )

    private suspend fun runStreamVariantProbe() {
        val contentId = ChannelPlaybackFragment.findContentId(this) ?: return finish()
        val channelId = resolvePreferredChannelId(
            contentId = contentId,
            requestedChannelId = ChannelPlaybackFragment.findChannelId(this)
        )

        val overrides = listOf(
            "HDR_UHD_DASHWV",
            "HDR_UHD_DASHWV_SINGLE",
            "HDR_UHD_DASH",
            "HDR_UHD_DASH_SINGLE",
            "HDR_UHD_CMAFWV",
            "HDR_UHD_CMAFWV_SINGLE",
            "HDR_UHD_CMAF",
            "SDR_UHD_DASHWV",
            "SDR_UHD_DASHWV_SINGLE",
            "SDR_UHD_DASH"
        )
        val platforms = listOf(
            Triple("3.0", "BIG_SCREEN_HLS", "player_bm"),
            Triple("3.0", "BIG_SCREEN_DASH", "player_bm"),
            Triple("3.0", "WEB_DASH", "player_bm")
        )

        val variants = buildList {
            for ((api, platform, player) in platforms) {
                for (ov in overrides) {
                    add(ProbeVariant("$platform/$ov", api, platform, player, ov))
                }
            }
        }

        Log.i(TAG, "STREAM VARIANT PROBE starting: ${variants.size} variants, " +
            "${PROBE_SECONDS_PER_VARIANT / 1000}s each")

        var index = 0
        for (v in variants) {
            index++
            val banner = "[$index/${variants.size}] ${v.label}"
            Log.i(TAG, "PROBE ===== $banner =====")
            val viewing = try {
                viewingService.probeViewingVariant(
                    channelId, contentId, v.api, v.platform, v.player, v.override
                )
            } catch (e: Exception) {
                Log.w(TAG, "PROBE $banner fetch failed: ${e.message}")
                null
            }
            if (viewing == null) {
                Log.i(TAG, "PROBE $banner -> UNAVAILABLE, skipping")
                continue
            }
            Log.i(
                TAG,
                "PROBE $banner -> PLAYING streamType=${viewing.streamType} " +
                    "laUrlPresent=${!viewing.laURL.isNullOrBlank()} url=${viewing.url}"
            )
            android.widget.Toast.makeText(
                this,
                "$banner — ${viewing.streamType}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            currentViewing = viewing
            openWithOfficialLikeHdrPlayer(viewing)
            kotlinx.coroutines.delay(PROBE_SECONDS_PER_VARIANT)
        }
        Log.i(TAG, "STREAM VARIANT PROBE complete")
    }

    private fun openWithOfficialLikeHdrPlayer(viewing: F1TvViewing) {
        currentPlaybackAttempt = PlaybackAttempt.NativeHdr
        Log.i(
            TAG,
            "Committing official-like bare secure HDR player " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType}"
        )
        isSwappingPlaybackFragment = true
        supportFragmentManager.commit {
            replace(
                R.id.fragment_container,
                OfficialLikeHdrPlaybackFragment.newInstance(viewing),
                ChannelPlaybackFragment.TAG
            )
            setReorderingAllowed(true)
            runOnCommit { isSwappingPlaybackFragment = false }
        }
    }

    private fun openToneMappedHdrPlayer(viewing: F1TvViewing, reason: String) {
        if (!DeviceInfo.supportsHdrToSdrToneMapping()) {
            Log.w(TAG, "HDR-to-SDR tone mapping unavailable; falling back to standard SDR reason=$reason")
            fallbackToStandardSdr("tone_mapping_unavailable:$reason")
            return
        }

        hasTriedToneMappedHdr = true
        Log.i(
            TAG,
            "Committing tone-mapped UHD/HDR player reason=$reason " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType}"
        )
        openWithInternalPlayer(
            viewing = viewing,
            forceDirectMedia3HdrSurface = true,
            forceHdrToSdrToneMapping = true,
            playbackAttempt = PlaybackAttempt.ToneMappedHdr
        )
    }

    private fun activePlaybackFragment(): ChannelPlaybackFragment? {
        return supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG) as? ChannelPlaybackFragment
    }

    internal fun isInternalPlaybackFragmentSwapInProgress(): Boolean {
        return isSwappingPlaybackFragment
    }

    private fun openWithExternalPlayer(viewing: F1TvViewing) {
        isSwappingPlaybackFragment = true
        supportFragmentManager.commit {
            replace(R.id.fragment_container, OpenedWithExternalPlayerFragment(), ChannelPlaybackFragment.TAG)
            setReorderingAllowed(true)
            runOnCommit { isSwappingPlaybackFragment = false }
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(viewing.url, "video/*") // Use the final URL
            Log.i(TAG, "Starting external player intent for URL: ${viewing.url}")
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No external player found.", e)
            handleError(R.string.unable_to_open_with_external_player, ::finish)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening external player", e)
            handleError(R.string.unable_to_open_with_external_player, ::finish)
        }
    }

    private fun openWithInternalPlayer(
        viewing: F1TvViewing,
        forceDirectMedia3HdrSurface: Boolean = false,
        usesProtectedHlgGraph: Boolean = false,
        forceProtectedHlgGraph: Boolean = false,
        forceHdrToSdrToneMapping: Boolean = false,
        playbackAttempt: PlaybackAttempt = PlaybackAttempt.Standard
    ) {
        currentPlaybackAttempt = playbackAttempt
        Log.d(
            TAG,
            "Committing internal player fragment " +
                "forceDirectMedia3HdrSurface=$forceDirectMedia3HdrSurface " +
                "usesProtectedHlgGraph=$usesProtectedHlgGraph " +
                "forceProtectedHlgGraph=$forceProtectedHlgGraph " +
                "forceHdrToSdrToneMapping=$forceHdrToSdrToneMapping " +
                "playbackAttempt=$playbackAttempt"
        )
        isSwappingPlaybackFragment = true
        supportFragmentManager.commit {
            replace(
                R.id.fragment_container,
                ChannelPlaybackFragment.newInstance(
                    viewing,
                    forceDirectMedia3HdrSurface = forceDirectMedia3HdrSurface,
                    forceProtectedHlgGraph = forceProtectedHlgGraph,
                    forceHdrToSdrToneMapping = forceHdrToSdrToneMapping
                ),
                ChannelPlaybackFragment.TAG
            )
            setReorderingAllowed(true)
            runOnCommit { isSwappingPlaybackFragment = false }
        }
    }

    private fun openWithProtectedHdrRenderer(viewing: F1TvViewing) {
        Log.i(
            TAG,
            "Committing Media3 protected HLG graph renderer " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType}"
        )
        openWithInternalPlayer(
            viewing = viewing,
            usesProtectedHlgGraph = true,
            forceProtectedHlgGraph = true,
            playbackAttempt = PlaybackAttempt.ProtectedHlgGraph
        )
    }

    fun hdrPresentationFailed(reason: String) {
        runOnUiThread {
            val failedViewing = currentViewing ?: return@runOnUiThread
            if (!ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(failedViewing)) {
                Log.i(TAG, "Ignoring HDR presentation failure for non-HDR stream reason=$reason")
                return@runOnUiThread
            }
            if (currentPlaybackAttempt != PlaybackAttempt.NativeHdr) {
                Log.i(
                    TAG,
                    "Ignoring HDR presentation failure for attempt=$currentPlaybackAttempt reason=$reason"
                )
                return@runOnUiThread
            }

            Log.w(
                TAG,
                "Native UHD/HDR presentation looks bad; trying fallback ladder reason=$reason"
            )
            if (!hasTriedToneMappedHdr && DeviceInfo.shouldTryHdrToSdrToneMappingPlayback(this)) {
                openToneMappedHdrPlayer(failedViewing, "native_hdr_presentation_failed:$reason")
            } else {
                fallbackToStandardSdr("native_hdr_presentation_failed:$reason")
            }
        }
    }

    private fun handleError(@StringRes errorMessage: Int, cancelAction: () -> Unit) {
        // Ensure this runs on the main thread
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                try {
                    // Use AppCompat AlertDialog
                    AlertDialog.Builder(this)
                        .setMessage(errorMessage)
                        .setPositiveButton(android.R.string.ok) { dialog, _ ->
                            dialog.dismiss()
                            cancelAction.invoke()
                        }
                        .setCancelable(false) // Prevent dismissal on back press during error
                        .show()
                } catch (e: Exception) {
                    // Catch potential exceptions during dialog show (like theme issues)
                    Log.e(TAG, "Error showing AlertDialog", e)
                    // Fallback or just finish
                    cancelAction.invoke()
                }
            } else {
                Log.w(TAG, "Activity finishing, not showing error dialog.")
            }
        }
    }

    /**
     * Called by [ChannelPlaybackFragment] when ExoPlayer reports an unrecoverable error.
     */
    fun playerError() {
        if (STREAM_VARIANT_PROBE) {
            // During the variant probe, a failing variant must not trigger the
            // normal fallback/finish chain -- otherwise the first bad variant
            // tears down the Activity and the probe stops early.
            Log.w(TAG, "PROBE: ignoring player error so the probe can continue to the next variant")
            return
        }
        val failedViewing = currentViewing
        val triedHdrManifest = failedViewing?.let {
            ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(it) ||
                looksLikeUhdOrHdr(it.streamType) ||
                looksLikeUhdOrHdr(it.requestedOverrideStreamType)
        } == true

        Log.e(
            TAG,
            "Player error. " +
                "contentId=${failedViewing?.contentId} " +
                "channelId=${failedViewing?.channelId} " +
                "platform=${failedViewing?.platform} " +
                "streamType=${failedViewing?.streamType} " +
                "requestedOverride=${failedViewing?.requestedOverrideStreamType} " +
                "playApiVersion=${failedViewing?.playApiVersion} " +
                "laUrl=${failedViewing?.laURL} " +
                "isLiveSession=${ChannelPlaybackFragment.findIsLiveSession(this)} " +
                "seasonYear=${ChannelPlaybackFragment.findSeasonYear(this)} " +
                "playbackAttempt=$currentPlaybackAttempt " +
                "triedHdrManifest=$triedHdrManifest"
        )

        if (
            failedViewing != null &&
            triedHdrManifest &&
            currentPlaybackAttempt != PlaybackAttempt.ToneMappedHdr &&
            !hasTriedToneMappedHdr &&
            DeviceInfo.shouldTryHdrToSdrToneMappingPlayback(this) &&
            !isFinishing &&
            !isDestroyed
        ) {
            Log.i(
                TAG,
                "HDR/UHD attempt failed - retrying same UHD manifest with HDR-to-SDR tone mapping"
            )
            openToneMappedHdrPlayer(failedViewing, "player_error:$currentPlaybackAttempt")
            return
        }

        if (
            failedViewing != null &&
            triedHdrManifest &&
            currentPlaybackAttempt == PlaybackAttempt.ProtectedHlgGraph &&
            !isFinishing &&
            !isDestroyed
        ) {
            Log.i(
                TAG,
                "HDR/UHD protected HLG graph failed - retrying same HDR manifest with direct Media3 surface fallback"
            )
            openWithInternalPlayer(
                failedViewing,
                forceDirectMedia3HdrSurface = true,
                playbackAttempt = PlaybackAttempt.DirectMedia3Hdr
            )
            return
        }

        if (triedHdrManifest && !isFinishing && !isDestroyed) {
            fallbackToStandardSdr("player_error:$currentPlaybackAttempt")
            return
        }

        handleError(R.string.unable_to_play_video_message, ::finish)
    }

    private fun fallbackToStandardSdr(reason: String) {
        currentViewing = null
        currentPlaybackAttempt = PlaybackAttempt.Standard
        Log.i(TAG, "Retrying with standard non-UHD HLS/SDR stream reason=$reason")
        val fragment = supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG)
        if (fragment != null) {
            isSwappingPlaybackFragment = true
            supportFragmentManager.commit {
                remove(fragment)
                runOnCommit {
                    isSwappingPlaybackFragment = false
                    lifecycleScope.launch {
                        attachViewingIfNeeded(Settings.StreamType.HLS, preferHdrManifest = false)
                    }
                }
            }
        } else {
            lifecycleScope.launch {
                attachViewingIfNeeded(Settings.StreamType.HLS, preferHdrManifest = false)
            }
        }
    }

    private fun looksLikeDash(streamType: String?, url: String): Boolean {
        return streamType?.contains("DASH", ignoreCase = true) == true ||
            url.contains(".mpd", ignoreCase = true)
    }

    private fun looksLikeUhdOrHdr(streamType: String?): Boolean {
        val normalized = streamType?.uppercase() ?: return false
        return "UHD" in normalized || "2160" in normalized || "HDR" in normalized
    }
}
