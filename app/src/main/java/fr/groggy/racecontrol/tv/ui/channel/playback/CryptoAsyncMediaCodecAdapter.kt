package fr.groggy.racecontrol.tv.ui.channel.playback

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PersistableBundle
import android.view.Surface
import androidx.media3.decoder.CryptoInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * DIAGNOSTIC (2026-08-22): tests [MediaCodec.CONFIGURE_FLAG_USE_CRYPTO_ASYNC] (API 34+)
 * in isolation. Neither of Media3 1.10.1's own bundled adapters ever set this flag
 * (confirmed by decompiling the library jar), so it has never been exercised by any
 * test in this investigation -- and it changes how the *platform* dispatches crypto
 * operations to the vendor Codec2/HAL crypto plugin, a different layer from
 * ExoPlayer's own sync/async buffer-callback threading (already tested and ruled out).
 *
 * A first attempt used a synchronous (poll-based) adapter, matching Media3's own
 * `SynchronousMediaCodecAdapter`, plus this one extra flag. It failed immediately with
 * `IllegalStateException: Block model is only valid with callback set (async mode)` --
 * on this device, requesting CRYPTO_ASYNC implicitly requires block-model buffers,
 * which in turn require a real `MediaCodec.Callback`. This adapter uses one, via the
 * standard documented async MediaCodec pattern (a single callback thread, synchronized
 * index queues) -- functionally equivalent to Media3's own `AsynchronousMediaCodecAdapter`
 * (whose class and constructor are package-private, so it cannot be reused directly),
 * just simpler (one thread instead of separate callback/queueing threads), which is
 * an acceptable difference for a diagnostic test but not for production use.
 */
class CryptoAsyncMediaCodecAdapter private constructor(
    private val codec: MediaCodec,
    private val callbackThread: HandlerThread
) : MediaCodecAdapter {

    private val lock = Object()
    private val inputBufferIndices = ArrayDeque<Int>()
    private val outputBufferIndices = ArrayDeque<Int>()
    private val outputBufferInfos = ArrayDeque<MediaCodec.BufferInfo>()
    private var outputFormat: MediaFormat? = null
    private var outputFormatChangePending = false
    private val pendingError = AtomicReference<MediaCodec.CodecException?>()

    class Factory(
        private val configureFlags: Int = MediaCodec.CONFIGURE_FLAG_USE_CRYPTO_ASYNC
    ) : MediaCodecAdapter.Factory {
        override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
            var codec: MediaCodec? = null
            var thread: HandlerThread? = null
            try {
                val created = MediaCodec.createByCodecName(configuration.codecInfo.name)
                codec = created
                val callbackThread = HandlerThread("CryptoAsyncMediaCodecAdapter")
                thread = callbackThread
                callbackThread.start()
                val adapter = CryptoAsyncMediaCodecAdapter(created, callbackThread)
                created.setCallback(adapter.callback, Handler(callbackThread.looper))
                created.configure(configuration.mediaFormat, configuration.surface, configuration.crypto, configureFlags)
                created.start()
                return adapter
            } catch (e: Exception) {
                codec?.release()
                thread?.quitSafely()
                throw e
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            synchronized(lock) { inputBufferIndices.add(index) }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                outputBufferIndices.add(index)
                outputBufferInfos.add(info)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            pendingError.set(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(lock) {
                outputFormat = format
                // Media3's renderer advances its output-format state only
                // when the adapter returns this sentinel. Without it the
                // renderer can receive an output buffer with no current
                // format, which caused the fallback in the first async test.
                outputFormatChangePending = true
            }
        }
    }

    private fun maybeThrow() {
        pendingError.getAndSet(null)?.let { throw it }
    }

    override fun needsReconfiguration(): Boolean = false

    override fun dequeueInputBufferIndex(): Int {
        maybeThrow()
        synchronized(lock) { return inputBufferIndices.pollFirst() ?: MediaCodec.INFO_TRY_AGAIN_LATER }
    }

    override fun dequeueOutputBufferIndex(bufferInfo: MediaCodec.BufferInfo): Int {
        maybeThrow()
        synchronized(lock) {
            if (outputFormatChangePending) {
                outputFormatChangePending = false
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            val info = outputBufferInfos.pollFirst() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
            val index = outputBufferIndices.pollFirst()!!
            bufferInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
            return index
        }
    }

    override fun getOutputFormat(): MediaFormat {
        synchronized(lock) { return outputFormat ?: codec.outputFormat }
    }

    override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun queueSecureInputBuffer(
        index: Int,
        offset: Int,
        info: CryptoInfo,
        presentationTimeUs: Long,
        flags: Int
    ) {
        codec.queueSecureInputBuffer(index, offset, info.frameworkCryptoInfo, presentationTimeUs, flags)
    }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        codec.releaseOutputBuffer(index, render)
    }

    override fun releaseOutputBuffer(index: Int, renderTimeStampNs: Long) {
        codec.releaseOutputBuffer(index, renderTimeStampNs)
    }

    override fun flush() {
        synchronized(lock) {
            inputBufferIndices.clear()
            outputBufferIndices.clear()
            outputBufferInfos.clear()
            outputFormatChangePending = false
        }
        codec.flush()
    }

    override fun release() {
        synchronized(lock) {
            inputBufferIndices.clear()
            outputBufferIndices.clear()
            outputBufferInfos.clear()
            outputFormatChangePending = false
        }
        codec.release()
        callbackThread.quitSafely()
    }

    override fun setOnFrameRenderedListener(listener: MediaCodecAdapter.OnFrameRenderedListener, handler: Handler) {
        codec.setOnFrameRenderedListener(
            { _, presentationTimeUs, nanoTime -> listener.onFrameRendered(this, presentationTimeUs, nanoTime) },
            handler
        )
    }

    override fun setOutputSurface(surface: Surface) {
        codec.setOutputSurface(surface)
    }

    override fun detachOutputSurface() {
        codec.detachOutputSurface()
    }

    override fun setParameters(params: Bundle) {
        codec.setParameters(params)
    }

    override fun setVideoScalingMode(scalingMode: Int) {
        codec.setVideoScalingMode(scalingMode)
    }

    override fun getMetrics(): PersistableBundle = codec.metrics

    override fun subscribeToVendorParameters(names: MutableList<String>) {
        codec.subscribeToVendorParameters(names)
    }

    override fun unsubscribeFromVendorParameters(names: MutableList<String>) {
        codec.unsubscribeFromVendorParameters(names)
    }
}
