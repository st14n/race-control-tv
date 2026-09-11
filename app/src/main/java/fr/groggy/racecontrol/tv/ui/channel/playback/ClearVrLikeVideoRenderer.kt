package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.FrameworkCryptoConfig
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource

/**
 * A small, independent raw MediaCodec renderer based on the observable ClearVR bridge contract.
 *
 * Media3 remains responsible for manifest loading, DASH extraction and license sessions. This
 * renderer owns the codec boundary: it supplies initialization bytes as a separate codec-config
 * buffer, forwards Media3's sample CryptoInfo/PTS unchanged, ignores vendor crop metadata, and
 * releases decoded buffers directly to the supplied Surface with a presentation timestamp.
 */
@UnstableApi
class ClearVrLikeVideoRenderer(
    private val context: Context,
    private val codecSelector: MediaCodecSelector
) : BaseRenderer(C.TRACK_TYPE_VIDEO) {

    private val formatHolder = FormatHolder()
    private val sourceBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    private val outputInfo = MediaCodec.BufferInfo()

    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var mediaCrypto: MediaCrypto? = null
    private var currentFormat: Format? = null
    private var currentDrmSession: DrmSession? = null
    private var headerData: ByteArray? = null
    private var headerQueued = false
    private var pendingSample = false
    private var pendingEndOfStream = false
    private var inputEnded = false
    private var outputEnded = false
    private var firstFrameLogged = false
    private var firstSampleLogged = false
    private var lastOutputFormat: MediaFormat? = null
    // Media3 can expose an absolute live/DASH timeline PTS while the renderer
    // position is relative to the current playback window. ClearVR receives a
    // separate native wall-clock render timestamp, so anchor that absolute PTS
    // to the first decoded frame instead of subtracting positionUs directly.
    private var renderPtsAnchorUs: Long? = null
    private var renderTimeAnchorNs: Long = 0L
    private var renderCallCount = 0L
    private var positionResetCount = 0L
    private var enabledCount = 0L
    private var queuedSampleCount = 0L
    private var inputBufferUnavailableCount = 0L

    override fun getName(): String = "ClearVrLikeVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.VIDEO_H265) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            RendererCapabilities.HARDWARE_ACCELERATION_SUPPORTED,
            RendererCapabilities.DECODER_SUPPORT_PRIMARY
        )
    }

    override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType != Renderer.MSG_SET_VIDEO_OUTPUT) {
            return
        }

        val newSurface = message as? Surface
        if (surface === newSurface) {
            return
        }
        surface = newSurface
        Log.i(TAG, "Direct codec surface changed present=${newSurface != null}")

        val activeCodec = codec
        if (activeCodec == null || newSurface == null) {
            if (newSurface == null) {
                releaseCodec()
            }
            return
        }

        if (Build.VERSION.SDK_INT >= 23) {
            runCatching { activeCodec.setOutputSurface(newSurface) }
                .onFailure {
                    Log.w(TAG, "setOutputSurface failed; restarting codec", it)
                    releaseCodec()
                }
        } else {
            releaseCodec()
        }
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        enabledCount++
        Log.i(
            TAG,
            "Lifecycle onEnabled #$enabledCount joining=$joining mayRenderStartOfStream=$mayRenderStartOfStream " +
                "codec=${codec != null}"
        )
        inputEnded = false
        outputEnded = false
        firstFrameLogged = false
        firstSampleLogged = false
    }

    override fun onStreamChanged(
        formats: Array<Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        // The first FORMAT_READ drives codec creation. The stream offset remains Media3's concern;
        // sample PTS values are forwarded exactly as delivered by the source.
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, offsetChange: Boolean) {
        positionResetCount++
        Log.i(
            TAG,
            "Lifecycle onPositionReset #$positionResetCount positionUs=$positionUs joining=$joining " +
                "offsetChange=$offsetChange codec=${codec != null} inputEnded=$inputEnded outputEnded=$outputEnded"
        )
        inputEnded = false
        outputEnded = false
        headerQueued = false
        pendingSample = false
        pendingEndOfStream = false
        renderPtsAnchorUs = null
        renderTimeAnchorNs = 0L
        codec?.flush()
    }

    override fun onDisabled() {
        Log.i(TAG, "Lifecycle onDisabled codec=${codec != null} renderCalls=$renderCallCount resets=$positionResetCount")
        releaseCodec()
        currentFormat = null
        currentDrmSession = null
        headerData = null
        headerQueued = false
        pendingSample = false
        pendingEndOfStream = false
    }

    override fun onReset() {
        Log.i(TAG, "Lifecycle onReset codec=${codec != null} renderCalls=$renderCallCount resets=$positionResetCount")
        releaseCodec()
        currentFormat = null
        currentDrmSession = null
        surface = null
        pendingSample = false
        pendingEndOfStream = false
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        renderCallCount++
        if (renderCallCount == 1L || renderCallCount % 120L == 0L) {
            Log.i(
                TAG,
                "Lifecycle render #$renderCallCount positionUs=$positionUs elapsedRealtimeUs=$elapsedRealtimeUs " +
                    "codec=${codec != null} pendingSample=$pendingSample headerQueued=$headerQueued " +
                    "inputEnded=$inputEnded outputEnded=$outputEnded"
            )
        }
        readAndQueueInput()
        drainOutput(positionUs)
        readAndQueueInput()
        drainOutput(positionUs)
    }

    override fun isReady(): Boolean = codec != null && !outputEnded

    override fun isEnded(): Boolean = outputEnded

    private fun readAndQueueInput() {
        if (getStream() == null) return

        repeat(MAX_INPUTS_PER_RENDER) {
            if (inputEnded) return

            if (!pendingSample && !pendingEndOfStream) {
                sourceBuffer.clear()
                val result = readSource(formatHolder, sourceBuffer, 0)
                when (result) {
                    C.RESULT_NOTHING_READ -> return
                    C.RESULT_FORMAT_READ -> {
                        val format = formatHolder.format ?: return@repeat
                        currentDrmSession = formatHolder.drmSession
                        formatHolder.clear()
                        val oldFormat = currentFormat
                        val dimensionsChanged = oldFormat != null && (
                            oldFormat.width != format.width ||
                                oldFormat.height != format.height ||
                                oldFormat.decodedWidth != format.decodedWidth ||
                                oldFormat.decodedHeight != format.decodedHeight
                            )
                        // DIAGNOSTIC (2026-09-11): ClearVR normally keeps a compatible HEVC
                        // decoder alive across ladder changes via MediaCodec's in-band format
                        // update instead of hard reconfiguring. On this device that path
                        // desyncs the MediaTek Codec2 driver's internal work tracking right
                        // after the update ("PipelineWatcher: onWorkDone: frameIndex not
                        // found", then "C2MtkVdec: Unknown output workId") and has also been
                        // observed to report wrong color aspects for the new tier. Every hard
                        // configure() in this investigation has derived HDR color info
                        // correctly; no in-place update has. Force a full teardown/reconfigure
                        // on every resolution change instead of reusing the codec instance.
                        val hardReconfigure = oldFormat == null ||
                            requiresReconfigure(oldFormat, format) ||
                            dimensionsChanged
                        if (hardReconfigure) {
                            releaseCodec()
                        }
                        val headerChanged = hardReconfigure ||
                            !sameInitializationData(oldFormat.initializationData, format.initializationData)
                        currentFormat = format
                        if (headerChanged) {
                            headerData = collectInitializationData(format)
                            headerQueued = false
                            if (oldFormat != null) {
                                Log.i(
                                    TAG,
                                    "Updating decoder (hardReconfigure=$hardReconfigure) " +
                                        "${oldFormat.width}x${oldFormat.height}->${format.width}x${format.height} " +
                                        "coded=${format.decodedWidth}x${format.decodedHeight} " +
                                        "initData=${format.initializationData.map { it.size }}"
                                )
                            }
                        }
                        return@repeat
                    }
                    C.RESULT_BUFFER_READ -> {
                        // The sample remains in sourceBuffer until the codec boundary accepts it.
                        // This is important when the Surface is created after the first read or
                        // when MediaCodec temporarily has no input slot.
                        sourceBuffer.flip()
                        if (sourceBuffer.isEndOfStream) {
                            pendingEndOfStream = true
                        } else {
                            pendingSample = true
                        }
                    }
                    else -> return
                }
            }

            if (!ensureCodec()) return
            if (pendingEndOfStream) {
                if (queueEndOfStream()) {
                    pendingEndOfStream = false
                    inputEnded = true
                }
                return
            }
            if (pendingSample) {
                if (!queueHeaderIfNeeded(sourceBuffer)) return
                if (!queueSample(sourceBuffer)) return
                pendingSample = false
            }
        }
    }

    private fun ensureCodec(): Boolean {
        if (codec != null) return true
        val format = currentFormat ?: return false
        val outputSurface = surface ?: return false
        val drmSession = currentDrmSession
        if (format.drmInitData != null && (drmSession == null || drmSession.cryptoConfig == null)) {
            Log.i(TAG, "Waiting for DRM crypto config before codec creation")
            return false
        }
        val requiresSecure = drmSession?.requiresSecureDecoder(format.sampleMimeType ?: "") == true
        val decoderInfos = try {
            codecSelector.getDecoderInfos(format.sampleMimeType ?: return false, requiresSecure, false)
        } catch (e: Exception) {
            Log.e(TAG, "Decoder query failed", e)
            return false
        }
        val decoderInfo = decoderInfos.firstOrNull() ?: run {
            Log.e(TAG, "No decoder for mime=${format.sampleMimeType} secure=$requiresSecure")
            return false
        }

        val created = try {
            MediaCodec.createByCodecName(decoderInfo.name)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create decoder ${decoderInfo.name}", e)
            return false
        }

        val crypto = tryCreateMediaCrypto(drmSession)
        // The DASH representation dimensions are the display/crop dimensions.
        // ClearVR keeps those separate from the coded canvas it passes to
        // MediaCodec. Media3 exposes the latter after parsing the repaired SPS
        // as decodedWidth/decodedHeight; using the display size here makes the
        // MediaTek decoder report a 1920x1088 coded frame cropped to 640x360,
        // but it was configured as if the coded canvas were only 640x360.
        val codecWidth = (format.decodedWidth.takeIf { it > 0 } ?: format.width)
            .coerceAtLeast(MIN_INITIAL_CODEC_WIDTH)
        val codecHeight = (format.decodedHeight.takeIf { it > 0 } ?: format.height)
            .coerceAtLeast(MIN_INITIAL_CODEC_HEIGHT)
        val mediaFormat = MediaFormat.createVideoFormat(
            format.sampleMimeType ?: MimeTypes.VIDEO_H265,
            codecWidth.coerceAtLeast(2),
            codecHeight.coerceAtLeast(2)
        ).apply {
            // These are the only general format hints used by the official bridge. In particular,
            // do not add csd-*, max-width/max-height, max-input-size, or operating-rate.
            setInteger("rotation-degrees", 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (format.frameRate > 0f) setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate)
        }

        try {
            created.configure(mediaFormat, outputSurface, crypto, 0)
            created.start()
            codec = created
            mediaCrypto = crypto
            Log.i(
                TAG,
                "Configured ${decoderInfo.name} mime=${format.sampleMimeType} " +
                    "coded=${codecWidth}x${codecHeight} display=${format.width}x${format.height} " +
                    "secure=$requiresSecure " +
                    "initData=${format.initializationData.map { it.size }} " +
                    "color=${format.colorInfo}"
            )
            return true
        } catch (e: Exception) {
            runCatching { created.release() }
            crypto?.release()
            Log.e(TAG, "Codec configure/start failed", e)
            return false
        }
    }

    private fun tryCreateMediaCrypto(drmSession: DrmSession?): MediaCrypto? {
        val config = drmSession?.cryptoConfig as? FrameworkCryptoConfig ?: return null
        return runCatching { MediaCrypto(config.uuid, config.sessionId) }
            .onFailure { Log.w(TAG, "Could not create MediaCrypto from active session", it) }
            .getOrNull()
    }

    /**
     * Recovers VPS/SPS/PPS from the clear prefix of an access unit.
     *
     * F1's HLS/CMAF Formats reach this renderer with empty `initializationData`, so no
     * codec-config buffer was ever queued. Measured against the official app on the same
     * device and stream: its secure decoder instance logs `mSawInputCSD was set.` and
     * `CSD work returned`, ours never did. Without CSD the MediaTek SVP decoder still
     * initialises far enough to report the correct size and colour aspects, but never
     * fills the protected output buffer -- and a zero-filled YUV buffer composites as
     * uniform green, with no error raised anywhere. Parameter sets precede the first VCL
     * NAL and live in the clear subsample, so they can be read back out of the sample.
     */
    private fun extractParameterSetsFromSample(buffer: DecoderInputBuffer): ByteArray? {
        val data = buffer.data ?: return null
        val clearLimit = if (buffer.isEncrypted) {
            buffer.cryptoInfo.numBytesOfClearData?.firstOrNull() ?: return null
        } else {
            data.remaining()
        }
        val limit = minOf(clearLimit, data.remaining())
        if (limit <= 4) return null
        val bytes = ByteArray(limit)
        data.duplicate().get(bytes, 0, limit)

        fun startCodeLengthAt(index: Int): Int = when {
            index + 3 < bytes.size && bytes[index] == ZERO && bytes[index + 1] == ZERO &&
                bytes[index + 2] == ZERO && bytes[index + 3] == ONE -> 4
            index + 2 < bytes.size && bytes[index] == ZERO && bytes[index + 1] == ZERO &&
                bytes[index + 2] == ONE -> 3
            else -> 0
        }

        val out = ByteArrayOutputStream()
        var index = 0
        var found = false
        while (index < bytes.size) {
            val startCodeLength = startCodeLengthAt(index)
            if (startCodeLength == 0) {
                index++
                continue
            }
            val nalStart = index + startCodeLength
            if (nalStart >= bytes.size) break
            val nalType = (bytes[nalStart].toInt() shr 1) and 0x3F
            var next = nalStart
            while (next < bytes.size && startCodeLengthAt(next) == 0) next++
            val nalEnd = next
            when {
                nalType in VPS_NAL_TYPE..PPS_NAL_TYPE -> {
                    out.write(ANNEX_B_START_CODE)
                    out.write(bytes, nalStart, nalEnd - nalStart)
                    found = true
                }
                // Parameter sets always precede the first VCL NAL of the access unit.
                nalType < VPS_NAL_TYPE -> return if (found) out.toByteArray() else null
            }
            index = nalEnd
        }
        return if (found) out.toByteArray() else null
    }

    private fun queueHeaderIfNeeded(pending: DecoderInputBuffer?): Boolean {
        if (headerQueued) return true
        var data = headerData
        if ((data == null || data.isEmpty()) && pending != null) {
            data = extractParameterSetsFromSample(pending)?.also {
                headerData = it
                Log.i(TAG, "Recovered ${it.size}-byte CSD from sample clear prefix")
            }
        }
        if (data == null || data.isEmpty()) {
            headerQueued = true
            return true
        }
        val activeCodec = codec ?: return false
        val index = activeCodec.dequeueInputBuffer(0L)
        if (index < 0) return false
        val input = activeCodec.getInputBuffer(index) ?: return false
        input.clear()
        if (input.remaining() < data.size) {
            Log.e(TAG, "Codec-config input buffer too small capacity=${input.remaining()} required=${data.size}")
            return false
        }
        input.put(data)
        activeCodec.queueInputBuffer(index, 0, data.size, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        headerQueued = true
        Log.i(TAG, "Queued independent codec-config buffer size=${data.size}")
        return true
    }

    private fun queueSample(buffer: DecoderInputBuffer): Boolean {
        val activeCodec = codec ?: return false
        val input = activeCodec.dequeueInputBuffer(0L)
        if (input < 0) {
            inputBufferUnavailableCount++
            if (inputBufferUnavailableCount <= 3L || inputBufferUnavailableCount % 120L == 0L) {
                Log.i(
                    TAG,
                    "Codec input unavailable #$inputBufferUnavailableCount " +
                        "queuedSamples=$queuedSampleCount renderCalls=$renderCallCount pendingSample=$pendingSample"
                )
            }
            return false
        }
        val destination = activeCodec.getInputBuffer(input) ?: return false
        val data = buffer.data ?: return false
        val size = data.remaining()
        destination.clear()
        if (destination.remaining() < size) {
            Log.e(TAG, "Sample input buffer too small capacity=${destination.remaining()} required=$size")
            return false
        }
        destination.put(data)
        val cryptoInfo = if (buffer.isEncrypted) buffer.cryptoInfo.frameworkCryptoInfo else null
        if (!firstSampleLogged) {
            firstSampleLogged = true
            if (cryptoInfo == null) {
                Log.i(TAG, "First sample pts=${buffer.timeUs} size=$size clear keyFrame=${buffer.isKeyFrame}")
            } else {
                Log.i(
                    TAG,
                    "First encrypted sample pts=${buffer.timeUs} size=$size keyFrame=${buffer.isKeyFrame} " +
                        "mode=${cryptoInfo.mode} subs=${cryptoInfo.numSubSamples} " +
                        "clear=${cryptoInfo.numBytesOfClearData?.toList()} " +
                        "encrypted=${cryptoInfo.numBytesOfEncryptedData?.toList()} " +
                        "pattern=${cryptoInfo.pattern?.encryptBlocks}:${cryptoInfo.pattern?.skipBlocks} " +
                        "key=${cryptoInfo.key?.joinToString("") { "%02x".format(it) }} " +
                        "iv=${cryptoInfo.iv?.joinToString("") { "%02x".format(it) }}"
                )
            }
        }
        // ClearVR uses flags=0 for every ordinary access unit, including keyframes. It reserves
        // BUFFER_FLAG_CODEC_CONFIG (2) for the independent header buffer above.
        val flags = 0
        if (cryptoInfo != null) {
            activeCodec.queueSecureInputBuffer(input, 0, cryptoInfo, buffer.timeUs, flags)
        } else {
            activeCodec.queueInputBuffer(input, 0, size, buffer.timeUs, flags)
        }
        queuedSampleCount++
        if (queuedSampleCount <= 3L || queuedSampleCount % 120L == 0L) {
            Log.i(TAG, "Queued sample #$queuedSampleCount pts=${buffer.timeUs} size=$size")
        }
        return true
    }

    private fun queueEndOfStream(): Boolean {
        val activeCodec = codec ?: return false
        val input = activeCodec.dequeueInputBuffer(0L)
        if (input < 0) return false
        activeCodec.queueInputBuffer(input, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return true
    }

    private fun drainOutput(positionUs: Long) {
        val activeCodec = codec ?: return
        repeat(MAX_OUTPUTS_PER_RENDER) {
            val index = activeCodec.dequeueOutputBuffer(outputInfo, 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    lastOutputFormat = activeCodec.outputFormat
                    Log.i(TAG, "Decoder output format (crop ignored): $lastOutputFormat")
                }
                index >= 0 -> {
                    val flags = outputInfo.flags
                    if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        activeCodec.releaseOutputBuffer(index, false)
                        outputEnded = true
                        return
                    }
                    val nowNs = System.nanoTime()
                    val anchorPtsUs = renderPtsAnchorUs ?: outputInfo.presentationTimeUs.also {
                        renderPtsAnchorUs = it
                        renderTimeAnchorNs = nowNs
                        Log.i(TAG, "Anchored render clock pts=$it positionUs=$positionUs")
                    }
                    val relativeUs = outputInfo.presentationTimeUs - anchorPtsUs
                    val releaseNs = if (relativeUs <= 0L) {
                        nowNs
                    } else {
                        renderTimeAnchorNs + relativeUs * 1_000L
                    }
                    if (surface == null) {
                        activeCodec.releaseOutputBuffer(index, false)
                    } else if (Build.VERSION.SDK_INT >= 21) {
                        activeCodec.releaseOutputBuffer(index, releaseNs)
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            Log.i(TAG, "Released first decoded frame pts=${outputInfo.presentationTimeUs}")
                        }
                    } else {
                        activeCodec.releaseOutputBuffer(index, true)
                    }
                }
                else -> return
            }
        }
    }

    private fun releaseCodec() {
        if (codec != null) {
            Log.i(TAG, "Lifecycle releaseCodec renderCalls=$renderCallCount resets=$positionResetCount")
        }
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        mediaCrypto?.let { runCatching { it.release() } }
        mediaCrypto = null
        outputEnded = false
        inputEnded = false
        headerQueued = false
        queuedSampleCount = 0L
        inputBufferUnavailableCount = 0L
        lastOutputFormat = null
        renderPtsAnchorUs = null
        renderTimeAnchorNs = 0L
    }

    private fun requiresReconfigure(old: Format, new: Format): Boolean {
        return old.sampleMimeType != new.sampleMimeType ||
            old.drmInitData != new.drmInitData
    }

    private fun sameInitializationData(first: List<ByteArray>, second: List<ByteArray>): Boolean {
        if (first.size != second.size) return false
        return first.indices.all { index -> first[index].contentEquals(second[index]) }
    }

    private fun collectInitializationData(format: Format): ByteArray? {
        if (format.initializationData.isEmpty()) return null
        val total = format.initializationData.sumOf { it.size }
        return ByteArray(total).also { result ->
            var offset = 0
            format.initializationData.forEach { part ->
                part.copyInto(result, offset)
                offset += part.size
            }
        }
    }

    private companion object {
        private const val TAG = "ClearVrLikeRenderer"
        private const val ZERO: Byte = 0
        private const val ONE: Byte = 1
        private const val VPS_NAL_TYPE = 32
        private const val PPS_NAL_TYPE = 34
        private val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val MIN_INITIAL_CODEC_WIDTH = 960
        private const val MIN_INITIAL_CODEC_HEIGHT = 544
        private const val MAX_INPUTS_PER_RENDER = 4
        private const val MAX_OUTPUTS_PER_RENDER = 8
    }
}
