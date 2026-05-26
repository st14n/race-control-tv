package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import fr.groggy.racecontrol.tv.core.settings.Settings
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

internal data class CustomRadioSource(
    val name: String,
    val url: String,
    val mimeType: String? = null,
    val disableIcyMetadata: Boolean = false,
    val normalizeWithInAppHls: Boolean = false
)

internal data class CustomRadioPlanEntry(
    val backend: Settings.CustomRadioBackend,
    val source: CustomRadioSource
)

internal interface CustomRadioEngine {
    fun play()
    fun pause()
    fun skipAhead(skipMs: Long): Boolean
    fun setVolume(volume: Int)  // 0 = mute, 100 = full
    /** Set the audio output delay in milliseconds. VLC buffers audio and outputs it [delayMs] late.
     *  Use this instead of pause/resume to avoid disconnecting a live stream. */
    fun setAudioDelay(delayMs: Long)
    fun release()
}

internal object CustomRadioSources {
    val normalizedGrandPrixRadio = CustomRadioSource(
        name = "grand-prix-radio-local-hls",
        url = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL_MP3,
        mimeType = MimeTypes.AUDIO_MPEG,
        normalizeWithInAppHls = true
    )

    val rawCandidates get() = listOf(
        CustomRadioSource(
            name = "playerservices-mp3",
            url = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL_MP3,
            mimeType = MimeTypes.AUDIO_MPEG
        ),
        CustomRadioSource(
            name = "playerservices-sc",
            url = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL_SC,
            mimeType = MimeTypes.AUDIO_MPEG
        ),
        CustomRadioSource(
            name = "playerservices-aac",
            url = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL_AAC,
            mimeType = MimeTypes.AUDIO_AAC,
            disableIcyMetadata = true
        )
    )
}

internal fun createCustomRadioEngine(
    context: Context,
    planEntry: CustomRadioPlanEntry,
    userAgent: String,
    initialAudioDelayMs: Long = 0L,
    initialVolume: Int = 100,
    useProxy: Boolean = false,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    onError: (String, Throwable?) -> Unit
): CustomRadioEngine {
    if (planEntry.source.normalizeWithInAppHls) {
        return InAppHlsCustomRadioEngine(
            context = context,
            upstreamSource = planEntry.source,
            userAgent = userAgent,
            initialAudioDelayMs = initialAudioDelayMs,
            initialVolume = initialVolume,
            onStarted = onStarted,
            onEnded = onEnded,
            onError = onError
        )
    }

    return when (planEntry.backend) {
        Settings.CustomRadioBackend.LIBVLC ->
            LibVlcCustomRadioEngine(
                context = context,
                source = planEntry.source,
                userAgent = userAgent,
                initialAudioDelayMs = initialAudioDelayMs,
                initialVolume = initialVolume,
                useProxy = useProxy,
                onStarted = onStarted,
                onEnded = onEnded,
                onError = onError
            )
        Settings.CustomRadioBackend.EXOPLAYER -> ExoCustomRadioEngine(
            context = context,
            source = planEntry.source,
            userAgent = userAgent,
            initialAudioDelayMs = initialAudioDelayMs,
            initialVolume = initialVolume,
            onStarted = onStarted,
            onEnded = onEnded,
            onError = onError
        )
        Settings.CustomRadioBackend.AUTO -> error("AUTO is not a concrete playback backend")
    }
}

internal class ExoCustomRadioEngine(
    context: Context,
    source: CustomRadioSource,
    userAgent: String,
    initialAudioDelayMs: Long,
    initialVolume: Int,
    private val onStarted: () -> Unit,
    private val onEnded: () -> Unit,
    private val onError: (String, Throwable?) -> Unit
) : CustomRadioEngine {

    private val player = ExoPlayer.Builder(context).build()
    private val handler = Handler(Looper.getMainLooper())
    private val supportsDelaySeek = source.mimeType == MimeTypes.APPLICATION_M3U8
        || source.url.endsWith(".m3u8", ignoreCase = true)
    private var targetDelayMs = initialAudioDelayMs.coerceAtLeast(0L)
    private var initialDelaySeekApplied = false
    private var released = false
    private var started = false

    init {
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false
        )
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && supportsDelaySeek && !initialDelaySeekApplied) {
                    maybeStartHlsAtRequestedDelay()
                    return
                }
                if (playbackState == Player.STATE_READY && !started) {
                    started = true
                    onStarted()
                }
                if (playbackState == Player.STATE_ENDED) {
                    onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onError(error.errorCodeName, error)
            }
        })

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(if (supportsDelaySeek) 3_000 else 8_000)
            .setReadTimeoutMs(if (supportsDelaySeek) 8_000 else 20_000)
            .apply {
                if (source.disableIcyMetadata) {
                    setDefaultRequestProperties(mapOf("Icy-MetaData" to "0"))
                }
            }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaItem = MediaItem.Builder()
            .setUri(source.url)
            .setMimeType(source.mimeType)
            .apply {
                if (supportsDelaySeek) {
                    setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(targetDelayMs)
                            .setMinPlaybackSpeed(1f)
                            .setMaxPlaybackSpeed(1f)
                            .build()
                    )
                }
            }
            .build()
        val mediaSource = if (source.mimeType == MimeTypes.APPLICATION_M3U8
            || source.url.endsWith(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        player.setMediaSource(mediaSource)
        player.prepare()
        player.volume = initialVolume / 100f
        player.playWhenReady = !supportsDelaySeek
    }

    private fun maybeStartHlsAtRequestedDelay() {
        if (released || initialDelaySeekApplied) return

        val durationMs = player.duration
        if (durationMs == C.TIME_UNSET || durationMs < targetDelayMs + 1_000L) {
            handler.postDelayed({ maybeStartHlsAtRequestedDelay() }, 500L)
            return
        }

        val targetPositionMs = hlsPositionForDelay(durationMs, targetDelayMs)
        initialDelaySeekApplied = true
        player.seekTo(targetPositionMs)
        player.playWhenReady = true
        if (!started) {
            started = true
            onStarted()
        }
        Log.i("ExoCustomRadio", "started HLS delay=${targetDelayMs}ms duration=${durationMs}ms position=${targetPositionMs}ms")
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun skipAhead(skipMs: Long): Boolean {
        if (skipMs <= 0L || !player.isCurrentMediaItemSeekable) {
            return false
        }
        player.seekTo((player.currentPosition + skipMs).coerceAtLeast(0L))
        return true
    }

    override fun setVolume(volume: Int) {
        player.volume = volume / 100f
    }

    override fun setAudioDelay(delayMs: Long) {
        val newDelayMs = delayMs.coerceAtLeast(0L)
        if (!supportsDelaySeek) {
            targetDelayMs = newDelayMs
            return
        }
        targetDelayMs = newDelayMs
        if (player.playbackState == Player.STATE_IDLE || !initialDelaySeekApplied) return

        val durationMs = player.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) return
        val targetPositionMs = hlsPositionForDelay(durationMs, targetDelayMs)
        player.seekTo(targetPositionMs)
        Log.i("ExoCustomRadio", "delay -> ${targetDelayMs}ms position=${targetPositionMs}ms duration=${durationMs}ms")
    }

    private fun hlsPositionForDelay(durationMs: Long, delayMs: Long): Long {
        return (durationMs - delayMs).coerceIn(0L, durationMs)
    }

    override fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}

private class LibVlcCustomRadioEngine(
    context: Context,
    private val source: CustomRadioSource,
    private val userAgent: String,
    /** Initial offset in milliseconds (how far behind live to start). All offset management
     *  is delegated to [CustomRadioDelayProxy]; libVLC's own audioDelay stays at 0. */
    private val initialAudioDelayMs: Long = 0L,
    initialVolume: Int = 100,
    private val useProxy: Boolean = USE_PROXY,
    private val onStarted: () -> Unit,
    private val onEnded: () -> Unit,
    private val onError: (String, Throwable?) -> Unit
) : CustomRadioEngine {

    companion object {
        private const val TAG = "LibVlcCustomRadio"
        /** Proxy is kept for debug experiments, but normal playback stays on direct
         *  libVLC: emulator and TV logs show the current proxy stream makes libVLC
         *  increase pts_delay and AudioTrack underrun/replay even when bytes are buffered. */
        private const val USE_PROXY: Boolean = false
        /** Max user-configurable offset; matches `CUSTOM_RADIO_MAX_OFFSET_MS` in the fragment. */
        private const val PROXY_MAX_OFFSET_MS: Long = 30_000L
        /** Hard cap on how long we wait for the proxy ring to fill before starting libVLC
         *  anyway (libVLC will then stall on read until upstream catches up). */
        private const val PROXY_BUFFER_TIMEOUT_HEADROOM_MS: Long = 10_000L
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--network-caching=1500",
            "--http-reconnect"
        )
    )
    private val mediaPlayer = VlcMediaPlayer(libVlc)
    @Volatile private var released = false
    private var started = false

    @Volatile private var audibleVolume: Int = initialVolume

    /** The active proxy, or null if proxy is disabled / failed and we're on the direct
     *  upstream + libVLC.audioDelay path. */
    private var proxy: CustomRadioDelayProxy? = null

    /** Desired audio delay in microseconds. Re-applied on every Playing event so silent
     *  ICY reconnects (which reset audioDelay to 0) don't lose user intent. */
    private var targetAudioDelayUs: Long = initialAudioDelayMs * 1_000L

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                VlcMediaPlayer.Event.Playing -> handlePlayingEvent()
                VlcMediaPlayer.Event.EndReached -> if (!released) onEnded()
                VlcMediaPlayer.Event.EncounteredError -> if (!released) onError("libvlc_encountered_error", null)
            }
        }

        if (useProxy) {
            startWithProxyAsync()
        } else {
            startDirect()
        }
    }

    // ─────────────────────────── proxy path ───────────────────────────

    private fun startWithProxyAsync() {
        // Spawn a worker so we don't block the fragment's onCreate for ~20s.
        Thread({
            val createdProxy = try {
                CustomRadioDelayProxy(
                    source = source,
                    userAgent = userAgent,
                    initialOffsetMs = initialAudioDelayMs,
                    maxOffsetMs = PROXY_MAX_OFFSET_MS
                )
            } catch (e: Throwable) {
                Log.w(TAG, "proxy construction failed; falling back to direct URL", e)
                handler.post { if (!released) startDirect() }
                return@Thread
            }
            if (released) {
                createdProxy.release()
                return@Thread
            }
            proxy = createdProxy

            val timeoutMs = initialAudioDelayMs + PROXY_BUFFER_TIMEOUT_HEADROOM_MS
            val ready = createdProxy.awaitInitialBuffer(timeoutMs.coerceAtLeast(5_000L))
            if (released) return@Thread
            if (!ready) {
                Log.w(TAG, "proxy buffer timeout — starting libVLC anyway (may stall briefly)")
            }

            handler.post { if (!released) startMediaPlayerWithUrl(createdProxy.streamUrl, viaProxy = true) }
        }, "custom-radio-engine-init").apply { isDaemon = true }.start()
    }

    // ─────────────────────────── direct path ──────────────────────────

    private fun startDirect() {
        proxy = null
        // Apply target delay BEFORE play() so libVLC buffers `initialAudioDelayMs` of
        // audio internally before producing output — no pause/reconnect needed.
        if (initialAudioDelayMs > 0L) {
            mediaPlayer.audioDelay = targetAudioDelayUs
        }
        mediaPlayer.volume = audibleVolume
        startMediaPlayerWithUrl(source.url, viaProxy = false)
    }

    private fun startMediaPlayerWithUrl(url: String, viaProxy: Boolean) {
        if (released) return
        try {
            val media = Media(libVlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(false, false)
                addOption(if (viaProxy) ":network-caching=2500" else ":network-caching=1500")
                addOption(if (viaProxy) ":live-caching=2500" else ":live-caching=1500")
                addOption(":http-user-agent=$userAgent")
                addOption(":http-referrer=https://grandprixradio.nl/radio-luisteren")
            }
            mediaPlayer.media = media
            media.release()

            if (viaProxy) {
                // Proxy already supplies bytes at the correct offset; libVLC plays immediately.
                mediaPlayer.audioDelay = 0L
                mediaPlayer.volume = audibleVolume
            }

            mediaPlayer.play()
        } catch (e: Throwable) {
            onError(if (viaProxy) "libvlc_proxy_play_failed" else "libvlc_play_failed", e)
        }
    }

    private fun handlePlayingEvent() {
        if (released) return
        if (proxy == null) {
            // Re-apply target delay (Playing fires on silent ICY reconnect too, which
            // resets audioDelay to 0).
            if (mediaPlayer.audioDelay != targetAudioDelayUs) {
                mediaPlayer.audioDelay = targetAudioDelayUs
            }
        }
        if (!started) {
            started = true
            onStarted()
        }
    }

    // ─────────────────────────── public API ───────────────────────────

    override fun play() { if (!released) mediaPlayer.play() }
    override fun pause() { if (!released) mediaPlayer.pause() }
    override fun skipAhead(skipMs: Long): Boolean = false

    override fun setVolume(volume: Int) {
        if (released) return
        audibleVolume = volume
        mediaPlayer.volume = volume
    }

    override fun setAudioDelay(delayMs: Long) {
        if (released) return
        val p = proxy
        if (p != null) {
            p.setOffsetMs(delayMs)
            return
        }
        // Direct path: absolute write. Increases insert silence, decreases skip ahead
        // (subject to libVLC's internal buffer — a large decrease may briefly stall).
        targetAudioDelayUs = delayMs * 1_000L
        mediaPlayer.audioDelay = targetAudioDelayUs
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { proxy?.release() }
        proxy = null
        mediaPlayer.setEventListener(null)
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }
}