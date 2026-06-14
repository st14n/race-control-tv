package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.min

/**
 * Repairs F1 UHD HDR DASH init segments that advertise hev1/cbcs but ship an
 * hvcC box with no VPS/SPS/PPS arrays. The media samples still carry in-band
 * parameter sets, but some secure HEVC decoders need a complete codec config
 * before encrypted slices arrive.
 */
class F1DashInitSegmentFixingDataSource private constructor(
    private val upstream: DataSource
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return F1DashInitSegmentFixingDataSource(upstreamFactory.createDataSource())
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

        if (!shouldRepair(dataSpec.uri)) {
            val length = upstream.open(dataSpec)
            upstreamOpened = true
            responseHeaders = upstream.responseHeaders
            return length
        }

        val originalBytes = readUpstreamFully(dataSpec)
        val fixedBytes = repairInitSegment(originalBytes) ?: originalBytes

        val requestedStart = dataSpec.position.coerceAtMost(fixedBytes.size.toLong()).toInt()
        val requestedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            fixedBytes.size
        } else {
            min(fixedBytes.size.toLong(), dataSpec.position + dataSpec.length).toInt()
        }

        memoryData = fixedBytes
        memoryPosition = requestedStart
        memoryLimit = requestedEnd

        if (fixedBytes !== originalBytes) {
            Log.i(
                TAG,
                "Repaired F1 DASH UHD HEVC init segment " +
                    "oldBytes=${originalBytes.size} newBytes=${fixedBytes.size} uri=${safeUriForLog(dataSpec.uri)}"
            )
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
                if (read == C.RESULT_END_OF_INPUT) break
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

    private fun shouldRepair(uri: Uri): Boolean {
        val url = uri.toString()
        return url.contains("HDR-UHD-DASH-WV", ignoreCase = true) &&
            url.contains("_HDR-UHD_HEVC", ignoreCase = true) &&
            url.contains("_init.mp4", ignoreCase = true)
    }

    private fun repairInitSegment(data: ByteArray): ByteArray? {
        val boxes = parseBoxes(data, 0, data.size, parent = null)
        val hvcc = boxes.firstOrNull { it.type == "hvcC" } ?: return null
        if (hvcc.size >= FULL_HVCC_BOX.size) return null

        val ancestors = generateSequence(hvcc.parent) { it.parent }.toList()
        val hasColorBox = hvcc.parent?.children?.any { it.type == "colr" } == true
        val replacement = if (hasColorBox) {
            FULL_HVCC_BOX
        } else {
            FULL_HVCC_BOX + BT2020_HLG_NCLX_COLOR_BOX
        }
        val delta = replacement.size - hvcc.size
        if (delta <= 0) return null

        val fixed = ByteArray(data.size + delta)
        System.arraycopy(data, 0, fixed, 0, hvcc.offset)
        System.arraycopy(replacement, 0, fixed, hvcc.offset, replacement.size)
        System.arraycopy(
            data,
            hvcc.offset + hvcc.size,
            fixed,
            hvcc.offset + replacement.size,
            data.size - (hvcc.offset + hvcc.size)
        )

        ancestors.forEach { box ->
            writeInt(fixed, box.offset, box.size + delta)
        }

        Log.i(
            TAG,
            "Expanded empty hvcC for F1 DASH UHD HEVC init segment " +
                "hvcC=${hvcc.size}->${FULL_HVCC_BOX.size} addedColr=${!hasColorBox}"
        )
        return fixed
    }

    private fun parseBoxes(
        data: ByteArray,
        start: Int,
        end: Int,
        parent: Mp4Box?
    ): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        var position = start
        while (position + BOX_HEADER_SIZE <= end) {
            val size = readInt(data, position)
            if (size < BOX_HEADER_SIZE || position + size > end) break
            val type = data.decodeToString(position + 4, position + 8)
            val box = Mp4Box(position, size, type, parent)
            boxes += box

            val childStart = when (type) {
                "moov", "trak", "mdia", "minf", "stbl", "sinf", "schi", "dinf" ->
                    position + BOX_HEADER_SIZE
                "stsd" -> position + BOX_HEADER_SIZE + STSD_FULL_BOX_AND_ENTRY_COUNT_SIZE
                "encv" -> position + BOX_HEADER_SIZE + VIDEO_SAMPLE_ENTRY_HEADER_SIZE
                else -> -1
            }
            if (childStart in (position + BOX_HEADER_SIZE) until (position + size)) {
                box.children = parseBoxes(data, childStart, position + size, box)
            }

            position += size
        }
        return boxes.flatMap { listOf(it) + it.children.flatten() }
    }

    private fun List<Mp4Box>.flatten(): List<Mp4Box> {
        return flatMap { listOf(it) + it.children.flatten() }
    }

    private fun readInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun safeUriForLog(uri: Uri): String {
        return uri.buildUpon().clearQuery().build().toString()
    }

    private class Mp4Box(
        val offset: Int,
        val size: Int,
        val type: String,
        val parent: Mp4Box?,
        var children: List<Mp4Box> = emptyList()
    )

    private companion object {
        private const val TAG = "F1DashInitFixingDataSource"
        private const val BOX_HEADER_SIZE = 8
        private const val STSD_FULL_BOX_AND_ENTRY_COUNT_SIZE = 8
        private const val VIDEO_SAMPLE_ENTRY_HEADER_SIZE = 78

        private val FULL_HVCC_BOX = byteArrayOf(
            0x00, 0x00, 0x00, 0x91.toByte(), 0x68, 0x76, 0x63, 0x43,
            0x01, 0x02, 0x00, 0x00, 0x00, 0x04, 0xB0.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x99.toByte(), 0xF0.toByte(), 0x00, 0xFC.toByte(),
            0xFD.toByte(), 0xF8.toByte(), 0xF8.toByte(), 0x00, 0x00, 0x03, 0x03, 0xA0.toByte(),
            0x00, 0x01, 0x00, 0x21, 0x40, 0x01, 0x0C, 0x01,
            0xFF.toByte(), 0xFF.toByte(), 0x02, 0x20, 0x00, 0x00, 0x03, 0x00,
            0xB0.toByte(), 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00,
            0x99.toByte(), 0x11, 0x40, 0xC0.toByte(), 0x00, 0x00, 0x03, 0x00,
            0x40, 0x00, 0x00, 0x0C, 0xBA.toByte(), 0xA1.toByte(), 0x00, 0x01,
            0x00, 0x39, 0x42, 0x01, 0x01, 0x02, 0x20, 0x00,
            0x00, 0x03, 0x00, 0xB0.toByte(), 0x00, 0x00, 0x03, 0x00,
            0x00, 0x03, 0x00, 0x99.toByte(), 0xA0.toByte(), 0x01, 0xE0.toByte(), 0x20,
            0x02, 0x20, 0x7C, 0x4B, 0x65, 0x11, 0x6E, 0x47,
            0x29, 0xC0.toByte(), 0x50, 0x84.toByte(), 0x89.toByte(), 0x04, 0x8A.toByte(), 0x00,
            0x00, 0x03, 0x00, 0x02, 0x00, 0x00, 0x03, 0x00,
            0x65, 0xE3.toByte(), 0xD5.toByte(), 0xEE.toByte(), 0x7E, 0x00, 0x75, 0x30,
            0x03, 0xA9.toByte(), 0xC8.toByte(), 0xA2.toByte(), 0x00, 0x01, 0x00, 0x09,
            0x44, 0x01, 0xC1.toByte(), 0xAC.toByte(), 0xBE.toByte(), 0x22, 0x13, 0xEC.toByte(),
            0x90.toByte()
        )

        private val BT2020_HLG_NCLX_COLOR_BOX = byteArrayOf(
            0x00, 0x00, 0x00, 0x13, 0x63, 0x6F, 0x6C, 0x72,
            0x6E, 0x63, 0x6C, 0x78, 0x00, 0x09, 0x00, 0x12,
            0x00, 0x09, 0x00
        )
    }
}
