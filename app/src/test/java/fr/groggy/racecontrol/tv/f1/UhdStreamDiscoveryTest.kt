package fr.groggy.racecontrol.tv.f1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UhdStreamDiscoveryTest {

    @Test
    fun `unencrypted HLS with a 4K HLG rendition counts`() {
        val m3u = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=18421582,RESOLUTION=3840x2160,VIDEO-RANGE=HLG,CODECS="hvc1.2.4.L153.B0,mp4a.40.2"
            a/index.m3u8
        """.trimIndent()
        assertTrue(UhdStreamDiscovery.looksClearUhd(m3u))
    }

    @Test
    fun `encrypted or HD-only HLS does not count`() {
        val encrypted = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,URI=\"skd://x\"\n#EXT-X-STREAM-INF:RESOLUTION=3840x2160\na.m3u8"
        val hd = "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080,VIDEO-RANGE=SDR\na.m3u8"
        assertFalse(UhdStreamDiscovery.looksClearUhd(encrypted))
        assertFalse(UhdStreamDiscovery.looksClearUhd(hd))
    }

    @Test
    fun `DASH needs 2160 and no ContentProtection`() {
        assertTrue(UhdStreamDiscovery.looksClearUhd("<MPD><Representation width=\"3840\" height=\"2160\"/></MPD>"))
        assertFalse(UhdStreamDiscovery.looksClearUhd("<MPD><ContentProtection value=\"cbcs\"/><Representation height=\"2160\"/></MPD>"))
        assertFalse(UhdStreamDiscovery.looksClearUhd("<h1>error 404</h1>"))
    }

    @Test
    fun `generated names follow F1's scheme, HLS-type first`() {
        assertEquals("HDR_UHD_CMAF", UhdStreamDiscovery.GENERATED.first())
        assertTrue("HDR_UHD_DASH_SINGLE" in UhdStreamDiscovery.GENERATED)
        assertEquals(12, UhdStreamDiscovery.GENERATED.size)
    }
}
