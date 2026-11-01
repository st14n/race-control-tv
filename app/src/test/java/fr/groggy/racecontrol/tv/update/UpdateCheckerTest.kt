package fr.groggy.racecontrol.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesNumerically() {
        assertTrue(UpdateChecker.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateChecker.compareVersions("v1.0.4", "1.0.3-DEBUG") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
        assertTrue(UpdateChecker.compareVersions("1.0.3", "1.0.4") < 0)
    }

    @Test
    fun picksVariantAsset() {
        val assets = listOf(
            ReleaseAsset("f1tv-player-1.0.4-standard.apk", "u1", 1),
            ReleaseAsset("f1tv-player-1.0.4-radio.apk", "u2", 1),
            ReleaseAsset("notes.txt", "u3", 1)
        )
        assertEquals("u2", UpdateChecker.pickAsset(assets, "radio")?.url)
        assertEquals("u1", UpdateChecker.pickAsset(assets, "standard")?.url)
    }

    @Test
    fun singleApkIsFallbackButAmbiguousIsNot() {
        val one = listOf(ReleaseAsset("app.apk", "u", 1))
        assertEquals("u", UpdateChecker.pickAsset(one, "radio")?.url)
        val two = listOf(ReleaseAsset("a.apk", "u", 1), ReleaseAsset("b.apk", "v", 1))
        assertNull(UpdateChecker.pickAsset(two, "radio"))
    }
}
