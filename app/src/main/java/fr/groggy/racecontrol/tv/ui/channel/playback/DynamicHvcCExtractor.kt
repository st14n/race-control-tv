package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Log
import androidx.media3.container.NalUnitUtil
import fr.groggy.racecontrol.tv.utils.DeviceInfo
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Extracts real HEVC VPS/SPS/PPS parameter sets from the in-band NAL units of
 * F1's DASH media segments and synthesizes an hvcC box from them.
 *
 * F1's UHD/HDR representations declare frma=hev1 with an empty hvcC
 * (numOfArrays=0). Per ISO/IEC 14496-15 that's a deliberate, spec-legal way
 * of saying "parameter sets are in-band, not in the container" -- it is not
 * corrupt data. Media3's own HevcConfig.parse() has no fallback for that
 * case (confirmed by decompiling media3-extractor 1.10.1: when numOfArrays
 * == 0 it returns an empty CSD list and never scans sample data), which is
 * the actual gap this class works around. The official app's player
 * apparently does implement that fallback internally; we replicate the
 * effect by pre-fetching the first segment out-of-band and handing Media3 a
 * container-level hvcC it can parse normally.
 *
 * Keyed by the resolved absolute init-segment URI (not a representation id
 * parsed back out of a CDN URL) so lookups keep working across CDN/URL
 * shape changes -- [F1DynamicHvcCDashManifestParser] and
 * [F1DashInitSegmentFixingDataSource] both derive that same URI from the
 * DASH manifest's own resolution logic, so they agree on the key without
 * needing to parse each other's URLs.
 */
object DynamicHvcCExtractor {
    private const val TAG = "DynamicHvcCExtractor"
    private const val MAX_CACHE_ENTRIES = 6
    private const val FETCH_RANGE_BYTES = 500_000
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    // Sentinel for extractHvcCFromSegment's default params (used directly by unit
    // tests, which don't have a manifest-declared resolution to validate against).
    private const val C_LENGTH_UNSET = -1

    private val threadCount = AtomicInteger(0)
    private val executor = Executors.newFixedThreadPool(
        2,
        ThreadFactory { runnable ->
            Thread(runnable, "DynamicHvcCExtractor-${threadCount.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    )

    // Bounded LRU: F1 signs manifest/segment URLs per-session, so entries are
    // rarely reused across sessions; without a cap this would grow for the
    // life of the process.
    private val cache = object : LinkedHashMap<String, Future<ByteArray?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Future<ByteArray?>>): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /**
     * Kicks off (or reuses an in-flight) background extraction for the given
     * init-segment URI. Non-blocking: safe to call from a manifest-parsing
     * thread.
     */
    @Synchronized
    fun extractAsync(
        initSegmentUri: String,
        representationId: String,
        sampleSegmentUrl: String,
        expectedWidth: Int,
        expectedHeight: Int
    ) {
        if (cache.containsKey(initSegmentUri)) {
            return
        }
        val future = executor.submit(
            Callable {
                try {
                    Log.i(
                        TAG,
                        "Fetching first segment to extract parameter sets for " +
                            "$representationId: $sampleSegmentUrl"
                    )
                    val hvcC = fetchAndExtract(sampleSegmentUrl, representationId, expectedWidth, expectedHeight)
                    if (hvcC != null) {
                        Log.i(
                            TAG,
                            "Successfully extracted dynamic hvcC box for $representationId " +
                                "(size=${hvcC.size} bytes)"
                        )
                    } else {
                        Log.w(TAG, "Failed to find valid VPS/SPS/PPS in segment for $representationId")
                    }
                    hvcC
                } catch (e: Exception) {
                    Log.e(TAG, "Error extracting parameter sets for $representationId", e)
                    null
                }
            }
        )
        cache[initSegmentUri] = future
    }

    /**
     * Blocks (up to [timeoutMs]) for a dynamic extraction matching
     * [initSegmentUri] to finish, if one was started. Returns null if none
     * was started, it failed, or it didn't finish in time -- callers should
     * treat null as "fall back to the hardcoded box".
     */
    fun await(initSegmentUri: String, timeoutMs: Long): ByteArray? {
        val future = synchronized(this) { cache[initSegmentUri] } ?: return null
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "Timed out waiting for dynamic hvcC extraction uri=$initSegmentUri")
            null
        } catch (e: ExecutionException) {
            Log.e(TAG, "Dynamic hvcC extraction failed uri=$initSegmentUri", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun fetchAndExtract(
        segmentUrl: String,
        representationId: String,
        expectedWidth: Int,
        expectedHeight: Int
    ): ByteArray? {
        val connection = URL(segmentUrl).openConnection() as HttpURLConnection
        return try {
            // First segment's leading bytes are more than enough to reach the
            // first mdat and its parameter sets.
            connection.setRequestProperty("Range", "bytes=0-${FETCH_RANGE_BYTES - 1}")
            // This is a bare HttpURLConnection, bypassing the app's normal OkHttp
            // pipeline entirely -- without these, Akamai's hdntl-tokenized CDN
            // (still used for live and current-week replay) silently serves
            // unusable content for requests that don't look like they came from
            // the real app, which surfaced as "found no VPS/SPS/PPS" rather than
            // an HTTP error.
            connection.setRequestProperty("User-Agent", DeviceInfo.userAgent)
            connection.setRequestProperty("Origin", "https://f1tv.formula1.com")
            connection.setRequestProperty("Referer", "https://f1tv.formula1.com/")
            connection.setRequestProperty("x-f1-device-info", DeviceInfo.f1DeviceInfo)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            val responseCode = connection.responseCode
            if (responseCode != 200 && responseCode != 206) {
                Log.e(TAG, "Failed to fetch segment $segmentUrl, response code: $responseCode")
                return null
            }
            val buffer = connection.inputStream.readBytes()
            extractHvcCFromSegment(buffer, representationId, expectedWidth, expectedHeight)
        } finally {
            connection.disconnect()
        }
    }

    /** Visible internally so unit tests can exercise real segment fixtures deterministically. */
    internal fun extractHvcCFromSegment(
        buffer: ByteArray,
        representationId: String = "unknown",
        expectedWidth: Int = C_LENGTH_UNSET,
        expectedHeight: Int = C_LENGTH_UNSET
    ): ByteArray? {
        val mdatOffset = findMdatOffset(buffer) ?: return null

        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        var pos = mdatOffset
        while (pos < buffer.size - 4 && (vps == null || sps == null || pps == null)) {
            if (isAnnexBStartCode(buffer, pos)) {
                var nextPos = pos + 4
                while (nextPos < buffer.size - 4 && !isAnnexBStartCode(buffer, nextPos)) {
                    nextPos++
                }
                val naluType = (buffer[pos + 4].toInt() and 0x7E) ushr 1
                val naluData = buffer.copyOfRange(pos + 4, nextPos)
                when (naluType) {
                    32 -> vps = naluData
                    33 -> sps = naluData
                    34 -> pps = naluData
                }
                pos = nextPos
            } else {
                // Length-prefixed format, matching F1's declared nal_length_size=4.
                val len = readUInt32BE(buffer, pos)
                if (len <= 0 || pos + 4 + len > buffer.size) break

                val naluType = (buffer[pos + 4].toInt() and 0x7E) ushr 1
                val naluData = buffer.copyOfRange(pos + 4, pos + 4 + len)
                when (naluType) {
                    32 -> vps = naluData
                    33 -> sps = naluData
                    34 -> pps = naluData
                }
                pos += 4 + len
            }
        }

        if (vps == null || sps == null || pps == null) return null
        if (!looksLikeValidParameterSets(vps, sps, representationId, expectedWidth, expectedHeight)) return null
        return buildHvcCBox(vps, sps, pps)
    }

    /**
     * Byte-pattern NAL matching alone can false-positive on encrypted or
     * otherwise non-parameter-set bytes that happen to carry a matching NAL
     * header type. Rather than trust that blindly, parse the candidate VPS/SPS
     * with Media3's own real H.265 bitstream parser (the same one HevcConfig
     * uses) and check the decoded resolution actually matches what the
     * manifest declared for this representation. A garbled false match is
     * far more likely to fail to parse at all or decode to a nonsense
     * resolution than to coincidentally match both the real width and height.
     */
    private fun looksLikeValidParameterSets(
        vps: ByteArray,
        sps: ByteArray,
        representationId: String,
        expectedWidth: Int,
        expectedHeight: Int
    ): Boolean {
        return try {
            val vpsData = NalUnitUtil.parseH265VpsNalUnit(vps, 0, vps.size)
            val spsData = NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, vpsData)
            // spsData.width/height are the conformance-window-cropped display
            // resolution (matches what a manifest declares); decodedWidth/
            // decodedHeight are the raw pre-crop coded picture size, rounded up
            // to CTU block granularity (e.g. 2160 -> 2176 for 64px CTUs) --
            // comparing against those instead rejected genuinely valid real
            // parameter sets as false positives.
            val widthOk = expectedWidth == C_LENGTH_UNSET || spsData.width == expectedWidth
            val heightOk = expectedHeight == C_LENGTH_UNSET || spsData.height == expectedHeight
            if (!widthOk || !heightOk) {
                Log.e(
                    TAG,
                    "Rejecting extracted parameter sets for $representationId: decoded " +
                        "${spsData.width}x${spsData.height} != manifest-declared " +
                        "${expectedWidth}x$expectedHeight (likely a false-positive NAL match, " +
                        "not real parameter sets)"
                )
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Rejecting extracted parameter sets for $representationId: " +
                    "Media3's own H.265 parser could not parse them as real VPS/SPS",
                e
            )
            false
        }
    }

    private fun findMdatOffset(buffer: ByteArray): Int? {
        for (i in 0 until buffer.size - 4) {
            if (buffer[i] == 'm'.code.toByte() && buffer[i + 1] == 'd'.code.toByte() &&
                buffer[i + 2] == 'a'.code.toByte() && buffer[i + 3] == 't'.code.toByte()
            ) {
                return i + 4
            }
        }
        return null
    }

    private fun isAnnexBStartCode(buffer: ByteArray, pos: Int): Boolean {
        return buffer[pos] == 0.toByte() && buffer[pos + 1] == 0.toByte() &&
            buffer[pos + 2] == 0.toByte() && buffer[pos + 3] == 1.toByte()
    }

    private fun readUInt32BE(buffer: ByteArray, pos: Int): Int {
        return ((buffer[pos].toInt() and 0xFF) shl 24) or
            ((buffer[pos + 1].toInt() and 0xFF) shl 16) or
            ((buffer[pos + 2].toInt() and 0xFF) shl 8) or
            (buffer[pos + 3].toInt() and 0xFF)
    }

    private fun buildHvcCBox(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        // Don't hardcode profile_idc=1: the stream is Main 10, and a mismatch
        // between the hvcC header and the real VPS/SPS crashes or greens out
        // the MediaTek decoder. Copy profile_tier_level straight from the
        // real VPS (bytes 6..17) instead.
        val header = ByteArray(23)
        header[0] = 0x01 // configurationVersion
        System.arraycopy(vps, 6, header, 1, 12) // profile_tier_level

        // Media3's HevcConfig.parse() skips these header bytes entirely and
        // derives bit depth/chroma from the parsed SPS instead (confirmed by
        // decompiling media3-extractor), so their exact values here don't
        // affect playback -- they're set to plausible Main10 4:2:0 values
        // only for a well-formed box.
        header[13] = 0xF0.toByte() // min_spatial_segmentation_idc
        header[14] = 0x00
        header[15] = 0xFC.toByte() // parallelismType
        header[16] = 0xFD.toByte() // chromaFormat = 1 (4:2:0)
        header[17] = 0xFA.toByte() // bitDepthLumaMinus8 = 2 (10-bit)
        header[18] = 0xFA.toByte() // bitDepthChromaMinus8 = 2 (10-bit)
        header[19] = 0x00 // avgFrameRate
        header[20] = 0x00
        // numTemporalLayers = 1, temporalIdNested = 1, lengthSizeMinusOne = 3
        header[21] = 0x0B.toByte()
        header[22] = 0x03 // numOfArrays

        val payloadSize = header.size +
            3 + 2 + vps.size +
            3 + 2 + sps.size +
            3 + 2 + pps.size

        val totalSize = 8 + payloadSize
        val box = ByteArray(totalSize)

        box[0] = (totalSize ushr 24).toByte()
        box[1] = (totalSize ushr 16).toByte()
        box[2] = (totalSize ushr 8).toByte()
        box[3] = totalSize.toByte()
        box[4] = 'h'.code.toByte()
        box[5] = 'v'.code.toByte()
        box[6] = 'c'.code.toByte()
        box[7] = 'C'.code.toByte()

        var offset = 8
        System.arraycopy(header, 0, box, offset, header.size)
        offset += header.size

        offset = writeParameterSetArray(box, offset, arrayType = 0xA0, nalu = vps)
        offset = writeParameterSetArray(box, offset, arrayType = 0xA1, nalu = sps)
        writeParameterSetArray(box, offset, arrayType = 0xA2, nalu = pps)

        return box
    }

    private fun writeParameterSetArray(box: ByteArray, start: Int, arrayType: Int, nalu: ByteArray): Int {
        var offset = start
        box[offset++] = arrayType.toByte() // array_completeness=1, NAL unit type
        box[offset++] = 0x00
        box[offset++] = 0x01 // numNalus = 1
        box[offset++] = (nalu.size ushr 8).toByte()
        box[offset++] = nalu.size.toByte()
        System.arraycopy(nalu, 0, box, offset, nalu.size)
        return offset + nalu.size
    }
}
