package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.content.Intent
import android.net.Uri // Keep Uri import
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import androidx.core.os.bundleOf
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem // Keep MediaItem import
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.EventLogger
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing // Import F1TvViewing
import fr.groggy.racecontrol.tv.ui.player.ExoPlayerPlaybackTransportControlGlue
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class ChannelPlaybackFragment : VideoSupportFragment(), Player.Listener {

    companion object {
        internal val TAG = ChannelPlaybackFragment::class.simpleName // Keep as val

        // --- CORRECTED ARGUMENT KEYS: Use 'val' and string literals ---
        private val ARG_VIEWING = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_VIEWING" // Unique key for the viewing object
        // Keep other keys if they are still needed by the Activity's Intent logic
        private val ARG_SESSION_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_SESSION_ID"
        private val ARG_CONTENT_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CONTENT_ID"
        private val ARG_CHANNEL_ID = "fr.groggy.racecontrol.tv.ui.channel.playback.ARG_CHANNEL_ID"
        // --- End Correction ---

        // --- Methods to pass data via Intent (used by Activity to start playback) ---
        // These seem necessary based on ChannelPlaybackActivity's intent() method
        fun putSessionId(intent: Intent, sessionId: String) {
            intent.putExtra(ARG_SESSION_ID, sessionId)
        }
        fun putChannelId(intent: Intent, channelId: String?) {
            intent.putExtra(ARG_CHANNEL_ID, channelId)
        }
        fun putContentId(intent: Intent, contentId: String) {
            intent.putExtra(ARG_CONTENT_ID, contentId)
        }

        // --- Methods to retrieve initial data in Activity (before viewing is fetched) ---
        // These seem necessary based on ChannelPlaybackActivity's attachViewingIfNeeded() method
        fun findChannelId(activity: Activity): String? =
            activity.intent.getStringExtra(ARG_CHANNEL_ID)

        fun findContentId(activity: Activity): String? =
            activity.intent.getStringExtra(ARG_CONTENT_ID)

        fun findSessionId(activity: Activity): String? =
            activity.intent.getStringExtra(ARG_SESSION_ID)

        // --- Method to retrieve the fully populated viewing data within the Fragment ---
        fun findViewing(fragment: ChannelPlaybackFragment): F1TvViewing? {
            // Retrieve the whole F1TvViewing object from arguments
            @Suppress("DEPRECATION") // Use getParcelable(String, Class) if minSdk allows
            return fragment.arguments?.getParcelable(ARG_VIEWING)
        }

        // --- Method to create Fragment instance ---
        // Pass the whole F1TvViewing object obtained from the service
        fun newInstance(viewing: F1TvViewing) = ChannelPlaybackFragment().apply {
            arguments = bundleOf(
                ARG_VIEWING to viewing // Put the parcelable viewing object directly
                // No need to pass individual fields like URI, tokens etc. anymore
                // Pass original IDs if needed for UI/logic unrelated to playback URL itself
                // ARG_SESSION_ID to findSessionId(context as Activity), // Maybe get from viewing if added?
                // ARG_CONTENT_ID to viewing.contentId,
                // ARG_CHANNEL_ID to viewing.channelId
            )
        }
    }

    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory

    private val trackSelector: DefaultTrackSelector by lazy {
        DefaultTrackSelector(requireContext())
    }

    private val player: ExoPlayer by lazy {
        Log.d(TAG, "Initializing ExoPlayer")
        val player = ExoPlayer.Builder(requireContext())
            .setTrackSelector(trackSelector)
            .build()
        player.playWhenReady = true
        player.addAnalyticsListener(EventLogger(trackSelector))
        player.addListener(this) // Add listener for errors and state changes
        player
    }

    // --- Lazy initialize MediaSource factory based on viewing data ---
    private val mediaSourceFactory: MediaSource.Factory by lazy {
        val viewing = findViewing(this)
        // Check the streamType string from the viewing object
        val streamTypeString = viewing?.streamType
        Log.d(TAG, "Determining MediaSource Factory for streamType: $streamTypeString")
        // Check if the actual URL ends with .mpd as a fallback
        val isDashUrl = viewing?.url?.toString()?.endsWith(".mpd", ignoreCase = true) == true
        if (streamTypeString != null && streamTypeString.contains("DASH", ignoreCase = true) || isDashUrl) {
            Log.i(TAG, "Using DashMediaSource.Factory")
            DashMediaSource.Factory(httpDataSourceFactory)
        } else {
            // Default to HLS if type is null, empty, or contains HLS (or anything else)
            Log.i(TAG, "Using HlsMediaSource.Factory (default or HLS type)")
            HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true) // Generally good for HLS VOD
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        startPlayer()
    }

    private fun startPlayer() {
        Log.d(TAG, "startPlayer called")
        try {
            val glue = ExoPlayerPlaybackTransportControlGlue(requireActivity(), player, trackSelector)
            glue.host = VideoSupportFragmentGlueHost(this)
            // glue.isSeekEnabled = true // Enable seeking if needed

            val viewing = findViewing(this)
            if (viewing == null) {
                Log.e(TAG, "Viewing data is null in startPlayer. Cannot proceed.")
                // Notify activity about the critical error
                (activity as? ChannelPlaybackActivity)?.playerError()
                return
            }
            Log.d(TAG, "Found viewing data: $viewing")
            // Prepare the player with the viewing data
            preparePlayer(viewing)
        } catch (e: Exception) {
            Log.e(TAG, "Error during startPlayer setup", e)
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
    }

    // Prepare player with the fetched viewing details
    private fun preparePlayer(viewing: F1TvViewing) {
        Log.i(TAG, "Preparing player for URL: ${viewing.url}, Type: ${viewing.streamType}")
        try {
            val mediaItem = MediaSourceItemFactory.newMediaItem(viewing)
            // Use the dynamically determined factory
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
            player.prepare()
            Log.d(TAG, "Player preparation initiated.")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating MediaSource or preparing player", e)
            // Notify activity if preparation fails
            (activity as? ChannelPlaybackActivity)?.playerError()
        }
    }

    // --- Player.Listener Implementation ---
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "ExoPlayer Error: ${error.errorCodeName} (${error.errorCode}) - ${error.message}", error)
        // Check for specific errors if needed, e.g., DRM errors error.errorCode == PlaybackException.ERROR_CODE_DRM_...
        // Notify the activity about the error
        (activity as? ChannelPlaybackActivity)?.playerError()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val stateString = when (playbackState) {
            Player.STATE_IDLE -> "IDLE" // Initial state, error, or ended
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY" // Can start playing
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN ($playbackState)"
        }
        Log.d(TAG, "Player state changed to: $stateString")
        // You might want to update UI based on state here
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.d(TAG, "Player isPlaying changed to: $isPlaying")
        // Update UI related to play/pause icon, etc.
    }
    // --- End Player.Listener ---


    override fun onPause() {
        super.onPause()
        // Pause playback when fragment is paused but not destroyed
        if (player.isPlaying) {
            player.pause()
            Log.d(TAG, "onPause: Pausing player.")
        }
    }

    override fun onResume() {
        super.onResume()
        // Optionally resume playback if it was playing before pause
        // Consider user preferences or whether it was paused due to interruption
        // if (shouldResumePlayback && !player.isPlaying) {
        //     player.play()
        //     Log.d(TAG, "onResume: Resuming player.")
        // }
    }

    // onDestroyView is called when the fragment's view is destroyed,
    // but the fragment instance might live on (e.g., back stack).
    // Good place to clean up view-related resources if needed.

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Releasing player.")
        // Ensure player resources are released when fragment is destroyed
        player.removeListener(this) // Remove listener to prevent memory leaks
        player.release()
        super.onDestroy()
    }
}
