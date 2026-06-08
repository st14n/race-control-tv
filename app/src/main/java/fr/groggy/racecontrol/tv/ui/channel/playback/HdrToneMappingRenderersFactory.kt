package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr.ProtectedHlgGlObjectsProvider

class HdrToneMappingRenderersFactory(
    context: Context,
    private val enableHdrToSdrToneMapping: Boolean,
    private val enableProtectedHlgVideoGraph: Boolean,
    private val enableOfficialLikeDirectHdrCodecConfig: Boolean
) : DefaultRenderersFactory(context) {

    companion object {
        private val TAG = HdrToneMappingRenderersFactory::class.simpleName
    }

    private val appContext = context.applicationContext

    init {
        setEnableDecoderFallback(true)
    }

    private fun shouldForceSynchronousCodecQueueing(): Boolean {
        val identity = listOf(
            Build.BRAND,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT
        ).joinToString(separator = " ").lowercase()
        return "google tv streamer" in identity || "kirkwood" in identity
    }

    private fun codecAdapterFactory(): MediaCodecAdapter.Factory {
        return if (shouldForceSynchronousCodecQueueing()) {
            Log.i(TAG, "Forcing synchronous codec queueing for Google TV Streamer")
            SynchronousMediaCodecAdapter.Factory()
        } else {
            MediaCodecAdapter.Factory.DEFAULT
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )

        if (!enableProtectedHlgVideoGraph &&
            !enableHdrToSdrToneMapping &&
            !enableOfficialLikeDirectHdrCodecConfig
        ) {
            return
        }

        for (index in out.indices) {
            val renderer = out[index]
            if (renderer is MediaCodecVideoRenderer) {
                out[index] = when {
                    enableProtectedHlgVideoGraph -> {
                        Log.i(TAG, "Installing Media3 protected HLG video graph renderer")
                        ProtectedHlgGraphMediaCodecVideoRenderer(
                            context = appContext,
                            codecAdapterFactory = codecAdapterFactory(),
                            mediaCodecSelector = mediaCodecSelector,
                            allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
                            enableDecoderFallback = enableDecoderFallback,
                            eventHandler = eventHandler,
                            eventListener = eventListener
                        )
                    }
                    enableHdrToSdrToneMapping -> {
                        ToneMappingMediaCodecVideoRenderer(
                            context = appContext,
                            codecAdapterFactory = codecAdapterFactory(),
                            mediaCodecSelector = mediaCodecSelector,
                            allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
                            enableDecoderFallback = enableDecoderFallback,
                            eventHandler = eventHandler,
                            eventListener = eventListener
                        )
                    }
                    else -> {
                        Log.i(TAG, "Installing official-like direct MediaCodec configuration renderer")
                        OfficialLikeDirectHdrMediaCodecVideoRenderer(
                            context = appContext,
                            codecAdapterFactory = codecAdapterFactory(),
                            mediaCodecSelector = mediaCodecSelector,
                            allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
                            enableDecoderFallback = enableDecoderFallback,
                            eventHandler = eventHandler,
                            eventListener = eventListener
                        )
                    }
                }
            }
        }
    }
}

private class ProtectedHlgGraphMediaCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener
) : MediaCodecVideoRenderer(
    Builder(context)
        .setCodecAdapterFactory(codecAdapterFactory)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
) {

    init {
        // An empty effect list is enough to make Media3 route decoder output through VideoSink/GL.
        setVideoEffects(emptyList<Effect>())
    }

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int
    ): MediaFormat {
        return OfficialLikeHdrMediaFormat.configure(
            mediaFormat = super.getMediaFormat(
                format,
                codecMimeType,
                codecMaxValues,
                codecOperatingRate,
                deviceNeedsNoPostProcessWorkaround,
                tunnelingAudioSessionId
            ),
            format = format,
            codecMimeType = codecMimeType,
            rendererPath = "protected_hlg_graph"
        )
    }

    override fun getCodecOperatingRateV23(
        operatingRate: Float,
        format: Format,
        streamFormats: Array<Format>
    ): Float {
        return OfficialLikeHdrMediaFormat.getCodecOperatingRateV23(
            rendererPath = "protected_hlg_graph",
            format = format,
            fallback = {
                super.getCodecOperatingRateV23(
                    operatingRate,
                    format,
                    streamFormats
                )
            }
        )
    }

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper {
        val videoFrameProcessorFactory = DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(ProtectedHlgGlObjectsProvider(forceSdrOutput = false))
            .build()
        val videoGraphFactory = SingleInputVideoGraph.Factory(videoFrameProcessorFactory)
        return PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .setVideoGraphFactory(videoGraphFactory)
            .build()
    }
}

private class OfficialLikeDirectHdrMediaCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener
) : MediaCodecVideoRenderer(
    Builder(context)
        .setCodecAdapterFactory(codecAdapterFactory)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
) {

    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: MediaCodecVideoRenderer.CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int
    ): MediaFormat {
        return OfficialLikeHdrMediaFormat.configure(
            mediaFormat = super.getMediaFormat(
                format,
                codecMimeType,
                codecMaxValues,
                codecOperatingRate,
                deviceNeedsNoPostProcessWorkaround,
                tunnelingAudioSessionId
            ),
            format = format,
            codecMimeType = codecMimeType,
            rendererPath = "direct_secure_surface"
        )
    }

    override fun getCodecOperatingRateV23(
        operatingRate: Float,
        format: Format,
        streamFormats: Array<Format>
    ): Float {
        return OfficialLikeHdrMediaFormat.getCodecOperatingRateV23(
            rendererPath = "direct_secure_surface",
            format = format,
            fallback = {
                super.getCodecOperatingRateV23(
                    operatingRate,
                    format,
                    streamFormats
                )
            }
        )
    }
}

private class ToneMappingMediaCodecVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener
) : MediaCodecVideoRenderer(
    Builder(context)
        .setCodecAdapterFactory(codecAdapterFactory)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
) {

    init {
        // Use an almost-identity RGB effect so Media3 cannot optimize the graph away as a no-op.
        // This is required to force Media3 to actually create and use the PlaybackVideoGraphWrapper
        // for OpenGL-based tone mapping.
        setVideoEffects(
            listOf<Effect>(
                RgbAdjustment.Builder()
                    .setRedScale(0.9999f)
                    .setGreenScale(1.0f)
                    .setBlueScale(1.0f)
                    .build()
            )
        )
        Log.i(
            HdrToneMappingRenderersFactory::class.simpleName,
            "Installed near-identity RGB effect to force Tone Mapping graph output path"
        )
    }

    override fun createPlaybackVideoGraphWrapper(
        context: Context,
        videoFrameReleaseControl: VideoFrameReleaseControl
    ): PlaybackVideoGraphWrapper {
        val videoFrameProcessorFactory = DefaultVideoFrameProcessor.Factory.Builder()
            .setGlObjectsProvider(ProtectedHlgGlObjectsProvider(forceSdrOutput = true))
            .build()
        val videoGraphFactory = SingleInputVideoGraph.Factory(videoFrameProcessorFactory)
        return PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
            .setEnablePlaylistMode(true)
            .setVideoGraphFactory(videoGraphFactory)
            .build().also {
            it.setRequestOpenGlToneMapping(true)
        }
    }
}

private object OfficialLikeHdrMediaFormat {

    private val TAG = HdrToneMappingRenderersFactory::class.simpleName

    fun configure(
        mediaFormat: MediaFormat,
        format: Format,
        codecMimeType: String,
        rendererPath: String
    ): MediaFormat {
        if (!looksLikeF1UhdHlgHevc(format, codecMimeType)) {
            return mediaFormat
        }

        mediaFormat.setInteger(KEY_PRIORITY, 0)
        mediaFormat.setInteger(KEY_ROTATION_DEGREES, 0)
        if (format.frameRate > 0f) {
            mediaFormat.setFloat(KEY_FRAME_RATE, format.frameRate)
        }
        Log.i(
            TAG,
            "Applied official-like secure HLG MediaCodec format flags " +
                "rendererPath=$rendererPath sampleMime=${format.sampleMimeType} codecMime=$codecMimeType " +
                "size=${format.width}x${format.height} frameRate=${format.frameRate} " +
                "priority=0 operatingRate=unset"
        )
        return mediaFormat
    }

    fun getCodecOperatingRateV23(
        rendererPath: String,
        format: Format,
        fallback: () -> Float
    ): Float {
        if (!looksLikeF1UhdHlgHevc(format, format.sampleMimeType.orEmpty())) {
            return fallback()
        }

        Log.i(
            TAG,
            "Suppressing Media3 MediaCodec operating-rate hint for official-like secure HLG path " +
                "rendererPath=$rendererPath format=${format.id} size=${format.width}x${format.height} " +
                "frameRate=${format.frameRate}"
        )
        return CODEC_OPERATING_RATE_UNSET
    }

    private fun looksLikeF1UhdHlgHevc(format: Format, codecMimeType: String): Boolean {
        val sampleMime = format.sampleMimeType
        val descriptor = listOfNotNull(format.id, format.label, format.codecs)
            .joinToString(separator = " ")
        val isHevc = sampleMime.equals(HEVC_MIME_TYPE, ignoreCase = true) ||
            codecMimeType.equals(HEVC_MIME_TYPE, ignoreCase = true) ||
            descriptor.contains("HEVC", ignoreCase = true) ||
            descriptor.contains("hvc", ignoreCase = true)
        val isUhd = format.width >= 3000 && format.height >= 1600 ||
            descriptor.contains("UHD", ignoreCase = true) ||
            descriptor.contains("2160", ignoreCase = true)
        val isHlg = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG ||
            descriptor.contains("HDR", ignoreCase = true) ||
            descriptor.contains("HLG", ignoreCase = true)
        return isHevc && isUhd && isHlg
    }

    private const val HEVC_MIME_TYPE = "video/hevc"
    private const val CODEC_OPERATING_RATE_UNSET = -1f
    private const val KEY_ROTATION_DEGREES = "rotation-degrees"
    private const val KEY_PRIORITY = "priority"
    private const val KEY_FRAME_RATE = "frame-rate"
}
