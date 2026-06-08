package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHdrStreamClassifier
import javax.inject.Inject

/**
 * UHD/HDR-only Media3 path that mirrors the official TV app's visible structure:
 * a black full-screen container, one secure SurfaceView, and the raw holder Surface
 * passed directly to MediaCodec through ExoPlayer.
 */
@AndroidEntryPoint
class OfficialLikeHdrPlaybackFragment : Fragment(), Player.Listener {

    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory
    @Inject internal lateinit var settingsRepository: SettingsRepository

    private var playbackSurfaceView: SurfaceView? = null
    private var playbackSurface: Surface? = null
    private var prepared = false
    private var currentViewing: F1TvViewing? = null

    private val trackSelector: DefaultTrackSelector by lazy {
        DefaultTrackSelector(requireContext()).apply {
            setParameters(buildUponParameters().applyOfficialTrackParameters().build())
        }
    }

    private val renderersFactory by lazy {
        // Do NOT enable MediaCodec codec config overrides (enableOfficialLikeDirectHdrCodecConfig).
        // The official Tiledmedia app lets ExoPlayer negotiate with MediaCodec natively.
        // Injecting KEY_COLOR_TRANSFER_REQUEST and unsetting operating-rate can cause a
        // double-mapping bug on the MediaTek secure decoder, producing a green screen.
        HdrToneMappingRenderersFactory(
            requireContext(),
            enableHdrToSdrToneMapping = false,
            enableProtectedHlgVideoGraph = false,
            enableOfficialLikeDirectHdrCodecConfig = false
        )
    }

    private val player: ExoPlayer by lazy {
        Log.i(TAG, "Initializing official-like bare Surface HDR ExoPlayer")
        ExoPlayer.Builder(requireContext(), renderersFactory)
            .setTrackSelector(trackSelector)
            .build()
            .also { exoPlayer ->
                exoPlayer.playWhenReady = true
                exoPlayer.addListener(this)
                exoPlayer.addAnalyticsListener(EventLogger())
                exoPlayer.addAnalyticsListener(object : AnalyticsListener {
                    override fun onRenderedFirstFrame(
                        eventTime: AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        HdrPresentationDiagnostics.logDisplaySnapshot(
                            requireContext(),
                            "officialLike-renderedFirstFrame"
                        )
                        Log.i(TAG, "onRenderedFirstFrame output=$output renderTimeMs=${renderTimeMs}ms")
                    }

                    override fun onPlayerError(
                        eventTime: AnalyticsListener.EventTime,
                        error: PlaybackException
                    ) {
                        Log.e(TAG, "Analytics player error ${error.errorCodeName} (${error.errorCode})", error)
                    }
                })
            }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.i(
                TAG,
                "Official-like HDR SurfaceHolder created " +
                    "surfaceValid=${holder.surface.isValid} view=${System.identityHashCode(playbackSurfaceView)}"
            )
            // Request 50 Hz with CHANGE_FRAME_RATE_ALWAYS so SurfaceFlinger is allowed to do a
            // *seamed* (brief display blank) refresh rate switch to 50 Hz.
            // Without this, the default OnlySeamless keeps the display at 60 Hz on this MediaTek
            // chip. The HWC MM plane for 50 fps HLG content cannot initialise at 60 Hz and
            // produces green video. The official app uses SeamedAndSeamless in SurfaceFlinger.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                holder.surface.setFrameRate(
                    50f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
                Log.i(TAG, "Set Surface frame rate 50 Hz CHANGE_FRAME_RATE_ALWAYS (SeamedAndSeamless)")
            }
            HdrPresentationDiagnostics.logDisplaySnapshot(requireContext(), "officialLike-surfaceCreated")
            bindPlaybackSurface(holder.surface, "surfaceCreated")
            prepareWhenSurfaceReady("surfaceCreated")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(
                TAG,
                "Official-like HDR SurfaceHolder changed " +
                    "format=$format size=${width}x${height} view=${System.identityHashCode(playbackSurfaceView)}"
            )
            HdrPresentationDiagnostics.logDisplaySnapshot(requireContext(), "officialLike-surfaceChanged")
            bindPlaybackSurface(holder.surface, "surfaceChanged")
            prepareWhenSurfaceReady("surfaceChanged")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.i(TAG, "Official-like HDR SurfaceHolder destroyed")
            playbackSurface?.let(player::clearVideoSurface)
            playbackSurface = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentViewing = findViewing(requireArguments())
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Log.i(
            TAG,
            "Created official-like bare Surface HDR fragment " +
                "streamType=${currentViewing?.streamType} requestedOverride=${currentViewing?.requestedOverrideStreamType}"
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        return FrameLayout(context).apply {
            background = null
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                SurfaceView(context).also { surfaceView ->
                    playbackSurfaceView = surfaceView
                    surfaceView.keepScreenOn = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        surfaceView.setSecure(true)
                    }
                    HdrSurfaceHints.applyAndroid14SurfaceLifecycle(surfaceView, "officialLike-onCreateView")
                    surfaceView.setZOrderOnTop(false)
                    surfaceView.setZOrderMediaOverlay(false)
                    surfaceView.holder.addCallback(surfaceCallback)
                    // Do NOT call setFormat(PixelFormat.OPAQUE) or setDataSpace here.
                    // The official Tiledmedia app only calls setSecure(true) and does NOT override
                    // the holder pixel format. Forcing OPAQUE or explicit dataspace hints can
                    // break the MediaTek HWC's implicit secure-YUV buffer negotiation, causing
                    // a permanent green video plane.
                    //
                    // the HWC panics and locks the video plane to green permanently.
                    surfaceView.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    Log.i(
                        TAG,
                        "Created official-like secure HDR SurfaceView (vanilla: only setSecure) " +
                            "view=${System.identityHashCode(surfaceView)} surfaceSecure=true"
                    )
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        prepareWhenSurfaceReady("onResume")
    }

    override fun onPause() {
        player.pause()
        Log.i(TAG, "Paused official-like HDR player")
        super.onPause()
    }

    override fun onDestroyView() {
        playbackSurfaceView?.holder?.removeCallback(surfaceCallback)
        playbackSurface?.let(player::clearVideoSurface)
        playbackSurface = null
        playbackSurfaceView = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        player.release()
        Log.i(TAG, "Released official-like HDR player")
        super.onDestroy()
    }

    private fun prepareWhenSurfaceReady(source: String) {
        if (prepared) return
        val viewing = currentViewing ?: return
        val surface = playbackSurface
        if (surface?.isValid != true) {
            Log.i(TAG, "Waiting for valid official-like HDR Surface source=$source")
            return
        }
        if (!ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing)) {
            Log.w(TAG, "Official-like HDR fragment received non-HDR stream; continuing with Media3 direct surface")
        }
        prepared = true
        preparePlayer(viewing, source)
    }

    private fun bindPlaybackSurface(surface: Surface, source: String) {
        if (!surface.isValid) {
            Log.i(TAG, "Skipping invalid official-like HDR Surface bind source=$source")
            return
        }
        if (playbackSurface === surface) {
            Log.i(TAG, "Official-like HDR Surface already bound source=$source")
            return
        }
        playbackSurface?.let(player::clearVideoSurface)
        playbackSurface = surface
        player.setVideoSurface(surface)
        Log.i(TAG, "Bound official-like HDR raw Surface source=$source surface=$surface")
    }

    private fun preparePlayer(viewing: F1TvViewing, source: String) {
        Log.i(
            TAG,
            "Preparing official-like bare Surface HDR player source=$source " +
                "contentId=${viewing.contentId} channelId=${viewing.channelId} " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                "laUrl=${viewing.laURL} url=${viewing.url}"
        )
        try {
            trackSelector.setParameters(buildTrackParameters(audioDisabled = false))
            val mediaSource = buildPlaybackMediaSource(viewing)
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
            Log.i(TAG, "Official-like bare Surface HDR player prepared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare official-like HDR player", e)
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
    }

    private fun buildPlaybackMediaSource(viewing: F1TvViewing): MediaSource {
        val mainItem = MediaSourceItemFactory.newMediaItem(viewing)
        val rawMainSource = createMediaSource(
            urlString = viewing.url.toString(),
            streamType = viewing.streamType,
            mediaItem = mainItem
        )

        val useExternalAudio = viewing.externalAudioUri != null &&
            (settingsRepository.getCurrent().useExternalAudio || viewing.externalAudioRequired)
        if (!useExternalAudio) {
            return rawMainSource
        }

        Log.i(TAG, "Official-like HDR path merging companion audio while keeping HDR source video-only")
        val audioItem = MediaSourceItemFactory.newExternalAudioMediaItem(viewing)
        val audioSource = createMediaSource(
            urlString = viewing.externalAudioUri.toString(),
            streamType = viewing.externalAudioStreamType,
            mediaItem = audioItem
        )
        val audioOnlySource = FilteringMediaSource(audioSource, C.TRACK_TYPE_AUDIO)
        val offsetMs = settingsRepository.getCurrent().audioOffsetMs
        val finalAudioSource = if (offsetMs != 0L) {
            Log.i(TAG, "Official-like HDR path applying audioOffsetMs=$offsetMs")
            ClippingMediaSource.Builder(audioOnlySource)
                .setStartPositionUs(if (offsetMs > 0) offsetMs * 1000L else 0L)
                .setEndPositionUs(C.TIME_END_OF_SOURCE)
                .setEnableInitialDiscontinuity(false)
                .setAllowDynamicClippingUpdates(true)
                .setRelativeToDefaultPosition(false)
                .build()
        } else {
            audioOnlySource
        }
        return MergingMediaSource(
            true,
            false,
            FilteringMediaSource(rawMainSource, C.TRACK_TYPE_VIDEO),
            finalAudioSource
        )
    }

    private fun createMediaSource(
        urlString: String,
        streamType: String?,
        mediaItem: androidx.media3.common.MediaItem
    ): MediaSource {
        val isDash = streamType?.contains("DASH", ignoreCase = true) == true ||
            urlString.contains(".mpd", ignoreCase = true)
        return if (isDash) {
            Log.i(TAG, "Official-like HDR path using DashMediaSource for $urlString")
            DashMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        } else {
            Log.i(TAG, "Official-like HDR path using HlsMediaSource for $urlString")
            HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        }
    }

    private fun buildTrackParameters(audioDisabled: Boolean = false) =
        trackSelector.buildUponParameters()
            .applyOfficialTrackParameters(audioDisabled)
            .build()

    private fun DefaultTrackSelector.Parameters.Builder.applyOfficialTrackParameters(
        audioDisabled: Boolean = false
    ) = apply {
        val isLegacySeason = ChannelPlaybackFragment.findSeasonYear(requireActivity()) <= 2025
        setMaxVideoSize(
            if (isLegacySeason) 1920 else Int.MAX_VALUE,
            if (isLegacySeason) 1080 else Int.MAX_VALUE
        )
        setMaxVideoBitrate(Int.MAX_VALUE)
        setForceHighestSupportedBitrate(true)
        setPreferredAudioLanguage(null)
        setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, audioDisabled)
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(
            TAG,
            "Official-like HDR player error ${error.errorCodeName} (${error.errorCode}) " +
                "message=${error.message}",
            error
        )
        (activity as? ChannelPlaybackActivity)?.playerError()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.i(
            TAG,
            "Official-like HDR playback state=$playbackState " +
                "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                "position=${player.currentPosition} buffered=${player.bufferedPosition}"
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.i(TAG, "Official-like HDR isPlaying=$isPlaying")
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        Log.i(
            TAG,
            "Official-like HDR videoSize ${videoSize.width}x${videoSize.height} " +
                "pixelRatio=${videoSize.pixelWidthHeightRatio}"
        )
        HdrPresentationDiagnostics.logDisplaySnapshot(requireContext(), "officialLike-videoSizeChanged")
    }

    companion object {
        private val TAG = OfficialLikeHdrPlaybackFragment::class.simpleName
        private const val ARG_VIEWING =
            "fr.groggy.racecontrol.tv.ui.channel.playback.OfficialLikeHdrPlaybackFragment.ARG_VIEWING"

        fun newInstance(viewing: F1TvViewing) = OfficialLikeHdrPlaybackFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_VIEWING, viewing)
            }
        }

        private fun findViewing(args: Bundle): F1TvViewing? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getParcelable(ARG_VIEWING, F1TvViewing::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getParcelable(ARG_VIEWING)
            }
        }
    }
}
