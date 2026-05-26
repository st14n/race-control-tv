package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Local HTTP proxy that buffers an upstream MP3/AAC Icecast stream and serves it to libVLC
 * with a configurable time offset. Designed so libVLC's `audioDelay` can stay at 0 — ALL
 * the delay management happens here, in compressed-byte land, where we can frame-align
 * jumps and avoid the libVLC PCM-queue rebuffer that breaks decrease-delay adjustments.
 *
 * Lifecycle:
 *   val proxy = CustomRadioDelayProxy(source, userAgent, initialOffsetMs = 20_000, maxOffsetMs = 30_000)
 *   proxy.awaitInitialBuffer(timeoutMs = 30_000)   // returns true once ring has initialOffsetMs of data
 *   mediaPlayer.media = Media(libVlc, Uri.parse(proxy.streamUrl))
 *   mediaPlayer.play()
 *   ...
 *   proxy.setOffsetMs(20_500)                       // shift read position; libVLC sees content jump
 *   ...
 *   proxy.release()
 *
 * Key details:
 *   - Serve only a small cushion ahead of the requested offset, then follow upstream.
 *   - Keep the localhost socket buffer small so libVLC cannot get seconds ahead of us.
 *   - Frame-align every offset jump, including the first read.
 *   - Initial buffer is awaited before libVLC starts, so the first offset is available.
 */
internal class CustomRadioDelayProxy(
    private val source: CustomRadioSource,
    private val userAgent: String,
    initialOffsetMs: Long,
    private val maxOffsetMs: Long
) {
    companion object {
        private const val TAG = "CustomRadioProxy"
        private const val INITIAL_BYTES_PER_MS: Double = 24.0
        private const val MAX_BYTES_PER_MS: Double = 64.0
        private const val SERVE_CHUNK = 2 * 1024
        private const val SOURCE_CHUNK = 32 * 1024
        private const val FRAME_SCAN_LIMIT = 16 * 1024
        private const val MIN_HEADROOM_BYTES = 64 * 1024
        private const val SOCKET_SEND_BUFFER_BYTES = 32 * 1024
        private const val INITIAL_HEADROOM_MS = 3_000L
        private const val TARGET_CLIENT_CUSHION_MS = 2_500L
        private const val MAX_CLIENT_CUSHION_MS = 6_000L
        private const val SERVE_RATE_MULTIPLIER = 1.04
        // Extra ring headroom beyond max offset so a slow client doesn't get overwritten.
        private const val HEADROOM_MS = 8_000L
    }

    private val ringSize: Int = (((maxOffsetMs + HEADROOM_MS) * MAX_BYTES_PER_MS).toInt())
        .coerceAtLeast(1 * 1024 * 1024)
    private val ring: ByteArray = ByteArray(ringSize)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    @Volatile private var totalBytesWritten: Long = 0L
    private var firstSourceByteAtElapsedMs: Long = 0L
    @Volatile private var currentOffsetMs: Long = initialOffsetMs.coerceIn(0L, maxOffsetMs)
    @Volatile private var offsetGeneration: Long = 0L
    @Volatile private var released: Boolean = false

    private val serverSocket: ServerSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val sourceThread: Thread
    private val acceptThread: Thread
    private val initialBufferLatch = CountDownLatch(1)

    val streamUrl: String = "http://127.0.0.1:${serverSocket.localPort}/custom-radio"

    init {
        sourceThread = Thread(::runSourceLoop, "custom-radio-proxy-source").apply {
            isDaemon = true
            start()
        }
        acceptThread = Thread(::runAcceptLoop, "custom-radio-proxy-accept").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Block up to [timeoutMs] until the ring has accumulated enough upstream bytes to honor
     * the initial offset. Returns true if ready, false on timeout (caller should still try
     * to use the proxy — libVLC will just stall on read until data arrives).
     */
    fun awaitInitialBuffer(timeoutMs: Long): Boolean {
        return try {
            initialBufferLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Change the served offset (ms behind live). Triggers a frame-aligned seek for any
     *  connected client at the next read iteration. Clamped to [0, maxOffsetMs]. */
    fun setOffsetMs(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(0L, maxOffsetMs)
        synchronized(lock) {
            if (currentOffsetMs == clamped) return
            currentOffsetMs = clamped
            offsetGeneration += 1
            lock.notifyAll()
        }
        Log.d(TAG, "offset → ${clamped}ms")
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            lock.notifyAll()
        }
        runCatching { serverSocket.close() }
        // Threads exit via `released` check; don't join (would block UI).
    }

    // ───────────────────────────── source ─────────────────────────────

    private fun runSourceLoop() {
        val buf = ByteArray(SOURCE_CHUNK)
        while (!released) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(source.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Referer", "https://grandprixradio.nl/radio-luisteren")
                    setRequestProperty("Icy-MetaData", "0")
                }
                conn.inputStream.use { input ->
                    while (!released) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) appendRing(buf, n)
                    }
                }
            } catch (e: Throwable) {
                if (!released) Log.w(TAG, "source read failed; reconnecting in 500ms", e)
            } finally {
                runCatching { conn?.disconnect() }
            }
            if (!released) try { Thread.sleep(500L) } catch (_: InterruptedException) { return }
        }
    }

    private fun appendRing(bytes: ByteArray, length: Int) {
        synchronized(lock) {
            if (firstSourceByteAtElapsedMs == 0L) {
                firstSourceByteAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            }
            var copied = 0
            while (copied < length) {
                val idx = ((totalBytesWritten + copied) % ring.size).toInt()
                val n = min(length - copied, ring.size - idx)
                bytes.copyInto(ring, idx, copied, copied + n)
                copied += n
            }
            totalBytesWritten += length
            lock.notifyAll()
            val sourceAgeMs = android.os.SystemClock.elapsedRealtime() - firstSourceByteAtElapsedMs
            if (initialBufferLatch.count > 0 && sourceAgeMs >= currentOffsetMs + INITIAL_HEADROOM_MS) {
                initialBufferLatch.countDown()
            }
        }
    }

    // ───────────────────────────── accept / serve ─────────────────────────────

    private fun runAcceptLoop() {
        while (!released) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (!released) Log.w(TAG, "accept failed", e)
                return
            }
            Thread({
                try {
                    handleClient(socket)
                } catch (e: Throwable) {
                    if (!released) Log.d(TAG, "client ended: ${e.message}")
                } finally {
                    runCatching { socket.close() }
                }
            }, "custom-radio-proxy-client").apply { isDaemon = true }.start()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        socket.sendBufferSize = SOCKET_SEND_BUFFER_BYTES
        val input = socket.getInputStream()
        if (!consumeHttpRequest(input)) return

        val output: OutputStream = socket.getOutputStream()
        writeResponseHeaders(output)

        val chunk = ByteArray(SERVE_CHUNK)
        var readPos: Long = -1L
        var currentGen: Long = -2L
        var totalServed: Long = 0L
        var lastStatusAtMs = 0L
        var nextWriteAtNs = System.nanoTime()

        while (!released && !socket.isClosed) {
            val snapshotGen = synchronized(lock) { offsetGeneration }
            if (snapshotGen != currentGen) {
                val targetPos = synchronized(lock) {
                    val offsetBytes = offsetBytesLocked(currentOffsetMs)
                    max(oldestPositionLocked(), totalBytesWritten - offsetBytes)
                }
                readPos = alignToFrameSync(targetPos)
                currentGen = snapshotGen
                nextWriteAtNs = System.nanoTime()
                Log.d(TAG, "client seek → byte $readPos (offset=${currentOffsetMs}ms, gen=$currentGen)")
            }

            if (!waitForServeWindow(readPos, currentGen)) return

            val n = readFromRing(readPos, chunk)
            if (n < 0) {
                // Reader fell off the back of the ring; re-seek to current target.
                synchronized(lock) { offsetGeneration += 1 }
                continue
            }
            if (n == 0) {
                // No data right now; block briefly for upstream.
                synchronized(lock) {
                    if (!released && totalBytesWritten - readPos <= 0L) {
                        try { lock.wait(250L) } catch (_: InterruptedException) { return }
                    }
                }
                continue
            }
            try {
                output.write(chunk, 0, n)
            } catch (e: IOException) {
                return
            }
            readPos += n
            totalServed += n

            nextWriteAtNs = waitAfterWrite(readPos, n, currentGen, nextWriteAtNs)
                ?: return

            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (nowMs - lastStatusAtMs >= 2_000L) {
                lastStatusAtMs = nowMs
                logClientStatus(readPos, totalServed)
            }
        }
    }

    private fun logClientStatus(readPos: Long, totalServed: Long) {
        synchronized(lock) {
            val rate = bytesPerMsLocked()
            val offsetBytes = offsetBytesLocked(currentOffsetMs)
            val idealReadPos = max(oldestPositionLocked(), totalBytesWritten - offsetBytes)
            val aheadBytes = readPos - idealReadPos
            val availableAheadBytes = totalBytesWritten - readPos
            Log.d(
                TAG,
                "client status served=$totalServed read=$readPos written=$totalBytesWritten " +
                    "rate=${String.format(Locale.US, "%.2f", rate)}B/ms ahead=$aheadBytes " +
                    "availableAhead=$availableAheadBytes offset=${currentOffsetMs}ms"
            )
        }
    }

    private fun waitForServeWindow(nextReadPos: Long, generation: Long): Boolean {
        while (!released) {
            synchronized(lock) {
                if (released) return false
                if (offsetGeneration != generation) return true
                val rate = bytesPerMsLocked()
                val offsetBytes = offsetBytesLocked(currentOffsetMs)
                val cushionBytes = max(SERVE_CHUNK.toLong(), (MAX_CLIENT_CUSHION_MS * rate).toLong())
                val idealReadPos = max(oldestPositionLocked(), totalBytesWritten - offsetBytes)
                val allowedReadPos = min(totalBytesWritten, idealReadPos + cushionBytes)
                if (nextReadPos <= allowedReadPos) return true
                try {
                    lock.wait(250L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    private fun waitAfterWrite(
        readPos: Long,
        bytesWritten: Int,
        generation: Long,
        previousTargetNs: Long
    ): Long? {
        synchronized(lock) {
            if (released) return null
            if (offsetGeneration != generation) return System.nanoTime()

            val rate = bytesPerMsLocked()
            val offsetBytes = offsetBytesLocked(currentOffsetMs)
            val idealReadPos = max(oldestPositionLocked(), totalBytesWritten - offsetBytes)
            val targetCushionBytes = max(SERVE_CHUNK.toLong(), (TARGET_CLIENT_CUSHION_MS * rate).toLong())
            val aheadBytes = readPos - idealReadPos
            if (aheadBytes < targetCushionBytes) return System.nanoTime()

            val targetNs = max(previousTargetNs, System.nanoTime()) +
                ((bytesWritten.toDouble() * 1_000_000.0) / (rate * SERVE_RATE_MULTIPLIER)).toLong()
            while (!released && offsetGeneration == generation) {
                val remainingNs = targetNs - System.nanoTime()
                if (remainingNs <= 0L) return targetNs
                val waitMs = remainingNs / 1_000_000L
                val waitNs = (remainingNs % 1_000_000L).toInt()
                try {
                    lock.wait(waitMs, waitNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return System.nanoTime()
        }
    }

    private fun consumeHttpRequest(input: InputStream): Boolean {
        var consecutiveLF = 0
        var total = 0
        while (true) {
            val v = input.read()
            if (v < 0) return false
            total++
            if (total > 8192) return false
            when (v) {
                '\n'.code -> {
                    consecutiveLF += 1
                    if (consecutiveLF >= 2) return true
                }
                '\r'.code -> { /* skip */ }
                else -> consecutiveLF = 0
            }
        }
    }

    private fun writeResponseHeaders(output: OutputStream) {
        val ct = when {
            source.mimeType?.lowercase(Locale.US)?.contains("aac") == true -> "audio/aac"
            else -> "audio/mpeg"
        }
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(ct).append("\r\n")
            append("icy-br: ").append(icyBitrateKbps()).append("\r\n")
            append("icy-name: Grand Prix Radio delayed\r\n")
            append("icy-pub: 1\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        // Single flush after headers so libVLC sees them immediately and starts reading body.
        output.flush()
    }

    private fun icyBitrateKbps(): Int {
        return if (source.mimeType?.lowercase(Locale.US)?.contains("aac") == true) 128 else 192
    }

    // ───────────────────────────── ring helpers ─────────────────────────────

    private fun oldestPositionLocked(): Long = max(0L, totalBytesWritten - ring.size + MIN_HEADROOM_BYTES)

    private fun bytesPerMsLocked(): Double {
        val firstByteAt = firstSourceByteAtElapsedMs
        if (firstByteAt == 0L) return INITIAL_BYTES_PER_MS
        val elapsedMs = (android.os.SystemClock.elapsedRealtime() - firstByteAt).coerceAtLeast(1L)
        return (totalBytesWritten.toDouble() / elapsedMs.toDouble())
            .coerceIn(8.0, MAX_BYTES_PER_MS)
    }

    private fun offsetBytesLocked(offsetMs: Long): Long {
        return (offsetMs.toDouble() * bytesPerMsLocked()).toLong()
    }

    /**
     * Returns:
     *   > 0  : bytes copied into [target]
     *   = 0  : no bytes available right now (caller should wait/retry)
     *   = -1 : [position] has been overwritten in the ring (caller must re-seek)
     */
    private fun readFromRing(position: Long, target: ByteArray): Int {
        synchronized(lock) {
            if (released) return -1
            val oldest = oldestPositionLocked()
            if (position < oldest) return -1
            val available = totalBytesWritten - position
            if (available <= 0L) return 0
            val n = min(target.size.toLong(), available).toInt()
            val idx = (position % ring.size).toInt()
            val first = min(n, ring.size - idx)
            ring.copyInto(target, 0, idx, idx + first)
            if (first < n) ring.copyInto(target, first, 0, n - first)
            return n
        }
    }

    /** Scan forward from [startPos] up to [FRAME_SCAN_LIMIT] bytes for a validated
     *  MP3/AAC frame. A plain 0xFFE sync check is too weak: that bit pattern appears in
     *  compressed payloads and makes libVLC start from garbage, then resync/replay. */
    private fun alignToFrameSync(startPos: Long): Long {
        synchronized(lock) {
            val oldest = oldestPositionLocked()
            val newest = totalBytesWritten - 1
            var pos = max(startPos, oldest)
            val end = min(pos + FRAME_SCAN_LIMIT, newest)
            while (pos < end) {
                if (isValidatedFrameAtLocked(pos, newest)) return pos
                pos++
            }
            return startPos
        }
    }

    private fun isValidatedFrameAtLocked(pos: Long, newest: Long): Boolean {
        val mp3Len = mp3FrameLengthAtLocked(pos, newest)
        if (mp3Len != null && hasCoarseFrameSyncAtLocked(pos + mp3Len, newest)) return true

        val adtsLen = adtsFrameLengthAtLocked(pos, newest)
        if (adtsLen != null && hasCoarseFrameSyncAtLocked(pos + adtsLen, newest)) return true

        return false
    }

    private fun hasCoarseFrameSyncAtLocked(pos: Long, newest: Long): Boolean {
        if (pos + 1 > newest) return false
        val b0 = byteAtLocked(pos)
        val b1 = byteAtLocked(pos + 1)
        return b0 == 0xFF && (b1 and 0xE0) == 0xE0
    }

    private fun mp3FrameLengthAtLocked(pos: Long, newest: Long): Int? {
        if (pos + 3 > newest) return null
        val b0 = byteAtLocked(pos)
        val b1 = byteAtLocked(pos + 1)
        val b2 = byteAtLocked(pos + 2)
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null

        val version = (b1 shr 3) and 0x03
        val layer = (b1 shr 1) and 0x03
        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleRateIndex = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01
        if (version == 1 || layer == 0 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return null
        }

        val bitrateKbps = when (layer) {
            3 -> intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)[bitrateIndex]
            2 -> intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)[bitrateIndex]
            else -> if (version == 3) {
                intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)[bitrateIndex]
            } else {
                intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)[bitrateIndex]
            }
        }
        if (bitrateKbps <= 0) return null

        val sampleRate = when (version) {
            3 -> intArrayOf(44_100, 48_000, 32_000)[sampleRateIndex]
            2 -> intArrayOf(22_050, 24_000, 16_000)[sampleRateIndex]
            else -> intArrayOf(11_025, 12_000, 8_000)[sampleRateIndex]
        }
        val length = when (layer) {
            3 -> (((12_000 * bitrateKbps) / sampleRate) + padding) * 4
            2 -> ((144_000 * bitrateKbps) / sampleRate) + padding
            else -> {
                val coefficient = if (version == 3) 144_000 else 72_000
                ((coefficient * bitrateKbps) / sampleRate) + padding
            }
        }
        return length.takeIf { it in 24..4096 }
    }

    private fun adtsFrameLengthAtLocked(pos: Long, newest: Long): Int? {
        if (pos + 6 > newest) return null
        val b0 = byteAtLocked(pos)
        val b1 = byteAtLocked(pos + 1)
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0 || (b1 and 0x06) != 0) return null
        val b3 = byteAtLocked(pos + 3)
        val b4 = byteAtLocked(pos + 4)
        val b5 = byteAtLocked(pos + 5)
        val length = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) shr 5)
        return length.takeIf { it in 7..8192 }
    }

    private fun byteAtLocked(pos: Long): Int = ring[(pos % ring.size).toInt()].toInt() and 0xFF
}
