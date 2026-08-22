package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the missing-hvcC insertion path with a synthetic MP4 box tree
 * (stsd -> encv -> sinf, no hvcC child at all) rather than a real F1 capture,
 * since that scenario has never actually been observed in the wild -- this
 * proves the box-splicing arithmetic itself is correct, independent of
 * whether/when F1 ever ships it.
 *
 * Uses a no-op [DataSource] purely to satisfy the constructor: the methods
 * under test ([F1DashInitSegmentFixingDataSource.parseBoxes] and
 * [F1DashInitSegmentFixingDataSource.insertMissingHvccBytes]) are pure byte
 * manipulation and never touch it.
 */
class F1DashInitSegmentFixingDataSourceTest {

    private val noOpUpstream = object : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long = throw UnsupportedOperationException()
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
        override fun close() {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw UnsupportedOperationException()
    }

    private val dataSource = F1DashInitSegmentFixingDataSource(noOpUpstream, isLiveSession = false)

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val size = 8 + body.size
        val header = ByteArray(8)
        header[0] = (size ushr 24).toByte()
        header[1] = (size ushr 16).toByte()
        header[2] = (size ushr 8).toByte()
        header[3] = size.toByte()
        type.forEachIndexed { i, c -> header[4 + i] = c.code.toByte() }
        return header + body
    }

    private fun buildStsdWithoutHvcc(): ByteArray {
        val sinf = box("sinf")
        val encv = box("encv", ByteArray(78), sinf)
        val stsdHeader = ByteArray(8) // fullbox version/flags(4) + entry_count(4)
        return box("stsd", stsdHeader, encv)
    }

    @Test
    fun `inserts a brand new hvcC when the box is entirely absent`() {
        val data = buildStsdWithoutHvcc()
        val boxes = dataSource.parseBoxes(data, 0, data.size, null)
        assertNull("fixture must have no hvcC to begin with", boxes.firstOrNull { it.type == "hvcC" })

        val hvccBytes = box("hvcC", byteArrayOf(1, 2, 3, 4, 5))
        val colorBoxBytes = box("colr", byteArrayOf(9, 9, 9))

        val fixed = dataSource.insertMissingHvccBytes(data, boxes, hvccBytes, colorBoxBytes)
        assertNotNull(fixed)
        checkNotNull(fixed)

        assertEquals(data.size + hvccBytes.size + colorBoxBytes.size, fixed.size)

        val fixedBoxes = dataSource.parseBoxes(fixed, 0, fixed.size, null)
        val stsd = fixedBoxes.first { it.type == "stsd" }
        val encv = fixedBoxes.first { it.type == "encv" }
        val childTypes = encv.children.map { it.type }

        assertEquals(
            "hvcC must be inserted as the first child, before the pre-existing sinf, " +
                "with colr right after it",
            listOf("hvcC", "colr", "sinf"),
            childTypes
        )
        assertEquals(
            "stsd box size must grow by exactly the inserted bytes",
            stsdSizeBeforeInsertion(data) + hvccBytes.size + colorBoxBytes.size,
            stsd.size
        )
        assertEquals(
            "encv box size must grow by exactly the inserted bytes",
            encvSizeBeforeInsertion(data) + hvccBytes.size + colorBoxBytes.size,
            encv.size
        )
    }

    @Test
    fun `skips inserting a color box when none is available, hvcC only`() {
        val data = buildStsdWithoutHvcc()
        val boxes = dataSource.parseBoxes(data, 0, data.size, null)
        val hvccBytes = box("hvcC", byteArrayOf(1, 2, 3))

        val fixed = checkNotNull(dataSource.insertMissingHvccBytes(data, boxes, hvccBytes, colorBoxBytes = null))
        assertEquals(data.size + hvccBytes.size, fixed.size)

        val encv = dataSource.parseBoxes(fixed, 0, fixed.size, null).first { it.type == "encv" }
        assertEquals(listOf("hvcC", "sinf"), encv.children.map { it.type })
    }

    @Test
    fun `returns null when there is no video sample entry to anchor an insertion on`() {
        val notAVideoTrack = box("stsd", ByteArray(8), box("mp4a", ByteArray(20)))
        val boxes = dataSource.parseBoxes(notAVideoTrack, 0, notAVideoTrack.size, null)

        val result = dataSource.insertMissingHvccBytes(
            notAVideoTrack,
            boxes,
            box("hvcC", byteArrayOf(1)),
            colorBoxBytes = null
        )

        assertNull(result)
    }

    private fun stsdSizeBeforeInsertion(data: ByteArray): Int {
        return dataSource.parseBoxes(data, 0, data.size, null).first { it.type == "stsd" }.size
    }

    private fun encvSizeBeforeInsertion(data: ByteArray): Int {
        return dataSource.parseBoxes(data, 0, data.size, null).first { it.type == "encv" }.size
    }
}
