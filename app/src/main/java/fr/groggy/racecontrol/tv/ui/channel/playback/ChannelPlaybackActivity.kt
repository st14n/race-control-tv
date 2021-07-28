package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R

@AndroidEntryPoint
class ChannelPlaybackActivity : FragmentActivity(R.layout.activity_channel_playback) {
    companion object {
        fun intent(context: Context, channelId: String?, contentId: String): Intent {
            val intent = Intent(context, ChannelPlaybackActivity::class.java)
            ChannelPlaybackFragment.putChannelId(
                intent,
                channelId
            )
            ChannelPlaybackFragment.putContentId(
                intent,
                contentId
            )
            return intent
        }
    }
}
