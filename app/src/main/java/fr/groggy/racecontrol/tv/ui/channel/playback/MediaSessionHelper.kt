package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class MediaSessionHelper @Inject constructor(
    @ApplicationContext context: Context
): MediaSession.Callback() {
    private val mediaSession by lazy {
        MediaSession(context, "F1ViewingService").apply {
            setCallback(this@MediaSessionHelper)
        }
    }

    fun setActive(isActive: Boolean) {
        mediaSession.isActive = isActive
        //updatePlaybackState()
        //setViewingMeta()
    }

    private fun setViewingMeta() {
        val mediaMetadata = MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "race")
            putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, "race")
//            putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, myData.artUri)
            putString(MediaMetadata.METADATA_KEY_TITLE, "race")
            putString(MediaMetadata.METADATA_KEY_ARTIST, "race")
        }.build()
        mediaSession.setMetadata(mediaMetadata)
    }

    private fun updatePlaybackState() {
        val position: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN

        val stateBuilder = PlaybackState.Builder()
            .setActions(0).apply {
                setState(getState(), position, 1.0f)
            }
        mediaSession.setPlaybackState(stateBuilder.build())
    }

    private fun getState(): Int {
        return if (mediaSession.isActive) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_PAUSED
        }
    }

    private fun getActions(): Long {
        if (mediaSession.isActive) {
            return PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PAUSE
        }
        return PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY
    }
}
