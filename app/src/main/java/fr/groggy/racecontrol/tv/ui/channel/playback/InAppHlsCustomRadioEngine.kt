package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class InAppHlsCustomRadioEngine(
	context: Context,
	private val upstreamSource: CustomRadioSource,
	private val userAgent: String,
	private val initialAudioDelayMs: Long,
	initialVolume: Int,
	private val onStarted: () -> Unit,
	private val onEnded: () -> Unit,
	private val onError: (String, Throwable?) -> Unit
) : CustomRadioEngine {

	private val appContext = context.applicationContext
	private val handler = Handler(Looper.getMainLooper())
	private val released = AtomicBoolean(false)
	private val hlsDir = File(appContext.cacheDir, "custom-radio-hls/${UUID.randomUUID()}")
	private val playlistFile = File(hlsDir, "live.m3u8")
	@Volatile private var worker: Thread? = null
	@Volatile private var grabber: FFmpegFrameGrabber? = null
	@Volatile private var recorder: FFmpegFrameRecorder? = null
	private var child: CustomRadioEngine? = null
	private var pendingVolume = initialVolume
	private var pendingDelayMs = initialAudioDelayMs.coerceAtLeast(0L)
	private var playRequested = true
	private var completionReported = false
	private var probeStartedAtMs = 0L

	init {
		configureJavaCppLogger()
		hlsDir.mkdirs()
		deleteHlsFiles()
		startFfmpeg()
		probeStartedAtMs = System.currentTimeMillis()
		handler.postDelayed(::waitForPlaylist, PLAYLIST_POLL_MS)
	}

	private fun startFfmpeg() {
		worker = Thread(::runFfmpegLoop, "custom-radio-hls-normalizer").apply {
			isDaemon = true
			start()
		}
		Log.i(TAG, "started in-app HLS normalizer for ${upstreamSource.name} at ${hlsDir.absolutePath}")
	}

	private fun configureJavaCppLogger() {
		if (System.getProperty(JAVACPP_LOGGER_PROPERTY).isNullOrBlank()) {
			System.setProperty(JAVACPP_LOGGER_PROPERTY, JAVACPP_LOGGER_SLF4J)
		}
	}

	private fun runFfmpegLoop() {
		try {
			val localGrabber = FFmpegFrameGrabber(upstreamSource.url).apply {
				setOption("reconnect", "1")
				setOption("reconnect_streamed", "1")
				setOption("reconnect_delay_max", "2")
				setOption("user_agent", userAgent)
				setOption("headers", "Icy-MetaData: 0\r\n")
				start()
			}
			grabber = localGrabber

			val channels = localGrabber.audioChannels.takeIf { it > 0 } ?: 2
			val sampleRate = localGrabber.sampleRate.takeIf { it > 0 } ?: 48_000
			val segmentPattern = File(hlsDir, "segment_%05d.ts").absolutePath
			val localRecorder = FFmpegFrameRecorder(playlistFile.absolutePath, channels).apply {
				format = "hls"
				audioCodec = AV_CODEC_ID_AAC
				audioBitrate = 192_000
				audioChannels = channels
				this.sampleRate = sampleRate
				setOption("hls_time", "1")
				setOption("hls_list_size", "60")
				setOption("hls_delete_threshold", "10")
				setOption("hls_flags", "delete_segments+omit_endlist+program_date_time+independent_segments")
				setOption("hls_segment_type", "mpegts")
				setOption("hls_segment_filename", segmentPattern)
				start()
			}
			recorder = localRecorder

			while (!released.get()) {
				val frame = localGrabber.grabSamples() ?: break
				localRecorder.record(frame)
			}

			if (!released.get()) {
				handler.post { reportError("ffmpeg_hls_upstream_ended", null) }
			}
		} catch (e: Throwable) {
			if (!released.get()) {
				handler.post { reportError("ffmpeg_hls_failed", e) }
			}
		} finally {
			stopRecorder()
		}
	}

	private fun waitForPlaylist() {
		if (released.get() || child != null) return

		val elapsedMs = System.currentTimeMillis() - probeStartedAtMs
		val playlistDurationMs = hlsPlaylistDurationMs()
		val requiredDurationMs = pendingDelayMs + HLS_START_HEADROOM_MS
		if (elapsedMs >= pendingDelayMs && playlistDurationMs >= requiredDurationMs) {
			startChildPlayer()
			return
		}

		if (elapsedMs > pendingDelayMs + PLAYLIST_START_TIMEOUT_MS) {
			reportError("ffmpeg_hls_playlist_timeout", null)
			return
		}

		handler.postDelayed(::waitForPlaylist, PLAYLIST_POLL_MS)
	}

	private fun startChildPlayer() {
		if (released.get()) return
		val fileUri = playlistFile.toUri().toString()
		val hlsSource = CustomRadioSource(
			name = "${upstreamSource.name}-cache",
			url = fileUri,
			mimeType = MimeTypes.APPLICATION_M3U8
		)
		child = ExoCustomRadioEngine(
			context = appContext,
			source = hlsSource,
			userAgent = userAgent,
			initialAudioDelayMs = pendingDelayMs,
			initialVolume = pendingVolume,
			onStarted = {
				if (released.get()) return@ExoCustomRadioEngine
				onStarted()
				if (!playRequested) child?.pause()
			},
			onEnded = ::handleChildEnded,
			onError = { code, throwable ->
				if (!released.get()) reportError("hls_$code", throwable)
			}
		)
		if (!playRequested) child?.pause()
		Log.i(TAG, "attached ExoPlayer to local HLS playlist delay=${pendingDelayMs}ms url=$fileUri")
	}

	private fun stopRecorder() {
		runCatching { recorder?.stop() }
		runCatching { recorder?.release() }
		recorder = null
		runCatching { grabber?.stop() }
		runCatching { grabber?.release() }
		grabber = null
	}

	private fun hlsPlaylistDurationMs(): Long {
		if (!playlistFile.isFile) return 0L
		return runCatching {
			playlistFile.readLines().sumOf { line ->
				if (!line.startsWith("#EXTINF:")) {
					0L
				} else {
					val seconds = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
					(seconds * 1_000L).toLong()
				}
			}
		}.getOrDefault(0L)
	}

	private fun reportError(code: String, throwable: Throwable?) {
		if (released.get() || completionReported) return
		completionReported = true
		Log.w(TAG, "radio normalizer error: $code", throwable)
		onError(code, throwable)
	}

	private fun handleChildEnded() {
		if (released.get() || completionReported) return
		completionReported = true
		onEnded()
	}

	override fun play() {
		playRequested = true
		child?.play()
	}

	override fun pause() {
		playRequested = false
		child?.pause()
	}

	override fun skipAhead(skipMs: Long): Boolean {
		return child?.skipAhead(skipMs) ?: false
	}

	override fun setVolume(volume: Int) {
		pendingVolume = volume.coerceIn(0, 100)
		child?.setVolume(pendingVolume)
	}

	override fun setAudioDelay(delayMs: Long) {
		pendingDelayMs = delayMs.coerceAtLeast(0L)
		child?.setAudioDelay(pendingDelayMs)
	}

	override fun release() {
		if (!released.compareAndSet(false, true)) return
		handler.removeCallbacksAndMessages(null)
		child?.release()
		child = null
		stopRecorder()
		runCatching { worker?.interrupt() }
		worker = null
		deleteHlsFiles()
		hlsDir.delete()
	}

	private fun deleteHlsFiles() {
		hlsDir.listFiles()?.forEach { file ->
			runCatching { file.deleteRecursively() }
		}
	}

	private companion object {
		const val TAG = "InAppHlsRadio"
		const val JAVACPP_LOGGER_PROPERTY = "org.bytedeco.javacpp.logger"
		const val JAVACPP_LOGGER_SLF4J = "slf4j"
		const val HLS_START_HEADROOM_MS = 1_500L
		const val PLAYLIST_POLL_MS = 250L
		const val PLAYLIST_START_TIMEOUT_MS = 20_000L
	}
}
