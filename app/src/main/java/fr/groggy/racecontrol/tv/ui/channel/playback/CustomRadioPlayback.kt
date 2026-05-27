package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
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
    /** Set the audio output delay in milliseconds without reconnecting the live stream. */
    fun setAudioDelay(delayMs: Long)
    fun release()
}

internal object CustomRadioSources {
    val defaultCandidate: CustomRadioSource?
        get() = fr.groggy.racecontrol.tv.BuildConfig.CUSTOM_RADIO_URL
            .takeIf { it.isNotBlank() }
            ?.let {
                CustomRadioSource(
                    name = "configured-custom-radio",
                    url = it,
                    normalizeWithInAppHls = true
                )
            }
}

internal fun createCustomRadioEngine(
    context: Context,
    planEntry: CustomRadioPlanEntry,
    userAgent: String,
    initialAudioDelayMs: Long = 0L,
    initialVolume: Int = 100,
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
