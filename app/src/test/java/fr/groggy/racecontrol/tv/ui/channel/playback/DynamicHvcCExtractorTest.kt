package fr.groggy.racecontrol.tv.ui.channel.playback

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.HevcConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [DynamicHvcCExtractor.extractHvcCFromSegment] against a real,
 * offline fixture instead of a live network fetch, so this test is
 * deterministic and doesn't depend on a signed CDN token staying valid.
 *
 * The fixture is the first 20000 bytes of `live_segment_1.mp4` (captured
 * 2026-06-14, old Akamai CDN, real F1 live UHD/HDR segment) -- enough to
 * cover ftyp/moof/mdat and the leading VPS/SPS/PPS NAL units, without
 * checking a 10MB file into the test resources.
 */
class DynamicHvcCExtractorTest {

    @Test
    fun `extracts real VPS SPS PPS from a genuine F1 UHD HDR segment`() {
        val buffer = javaClass.classLoader!!
            .getResourceAsStream("fixtures/live_segment_1_trimmed.mp4")!!
            .use { it.readBytes() }

        val hvcC = DynamicHvcCExtractor.extractHvcCFromSegment(buffer)
        assertNotNull("expected VPS/SPS/PPS to be found in the fixture's in-band NAL units", hvcC)
        checkNotNull(hvcC)

        // Box header (4 size + 4 'hvcC') + 23-byte HEVCDecoderConfigurationRecord.
        val declaredSize = ((hvcC[0].toInt() and 0xFF) shl 24) or
            ((hvcC[1].toInt() and 0xFF) shl 16) or
            ((hvcC[2].toInt() and 0xFF) shl 8) or
            (hvcC[3].toInt() and 0xFF)
        assertEquals("hvcC box size field must match actual array length", hvcC.size, declaredSize)
        assertEquals("hvcC", String(hvcC, 4, 4, Charsets.US_ASCII))

        val numOfArrays = hvcC[8 + 22].toInt() and 0xFF
        assertEquals("must carry VPS/SPS/PPS arrays (not the F1 empty/in-band-only shape)", 3, numOfArrays)
    }

    @Test
    fun `synthesized hvcC parses cleanly through Media3's real HevcConfig and reports 10-bit`() {
        val buffer = javaClass.classLoader!!
            .getResourceAsStream("fixtures/live_segment_1_trimmed.mp4")!!
            .use { it.readBytes() }

        val hvcC = checkNotNull(DynamicHvcCExtractor.extractHvcCFromSegment(buffer))

        // HevcConfig.parse expects the config record payload, i.e. the bytes
        // after the 8-byte box header -- matching how Media3's own BoxParser
        // calls it when reading an stsd/hvcC box.
        val payload = hvcC.copyOfRange(8, hvcC.size)
        val config = HevcConfig.parse(ParsableByteArray(payload))

        assertTrue(
            "Media3 must produce non-empty CSD from real VPS/SPS/PPS " +
                "(this is exactly what it fails to do for F1's own empty/in-band hvcC)",
            config.initializationData.isNotEmpty()
        )
        assertEquals("real F1 UHD/HDR streams are Main10 (10-bit luma)", 10, config.bitdepthLuma)
        assertEquals("real F1 UHD/HDR streams are Main10 (10-bit chroma)", 10, config.bitdepthChroma)
        assertEquals(3840, config.width)
        assertEquals(2160, config.height)
    }

    @Test
    fun `accepts extraction when expected resolution matches the manifest`() {
        val buffer = javaClass.classLoader!!
            .getResourceAsStream("fixtures/live_segment_1_trimmed.mp4")!!
            .use { it.readBytes() }

        val hvcC = DynamicHvcCExtractor.extractHvcCFromSegment(
            buffer,
            representationId = "_HDR-UHD_HEVC_2",
            expectedWidth = 3840,
            expectedHeight = 2160
        )

        assertNotNull("must accept extraction when the decoded SPS matches the manifest's declared resolution", hvcC)
    }

    @Test
    fun `rejects extraction when it doesn't match the manifest-declared resolution`() {
        val buffer = javaClass.classLoader!!
            .getResourceAsStream("fixtures/live_segment_1_trimmed.mp4")!!
            .use { it.readBytes() }

        // A representation the manifest claims is 1280x720 can never legitimately
        // produce a 3840x2160 SPS -- this simulates a false-positive NAL match
        // (e.g. on encrypted bytes) without needing to fabricate corrupt input,
        // and proves DynamicHvcCExtractor actually rejects a mismatch instead of
        // trusting byte-pattern matching alone.
        val hvcC = DynamicHvcCExtractor.extractHvcCFromSegment(
            buffer,
            representationId = "_HDR-UHD_HEVC_2",
            expectedWidth = 1280,
            expectedHeight = 720
        )

        assertNull("must reject extraction when decoded resolution doesn't match the manifest", hvcC)
    }
}
