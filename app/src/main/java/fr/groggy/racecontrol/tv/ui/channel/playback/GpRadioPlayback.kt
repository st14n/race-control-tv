package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import fr.groggy.racecontrol.tv.core.settings.Settings
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer

internal data class GpRadioSource(
    val name: String,
    val url: String,
    val mimeType: String? = null,
    val disableIcyMetadata: Boolean = false
)

internal data class GpRadioPlanEntry(
    val backend: Settings.CustomRadioBackend,
    val source: GpRadioSource
)

internal interface GpRadioEngine {
    fun play()
    fun pause()
    fun skipAhead(skipMs: Long): Boolean
    fun setVolume(volume: Int)  // 0 = mute, 100 = full
    /** Set the audio output delay in milliseconds. VLC buffers audio and outputs it [delayMs] late.
     *  Use this instead of pause/resume to avoid disconnecting a live stream. */
    fun setAudioDelay(delayMs: Long)
    fun release()
}

internal object GpRadioSources {
    val rawCandidates get() = listOf(
        GpRadioSource(
            name = "playerservices-mp3",
            url = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL_MP3,
            mimeType = MimeTypes.AUDIO_MPEG
        ),
        GpRadioSource(
            name = "playerservices-sc",
            url = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL_SC,
            mimeType = MimeTypes.AUDIO_MPEG
        ),
        GpRadioSource(
            name = "playerservices-aac",
            url = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL_AAC,
            mimeType = MimeTypes.AUDIO_AAC,
            disableIcyMetadata = true
        )
    )
}

internal fun createGpRadioEngine(
    context: Context,
    planEntry: GpRadioPlanEntry,
    userAgent: String,
    initialAudioDelayMs: Long = 0L,
    initialVolume: Int = 100,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    onError: (String, Throwable?) -> Unit
): GpRadioEngine {
    return when (planEntry.backend) {
        Settings.CustomRadioBackend.LIBVLC -> LibVlcGpRadioEngine(
            context = context,
            source = planEntry.source,
            userAgent = userAgent,
            initialAudioDelayMs = initialAudioDelayMs,
            initialVolume = initialVolume,
            onStarted = onStarted,
            onEnded = onEnded,
            onError = onError
        )
        Settings.CustomRadioBackend.EXOPLAYER -> ExoGpRadioEngine(
            context = context,
            source = planEntry.source,
            userAgent = userAgent,
            initialVolume = initialVolume,
            onStarted = onStarted,
            onEnded = onEnded,
            onError = onError
        )
        Settings.CustomRadioBackend.AUTO -> error("AUTO is not a concrete playback backend")
    }
}

private class ExoGpRadioEngine(
    context: Context,
    source: GpRadioSource,
    userAgent: String,
    initialVolume: Int,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    onError: (String, Throwable?) -> Unit
) : GpRadioEngine {

    private val player = ExoPlayer.Builder(context).build()
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

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .apply {
                if (source.disableIcyMetadata) {
                    setDefaultRequestProperties(mapOf("Icy-MetaData" to "0"))
                }
            }
        val mediaItem = MediaItem.Builder()
            .setUri(source.url)
            .setMimeType(source.mimeType)
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
        player.playWhenReady = true
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

    // ExoPlayer has no built-in audio-delay API; sync is handled by the LibVLC engine.
    override fun setAudioDelay(delayMs: Long) = Unit

    override fun release() {
        player.release()
    }
}

private class LibVlcGpRadioEngine(
    context: Context,
    source: GpRadioSource,
    userAgent: String,
    /** Pre-set audio delay in milliseconds. VLC will buffer this much audio before outputting,
     *  creating a natural delay that matches the F1TV stream offset — with no pause/reconnect. */
    initialAudioDelayMs: Long = 0L,
    initialVolume: Int = 100,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    onError: (String, Throwable?) -> Unit
) : GpRadioEngine {

    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--network-caching=1500",
            "--http-reconnect",
            "--no-audio-time-stretch"
        )
    )
    private val mediaPlayer = VlcMediaPlayer(libVlc)
    private var released = false
    private var started = false

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                VlcMediaPlayer.Event.Playing -> {
                    if (!started) {
                        started = true
                        onStarted()
                    }
                }
                VlcMediaPlayer.Event.EndReached -> if (!released) onEnded()
                VlcMediaPlayer.Event.EncounteredError -> {
                    if (released) return@setEventListener
                    onError("libvlc_encountered_error", null)
                }
            }
        }

        val media = Media(libVlc, Uri.parse(source.url)).apply {
            setHWDecoderEnabled(false, false)
            addOption(":network-caching=1500")
            addOption(":http-user-agent=$userAgent")
            addOption(":http-referrer=https://grandprixradio.nl/radio-luisteren")
        }
        mediaPlayer.media = media
        media.release()

        // Apply the sync delay BEFORE play so VLC buffers [initialAudioDelayMs] of audio
        // internally before outputting — no pause/reconnect needed.
        if (initialAudioDelayMs > 0L) {
            mediaPlayer.audioDelay = initialAudioDelayMs * 1_000L  // microseconds
        }
        mediaPlayer.volume = initialVolume

        try {
            mediaPlayer.play()
        } catch (e: Throwable) {
            onError("libvlc_play_failed", e)
        }
    }

    override fun play() {
        if (!released) {
            mediaPlayer.play()
        }
    }

    override fun pause() {
        if (!released) {
            mediaPlayer.pause()
        }
    }

    override fun skipAhead(skipMs: Long): Boolean {
        if (released || skipMs <= 0L) {
            return false
        }
        val currentTimeMs = mediaPlayer.time.coerceAtLeast(0L)
        val targetTimeMs = currentTimeMs + skipMs
        return runCatching {
            mediaPlayer.setTime(targetTimeMs) >= 0L
        }.getOrDefault(false)
    }

    override fun setVolume(volume: Int) {
        if (!released) mediaPlayer.volume = volume
    }

    override fun setAudioDelay(delayMs: Long) {
        if (!released) mediaPlayer.audioDelay = delayMs * 1_000L  // microseconds
    }

    override fun release() {
        if (released) return
        released = true
        mediaPlayer.setEventListener(null)
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }
}