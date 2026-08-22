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
import fr.groggy.racecontrol.tv.ui.player.CustomRadioSyncDialog
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

        private const val CUSTOM_RADIO_DEFAULT_OFFSET_MS = 20_000L
        private const val CUSTOM_RADIO_MAX_OFFSET_MS = 120_000L
        private const val CUSTOM_RADIO_EARLY_END_THRESHOLD_MS = 12_000L
        private const val CUSTOM_RADIO_RETRY_DELAY_MS = 500L
        private const val OVERLAY_AUTO_CLOSE_DELAY_MS = 10_000L
        private const val UHD_STARTUP_BUFFERING_TIMEOUT_MS = 12_000L
        private const val UHD_TONE_MAPPING_FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val UHD_HDR_PRESENTATION_FRAME_RATE = 50f
        private const val DISPLAY_MODE_REFRESH_TOLERANCE = 0.25f
        private const val CUSTOM_RADIO_SYNC_DIALOG_TAG = "custom_radio_sync"
        private const val CUSTOM_RADIO_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Google TV Streamer Build/UTT3.240625.001.K5; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36"

        private const val ARG_VIEWING = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_VIEWING"
        private const val ARG_SESSION_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SESSION_ID"
        private const val ARG_CONTENT_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CONTENT_ID"
        private const val ARG_CHANNEL_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CHANNEL_ID"
        private const val ARG_IS_LIVE_SESSION = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_IS_LIVE_SESSION"
        private const val ARG_SEASON_YEAR = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SEASON_YEAR"
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
    private var currentViewing: F1TvViewing? = null
    private var hasRenderedFirstFrameForCurrentSource = false
    private var startupBufferingWatchdogRunnable: Runnable? = null
    private var toneMappingFirstFrameWatchdogRunnable: Runnable? = null
    private var suppressOverlayReopenUntilElapsedMs: Long = 0L
    private var lastVideoHostWidth: Int = 0
    private var lastVideoHostHeight: Int = 0
    private var hasStartedPlayer = false
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
        dismissCustomRadioSyncDialogIfShown()
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

    private fun shouldUseHdrToSdrToneMapping(): Boolean {
        return settingsRepository.getCurrent().disableHdrOn4kStreams || isForceHdrToSdrToneMapping()
    }

    private fun shouldUseProtectedHlgGraph(viewing: F1TvViewing? = currentViewing ?: findViewing(this)): Boolean {
        return !isForceDirectMedia3HdrSurface() &&
            !shouldUseHdrToSdrToneMapping() &&
            viewing?.let { looksLikeHdrUhdWidevine(it) } == true &&
            (isForceProtectedHlgGraph() || protectedHdrCapabilities.canCreateProtectedHlgEglSurface)
    }



    private val renderersFactory: RenderersFactory by lazy {
        val settings = settingsRepository.getCurrent()
        val viewing = findViewing(this)
        val enableHdrToSdrToneMapping = settings.disableHdrOn4kStreams || isForceHdrToSdrToneMapping()
        val enableProtectedHlgVideoGraph = shouldUseProtectedHlgGraph(viewing) && !enableHdrToSdrToneMapping
        val enableOfficialLikeDirectHdrCodecConfig = false
        Log.i(
            TAG,
            "Media3 renderer factory protectedHlgGraph=$enableProtectedHlgVideoGraph " +
                "enableHdrToSdrToneMapping=$enableHdrToSdrToneMapping " +
                "officialLikeDirectHdrCodecConfig=$enableOfficialLikeDirectHdrCodecConfig " +
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
            // DIAGNOSTIC (2026-08-22): log CryptoInfo here too, so one session
            // captures BOTH the failing HDR attempt and the working SDR
            // fallback and their encryption layouts can be compared directly.
            enableCryptoDiagnostics = false
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

    private fun releasePlayerSafely(source: String) {
        cancelStartupBufferingWatchdog()
        cancelToneMappingFirstFrameWatchdog()
        val playerToRelease = _player ?: run {
            Log.i(TAG, "releasePlayerSafely skipped; player already null source=$source")
            return
        }
        _player = null
        boundHlgGraphSurfaceView = null
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
                onCustomRadioRequested = ::injectCustomRadio,
                onCustomRadioSyncRequested = ::showCustomRadioSyncDialog,
                onAudioTrackSelected = ::stopCustomRadio,
                onPlayRequested = ::resumePlaybackFromTransportControls,
                onPauseRequested = ::pausePlaybackFromTransportControls,
                isCustomRadioSelectable = ::isCustomRadioAvailableForCurrentSession,
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
                (settings.useExternalAudio || viewing.externalAudioRequired)
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

            player.setMediaSource(mediaSource)
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
            factory.createMediaSource(mediaItem)
        } else {
            Log.i(TAG, "Using HlsMediaSource for $urlString rewriteF1CmafHlsDrm=$rewriteF1CmafHlsDrm")
            val dataSourceFactory = if (rewriteF1CmafHlsDrm) {
                F1CmafHlsDrmFixingDataSource.Factory(httpDataSourceFactory)
            } else {
                httpDataSourceFactory
            }
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
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
        if (playbackState == Player.STATE_READY && !hasAutoInjectedCustomRadio) {
            hasAutoInjectedCustomRadio = true
            val settings = settingsRepository.getCurrent()
            if (
                shouldAutoInjectCustomRadio(settings)
            ) {
                injectCustomRadio()
            }
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
            val radio = customRadioPlayer ?: return
            if (customRadioMuted || customRadioInjected) {
                radio.play()
            }
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
        releaseCustomRadioPlayer()
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
        cancelCustomRadioDelayNudge(resume = false)
        val activePlayer = _player
        if (activePlayer?.isPlaying == true) {
            activePlayer.pause()
        }
        customRadioPlayer?.pause()
        Log.d(TAG, "$logSource: paused main player and custom radio")
    }

    private fun resumePlaybackFromTransportControls(logSource: String = "transport") {
        val activePlayer = _player
        if (activePlayer?.isPlaying == false) {
            activePlayer.play()
        }
        if (customRadioMuted || customRadioInjected) {
            customRadioPlayer?.play()
        }
        Log.d(TAG, "$logSource: resumed main player and custom radio")
    }

    // ── Grand Prix Radio ──────────────────────────────────────────────────────

    internal fun injectCustomRadio() {
        if (customRadioInjected) return
        val settings = settingsRepository.getCurrent()
        if (!isCustomRadioAvailableForCurrentSession(settings)) {
            Toast.makeText(requireContext(), R.string.custom_radio_live_only_message, Toast.LENGTH_LONG).show()
            return
        }
        // If the radio is already streaming but muted, just unmute — no 20s wait needed
        if (customRadioMuted && customRadioPlayer != null) {
            customRadioMuted = false
            customRadioInjected = true
            customRadioPlayer!!.setVolume(100)
            playbackGlue?.refreshSubtitle()
            setMainPlayerAudioDisabled(disabled = true, reason = "custom_radio_unmute")
            logCustomRadioTelemetry("unmuted", detail = "Unmuted existing stream, no delay")
            return
        }
        // VLC was muted but died while muted — reset muted flag before fresh start
        customRadioMuted = false
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
        setMainPlayerAudioDisabled(disabled = true, reason = "custom_radio_start")
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
        setMainPlayerAudioDisabled(disabled = false, reason = "custom_radio_stop")
    }

    internal fun showCustomRadioSyncDialog() {
        view?.post {
            if (childFragmentManager.findFragmentByTag(CUSTOM_RADIO_SYNC_DIALOG_TAG) != null) {
                return@post
            }
            CustomRadioSyncDialog(
                currentOffsetMs = customRadioOffsetMs,
                onOffsetSelected = { offsetMs -> setCustomRadioOffset(offsetMs) },
                onUserInteraction = ::resetOverlayAutoCloseTimer,
                onDialogDismissed = { playbackGlue?.notifyMenuDismissed() }
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

    private fun isCustomRadioAvailableForCurrentSession(
        settings: Settings = settingsRepository.getCurrent()
    ): Boolean {
        if (!settings.restrictCustomRadioToLiveSessions) return true
        if (findIsLiveSession(requireActivity())) return true

        return false
    }

    private fun shouldAutoInjectCustomRadio(
        settings: Settings = settingsRepository.getCurrent()
    ): Boolean {
        if (!settings.autoSelectCustomRadio) return false
        val viewing = currentViewing

        if (viewing?.externalAudioRequired == true || viewing?.externalAudioUri != null) {
            Log.i(TAG, "Auto custom radio allowed with external/companion audio; main player audio will be disabled")
        }
        if (!isCustomRadioAvailableForCurrentSession(settings)) return false
        return buildCustomRadioPlan(settings).isNotEmpty()
    }

    private fun buildCustomRadioPlan(settings: Settings): List<CustomRadioPlanEntry> {
        val customUrl = settings.customRadioUrl.trim()
        if (customUrl.isNotBlank()) {
            if (!isSupportedCustomRadioUrl(customUrl)) {
                Log.w(TAG, "Ignoring invalid custom radio URL: $customUrl")
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
