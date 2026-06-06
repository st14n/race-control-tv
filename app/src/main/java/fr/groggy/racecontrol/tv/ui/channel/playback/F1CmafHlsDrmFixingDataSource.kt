package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.math.min

/**
 * F1's current Android TV UHD HLS playlists declare SAMPLE-AES-CTR while the
 * CMAF init segments declare cbcs. Media3 maps SAMPLE-AES-CTR to cenc, so fix
 * only these playlist responses before HlsPlaylistParser sees them.
 */
class F1CmafHlsDrmFixingDataSource private constructor(
    private val upstream: DataSource
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return F1CmafHlsDrmFixingDataSource(upstreamFactory.createDataSource())
        }
    }

    private var openedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var memoryData: ByteArray? = null
    private var memoryPosition = 0
    private var memoryLimit = 0
    private var upstreamOpened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        openedUri = dataSpec.uri

        if (!shouldRewritePlaylist(dataSpec.uri)) {
            val length = upstream.open(dataSpec)
            upstreamOpened = true
            responseHeaders = upstream.responseHeaders
            return length
        }

        val originalBytes = readUpstreamFully(dataSpec)
        val originalText = originalBytes.toString(StandardCharsets.UTF_8)
        val fixedText = rewritePlaylist(originalText)
        val fixedBytes = fixedText.toByteArray(StandardCharsets.UTF_8)

        val requestedStart = dataSpec.position.coerceAtMost(fixedBytes.size.toLong()).toInt()
        val requestedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            fixedBytes.size
        } else {
            min(fixedBytes.size.toLong(), dataSpec.position + dataSpec.length).toInt()
        }

        memoryData = fixedBytes
        memoryPosition = requestedStart
        memoryLimit = requestedEnd

        if (fixedText !== originalText) {
            Log.i(TAG, "Rewrote F1 HDR CMAF HLS DRM method to cbcs for ${safeUriForLog(dataSpec.uri)}")
        }

        return (memoryLimit - memoryPosition).toLong()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = memoryData
        if (data == null) {
            return upstream.read(buffer, offset, length)
        }
        if (length == 0) {
            return 0
        }
        if (memoryPosition >= memoryLimit) {
            return C.RESULT_END_OF_INPUT
        }

        val bytesToRead = min(length, memoryLimit - memoryPosition)
        data.copyInto(buffer, offset, memoryPosition, memoryPosition + bytesToRead)
        memoryPosition += bytesToRead
        return bytesToRead
    }

    override fun getUri(): Uri? {
        return openedUri ?: upstream.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return responseHeaders
    }

    @Throws(IOException::class)
    override fun close() {
        memoryData = null
        memoryPosition = 0
        memoryLimit = 0
        if (upstreamOpened) {
            upstreamOpened = false
            upstream.close()
        }
    }

    @Throws(IOException::class)
    private fun readUpstreamFully(dataSpec: DataSpec): ByteArray {
        try {
            upstream.open(dataSpec)
            upstreamOpened = true
            responseHeaders = upstream.responseHeaders

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = upstream.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) {
                    break
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            if (upstreamOpened) {
                upstreamOpened = false
                upstream.close()
            }
        }
    }

    private fun shouldRewritePlaylist(uri: Uri): Boolean {
        val url = uri.toString()
        return url.contains("HDR-UHD-CMAF-WV", ignoreCase = true) &&
            url.contains(".m3u8", ignoreCase = true)
    }

    private fun rewritePlaylist(text: String): String {
        if (!text.startsWith("#EXTM3U")) {
            return text
        }
        if (!text.contains("KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"")) {
            return text
        }
        return text.replace("METHOD=SAMPLE-AES-CTR", "METHOD=SAMPLE-AES")
    }

    private fun safeUriForLog(uri: Uri): String {
        return uri.buildUpon().clearQuery().build().toString()
    }

    private companion object {
        private const val TAG = "F1CmafHlsDrmFixingDataSource"
    }
}
