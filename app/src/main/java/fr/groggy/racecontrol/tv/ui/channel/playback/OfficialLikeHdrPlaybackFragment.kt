package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
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
import fr.groggy.racecontrol.tv.ui.player.ExoPlayerPlaybackTransportControlGlue
import fr.groggy.racecontrol.tv.utils.DeviceInfo
import javax.inject.Inject

/**
 * UHD/HDR-only Media3 path that mirrors the official TV app's visible structure:
 * a black full-screen container, one secure SurfaceView, and the raw holder Surface
 * passed directly to MediaCodec through ExoPlayer.
 */
@AndroidEntryPoint
class OfficialLikeHdrPlaybackFragment : VideoSupportFragment(), Player.Listener {

    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory
    @Inject internal lateinit var settingsRepository: SettingsRepository

    private var playbackSurfaceView: SurfaceView? = null
    private var playbackSurface: Surface? = null
    private var prepared = false
    private var currentViewing: F1TvViewing? = null
    private var playbackGlue: ExoPlayerPlaybackTransportControlGlue? = null
    private var nativeHdrPresentationWatchdogRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

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
            enableOfficialLikeDirectHdrCodecConfig = false,
            // Hardware (decoder-side) HDR->SDR tone mapping via
            // KEY_COLOR_TRANSFER_REQUEST was tested on 2026-08-21 and is NOT
            // supported by c2.mtk.hevc.decoder.secure: the codec reported back
            // `color-transfer-request = 0` and its output stayed HLG
            // (`color-transfer = 7`), i.e. the request was silently ignored.
            // Left wired up but off so it is not re-tried blindly.
            enableHardwareToneMapping = false,
            // CryptoInfo diagnostic (2026-08-21) confirmed correct: pattern
            // (encryptedBlocks=1, clearBlocks=9) and the constant IV match the
            // real tenc box exactly. Sample decryption parameters are not the
            // cause. Left available but off.
            enableCryptoDiagnostics = false,
            // DIAGNOSTIC (2026-08-22): force Google's pure-software HEVC
            // decoder (c2.android.hevc.decoder) instead of MediaTek's.
            // Forcing Widevine L3 proved the non-secure MediaTek decoder
            // (c2.mtk.hevc.decoder) still renders green with clear content --
            // exonerating DRM/secure buffers/TEE/HDCP entirely. The remaining
            // pattern: SDR is hev1.1.6 (Main, 8-bit) and works, HDR is
            // hev1.2.4 (Main10, 10-bit) and is green -- pointing at MediaTek's
            // 10-bit HEVC handling. Software decode bypasses it completely.
            enableSoftwareVideoDecoder = SOFTWARE_DECODER_DIAGNOSTIC
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
                        scheduleNativeHdrPresentationWatchdog()
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
        if (!ALLOW_SCREENCAPTURE_DIAGNOSTIC) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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
    ): View? {
        return super.onCreateView(inflater, container, savedInstanceState)?.also { root ->
            configureOfficialLikeSurface(root, "onCreateView")
        }
    }

    override fun onResume() {
        super.onResume()
        configureOfficialLikeSurface(view, "onResume")
        prepareWhenSurfaceReady("onResume")
    }

    override fun onPause() {
        player.pause()
        Log.i(TAG, "Paused official-like HDR player")
        super.onPause()
    }

    override fun onDestroyView() {
        cancelNativeHdrPresentationWatchdog()
        playbackGlue = null
        playbackSurfaceView?.holder?.removeCallback(surfaceCallback)
        playbackSurface?.let(player::clearVideoSurface)
        playbackSurface = null
        playbackSurfaceView = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cancelNativeHdrPresentationWatchdog()
        player.release()
        Log.i(TAG, "Released official-like HDR player")
        super.onDestroy()
    }

    private fun configureOfficialLikeSurface(root: View?, source: String): SurfaceView? {
        val surfaceView = findSurfaceView(root) ?: runCatching { surfaceView }.getOrNull()
        if (surfaceView == null) {
            Log.w(TAG, "Official-like HDR SurfaceView unavailable source=$source")
            return null
        }
        if (playbackSurfaceView !== surfaceView) {
            playbackSurfaceView?.holder?.removeCallback(surfaceCallback)
            playbackSurfaceView = surfaceView
            surfaceView.keepScreenOn = true
            // DIAGNOSTIC (2026-08-22): with FORCE_WIDEVINE_L3_DIAGNOSTIC the
            // content is decrypted in software and decoded by a NON-secure
            // decoder, so the surface does not need to be secure -- and making
            // it non-secure lets `adb screencap` capture what is actually being
            // rendered. That distinguishes "the decoder emits green pixels"
            // from "the pixels are correct but the HDMI/display path shows
            // green", which nothing in logcat can tell us apart.
            surfaceView.setSecure(!ALLOW_SCREENCAPTURE_DIAGNOSTIC)
            surfaceView.setZOrderOnTop(false)
            surfaceView.setZOrderMediaOverlay(false)
            surfaceView.holder.addCallback(surfaceCallback)
            Log.i(
                TAG,
                "Configured official-like secure HDR SurfaceView source=$source " +
                    "view=${System.identityHashCode(surfaceView)} " +
                    "surfaceSecure=${!ALLOW_SCREENCAPTURE_DIAGNOSTIC}"
            )
        }
        return surfaceView
    }

    private fun findSurfaceView(root: View?): SurfaceView? {
        return when (root) {
            null -> null
            is SurfaceView -> root
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    val found = findSurfaceView(root.getChildAt(index))
                    if (found != null) return found
                }
                null
            }
            else -> null
        }
    }

    private fun ensurePlaybackGlue() {
        if (playbackGlue != null) return
        val glue = ExoPlayerPlaybackTransportControlGlue(
            requireActivity(),
            player,
            trackSelector,
            isCustomRadioSelectable = { false },
            onPlayRequested = {
                player.play()
            },
            onPauseRequested = {
                player.pause()
            },
            onControlsInteraction = {
                showControlsOverlay(true)
            },
            onPlayerMenuDismissed = {
                hideControlsOverlay(false)
            }
        )
        playbackGlue = glue
        glue.host = VideoSupportFragmentGlueHost(this)
    }

    private fun scheduleNativeHdrPresentationWatchdog() {
        val viewing = currentViewing ?: return
        if (!ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing)) return
        if (nativeHdrPresentationWatchdogRunnable != null) return

        val runnable = Runnable {
            nativeHdrPresentationWatchdogRunnable = null
            if (!isAdded) return@Runnable
            val snapshot = HdrPresentationDiagnostics.snapshot(
                requireContext(),
                "officialLike-nativeHdrPresentationWatchdog"
            )
            if (snapshot == null) {
                Log.w(TAG, "Native HDR presentation watchdog inconclusive; no display snapshot")
                return@Runnable
            }
            if (!snapshot.canDiagnoseNativeHdrPresentation) {
                Log.i(
                    TAG,
                    "Native HDR presentation watchdog inconclusive on this Android/display API " +
                        "snapshot=$snapshot"
                )
                return@Runnable
            }
            if (snapshot.confirmsNativeHlgPresentation) {
                Log.i(TAG, "Native HDR presentation watchdog passed snapshot=$snapshot")
                return@Runnable
            }

            val reason = snapshot.nativeHlgFailureReason()
            Log.w(TAG, "Native HDR presentation watchdog failed reason=$reason snapshot=$snapshot")
            (activity as? ChannelPlaybackActivity)?.hdrPresentationFailed(reason)
        }
        nativeHdrPresentationWatchdogRunnable = runnable
        handler.postDelayed(runnable, NATIVE_HDR_PRESENTATION_WATCHDOG_MS)
    }

    private fun cancelNativeHdrPresentationWatchdog() {
        val runnable = nativeHdrPresentationWatchdogRunnable ?: return
        handler.removeCallbacks(runnable)
        nativeHdrPresentationWatchdogRunnable = null
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
        // A ~2.5s pre-prepare delay (2026-08-22, to test whether HDCP
        // negotiation over HDMI is asynchronous relative to the secure
        // Surface becoming valid) made no difference -- still green. Ruled
        // out; reverted to preparing immediately.
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
            ensurePlaybackGlue()
            // DIAGNOSTIC (2026-08-22): if a local test file is present, play it
            // through this exact same rendering path instead of the F1 stream.
            // The screencap proved the decoder emits a uniformly zero-filled
            // buffer (RGB 0,26,0) with no image data, ruling out compositing,
            // display, DRM and colour conversion. This isolates the remaining
            // two candidates: our rendering path vs. how we feed F1's specific
            // stream. Known-good 10-bit HLG BT.2020 content, so if this renders
            // the path is fine and the stream handling is at fault.
            // Must live in the app's own external files dir -- scoped storage
            // denies reading arbitrary /sdcard paths (EACCES) on Android 11+.
            val testFile = java.io.File(requireContext().getExternalFilesDir(null), "hdrtest.mp4")
            if (LOCAL_TEST_FILE_DIAGNOSTIC && testFile.exists()) {
                Log.i(TAG, "DIAGNOSTIC: playing local HLG test file ${testFile.absolutePath} (${testFile.length()} bytes)")
                trackSelector.setParameters(buildTrackParameters(audioDisabled = false))
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(testFile)))
                player.prepare()
                player.play()
                return
            }
            val useExternalAudio = viewing.externalAudioUri != null &&
                (settingsRepository.getCurrent().useExternalAudio || viewing.externalAudioRequired)
            val disableEmbeddedAudio =
                ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing) && !useExternalAudio
            trackSelector.setParameters(buildTrackParameters(audioDisabled = disableEmbeddedAudio))
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

        // Tested single-source embedded audio (one DRM session, matching the
        // official app) on 2026-08-21 -- still green. Ruled out; reverted to
        // the companion-audio path so custom radio and audio offset work again.
        val useExternalAudio = viewing.externalAudioUri != null &&
            (settingsRepository.getCurrent().useExternalAudio || viewing.externalAudioRequired)
        if (!useExternalAudio) {
            Log.i(TAG, "Official-like HDR path using video-only source; no companion audio available")
            return FilteringMediaSource(rawMainSource, C.TRACK_TYPE_VIDEO)
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
            val useF1DashUhdHdrFixes = shouldUseF1DashUhdHdrFixes(urlString, streamType)
            val rewriteF1DashInit = shouldRewriteF1DashUhdHdrInit(urlString, streamType)
            Log.i(
                TAG,
                "Official-like HDR path using DashMediaSource for $urlString " +
                    "f1UhdHdrFixes=$useF1DashUhdHdrFixes rewriteInit=$rewriteF1DashInit"
            )
            val dashDataSourceFactory = if (rewriteF1DashInit) {
                val isLiveSession = ChannelPlaybackFragment.findIsLiveSession(requireActivity())
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
            if (useF1DashUhdHdrFixes && FORCE_WIDEVINE_L3_DIAGNOSTIC) {
                factory.setDrmSessionManagerProvider(
                    ForceWidevineL3DrmSessionManagerProvider(httpDataSourceFactory)
                )
            } else if (LOG_CDM_KEY_STATUS_DIAGNOSTIC) {
                factory.setDrmSessionManagerProvider(
                    KeyStatusLoggingDrmSessionManagerProvider(httpDataSourceFactory)
                )
            }
            factory.createMediaSource(mediaItem)
        } else {
            Log.i(TAG, "Official-like HDR path using HlsMediaSource for $urlString")
            HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(mediaItem)
        }
    }

    private fun shouldUseF1DashUhdHdrFixes(urlString: String, streamType: String?): Boolean {
        // EXPERIMENT (2026-08-22): disable ALL custom F1 DASH handling and play
        // F1's HDR stream through completely vanilla Media3.
        //
        // A synthetic 4K 10-bit HLG BT.2020 clip plays correctly through this
        // exact fragment/surface/decoder, in both hvc1 and hev1 flavours -- so
        // the rendering path, the surface, the MediaTek decoder and the device
        // are all fine, and hev1 in-band parameter sets are not the problem.
        // What has never been tested is F1's stream WITHOUT our custom
        // extractor (F1DashChunkExtractorFactory), custom manifest parser
        // (F1DynamicHvcCDashManifestParser) and init-segment rewriter -- every
        // prior test ran through at least one of them.
        if (VANILLA_MEDIA3_DIAGNOSTIC) return false
        val identity = "$urlString ${streamType.orEmpty()}".uppercase()
        val isDash = identity.contains("DASH") || identity.contains(".MPD")
        val isHdrOrUhd = identity.contains("HDR") || identity.contains("UHD")
        return isDash && isHdrOrUhd
    }

    private fun shouldRewriteF1DashUhdHdrInit(urlString: String, streamType: String?): Boolean {
        // Tested disabling this entirely on 2026-08-21 (hypothesis: injecting a
        // synthesized hvcC into hev1 content, which carries parameter sets
        // in-band, was itself causing the green screen). Still green either
        // way, and a byte-for-byte SurfaceFlinger comparison against the
        // official app's working layer ruled out compositing/format as the
        // cause too -- so the injection is exonerated. Reverted: this repair
        // is still needed for genuinely broken (empty numOfArrays=0) init
        // segments, e.g. live content still on Akamai (see
        // docs/uhd-hdr-green-video-handoff.md, 2026-08-21 entry).
        return shouldUseF1DashUhdHdrFixes(urlString, streamType)
    }

    private fun buildTrackParameters(audioDisabled: Boolean = false) =
        trackSelector.buildUponParameters()
            .applyOfficialTrackParameters(audioDisabled)
            .build()

    private fun DefaultTrackSelector.Parameters.Builder.applyOfficialTrackParameters(
        audioDisabled: Boolean = false
    ) = apply {
        val isLegacySeason = ChannelPlaybackFragment.findSeasonYear(requireActivity()) <= 2025
        val capTo1080p = isLegacySeason || SOFTWARE_DECODER_DIAGNOSTIC
        setMaxVideoSize(
            if (capTo1080p) 1920 else Int.MAX_VALUE,
            if (capTo1080p) 1080 else Int.MAX_VALUE
        )
        setMaxVideoBitrate(Int.MAX_VALUE)
        // Letting ABR start low and adapt upward (2026-08-21, to test whether
        // configuring the secure decoder directly at 4K was the cause) made no
        // difference -- green at 480x270 too. Ruled out; reverted to forcing
        // the top track directly, since that's what we actually want to play
        // and unpredictable ramp-up only made testing slower and noisier.
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
        // Without this, the SurfaceView's BufferQueue stays at whatever size
        // Leanback laid it out at (e.g. 1920x1080) while MediaCodec tries to
        // write actual 3840x2160 secure/protected buffers into it -- a
        // mismatch between the view's logical buffer size and the producer's
        // real output size that can fail protected buffer allocation
        // (createGraphicBuffer failed) rather than just being scaled, unlike
        // regular unprotected content.
        if (videoSize.width > 0 && videoSize.height > 0) {
            playbackSurfaceView?.holder?.setFixedSize(videoSize.width, videoSize.height)
        }
        HdrPresentationDiagnostics.logDisplaySnapshot(requireContext(), "officialLike-videoSizeChanged")
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

    companion object {
        private val TAG = OfficialLikeHdrPlaybackFragment::class.simpleName
        private const val NATIVE_HDR_PRESENTATION_WATCHDOG_MS = 3_000L
        private const val LIVE_HDR_VISUAL_FALLBACK_WATCHDOG_MS = 7_000L
        private const val HDCP_SETTLE_DELAY_MS = 2_500L
        // DIAGNOSTIC (2026-08-22): force Widevine L3 (software decode, no
        // MediaTek secure hardware/TEE path) to isolate whether the green
        // screen is specific to the secure hardware decode path. See
        // ForceWidevineL3DrmSessionManagerProvider for the full reasoning.
        private const val FORCE_WIDEVINE_L3_DIAGNOSTIC = false
        // Log the key IDs the CDM actually holds, to compare against the KID the
        // content asks for in CryptoInfo.
        private const val LOG_CDM_KEY_STATUS_DIAGNOSTIC = false
        // Requires FORCE_WIDEVINE_L3_DIAGNOSTIC (a software decoder cannot
        // consume L1 protected buffers). Caps resolution to 1080p because
        // software 4K50 Main10 HEVC decode is not feasible on this CPU -- the
        // point is to check colour correctness, not smoothness.
        // Strict software-only selection could not engage: Media3 still asked for
        // a secure decoder (requiresSecureDecoder=true) and the only secure HEVC
        // decoder on this device is MediaTek's, so the filter fell back. The run
        // was still useful -- it showed the SDR/BT.709 tracks in F1's HDR
        // manifest decode fine on the same MediaTek decoder.
        private const val SOFTWARE_DECODER_DIAGNOSTIC = false
        // Play /Android/data/<pkg>/files/hdrtest.mp4 through this path instead of
        // the F1 stream. Proved the rendering path handles 4K 10-bit HLG fine.
        private const val LOCAL_TEST_FILE_DIAGNOSTIC = false
        // Play F1's HDR stream through unmodified Media3 (no custom extractor,
        // manifest parser, or init-segment rewriting).
        private const val VANILLA_MEDIA3_DIAGNOSTIC = false
        // Requires FORCE_WIDEVINE_L3_DIAGNOSTIC. Drops setSecure() so screencap
        // can capture the rendered frame. NEVER ship this enabled -- it exists
        // purely to tell rendering and display-path failures apart.
        private const val ALLOW_SCREENCAPTURE_DIAGNOSTIC = false
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
