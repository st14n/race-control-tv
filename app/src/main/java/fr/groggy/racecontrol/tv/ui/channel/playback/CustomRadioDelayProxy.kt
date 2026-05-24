package fr.groggy.racecontrol.tv.ui.channel.playback

import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal class CustomRadioDelayProxy(
    private val source: CustomRadioSource,
    private val userAgent: String,
    private val maxDelayMs: Long
) {
    companion object {
        private val TAG = CustomRadioDelayProxy::class.simpleName
        private const val DEFAULT_BYTES_PER_MS = 16.0 // 128 kbit/s MP3/AAC fallback.
        private const val RING_BUFFER_BYTES = 4 * 1024 * 1024
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val buffer = ByteArray(RING_BUFFER_BYTES)
    private var totalBytesWritten = 0L
    private var firstByteAtElapsedMs = 0L
    private var lastByteAtElapsedMs = 0L
    private var released = false
    private var serverSocket: ServerSocket? = null
    private var sourceConnection: HttpURLConnection? = null
    private var streamId = 0L
    private var playbackOffsetMs = 0L
    private var playbackOffsetGeneration = 0L

    val localBaseUrl: String

    init {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        localBaseUrl = "http://127.0.0.1:${server.localPort}/custom-radio"
        Thread(::acceptLoop, "custom-radio-delay-proxy-accept").start()
        Thread(::readSourceLoop, "custom-radio-delay-proxy-source").start()
    }

    fun streamUrl(offsetMs: Long): String {
        val clampedOffsetMs = offsetMs.coerceIn(0L, maxDelayMs)
        setPlaybackOffset(clampedOffsetMs)
        return "$localBaseUrl?offsetMs=$clampedOffsetMs&streamId=${++streamId}"
    }

    fun setPlaybackOffset(offsetMs: Long) {
        val clampedOffsetMs = offsetMs.coerceIn(0L, maxDelayMs)
        synchronized(lock) {
            if (playbackOffsetMs == clampedOffsetMs) return
            playbackOffsetMs = clampedOffsetMs
            playbackOffsetGeneration += 1L
            lock.notifyAll()
        }
        Log.d(TAG, "Local radio playback offset set offsetMs=$clampedOffsetMs")
    }

    fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
        runCatching { sourceConnection?.disconnect() }
        runCatching { serverSocket?.close() }
    }

    private fun readSourceLoop() {
        while (!isReleased()) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Referer", "https://grandprixradio.nl/radio-luisteren")
                    if (source.disableIcyMetadata) {
                        setRequestProperty("Icy-MetaData", "0")
                    }
                }
                sourceConnection = connection
                connection.inputStream.use { input ->
                    val chunk = ByteArray(16 * 1024)
                    while (!isReleased()) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        if (read > 0) appendToRing(chunk, read)
                    }
                }
            } catch (e: Throwable) {
                if (!isReleased()) {
                    Log.w(TAG, "Source read failed; reconnecting", e)
                }
            } finally {
                runCatching { connection?.disconnect() }
                if (!isReleased()) {
                    Thread.sleep(500L)
                }
            }
        }
    }

    private fun appendToRing(bytes: ByteArray, length: Int) {
        synchronized(lock) {
            val nowMs = SystemClock.elapsedRealtime()
            if (firstByteAtElapsedMs == 0L) {
                firstByteAtElapsedMs = nowMs
            }
            var copied = 0
            while (copied < length) {
                val writeIndex = ((totalBytesWritten + copied) % buffer.size).toInt()
                val copyLength = min(length - copied, buffer.size - writeIndex)
                bytes.copyInto(buffer, writeIndex, copied, copied + copyLength)
                copied += copyLength
            }
            totalBytesWritten += length
            lastByteAtElapsedMs = nowMs
            lock.notifyAll()
        }
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (!isReleased()) {
            try {
                val socket = server.accept()
                Thread({
                    try {
                        handleClient(socket)
                    } catch (e: SocketException) {
                        if (!isReleased()) {
                            Log.d(TAG, "Local radio client disconnected: ${e.message}")
                        }
                    } catch (e: IOException) {
                        if (!isReleased()) {
                            Log.d(TAG, "Local radio client closed", e)
                        }
                    } catch (e: Throwable) {
                        if (!isReleased()) {
                            Log.w(TAG, "Local radio client failed", e)
                        }
                    }
                }, "custom-radio-delay-proxy-client").start()
            } catch (e: IOException) {
                if (!isReleased()) {
                    Log.w(TAG, "Local radio server accept failed", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val requestLine = readHttpLine(input) ?: return
            while (true) {
                val line = readHttpLine(input) ?: return
                if (line.isEmpty()) break
            }
            val requestedOffsetMs = parseOffsetMs(requestLine).coerceIn(0L, maxDelayMs)
            setPlaybackOffset(requestedOffsetMs)
            val output = BufferedOutputStream(client.getOutputStream())
            output.write(
                buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${contentType()}\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.US_ASCII)
            )
            output.flush()

            var offsetMs = currentPlaybackOffsetMs()
            var offsetGeneration = currentPlaybackOffsetGeneration()
            var delayBytes = bytesForDelay(offsetMs)
            var readPosition = initialReadPosition(offsetMs)
            val chunk = ByteArray(8 * 1024)
            while (!isReleased() && !client.isClosed) {
                val newOffsetGeneration = currentPlaybackOffsetGeneration()
                if (newOffsetGeneration != offsetGeneration) {
                    offsetMs = currentPlaybackOffsetMs()
                    delayBytes = bytesForDelay(offsetMs)
                    readPosition = initialReadPosition(offsetMs)
                    offsetGeneration = newOffsetGeneration
                    Log.d(TAG, "Local radio client repositioned offsetMs=$offsetMs")
                }
                val read = readFromRing(readPosition, chunk, delayBytes)
                if (read <= 0) break
                output.write(chunk, 0, read)
                output.flush()
                readPosition += read
            }
        }
    }

    private fun currentPlaybackOffsetMs(): Long = synchronized(lock) { playbackOffsetMs }

    private fun currentPlaybackOffsetGeneration(): Long = synchronized(lock) { playbackOffsetGeneration }

    private fun initialReadPosition(offsetMs: Long): Long {
        synchronized(lock) {
            while (!released) {
                val delayBytes = bytesForDelayLocked(offsetMs)
                val newest = totalBytesWritten
                val oldest = oldestAvailablePositionLocked()
                val target = newest - delayBytes
                if (offsetMs == 0L || target >= oldest) {
                    return max(oldest, target)
                }
                lock.wait(250L)
            }
            return 0L
        }
    }

    private fun readFromRing(position: Long, target: ByteArray, delayBytes: Long): Int {
        synchronized(lock) {
            while (!released && delayedReadablePositionLocked(delayBytes) <= position) {
                lock.wait(250L)
            }
            if (released) return -1
            val oldest = oldestAvailablePositionLocked()
            if (position < oldest) return -1
            val readablePosition = delayedReadablePositionLocked(delayBytes)
            val available = min(target.size.toLong(), readablePosition - position).toInt()
            if (available <= 0) return -1
            val readIndex = (position % buffer.size).toInt()
            val firstCopy = min(available, buffer.size - readIndex)
            buffer.copyInto(target, 0, readIndex, readIndex + firstCopy)
            if (firstCopy < available) {
                buffer.copyInto(target, firstCopy, 0, available - firstCopy)
            }
            return available
        }
    }

    private fun delayedReadablePositionLocked(delayBytes: Long): Long {
        if (delayBytes <= 0L) return totalBytesWritten
        val delayedPosition = totalBytesWritten - delayBytes
        return max(oldestAvailablePositionLocked(), delayedPosition)
    }

    private fun bytesForDelay(delayMs: Long): Long = synchronized(lock) { bytesForDelayLocked(delayMs) }

    private fun bytesForDelayLocked(delayMs: Long): Long {
        val elapsedMs = (lastByteAtElapsedMs - firstByteAtElapsedMs).coerceAtLeast(1L)
        val observedBytesPerMs = if (totalBytesWritten >= 64 * 1024 && elapsedMs > 2_000L) {
            totalBytesWritten.toDouble() / elapsedMs.toDouble()
        } else {
            DEFAULT_BYTES_PER_MS
        }
        return (observedBytesPerMs * delayMs).toLong().coerceAtMost((buffer.size * 0.9).toLong())
    }

    private fun oldestAvailablePositionLocked(): Long {
        return max(0L, totalBytesWritten - buffer.size)
    }

    private fun parseOffsetMs(requestLine: String): Long {
        val marker = "offsetMs="
        val start = requestLine.indexOf(marker)
        if (start < 0) return 0L
        val valueStart = start + marker.length
        val valueEnd = requestLine.indexOfAny(charArrayOf('&', ' '), valueStart).let { if (it < 0) requestLine.length else it }
        return requestLine.substring(valueStart, valueEnd).toLongOrNull() ?: 0L
    }

    private fun contentType(): String {
        return when {
            source.mimeType?.lowercase(Locale.US)?.contains("aac") == true -> "audio/aac"
            else -> "audio/mpeg"
        }
    }

    private fun readHttpLine(input: java.io.InputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun isReleased(): Boolean = synchronized(lock) { released }
}