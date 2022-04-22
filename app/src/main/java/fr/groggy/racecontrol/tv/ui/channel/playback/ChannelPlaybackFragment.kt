package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.Keep
import androidx.core.os.bundleOf
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.util.EventLogger
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.ui.player.ExoPlayerPlaybackTransportControlGlue
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class ChannelPlaybackFragment : VideoSupportFragment() {

    companion object {
        internal val TAG = ChannelPlaybackFragment::class.simpleName

        private val CHANNEL_ID = "${ChannelPlaybackFragment::class}.CHANNEL_ID"
        private val CONTENT_ID = "${ChannelPlaybackFragment::class}.CONTENT_ID"
        private val VIEWING_URI = "${ChannelPlaybackFragment::class}.VIEWING"
        private val VIEWING_TYPE = "${ChannelPlaybackFragment::class}.VIEWING_TYPE"

        fun putChannelId(intent: Intent, channelId: String?) {
            intent.putExtra(CHANNEL_ID, channelId)
        }

        fun putContentId(intent: Intent, contentId: String) {
            intent.putExtra(CONTENT_ID, contentId)
        }

        fun findChannelId(activity: Activity): String? =
            activity.intent.getStringExtra(CHANNEL_ID)

        fun findContentId(activity: Activity): String? {
            return activity.intent.getStringExtra(CONTENT_ID)
        }

        fun findViewing(fragment: ChannelPlaybackFragment): F1TvViewing? {
            val uri = fragment.arguments?.getParcelable<Uri>(VIEWING_URI) ?: return null
            val contentId = findContentId(fragment.requireActivity()) ?: return null
            val channelId = findChannelId(fragment.requireActivity())

            return F1TvViewing(uri, contentId, channelId)
        }

        fun newInstance(viewing: F1TvViewing, viewingType: Settings.StreamType) = ChannelPlaybackFragment().apply {
            arguments = bundleOf(
                VIEWING_URI to viewing.url,
                VIEWING_TYPE to viewingType
            )
        }
    }

    @Inject internal lateinit var httpDataSourceFactory: HttpDataSource.Factory
    @Inject internal lateinit var settingsRepository: SettingsRepository

    private val trackSelector: DefaultTrackSelector by lazy {
        DefaultTrackSelector(requireContext())
    }

    private val player: ExoPlayer by lazy {
        val player = ExoPlayer.Builder(requireContext())
            .setTrackSelector(trackSelector)
            .build()
        player.playWhenReady = true
        player.addAnalyticsListener(EventLogger(trackSelector))
        player
    }

    private val mediaSourceFactory: MediaSource.Factory by lazy {
        if (arguments?.getSerializable(VIEWING_TYPE) == Settings.StreamType.HLS) {
            HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
        } else {
            DashMediaSource.Factory(httpDataSourceFactory)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startPlayer()
    }

    private fun startPlayer() {
        val glue = ExoPlayerPlaybackTransportControlGlue(requireActivity(), player, trackSelector)
        glue.host = VideoSupportFragmentGlueHost(this)

        val viewing = findViewing(this) ?: return requireActivity().finish()
        onViewingCreated(viewing)
    }

    private fun onViewingCreated(viewing: F1TvViewing) {
        Log.d(TAG, "Viewing is ready $viewing")
        if (!player.isPlaying) { //IF is playing already just ignore these calls
            val mediaSource = mediaSourceFactory
                .createMediaSource(MediaSourceItemFactory.newMediaItem(viewing))
            player.setMediaSource(mediaSource)
            player.prepare()
        }
    }

    override fun onStop() {
        super.onStop()
        player.playWhenReady = false
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
