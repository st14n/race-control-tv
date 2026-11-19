package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.SurfaceView
import android.widget.Toast
import androidx.annotation.Keep
import androidx.fragment.app.DialogFragment
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.ui.player.ExoPlayerPlaybackTransportControlGlue
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHdrStreamClassifier
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHdrCapabilitiesProbe
import javax.inject.Inject
import kotlin.math.abs
import java.util.Locale

@Keep
@AndroidEntryPoint
class ChannelPlaybackFragment : VideoSupportFragment(), Player.Listener {

    companion object {
        internal val TAG = ChannelPlaybackFragment::class.simpleName

        private const val UHD_WIDTH = 3840
        private const val UHD_HEIGHT = 2160
        private const val OVERLAY_AUTO_CLOSE_DELAY_MS = 10_000L
        private const val UHD_STARTUP_BUFFERING_TIMEOUT_MS = 12_000L
        private const val UHD_TONE_MAPPING_FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val UHD_HDR_PRESENTATION_FRAME_RATE = 50f
        private const val DISPLAY_MODE_REFRESH_TOLERANCE = 0.25f

        private const val ARG_VIEWING = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_VIEWING"
        private const val ARG_SESSION_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SESSION_ID"
        private const val ARG_CONTENT_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CONTENT_ID"
        private const val ARG_CHANNEL_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CHANNEL_ID"
        private const val ARG_IS_LIVE_SESSION = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_IS_LIVE_SESSION"
        private const val ARG_SEASON_YEAR = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SEASON_YEAR"
        private const val ARG_START_POSITION_MS = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_START_POSITION_MS"
        // DIAGNOSTIC (2026-08-23): see createMediaSource. Off by default.
        private const val FORCE_WIDEVINE_L3_VALIDATION_DIAGNOSTIC = false
        // DIAGNOSTIC (2026-10-11): route the standard (SDR / UHD DASH) path through
        // KeyStatusLoggingDrmSessionManagerProvider so the Widevine app_info is rewritten
        // to the official F1 TV identity AND the raw license request/response (including
        // the granted security_level) is dumped to logcat. This fragment is the one the
        // emulator actually plays (SDR fallback / tone-mapped HDR), so the HDR-only
        // logging path never fires here without this. Off by default.
        // Dumps every licence exchange to the log: diagnostics only
        private val LOG_KEY_RESPONSE_STANDARD_PATH = fr.groggy.racecontrol.tv.BuildConfig.DEBUG
        private const val ARG_FORCE_DIRECT_MEDIA3_HDR_SURFACE =
            "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_FORCE_DIRECT_MEDIA3_HDR_SURFACE"
        private const val ARG_FORCE_PROTECTED_HLG_GRAPH =
            "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_FORCE_PROTECTED_HLG_GRAPH"
        private const val ARG_FORCE_HDR_TO_SDR_TONE_MAPPING =
            "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_FORCE_HDR_TO_SDR_TONE_MAPPING"

        fun putSessionId(intent: Intent, sessionId: String) = intent.putExtra(ARG_SESSION_ID, sessionId)
        fun putChannelId(intent: Intent, channelId: String?) = intent.putExtra(ARG_CHANNEL_ID, channelId)
        fun putContentId(intent: Intent, contentId: String) = intent.putExtra(ARG_CONTENT_ID, contentId)
        fun putIsLiveSession(intent: Intent, isLiveSession: Boolean) = intent.putExtra(ARG_IS_LIVE_SESSION, isLiveSession)
        fun putSeasonYear(intent: Intent, seasonYear: Int) = intent.putExtra(ARG_SEASON_YEAR, seasonYear)
        /** Where to start a replay (switching feed keeps the position); 0 = from the start / live edge. */
        fun putStartPositionMs(intent: Intent, positionMs: Long) = intent.putExtra(ARG_START_POSITION_MS, positionMs)
        fun findStartPositionMs(activity: Activity): Long = activity.intent.getLongExtra(ARG_START_POSITION_MS, 0L)

        fun findChannelId(activity: Activity): String? = activity.intent.getStringExtra(ARG_CHANNEL_ID)
        fun findContentId(activity: Activity): String? = activity.intent.getStringExtra(ARG_CONTENT_ID)
        fun findSessionId(activity: Activity): String? = activity.intent.getStringExtra(ARG_SESSION_ID)
        fun findIsLiveSession(activity: Activity): Boolean = activity.intent.getBooleanExtra(ARG_IS_LIVE_SESSION, false)
        fun findSeasonYear(activity: Activity): Int = activity.intent.getIntExtra(ARG_SEASON_YEAR, org.threeten.bp.Year.now().value)

        fun findViewing(fragment: ChannelPlaybackFragment): F1TvViewing? {
            val args = fragment.arguments ?: return null
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getParcelable(ARG_VIEWING, F1TvViewing::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getParcelable(ARG_VIEWING)
            }
        }

        fun newInstance(
            viewing: F1TvViewing,
            forceDirectMedia3HdrSurface: Boolean = false,
            forceProtectedHlgGraph: Boolean = false,
            forceHdrToSdrToneMapping: Boolean = false
        ) = ChannelPlaybackFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_VIEWING, viewing)
                putBoolean(ARG_FORCE_DIRECT_MEDIA3_HDR_SURFACE, forceDirectMedia3HdrSurface)
                putBoolean(ARG_FORCE_PROTECTED_HLG_GRAPH, forceProtectedHlgGraph)
                putBoolean(ARG_FORCE_HDR_TO_SDR_TONE_MAPPING, forceHdrToSdrToneMapping)
            }
        }
    }

    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory
    @Inject internal lateinit var settingsRepository: SettingsRepository
    @Inject internal lateinit var watchProgressStore: fr.groggy.racecontrol.tv.core.progress.WatchProgressStore

    /** For switching feed in a replay: where the picture is now, or null for live / unknown. */
    internal fun positionToKeepOnFeedSwitchMs(): Long? {
        val p = _player ?: return null
        if (p.isCurrentMediaItemLive || p.currentPosition <= 0L) return null
        return p.currentPosition
    }

    private var playbackGlue: ExoPlayerPlaybackTransportControlGlue? = null
    /** The audio track the viewer picked in the audio menu, if any. */
    private var userAudioOverride: androidx.media3.common.TrackSelectionOverride? = null
    private val customRadio by lazy {
        CustomRadioController(
            fragment = this,
            settingsRepository = settingsRepository,
            host = object : CustomRadioHost {
                override val mainPlayer: ExoPlayer? get() = _player
                override fun setMainAudioMuted(muted: Boolean, reason: String) {
                    // Volume rather than disabling the audio track, so the F1 audio keeps being decoded
                    // for acoustic sync
                    _player?.volume = if (muted) 0f else 1f
                    Log.i(TAG, "Main player audio muted=$muted reason=$reason")
                }
                override val mainAudioFeatures: AcousticFeatureStream get() = this@ChannelPlaybackFragment.mainAudioFeatures
                override fun setAmbientAudioPreferred(preferred: Boolean): Boolean {
                    val player = _player ?: return false
                    val result = player.selectAmbientAudioTrack(preferred)
                    if (!preferred) {
                        // Clearing the FX override also cleared the viewer's own pick: put it back
                        userAudioOverride?.let {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setOverrideForType(it).build()
                        }
                    }
                    return result
                }
                override fun refreshRadioOverlay() {
                    playbackGlue?.refreshSubtitle()
                }
                override fun onRadioDialogInteraction() = resetOverlayAutoCloseTimer()
                override fun onRadioDialogDismissed() {
                    playbackGlue?.notifyMenuDismissed()
                }
            },
            tag = TAG
        )
    }
    private var currentViewing: F1TvViewing? = null
    private var hasRenderedFirstFrameForCurrentSource = false
    private var startupBufferingWatchdogRunnable: Runnable? = null
    private var toneMappingFirstFrameWatchdogRunnable: Runnable? = null
    private var suppressOverlayReopenUntilElapsedMs: Long = 0L
    private var lastVideoHostWidth: Int = 0
    private var lastVideoHostHeight: Int = 0
    private var hasStartedPlayer = false
    /** Where a replay was when its surface went away (-1: nothing to resume). */
    private var resumeAfterSurfaceLossMs = -1L
    private var intentStartPositionUsed = false
    private var boundPlaybackSurfaceView: SurfaceView? = null
    private var boundPlaybackSurfaceHolder: SurfaceHolder? = null
    private var boundDirectSurfaceView: SurfaceView? = null
    private var playbackSurfaceBufferWidth: Int = 0
    private var playbackSurfaceBufferHeight: Int = 0
    private var hasProbedProtectedHlgEglSurface: Boolean = false
    // Track the SurfaceView already bound to the PlaybackVideoGraphWrapper output.
    // setVideoSurfaceView() must NOT be called repeatedly with the same view — each call
    // triggers setOutputSurfaceInfo() inside the graph, corrupting the EGL context/surface
    // binding mid-stream and causing EGL_BAD_MATCH (0x3009) on the second rendered frame.
    private var boundHlgGraphSurfaceView: SurfaceView? = null
    private val overlayAutoCloseHandler = Handler(Looper.getMainLooper())
    private val overlayAutoCloseRunnable = Runnable {
        if (!isAdded) return@Runnable
        customRadio.dismissSyncDialog()
        if (isControlsOverlayVisible()) {
            hideControlsOverlay(true)
        }
    }
    private val videoHostLayoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        val newWidth = right - left
        val newHeight = bottom - top
        val oldWidth = oldRight - oldLeft
        val oldHeight = oldBottom - oldTop
        if (newWidth <= 0 || newHeight <= 0) return@OnLayoutChangeListener
        if (newWidth == oldWidth && newHeight == oldHeight) return@OnLayoutChangeListener
        if (newWidth == lastVideoHostWidth && newHeight == lastVideoHostHeight) return@OnLayoutChangeListener
        lastVideoHostWidth = newWidth
        lastVideoHostHeight = newHeight
        Log.d(TAG, "Video host resized to ${newWidth}x${newHeight}; updating surface")
        val activePlayer = _player ?: return@OnLayoutChangeListener
        updateVideoSurfaceSize(activePlayer.videoSize.width, activePlayer.videoSize.height)
    }
    private val playbackSurfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val surfaceView = boundPlaybackSurfaceView ?: return
            Log.i(TAG, "Playback SurfaceHolder surfaceCreated view=${System.identityHashCode(surfaceView)}")
            HdrPresentationDiagnostics.logDisplaySnapshot(requireContext(), "channel-surfaceCreated")
            bindPlaybackVideoSurface(surfaceView, "surfaceCreated")
            
            if (!hasStartedPlayer) {
                Log.i(TAG, "Surface created, now safe to start player and evaluate EGL capabilities.")
                hasStartedPlayer = true
                startPlayer()
            } else if (_player == null && isResumed) {
                // The surface went away while playing (the player had to be released with it): start
                // again, where the picture was
                Log.i(TAG, "Surface recreated while playing: restarting the player at ${resumeAfterSurfaceLossMs}ms")
                startPlayer()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            val surfaceView = boundPlaybackSurfaceView ?: return
            Log.i(
                TAG,
                "Playback SurfaceHolder surfaceChanged " +
                    "view=${System.identityHashCode(surfaceView)} format=$format size=${width}x${height}"
            )
            HdrPresentationDiagnostics.logDisplaySnapshot(requireContext(), "channel-surfaceChanged")
            bindPlaybackVideoSurface(surfaceView, "surfaceChanged")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "Playback SurfaceHolder surfaceDestroyed - Explicitly releasing player to avoid DummySurface codec crash")
            releasePlayerSafely("surfaceDestroyed")
        }
    }

    private val trackSelector: DefaultTrackSelector by lazy {
        DefaultTrackSelector(requireContext()).apply {
            setParameters(buildUponParameters().applyPlaybackVideoConstraints().build())
        }
    }

    private fun buildPlaybackTrackParameters(audioDisabled: Boolean = false) =
        trackSelector.buildUponParameters()
            .applyPlaybackVideoConstraints(audioDisabled = audioDisabled)
            .build()

    private fun DefaultTrackSelector.Parameters.Builder.applyPlaybackVideoConstraints(
        audioDisabled: Boolean = false
    ) = apply {
            val isLegacySeason = findSeasonYear(requireActivity()) <= 2025
            setMaxVideoSize(
                if (isLegacySeason) 1920 else Int.MAX_VALUE,
                if (isLegacySeason) 1080 else Int.MAX_VALUE
            )
            setMaxVideoBitrate(Int.MAX_VALUE)
            setForceHighestSupportedBitrate(true)
            setPreferredAudioLanguage(null)
            // F1 Live's 4K stream offers each language as AAC stereo and as AC-3 5.1; Media3 picks the most
            // channels. That AC-3 carries dialnorm -23, so every decoder (TV, soundbar, the device) plays it
            // 8 dB quieter, with heavy compression: F1 Live sounded dull next to International and the radio.
            // Start with the AAC; the 5.1 stays in the audio menu.
            setPreferredAudioMimeTypes(androidx.media3.common.MimeTypes.AUDIO_AAC)
            setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioDisabled)
        }

    private fun setMainPlayerAudioDisabled(disabled: Boolean, reason: String) {
        trackSelector.setParameters(buildPlaybackTrackParameters(audioDisabled = disabled))
        Log.i(TAG, "Main player audio disabled=$disabled while preserving UHD/HDR video selector reason=$reason")
    }

    private val protectedHdrCapabilities by lazy {
        ProtectedHdrCapabilitiesProbe.probe()
    }

    private fun isForceDirectMedia3HdrSurface(): Boolean {
        return arguments?.getBoolean(ARG_FORCE_DIRECT_MEDIA3_HDR_SURFACE, false) == true
    }

    private fun isForceProtectedHlgGraph(): Boolean {
        return arguments?.getBoolean(ARG_FORCE_PROTECTED_HLG_GRAPH, false) == true
    }

    private fun isForceHdrToSdrToneMapping(): Boolean {
        return arguments?.getBoolean(ARG_FORCE_HDR_TO_SDR_TONE_MAPPING, false) == true
    }

    private fun shouldUseHdrToSdrToneMapping(viewing: F1TvViewing? = currentViewing ?: findViewing(this)): Boolean {
        return isForceHdrToSdrToneMapping() || toneMapsForSetting(viewing)
    }

    /**
     * "Disable HDR": a 4K HDR stream (Widevine, or the unencrypted 4K stream's 2160p HLG ladder) is
     * tone-mapped to SDR at full resolution. A normal stream is SDR already and plays as it is: forcing
     * the tone-mapping renderer on it made playback fail and return to the home screen.
     */
    private fun toneMapsForSetting(viewing: F1TvViewing?): Boolean =
        settingsRepository.getCurrent().disableHdrOn4kStreams && viewing != null &&
            (looksLikeHdrUhdWidevine(viewing) || isClearUhd(viewing))

    private fun isClearUhd(viewing: F1TvViewing): Boolean =
        viewing.streamType.orEmpty().contains("UHD", ignoreCase = true) && !looksLikeHdrUhdWidevine(viewing)

    private fun shouldUseProtectedHlgGraph(viewing: F1TvViewing? = currentViewing ?: findViewing(this)): Boolean {
        return !isForceDirectMedia3HdrSurface() &&
            !shouldUseHdrToSdrToneMapping(viewing) &&
            viewing?.let { looksLikeHdrUhdWidevine(it) } == true &&
            (isForceProtectedHlgGraph() || protectedHdrCapabilities.canCreateProtectedHlgEglSurface)
    }



    /** Fingerprint of the decoded F1 audio, for radio acoustic sync. */
    private val mainAudioFeatures = AcousticFeatureStream(withSide = true)

    /** A radio stream is configured and allowed for this session: its sync needs the F1 audio decoded. */
    private fun radioMayPlay(settings: Settings): Boolean =
        (settings.customRadioUrl.isNotBlank() || CustomRadioSources.defaultCandidate != null) &&
            customRadio.isSelectable(settings)

    private val renderersFactory: RenderersFactory by lazy {
        val settings = settingsRepository.getCurrent()
        val viewing = findViewing(this)
        val enableHdrToSdrToneMapping = shouldUseHdrToSdrToneMapping(viewing)
        val enableProtectedHlgVideoGraph = shouldUseProtectedHlgGraph(viewing) && !enableHdrToSdrToneMapping
        val enableOfficialLikeDirectHdrCodecConfig = false
        // The clean-room raw MediaCodec renderer models ClearVR's explicit
        // header/access-unit bridge, but it must not replace Media3's HLS
        // renderer: the TME CMAF route is already demuxed by Media3's HLS
        // extractor and its format carries no separate header buffer. Keep
        // the experimental bridge for DASH, where the F1 init-segment repair
        // path can provide the same kind of initialization boundary.
        val enableTiledmediaLikeCodecPath = viewing?.let {
            looksLikeHdrUhdWidevine(it) && (
                it.streamType?.contains("DASH", ignoreCase = true) == true ||
                    it.url.toString().contains(".mpd", ignoreCase = true)
                )
        } == true
        Log.i(
            TAG,
            "Media3 renderer factory protectedHlgGraph=$enableProtectedHlgVideoGraph " +
                "enableHdrToSdrToneMapping=$enableHdrToSdrToneMapping " +
                "officialLikeDirectHdrCodecConfig=$enableOfficialLikeDirectHdrCodecConfig " +
                "tiledmediaLikeCodecPath=$enableTiledmediaLikeCodecPath " +
                "forceDirectMedia3HdrSurface=${isForceDirectMedia3HdrSurface()} " +
                "forceProtectedHlgGraph=${isForceProtectedHlgGraph()} " +
                "forceHdrToSdrToneMapping=${isForceHdrToSdrToneMapping()} " +
                "streamType=${viewing?.streamType} requestedOverride=${viewing?.requestedOverrideStreamType}"
        )
        HdrToneMappingRenderersFactory(
            requireContext(),
            enableHdrToSdrToneMapping = enableHdrToSdrToneMapping,
            enableProtectedHlgVideoGraph = enableProtectedHlgVideoGraph,
            enableOfficialLikeDirectHdrCodecConfig = enableOfficialLikeDirectHdrCodecConfig,
            enableTiledmediaLikeCodecPath = enableTiledmediaLikeCodecPath,
            // DIAGNOSTIC (2026-08-22): log CryptoInfo here too, so one session
            // captures BOTH the failing HDR attempt and the working SDR
            // fallback and their encryption layouts can be compared directly.
            enableCryptoDiagnostics = false,
            // The fingerprints (two FFTs every 20 ms) are only for the radio's acoustic sync
            audioBufferSink = if (radioMayPlay(settings)) mainAudioFeatures else null,
            decodeAudioToPcm = radioMayPlay(settings),
            toneMapProtectedContent = viewing?.let { !isClearUhd(it) } ?: true
        )
    }

    private var _player: ExoPlayer? = null
    private val player: ExoPlayer
        get() {
            if (_player == null) {
                Log.d(TAG, "Initializing ExoPlayer (Media3)")
                _player = ExoPlayer.Builder(requireContext(), renderersFactory)
                    .setTrackSelector(trackSelector)
                    .build().also { p ->
                        // Fall back to default frame rate strategy since we manually set the display mode in ChannelPlaybackActivity.
                        p.playWhenReady = true
                        p.addAnalyticsListener(EventLogger())
                        p.addAnalyticsListener(object : AnalyticsListener {
                            override fun onRenderedFirstFrame(
                                eventTime: AnalyticsListener.EventTime,
                                output: Any,
                                renderTimeMs: Long
                            ) {
                                hasRenderedFirstFrameForCurrentSource = true
                                cancelToneMappingFirstFrameWatchdog()
                                HdrPresentationDiagnostics.logDisplaySnapshot(
                                    requireContext(),
                                    "channel-renderedFirstFrame"
                                )
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
            return _player!!
        }

    /** Where a replay was left, for "Continue watching" on the home screen (live streams are not kept). */
    private fun recordWatchProgress(player: ExoPlayer) {
        runCatching {
            if (player.isCurrentMediaItemLive || player.currentMediaItem == null) return
            val activity = activity ?: return
            val sessionId = findSessionId(activity) ?: return
            val contentId = findContentId(activity) ?: return
            watchProgressStore.record(
                sessionId = sessionId,
                contentId = contentId,
                channelId = findChannelId(activity),
                feedLabel = (currentViewing ?: findViewing(this))?.feedLabel,
                positionMs = player.currentPosition,
                durationMs = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: return
            )
        }.onFailure { Log.w(TAG, "Unable to record watch progress", it) }
    }

    private fun releasePlayerSafely(source: String) {
        cancelStartupBufferingWatchdog()
        cancelToneMappingFirstFrameWatchdog()
        val playerToRelease = _player ?: run {
            Log.i(TAG, "releasePlayerSafely skipped; player already null source=$source")
            return
        }
        if (source == "surfaceDestroyed" && !playerToRelease.isCurrentMediaItemLive && playerToRelease.currentPosition > 0) {
            resumeAfterSurfaceLossMs = playerToRelease.currentPosition
        }
        recordWatchProgress(playerToRelease)
        _player = null
        boundHlgGraphSurfaceView = null
        // A new player must be bound to the surface again
        boundDirectSurfaceView = null
        playerToRelease.removeListener(this)
        runCatching {
            playerToRelease.clearVideoSurface()
        }.onFailure {
            Log.w(TAG, "Unable to clear video surface before release source=$source", it)
        }
        runCatching {
            playerToRelease.release()
            Log.i(TAG, "Released ExoPlayer source=$source")
        }.onFailure {
            Log.w(TAG, "ExoPlayer release failed source=$source", it)
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
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return super.onCreateView(inflater, container, savedInstanceState)?.also {
            configurePlaybackVideoSurface(it, "onCreateView")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configurePlaybackVideoSurface(view, "onViewCreated")?.let {
            bindPlaybackVideoSurface(it, "onViewCreated")
        }
        lastVideoHostWidth = view.width
        lastVideoHostHeight = view.height
        view.addOnLayoutChangeListener(videoHostLayoutChangeListener)
        // We defer startPlayer() until surfaceCreated() so that we have a valid Window Surface
        // to probe for EGL Widevine capabilities before ExoPlayer is lazily instantiated.
        view.post {
            // Need to handle player size if it somehow already exists, but we removed startPlayer here.
            _player?.let { updateVideoSurfaceSize(it.videoSize.width, it.videoSize.height) }
        }
    }

    private fun startPlayer() {
        Log.d(TAG, "startPlayer")
        try {
            val glue = ExoPlayerPlaybackTransportControlGlue(
                requireActivity(), player, trackSelector,
                onCustomRadioRequested = customRadio::inject,
                onCustomRadioSyncRequested = customRadio::showSyncDialog,
                onAudioTrackSelected = customRadio::stop,
                onPlayRequested = ::resumePlaybackFromTransportControls,
                onPauseRequested = ::pausePlaybackFromTransportControls,
                isCustomRadioSelectable = { customRadio.isSelectable() },
                isCustomRadioActive = { customRadio.isActive },
                currentAudioLabelOverride = {
                    if (customRadio.isActive) customRadio.overlayLabel() else null
                },
                onCustomRadioOffsetAdjust = customRadio::adjust,
                radioSyncStatus = { if (customRadio.isActive) customRadio.syncStatus() else null },
                onControlsInteraction = ::resetOverlayAutoCloseTimer,
                onPlayerMenuDismissed = ::hidePlayerMenusAndControls,
                feedLabels = {
                    val v = currentViewing ?: findViewing(this)
                    val a = v?.feedLabel
                    val b = v?.companionFeedLabel
                    if (v != null && v.externalAudioIsCompanion && a != null && b != null) a to b else null
                },
                onAudioOverrideChosen = { userAudioOverride = it },
                feedName = { (currentViewing ?: findViewing(this))?.feedLabel }
            )
            playbackGlue = glue
            glue.host = VideoSupportFragmentGlueHost(this)
            // Attaching the host makes LeanbackPlayerAdapter call player.setVideoSurface(raw surface),
            // which drops the size the video graph needs ("Output surface and size not set" → no
            // picture, watchdog → SDR fallback). Bind the SurfaceView again so the graph gets it.
            boundHlgGraphSurfaceView = null
            boundPlaybackSurfaceView?.let {
                bindPlaybackVideoSurface(it, "startPlayer")
            }
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
        currentViewing = viewing
        hasRenderedFirstFrameForCurrentSource = false
        cancelStartupBufferingWatchdog()
        cancelToneMappingFirstFrameWatchdog()
        Log.i(
            TAG,
            "preparePlayer: " +
                "contentId=${viewing.contentId} channelId=${viewing.channelId} " +
                "platform=${viewing.platform} playApiVersion=${viewing.playApiVersion} " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                "laUrl=${viewing.laURL} url=${viewing.url}"
        )
        try {
            boundPlaybackSurfaceView?.let {
                bindPlaybackVideoSurface(it, "preparePlayer")
            }
            val settings = settingsRepository.getCurrent()
            val mainItem = MediaSourceItemFactory.newMediaItem(viewing)
            val rawMainSource = createMediaSource(
                urlString = viewing.url.toString(),
                streamType = viewing.streamType,
                mediaItem = mainItem,
                rewriteF1CmafHlsDrm = shouldRewriteF1CmafHlsDrm(viewing),
                isLiveSession = ChannelPlaybackFragment.findIsLiveSession(requireActivity())
            )
            val hasHdrWidevineVideo = looksLikeHdrUhdWidevine(viewing)
            val useExternalAudio = viewing.externalAudioUri != null &&
                (settings.useExternalAudio || viewing.externalAudioRequired || viewing.externalAudioIsCompanion)
            val useVideoOnlyHdrMain = hasHdrWidevineVideo && useExternalAudio
            // Keep Media3 audio enabled here: the HDR main source is filtered to video-only,
            // so disabling the audio track type would also disable the merged companion audio.
            setMainPlayerAudioDisabled(
                disabled = false,
                reason = if (useVideoOnlyHdrMain) "hdr_companion_audio_merge" else "prepare"
            )
            val mainSource = if (useVideoOnlyHdrMain) {
                Log.i(TAG, "Filtering main UHD/HDR source to video only; companion/external source supplies audio")
                FilteringMediaSource(rawMainSource, C.TRACK_TYPE_VIDEO)
            } else {
                rawMainSource
            }

            val mediaSource = if (useExternalAudio) {
                Log.i(TAG, "External audio enabled — building MergingMediaSource")
                val audioItem = MediaSourceItemFactory.newExternalAudioMediaItem(viewing)
                val audioSource = createMediaSource(
                    viewing.externalAudioUri.toString(),
                    viewing.externalAudioStreamType,
                    audioItem,
                    rewriteF1CmafHlsDrm = false
                )
                val finalMainSource = if (useVideoOnlyHdrMain) {
                    mainSource
                } else if (viewing.externalAudioRequired) {
                    Log.i(TAG, "Filtering main UHD/HDR source to video only; companion/custom source supplies audio")
                    FilteringMediaSource(mainSource, C.TRACK_TYPE_VIDEO)
                } else {
                    mainSource
                }
                val audioOnlySource = FilteringMediaSource(audioSource, C.TRACK_TYPE_AUDIO)
                // UHD/HDR embedded AAC is broken on some Android TV devices. For that case,
                // the main source is video-only and the companion source supplies audio.
                // Use period-time adjustment because F1's HLS and DASH live windows do not always
                // share identical period offsets; without this, the DASH audio can be present but silent.
                val offsetMs = settings.audioOffsetMs
                val finalAudioSource = if (offsetMs != 0L) {
                    Log.i(TAG, "Applying audioOffsetMs=$offsetMs to audio source")
                    ClippingMediaSource.Builder(audioOnlySource)
                        .setStartPositionUs(if (offsetMs > 0) offsetMs * 1000L else 0L)
                        .setEndPositionUs(C.TIME_END_OF_SOURCE)
                        .setEnableInitialDiscontinuity(false)
                        .setAllowDynamicClippingUpdates(true)
                        .setRelativeToDefaultPosition(false)
                        .build()
                } else audioOnlySource
                MergingMediaSource(
                    /* adjustPeriodTimeOffsets = */ true,
                    /* clipDurations = */ false,
                    finalMainSource,
                    finalAudioSource
                )
            } else {
                mainSource
            }

            // A feed switch in a replay (see positionToKeepOnFeedSwitchMs), once; or a restart after the
            // surface was lost. A live stream has neither.
            val startPositionMs = when {
                resumeAfterSurfaceLossMs > 0L -> resumeAfterSurfaceLossMs.also { resumeAfterSurfaceLossMs = -1L }
                !intentStartPositionUsed -> ChannelPlaybackFragment.findStartPositionMs(requireActivity())
                else -> 0L
            }
            intentStartPositionUsed = true
            if (startPositionMs > 0L) {
                Log.i(TAG, "Starting at ${startPositionMs / 1000}s (kept from the feed that was playing)")
                player.setMediaSource(mediaSource, startPositionMs)
            } else {
                player.setMediaSource(mediaSource)
            }
            player.prepare()
            scheduleStartupBufferingWatchdog("prepare")
            scheduleToneMappingFirstFrameWatchdog("prepare")
            Log.d(TAG, "Player prepared")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing player", e)
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
    }

    private fun configurePlaybackVideoSurface(root: View? = view, source: String): SurfaceView? {
        val playbackSurfaceView = findSurfaceView(root) ?: runCatching { surfaceView }.getOrNull()
        if (playbackSurfaceView == null) {
            Log.w(TAG, "Video SurfaceView unavailable during playback-surface setup source=$source")
            return null
        }
        playbackSurfaceView.keepScreenOn = true
        playbackSurfaceView.setSecure(true)
        playbackSurfaceView.setZOrderOnTop(false)
        playbackSurfaceView.setZOrderMediaOverlay(false)
        // Keep the video plane vanilla after setSecure(true). The official TV path does not
        // force PixelFormat or SurfaceControl dataspace; those overrides break the MediaTek
        // secure YUV/HDR handoff and can produce the green video plane.
        boundPlaybackSurfaceView = playbackSurfaceView
        ensurePlaybackSurfaceHolderCallback(playbackSurfaceView)
        val path = if (shouldUseProtectedHlgGraph()) {
            "forced Media3 protected HLG graph SurfaceView"
        } else {
            "protected/secure SurfaceView"
        }
        Log.i(
            TAG,
            "Configured $path for playback " +
                "source=$source view=${System.identityHashCode(playbackSurfaceView)} " +
                "surfaceSecure=true"
        )
        return playbackSurfaceView
    }

    private fun bindPlaybackVideoSurface(surfaceView: SurfaceView, source: String) {
        val surface = surfaceView.holder.surface
        if (!surface.isValid) {
            Log.w(TAG, "Surface is not valid yet in bindPlaybackVideoSurface, source=$source")
            return
        }
        
        // Intentionally NOT applying BT.2020 HLG dataspace explicitly to avoid breaking MediaTek secure surfaces

        val useVideoGraph = shouldUseProtectedHlgGraph() || shouldUseHdrToSdrToneMapping()
        if (useVideoGraph) {
            // When the PlaybackVideoGraphWrapper (video effects graph) is active, we MUST use
            // setVideoSurfaceView() rather than setVideoSurface(raw surface).
            // setVideoSurface() bypasses the graph and connects the raw surface directly to the
            // decoder, leaving the graph's FinalShaderWrapper with no output surface
            // (→ "Output surface and size not set, dropping frame" warnings → black screen).
            // setVideoSurfaceView() routes through the graph's setOutputSurfaceInfo() path, which
            // properly wires FinalShaderWrapper's output to the SurfaceView surface.
            //
            // CRITICAL: only call setVideoSurfaceView() when the SurfaceView has actually changed.
            // Repeated calls with the same view trigger repeated setOutputSurfaceInfo() inside
            // PlaybackVideoGraphWrapper, which swaps the GL output surface mid-stream and causes
            // EGL_BAD_MATCH (0x3009) on the second rendered frame → player crash.
            if (surfaceView === boundHlgGraphSurfaceView) {
                Log.i(
                    TAG,
                    "Skipping redundant setVideoSurfaceView — same SurfaceView already bound to HLG graph " +
                        "source=$source view=${System.identityHashCode(surfaceView)}"
                )
            } else {
                Log.i(TAG, "Binding SurfaceView to ExoPlayer via setVideoSurfaceView (graph output path) source=$source")
                // The video graph reports no video size, so onVideoSizeChanged never enlarges the
                // buffer: it stays at the 1080p UI size and the tone-mapped 4K picture is drawn at
                // 1080p. Give a 4K stream a 2160p buffer from the start (2160p SDR on e.g. a Shield).
                val viewing = currentViewing ?: findViewing(this)
                if (shouldUseHdrToSdrToneMapping(viewing) &&
                    viewing?.streamType.orEmpty().contains("UHD", ignoreCase = true)
                ) {
                    surfaceView.holder.setFixedSize(UHD_WIDTH, UHD_HEIGHT)
                    Log.i(TAG, "Tone-mapped UHD: SurfaceView buffer set to ${UHD_WIDTH}x$UHD_HEIGHT")
                }
                player.setVideoSurfaceView(surfaceView)
                boundHlgGraphSurfaceView = surfaceView
                Log.i(
                    TAG,
                    "Bound ExoPlayer to forced Media3 protected HLG graph SurfaceView (via setVideoSurfaceView) " +
                        "source=$source view=${System.identityHashCode(surfaceView)}"
                )
            }
        } else {
            if (surfaceView === boundDirectSurfaceView) {
                Log.i(
                    TAG,
                    "Skipping redundant setVideoSurfaceView — same SurfaceView already bound to direct path " +
                        "source=$source view=${System.identityHashCode(surfaceView)}"
                )
            } else {
                Log.i(TAG, "Binding SurfaceView to ExoPlayer via setVideoSurfaceView (direct fallback path) source=$source")
                player.setVideoSurfaceView(surfaceView)
                boundDirectSurfaceView = surfaceView
                Log.i(
                    TAG,
                    "Bound ExoPlayer to protected/secure SurfaceView via setVideoSurfaceView " +
                        "source=$source view=${System.identityHashCode(surfaceView)}"
                )
            }
        }
        boundPlaybackSurfaceView = surfaceView
    }

    private fun updateVideoSurfaceSize(videoWidth: Int, videoHeight: Int, source: String) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        playbackSurfaceBufferWidth = videoWidth
        playbackSurfaceBufferHeight = videoHeight
    }



    private fun ensurePlaybackSurfaceHolderCallback(surfaceView: SurfaceView) {
        val holder = surfaceView.holder
        if (holder == boundPlaybackSurfaceHolder) return
        boundPlaybackSurfaceHolder?.removeCallback(playbackSurfaceHolderCallback)
        holder.addCallback(playbackSurfaceHolderCallback)
        boundPlaybackSurfaceHolder = holder
        Log.i(
            TAG,
            "Registered playback SurfaceHolder callback view=${System.identityHashCode(surfaceView)}"
        )
    }



    private fun findSurfaceView(root: View?): SurfaceView? {
        return when (root) {
            is SurfaceView -> root
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    findSurfaceView(root.getChildAt(index))?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun looksLikeHdrUhdWidevine(viewing: F1TvViewing): Boolean {
        return ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing)
    }

    /**
     * Choose DASH or HLS factory based on stream type and URL extension.
     * Defaults to HLS for unrecognised types (safer for Live streams).
     */
    private fun createMediaSource(
        urlString: String,
        streamType: String?,
        mediaItem: androidx.media3.common.MediaItem,
        rewriteF1CmafHlsDrm: Boolean = false,
        isLiveSession: Boolean = false
    ): androidx.media3.exoplayer.source.MediaSource {
        val isDash = streamType?.contains("DASH", ignoreCase = true) == true
            || urlString.contains(".mpd", ignoreCase = true)
        return if (isDash) {
            val useF1DashUhdHdrFixes = shouldUseF1DashUhdHdrFixes(urlString, streamType)
            val rewriteF1DashInit = useF1DashUhdHdrFixes // Assume reuse of existing flag logic
            Log.i(
                TAG,
                "Using DashMediaSource for $urlString " +
                    "f1UhdHdrFixes=$useF1DashUhdHdrFixes rewriteInit=$rewriteF1DashInit isLiveSession=$isLiveSession"
            )
            val dashDataSourceFactory = if (rewriteF1DashInit) {
                F1DashInitSegmentFixingDataSource.Factory(httpDataSourceFactory, isLiveSession)
            } else {
                httpDataSourceFactory
            }
            val chunkSourceFactory = if (useF1DashUhdHdrFixes) {
                DefaultDashChunkSource.Factory(F1DashChunkExtractorFactory(), dashDataSourceFactory, 1)
            } else {
                DefaultDashChunkSource.Factory(dashDataSourceFactory)
            }
            val factory = DashMediaSource.Factory(
                chunkSourceFactory,
                dashDataSourceFactory
            )
            if (useF1DashUhdHdrFixes) {
                factory.setManifestParser(F1DynamicHvcCDashManifestParser())
            }
            // DIAGNOSTIC (2026-08-23): validate whether forcing Widevine L3
            // yields correct plaintext at all. Every earlier HEVC-vs-AVC
            // comparison ran HEVC at L3 and AVC at L1 (the SDR/AVC stream
            // routes to this fragment, which never applied L3), so "AVC works"
            // never tested the L3 path. If AVC also corrupts at L3, then L3
            // forcing is itself broken and every L3-based conclusion in this
            // investigation -- including the software-decoder C2_CORRUPTED --
            // is invalid.
            if (FORCE_WIDEVINE_L3_VALIDATION_DIAGNOSTIC) {
                Log.i(TAG, "DIAGNOSTIC: forcing Widevine L3 on standard playback path")
                factory.setDrmSessionManagerProvider(
                    ForceWidevineL3DrmSessionManagerProvider(httpDataSourceFactory)
                )
            } else if (LOG_KEY_RESPONSE_STANDARD_PATH) {
                Log.i(
                    TAG,
                    "DIAGNOSTIC: logging Widevine key status + rewriting identity " +
                        "on standard playback path"
                )
                factory.setDrmSessionManagerProvider(
                    KeyStatusLoggingDrmSessionManagerProvider(httpDataSourceFactory)
                )
            }
            factory.createMediaSource(mediaItem)
        } else {
            Log.i(TAG, "Using HlsMediaSource for $urlString rewriteF1CmafHlsDrm=$rewriteF1CmafHlsDrm")
            val dataSourceFactory = if (rewriteF1CmafHlsDrm) {
                F1CmafHlsDrmFixingDataSource.Factory(httpDataSourceFactory)
            } else {
                httpDataSourceFactory
            }
            val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
            // DIAGNOSTIC (2026-10-11): the HDR CMAF HLS source carries the real
            // widevine_l1 license exchange, but only the DASH branch above
            // attached the logging/rewriting DRM provider, so the exchange on
            // this path was never captured. Apply the same switch here.
            if (FORCE_WIDEVINE_L3_VALIDATION_DIAGNOSTIC) {
                Log.i(TAG, "DIAGNOSTIC: forcing Widevine L3 on standard playback path (HLS)")
                hlsFactory.setDrmSessionManagerProvider(
                    ForceWidevineL3DrmSessionManagerProvider(httpDataSourceFactory)
                )
            } else if (LOG_KEY_RESPONSE_STANDARD_PATH) {
                Log.i(
                    TAG,
                    "DIAGNOSTIC: logging Widevine key status + rewriting identity " +
                        "on standard playback path (HLS)"
                )
                hlsFactory.setDrmSessionManagerProvider(
                    KeyStatusLoggingDrmSessionManagerProvider(httpDataSourceFactory)
                )
            }
            hlsFactory.createMediaSource(mediaItem)
        }
    }

    private fun shouldUseF1DashUhdHdrFixes(urlString: String, streamType: String?): Boolean {
        val identity = "$urlString ${streamType.orEmpty()}"
        val isUhdOrHdr = identity.contains("UHD", ignoreCase = true) || identity.contains("HDR", ignoreCase = true)
        return isUhdOrHdr && identity.contains("DASH", ignoreCase = true)
    }

    private fun shouldRewriteF1CmafHlsDrm(viewing: F1TvViewing): Boolean {
        // Do NOT rewrite F1's HDR CMAF-WV playlists to SAMPLE-AES/cbcs by default.
        //
        // The logs show the player successfully licenses and renders HDR_UHD_CMAFWV,
        // but the decoded picture is green and the embedded AAC can become invalid.
        // That is consistent with decrypting CTR/cenc media as cbcs after rewriting
        // SAMPLE-AES-CTR to SAMPLE-AES. Keep the playlist encryption method exactly
        // as returned by F1 and let Media3 choose the scheme.
        val looksLikeHdrCmafWv =
            viewing.url.toString().contains("HDR-UHD-CMAF-WV", ignoreCase = true) ||
                viewing.streamType?.contains("CMAFWV", ignoreCase = true) == true ||
                viewing.requestedOverrideStreamType?.contains("CMAFWV", ignoreCase = true) == true
        if (looksLikeHdrCmafWv) {
            Log.i(TAG, "Leaving HDR CMAF-WV HLS DRM method unchanged; playlist rewrite disabled")
        }
        return false
    }

    // ── Player.Listener ──────────────────────────────────────────────────────

    private var appliedHdrLadderRestriction = false

    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
        restrictToOneUhdLadderIfNeeded(tracks)
        applyPresetAudioLanguageIfNeeded(tracks)
    }

    private var appliedPresetAudioLanguage = false

    /**
     * The commentary chosen at first start (Settings): picked once, when the tracks are known. This feed's
     * own track first; otherwise the other feed's, merged in for the menu (F1 Live carries English and
     * Spanish, International every language, in every resolution: Dutch on F1 Live comes from International).
     * Not available at all: the feed's default stays. A pick in the audio menu, or the radio, wins.
     */
    private fun applyPresetAudioLanguageIfNeeded(tracks: androidx.media3.common.Tracks) {
        if (appliedPresetAudioLanguage || userAudioOverride != null) return
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return
        appliedPresetAudioLanguage = true
        val wanted = settingsRepository.getCurrent().audioLanguage
        if (wanted == Settings.AUDIO_LANGUAGE_STREAM || customRadio.isActive) return
        val viewing = currentViewing ?: findViewing(this)
        fun isOtherFeed(group: androidx.media3.common.Tracks.Group) =
            viewing?.externalAudioIsCompanion == true && group.mediaTrackGroup.id.startsWith("1:")
        fun language(format: androidx.media3.common.Format): String? {
            val raw = format.language?.lowercase(Locale.ROOT) ?: return null
            return if (raw == "cfx") "fx" else fr.groggy.racecontrol.tv.ui.player.normalizeAudioLanguageCode(raw)
        }
        val match = (audioGroups.filterNot(::isOtherFeed) + audioGroups.filter(::isOtherFeed)).firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull { group.isTrackSupported(it) && language(group.getTrackFormat(it)) == wanted }
                ?.let { group to it }
        }
        if (match == null) {
            Log.i(TAG, "Preset commentary '$wanted' not in this stream or the other feed: keeping its default")
            return
        }
        val (group, index) = match
        if (group.isTrackSelected(index)) return
        Log.i(TAG, "Preset commentary '$wanted': selecting it otherFeed=${isOtherFeed(group)}")
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, index))
        )
    }

    /**
     * F1's unencrypted HDR_UHD_CMAF playlist has two HEVC ladders under one video group: HLG
     * (`hvc1.2.*` Main10, up to 3840x2160) and SDR (`hvc1.1.*` Main, up to 2880x1620). Media3 does not
     * read VIDEO-RANGE, so ABR would cross between them mid-playback. Keep the HLG ladder on an HDR
     * display; elsewhere keep the SDR one (still above 1080p).
     */
    private fun restrictToOneUhdLadderIfNeeded(tracks: androidx.media3.common.Tracks) {
        if (appliedHdrLadderRestriction) return
        val viewing = currentViewing ?: findViewing(this) ?: return
        if (!viewing.streamType.orEmpty().contains("UHD", ignoreCase = true)) return
        // HDR switched off in Settings: the 2160p HLG ladder, tone-mapped to SDR by the renderer
        val hdrDisplay = fr.groggy.racecontrol.tv.utils.DeviceInfo.supportsHlgDisplay(requireContext()) ||
            shouldUseHdrToSdrToneMapping(viewing)
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            val codecs = (0 until group.length).map { group.getTrackFormat(it).codecs.orEmpty() }
            val hlg = codecs.indices.filter { codecs[it].startsWith("hvc1.2.") || codecs[it].startsWith("hev1.2.") }
            val sdr = codecs.indices.filter { codecs[it].startsWith("hvc1.1.") || codecs[it].startsWith("hev1.1.") }
            if (hlg.isEmpty() || sdr.isEmpty()) continue
            val keep = if (hdrDisplay) hlg else sdr
            Log.i(TAG, "UHD ladder: keeping ${if (hdrDisplay) "HLG" else "SDR"} tracks $keep of ${group.length} codecs=$codecs")
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, keep))
                    .build()
            )
            appliedHdrLadderRestriction = true
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val activePlayer = _player
        if (activePlayer == null) {
            Log.w(TAG, "Ignoring player error after player release: ${error.errorCodeName} (${error.errorCode})")
            return
        }
        val cause = error.cause
        Log.e(
            TAG,
            "onPlayerError: ${error.errorCodeName} (${error.errorCode}) " +
                "message=${error.message} " +
                "cause=${cause?.javaClass?.simpleName}:${cause?.message} " +
                "trackSelectorMax=${trackSelector.parameters.maxVideoWidth}x${trackSelector.parameters.maxVideoHeight} " +
                "currentTracks=${activePlayer.currentTracks.groups.size} " +
                "videoSize=${activePlayer.videoSize.width}x${activePlayer.videoSize.height}",
            error
        )
        (activity as? ChannelPlaybackActivity)?.playerError()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val activePlayer = _player ?: run {
            Log.w(TAG, "Ignoring playback state after player release rawState=$playbackState")
            return
        }
        Log.d(
            TAG,
            "onPlaybackStateChanged rawState=$playbackState " +
                "playWhenReady=${activePlayer.playWhenReady} " +
                "isPlaying=${activePlayer.isPlaying} " +
                "currentPosition=${activePlayer.currentPosition} " +
                "bufferedPosition=${activePlayer.bufferedPosition}"
        )
        val state = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($playbackState)"
        }
        Log.d(TAG, "Playback state → $state")
        when (playbackState) {
            Player.STATE_BUFFERING -> scheduleStartupBufferingWatchdog("buffering")
            Player.STATE_READY -> {
                cancelStartupBufferingWatchdog()
                scheduleToneMappingFirstFrameWatchdog("ready")
            }
            Player.STATE_ENDED,
            Player.STATE_IDLE -> {
                cancelStartupBufferingWatchdog()
                cancelToneMappingFirstFrameWatchdog()
            }
        }
        if (playbackState == Player.STATE_READY) {
            customRadio.onMainPlayerReady()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.d(TAG, "isPlaying → $isPlaying")
    }

    private fun scheduleStartupBufferingWatchdog(reason: String) {
        val viewing = currentViewing ?: return
        if (!looksLikeUhdOrHdr(viewing.streamType) && !looksLikeUhdOrHdr(viewing.requestedOverrideStreamType)) {
            return
        }
        if (startupBufferingWatchdogRunnable != null) return

        val runnable = Runnable {
            startupBufferingWatchdogRunnable = null
            if (!isAdded) return@Runnable
            val activePlayer = _player ?: return@Runnable
            if (activePlayer.playbackState != Player.STATE_BUFFERING || activePlayer.isPlaying) return@Runnable

            Log.w(
                TAG,
                "HDR/UHD startup buffering watchdog fired " +
                    "contentId=${viewing.contentId} channelId=${viewing.channelId} " +
                    "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                    "renderedFirstFrame=$hasRenderedFirstFrameForCurrentSource " +
                    "position=${activePlayer.currentPosition} buffered=${activePlayer.bufferedPosition} " +
                    "videoSize=${activePlayer.videoSize.width}x${activePlayer.videoSize.height} " +
                    "reason=$reason timeoutMs=$UHD_STARTUP_BUFFERING_TIMEOUT_MS"
            )
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
        startupBufferingWatchdogRunnable = runnable
        overlayAutoCloseHandler.postDelayed(runnable, UHD_STARTUP_BUFFERING_TIMEOUT_MS)
    }

    private fun cancelStartupBufferingWatchdog() {
        val runnable = startupBufferingWatchdogRunnable ?: return
        overlayAutoCloseHandler.removeCallbacks(runnable)
        startupBufferingWatchdogRunnable = null
    }

    private fun scheduleToneMappingFirstFrameWatchdog(reason: String) {
        val viewing = currentViewing ?: return
        if (!shouldUseHdrToSdrToneMapping()) return
        if (!looksLikeUhdOrHdr(viewing.streamType) && !looksLikeUhdOrHdr(viewing.requestedOverrideStreamType)) {
            return
        }
        if (hasRenderedFirstFrameForCurrentSource) return
        if (toneMappingFirstFrameWatchdogRunnable != null) return

        val runnable = Runnable {
            toneMappingFirstFrameWatchdogRunnable = null
            if (!isAdded) return@Runnable
            val activePlayer = _player ?: return@Runnable
            if (hasRenderedFirstFrameForCurrentSource) return@Runnable

            val state = activePlayer.playbackState
            val isTryingToPresent = activePlayer.isPlaying ||
                (state == Player.STATE_READY && activePlayer.playWhenReady)
            if (!isTryingToPresent) {
                Log.d(
                    TAG,
                    "HDR/UHD tone-mapping first-frame watchdog deferred " +
                        "state=$state isPlaying=${activePlayer.isPlaying} " +
                        "playWhenReady=${activePlayer.playWhenReady} reason=$reason"
                )
                return@Runnable
            }

            Log.w(
                TAG,
                "HDR/UHD tone-mapping first-frame watchdog fired " +
                    "contentId=${viewing.contentId} channelId=${viewing.channelId} " +
                    "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                    "state=$state isPlaying=${activePlayer.isPlaying} " +
                    "playWhenReady=${activePlayer.playWhenReady} " +
                    "position=${activePlayer.currentPosition} buffered=${activePlayer.bufferedPosition} " +
                    "videoSize=${activePlayer.videoSize.width}x${activePlayer.videoSize.height} " +
                    "reason=$reason timeoutMs=$UHD_TONE_MAPPING_FIRST_FRAME_TIMEOUT_MS"
            )
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
        toneMappingFirstFrameWatchdogRunnable = runnable
        overlayAutoCloseHandler.postDelayed(runnable, UHD_TONE_MAPPING_FIRST_FRAME_TIMEOUT_MS)
    }

    private fun cancelToneMappingFirstFrameWatchdog() {
        val runnable = toneMappingFirstFrameWatchdogRunnable ?: return
        overlayAutoCloseHandler.removeCallbacks(runnable)
        toneMappingFirstFrameWatchdogRunnable = null
    }

    private fun looksLikeUhdOrHdr(value: String?): Boolean {
        val normalized = value?.uppercase() ?: return false
        return "UHD" in normalized || "2160" in normalized || "HDR" in normalized
    }

    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        Log.d(
            TAG,
            "onVideoSizeChanged width=${videoSize.width} height=${videoSize.height} " +
                "pixelRatio=${videoSize.pixelWidthHeightRatio} " +
                "unappliedRotationDegrees=${videoSize.unappliedRotationDegrees}"
        )
        updateVideoSurfaceSize(videoSize.width, videoSize.height)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            customRadio.onMainPlayerSeek()
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
        pausePlaybackFromTransportControls(logSource = "onPause")
        
        val activity = requireActivity()
        if (activity.isFinishing || activity.isChangingConfigurations) {
            Log.i(TAG, "onPause: Activity finishing or changing config. Releasing player early to beat SurfaceFlinger teardown!")
            releasePlayerSafely("onPause")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasStartedPlayer) return
        val activePlayer = _player ?: return
        if (activePlayer.playWhenReady) {
            resumePlaybackFromTransportControls(logSource = "onResume")
        } else {
            customRadio.resume()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop: explicitly releasing player early to prevent MediaTek secure codec corruption upon surfaceDestroyed")
        releasePlayerSafely("onStop")
        val playbackActivity = activity as? ChannelPlaybackActivity
        if (playbackActivity?.isInternalPlaybackFragmentSwapInProgress() == true) {
            Log.i(TAG, "onStop: playback fragment swap in progress; keeping playback activity alive")
            return
        }
        if (!requireActivity().isChangingConfigurations) {
            Log.i(TAG, "onStop: finishing playback activity as it was pushed to background")
            requireActivity().finish()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: releasing player")
        cancelStartupBufferingWatchdog()
        cancelToneMappingFirstFrameWatchdog()
        cancelOverlayAutoCloseTimer()
        playbackGlue = null
        customRadio.release()
        releasePlayerSafely("onDestroy")
        super.onDestroy()
    }

    override fun onDestroyView() {
        view?.removeOnLayoutChangeListener(videoHostLayoutChangeListener)
        boundPlaybackSurfaceHolder?.removeCallback(playbackSurfaceHolderCallback)
        boundPlaybackSurfaceHolder = null
        boundPlaybackSurfaceView = null
        playbackSurfaceBufferWidth = 0
        playbackSurfaceBufferHeight = 0
        hasProbedProtectedHlgEglSurface = false
        lastVideoHostWidth = 0
        lastVideoHostHeight = 0
        super.onDestroyView()
    }

    internal fun onHostConfigurationChanged() {
        view?.post {
            val fragmentView = view ?: return@post
            fragmentView.requestLayout()
            fragmentView.requestApplyInsets()
        }
    }

    internal fun shouldHandleTouchOverlayGesture(): Boolean {
        return isAdded && !isControlsOverlayVisible() && !hasOpenPlaybackDialog()
    }

    internal fun showControlsOverlayFromTouch(): Boolean {
        if (!shouldHandleTouchOverlayGesture()) return false
        showControlsOverlay(true)
        return true
    }

    private fun hasOpenPlaybackDialog(): Boolean {
        return childFragmentManager.fragments.any { fragment ->
            fragment is DialogFragment && fragment.dialog?.isShowing == true
        }
    }

    private fun pausePlaybackFromTransportControls(logSource: String = "transport") {
        val activePlayer = _player
        if (activePlayer?.isPlaying == true) {
            activePlayer.pause()
        }
        customRadio.pause()
        Log.d(TAG, "$logSource: paused main player and custom radio")
    }

    private fun resumePlaybackFromTransportControls(logSource: String = "transport") {
        val activePlayer = _player
        if (activePlayer?.isPlaying == false) {
            activePlayer.play()
        }
        customRadio.resume()
        Log.d(TAG, "$logSource: resumed main player and custom radio")
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

    private fun resetOverlayAutoCloseTimer() {
        cancelOverlayAutoCloseTimer()
        overlayAutoCloseHandler.postDelayed(overlayAutoCloseRunnable, OVERLAY_AUTO_CLOSE_DELAY_MS)
    }

    private fun cancelOverlayAutoCloseTimer() {
        overlayAutoCloseHandler.removeCallbacks(overlayAutoCloseRunnable)
    }

    private fun updateVideoSurfaceSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || view == null) return
        // Leanback's own onVideoSizeChanged only adjusts the on-screen view's
        // aspect ratio/bounds -- it does not resize the SurfaceView's actual
        // BufferQueue. Without also fixing that, the buffer stays at whatever
        // size Leanback initially laid it out at (e.g. 1920x1080) while
        // MediaCodec writes real 3840x2160 secure/protected buffers into it,
        // a mismatch that can fail protected buffer allocation rather than
        // just being scaled, unlike regular unprotected content.
        boundPlaybackSurfaceView?.holder?.setFixedSize(videoWidth, videoHeight)
        super<VideoSupportFragment>.onVideoSizeChanged(videoWidth, videoHeight)
    }

}
