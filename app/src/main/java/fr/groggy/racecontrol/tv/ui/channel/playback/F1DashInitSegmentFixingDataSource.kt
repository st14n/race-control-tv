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
class F1DashInitSegmentFixingDataSource internal constructor(
    private val upstream: DataSource,
    private val isLiveSession: Boolean
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val isLiveSession: Boolean
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return F1DashInitSegmentFixingDataSource(upstreamFactory.createDataSource(), isLiveSession)
        }
    }

    private var openedUri: Uri? = null
    private var resolvedUri: Uri? = null
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

        if (!shouldRepair(dataSpec)) {
            val length = upstream.open(dataSpec)
            upstreamOpened = true
            resolvedUri = upstream.uri
            responseHeaders = upstream.responseHeaders
            return length
        }

        val originalBytes = readUpstreamFully(dataSpec)
        val fixedBytes = repairInitSegment(dataSpec, originalBytes) ?: originalBytes

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
        return resolvedUri ?: openedUri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return responseHeaders
    }

    @Throws(IOException::class)
    override fun close() {
        memoryData = null
        memoryPosition = 0
        memoryLimit = 0
        openedUri = null
        resolvedUri = null
        responseHeaders = emptyMap()
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
            resolvedUri = upstream.uri
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

    private fun shouldRepair(dataSpec: DataSpec): Boolean {
        val url = safeUriForLog(dataSpec.uri).uppercase()
        val isMp4 = url.contains(".MP4")
        val isInit = url.contains("INIT")
        if (!isMp4 || !isInit) return false

        val isAudio = url.contains("AUDIO")
        if (isAudio) return false

        // The manifest parser registers every UHD/HDR representation it finds,
        // keyed by this exact resolved URI -- that's authoritative and doesn't
        // depend on the CDN's URL shape. Only fall back to the URL keyword
        // heuristic (which historically broke silently on a CDN migration) if
        // the registry has nothing for this URI, e.g. a manifest-parsing edge
        // case we didn't anticipate.
        if (F1DashRepairMetadataRegistry.get(dataSpec.uri.toString()) != null) {
            return true
        }

        val isVideo = url.contains("SEGMENT") || url.contains("VIDEO")
        val isUhdOrHdr = url.contains("UHD") || url.contains("HDR")

        return isVideo || isUhdOrHdr
    }

    private fun repairInitSegment(dataSpec: DataSpec, data: ByteArray): ByteArray? {
        val boxes = parseBoxes(data, 0, data.size, null)
        val hvcc = boxes.firstOrNull { it.type == "hvcC" }
        return if (hvcc != null) {
            repairExistingHvcc(dataSpec, data, hvcc)
        } else {
            // No hvcC box shell at all, not just an empty one -- MediaCodec gets
            // zero codec config either way, but we can't byte-patch a box that
            // isn't there. Insert a brand new one as the sample entry's first
            // child instead (matching ISO/IEC 14496-15 box ordering: hvcC comes
            // before colr/sinf/etc).
            insertMissingHvcc(dataSpec, data, boxes)
        }
    }

    private fun repairExistingHvcc(dataSpec: DataSpec, data: ByteArray, hvcc: Mp4Box): ByteArray? {
        val metadata = F1DashRepairMetadataRegistry.get(dataSpec.uri.toString())
        val hasColorBox = hvcc.parent?.children?.any { it.type == "colr" } == true
        val hasRealParameterSets = hvccHasParameterSets(data, hvcc)

        // Only ever inject a color box built from the manifest's own real CICP
        // descriptors -- never a hardcoded BT.2020/HLG guess. Without a
        // registry hit for this exact representation we still fix missing
        // parameter sets (that's a generic HEVC decoder requirement, not an
        // HDR-specific one) but leave color handling untouched rather than
        // mistagging a representation we don't have real data for.
        val colorBoxToAdd = if (!hasColorBox) {
            metadata?.colorInfo?.let { buildNclxColorBox(it) }
        } else {
            null
        }

        if (hasRealParameterSets && colorBoxToAdd == null) return null

        val (replacementHvcc, hvccSource) = if (hasRealParameterSets) {
            data.copyOfRange(hvcc.offset, hvcc.offset + hvcc.size) to "original"
        } else {
            resolveFallbackHvccBox(dataSpec)
        }

        val replacement = if (colorBoxToAdd != null) {
            replacementHvcc + colorBoxToAdd
        } else {
            replacementHvcc
        }
        if (replacement.size <= hvcc.size) return null

        val ancestors = generateSequence(hvcc.parent) { it.parent }.toList()
        val fixed = spliceReplacement(data, hvcc.offset, hvcc.offset + hvcc.size, replacement, ancestors)

        Log.i(
            TAG,
            "Expanded empty hvcC for F1 DASH UHD HEVC init segment " +
                "hvcC=${hvcc.size}->${replacementHvcc.size} addedColr=${colorBoxToAdd != null} " +
                "source=$hvccSource colorInfo=${metadata?.colorInfo}"
        )
        return fixed
    }

    private fun insertMissingHvcc(dataSpec: DataSpec, data: ByteArray, boxes: List<Mp4Box>): ByteArray? {
        val metadata = F1DashRepairMetadataRegistry.get(dataSpec.uri.toString())
        val (hvccBytes, hvccSource) = resolveFallbackHvccBox(dataSpec)
        val colorBoxBytes = metadata?.colorInfo?.let { buildNclxColorBox(it) }

        val fixed = insertMissingHvccBytes(data, boxes, hvccBytes, colorBoxBytes) ?: return null

        Log.i(
            TAG,
            "Inserted missing hvcC for F1 DASH UHD HEVC init segment (no hvcC box present at all) " +
                "newHvcC=${hvccBytes.size} addedColr=${colorBoxBytes != null} " +
                "source=$hvccSource colorInfo=${metadata?.colorInfo}"
        )
        return fixed
    }

    /**
     * Pure box-arithmetic core of [insertMissingHvcc], factored out so it can
     * be unit tested against a synthetic buffer without needing a real
     * [DataSpec]/[android.net.Uri] (which don't behave usefully in plain JVM
     * unit tests without Robolectric).
     */
    internal fun insertMissingHvccBytes(
        data: ByteArray,
        boxes: List<Mp4Box>,
        hvccBytes: ByteArray,
        colorBoxBytes: ByteArray?
    ): ByteArray? {
        val sampleEntry = boxes.firstOrNull {
            it.type == "encv" || it.type == "hvc1" || it.type == "hev1"
        } ?: return null

        val hasColorBox = sampleEntry.children.any { it.type == "colr" }
        val insertion = if (!hasColorBox && colorBoxBytes != null) hvccBytes + colorBoxBytes else hvccBytes

        val insertOffset = sampleEntry.offset + BOX_HEADER_SIZE + VIDEO_SAMPLE_ENTRY_HEADER_SIZE
        if (insertOffset > data.size) return null

        val ancestors = listOf(sampleEntry) + generateSequence(sampleEntry.parent) { it.parent }
        return spliceReplacement(data, insertOffset, insertOffset, insertion, ancestors)
    }

    /**
     * Replaces `data[rangeStart, rangeEnd)` with [replacement] and grows every
     * box in [ancestors] by the resulting size delta. Passing `rangeStart ==
     * rangeEnd` is a pure insertion (nothing removed) rather than a
     * replacement -- that's how [insertMissingHvccBytes] reuses this for
     * adding a box that doesn't exist yet.
     */
    internal fun spliceReplacement(
        data: ByteArray,
        rangeStart: Int,
        rangeEnd: Int,
        replacement: ByteArray,
        ancestors: List<Mp4Box>
    ): ByteArray {
        val delta = replacement.size - (rangeEnd - rangeStart)
        val fixed = ByteArray(data.size + delta)
        System.arraycopy(data, 0, fixed, 0, rangeStart)
        System.arraycopy(replacement, 0, fixed, rangeStart, replacement.size)
        System.arraycopy(data, rangeEnd, fixed, rangeStart + replacement.size, data.size - rangeEnd)
        ancestors.forEach { box -> writeInt(fixed, box.offset, box.size + delta) }
        return fixed
    }

    private fun buildNclxColorBox(colorInfo: F1DashRepairMetadataRegistry.ColorInfo): ByteArray {
        val box = ByteArray(NCLX_COLOR_BOX_SIZE)
        writeInt(box, 0, NCLX_COLOR_BOX_SIZE)
        box[4] = 'c'.code.toByte()
        box[5] = 'o'.code.toByte()
        box[6] = 'l'.code.toByte()
        box[7] = 'r'.code.toByte()
        box[8] = 'n'.code.toByte()
        box[9] = 'c'.code.toByte()
        box[10] = 'l'.code.toByte()
        box[11] = 'x'.code.toByte()
        box[12] = (colorInfo.primaries ushr 8).toByte()
        box[13] = colorInfo.primaries.toByte()
        box[14] = (colorInfo.transfer ushr 8).toByte()
        box[15] = colorInfo.transfer.toByte()
        box[16] = (colorInfo.matrix ushr 8).toByte()
        box[17] = colorInfo.matrix.toByte()
        box[18] = 0x00 // full_range_flag=0 (limited) + 7 reserved bits
        return box
    }

    /**
     * Reads the hvcC config record's numOfArrays field directly rather than
     * comparing byte lengths against a specific fallback box. F1's real
     * broken init segments declare numOfArrays=0 (no VPS/SPS/PPS present);
     * anything else means the box already carries real parameter sets and
     * should be trusted as-is, regardless of how its size happens to compare
     * to whichever fallback box we'd otherwise use.
     */
    private fun hvccHasParameterSets(data: ByteArray, hvcc: Mp4Box): Boolean {
        val numOfArraysOffset = hvcc.offset + BOX_HEADER_SIZE + HVCC_NUM_OF_ARRAYS_OFFSET
        if (numOfArraysOffset >= hvcc.offset + hvcc.size || numOfArraysOffset >= data.size) {
            return false
        }
        return (data[numOfArraysOffset].toInt() and 0xFF) > 0
    }

    private fun resolveFallbackHvccBox(dataSpec: DataSpec): Pair<ByteArray, String> {
        val dynamic = DynamicHvcCExtractor.await(dataSpec.uri.toString(), DYNAMIC_HVCC_AWAIT_TIMEOUT_MS)
        if (dynamic != null) {
            return dynamic to "dynamic"
        }
        return if (isLiveSession) {
            FULL_HVCC_BOX_LIVE to "hardcoded-live"
        } else {
            FULL_HVCC_BOX_REPLAY to "hardcoded-replay"
        }
    }

    internal fun parseBoxes(
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
                "encv", "hvc1", "hev1" -> position + BOX_HEADER_SIZE + VIDEO_SAMPLE_ENTRY_HEADER_SIZE
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

    internal class Mp4Box(
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

        // Offset of numOfArrays within the 23-byte HEVCDecoderConfigurationRecord
        // that follows the hvcC box header (confirmed against F1's real minimal
        // live hvcC: configurationVersion(1) + profile/tier/level(12) +
        // min_spatial_segmentation_idc(2) + parallelismType(1) + chromaFormat(1) +
        // bitDepthLumaMinus8(1) + bitDepthChromaMinus8(1) + avgFrameRate(2) +
        // constFrameRate/numTemporalLayers/nested/lengthSizeMinusOne(1) = 22.
        private const val HVCC_NUM_OF_ARRAYS_OFFSET = 22

        // Generous but bounded: DynamicHvcCExtractor.extractAsync is kicked off
        // during manifest parsing, well before the init segment is opened, so
        // in practice this rarely blocks at all -- this is a safety net for
        // slow networks, not the expected path.
        private const val DYNAMIC_HVCC_AWAIT_TIMEOUT_MS = 4_000L

        // 4(size) + 4('colr') + 4('nclx') + 2(primaries) + 2(transfer) + 2(matrix) + 1(full_range+reserved).
        private const val NCLX_COLOR_BOX_SIZE = 19

        private val FULL_HVCC_BOX_REPLAY = byteArrayOf(
            0x00, 0x00, 0x00, 0x91.toByte(), 0x68, 0x76, 0x63, 0x43,
            0x01, 0x02, 0x00, 0x00, 0x00, 0x04, 0xB0.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x99.toByte(), 0xF0.toByte(), 0x00, 0xFC.toByte(),
            0xFD.toByte(), 0xFA.toByte(), 0xFA.toByte(), 0x00, 0x00, 0x03, 0x03, 0xA0.toByte(),
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

        private val FULL_HVCC_BOX_LIVE = byteArrayOf(
            0x00, 0x00, 0x00, 0x8F.toByte(), 0x68, 0x76, 0x63, 0x43, 0x01, 0x02, 0x20, 0x00, 0x00, 0x00, 0xB0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x99.toByte(), 0xF0.toByte(), 0x00, 0xFC.toByte(), 0xFD.toByte(), 0xFA.toByte(), 0xFA.toByte(), 0x00, 0x00, 0x03, 0x03, 0xA0.toByte(), 0x00, 0x01, 0x00, 0x21, 0x40, 0x01, 0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x02, 0x20, 0x00, 0x00, 0x03, 0x00, 0xB0.toByte(), 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00, 0x99.toByte(), 0x11, 0x40, 0xC0.toByte(), 0x00, 0x00, 0x03, 0x00, 0x40, 0x00, 0x00, 0x0C, 0xBA.toByte(), 0x21, 0x00, 0x01, 0x00, 0x38, 0x42, 0x01, 0x01, 0x02, 0x20, 0x00, 0x00, 0x03, 0x00, 0xB0.toByte(), 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00, 0x99.toByte(), 0xA0.toByte(), 0x01, 0xE0.toByte(), 0x20, 0x02, 0x1C, 0x4D, 0x94.toByte(), 0x45, 0xB9.toByte(), 0x1C, 0xA7.toByte(), 0x01, 0x42, 0x12, 0x24, 0x12, 0x28, 0x00, 0x00, 0x03, 0x00, 0x08, 0x00, 0x00, 0x03, 0x01, 0x97.toByte(), 0x8F.toByte(), 0x57, 0xB9.toByte(), 0xF8.toByte(), 0x01, 0xD4.toByte(), 0xC0.toByte(), 0x0E, 0xA7.toByte(), 0x20, 0x22, 0x00, 0x01, 0x00, 0x08, 0x44, 0x01, 0xC1.toByte(), 0xAC.toByte(), 0xBE.toByte(), 0x25, 0xFB.toByte(), 0x24
        )

    }
}
