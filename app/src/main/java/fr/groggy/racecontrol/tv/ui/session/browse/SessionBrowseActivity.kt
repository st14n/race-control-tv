package fr.groggy.racecontrol.tv.ui.session.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackActivity
import org.threeten.bp.Year

@AndroidEntryPoint
class SessionBrowseActivity : FragmentActivity() {
    companion object {
        private const val IS_LIVE_SESSION = "fr.groggy.racecontrol.tv.ui.session.browse.IS_LIVE_SESSION"

        fun intent(
            context: Context,
            sessionId: String,
            contentId: String,
            seasonYear: Int = Year.now().value
        ): Intent = intent(context, sessionId, contentId, isLiveSession = false, seasonYear = seasonYear)

        fun intent(
            context: Context,
            sessionId: String,
            contentId: String,
            isLiveSession: Boolean,
            seasonYear: Int
        ): Intent {
            val intent = Intent(context, SessionBrowseActivity::class.java)
            SessionGridFragment.putContentId(intent, contentId)
            SessionGridFragment.putSessionId(intent, sessionId)
            intent.putExtra(IS_LIVE_SESSION, isLiveSession)
            SessionGridFragment.putSeasonYear(intent, seasonYear)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_browse)

        val contentId = SessionGridFragment.findContentId(this)
            ?: return finish()
        val sessionId = SessionGridFragment.findSessionId(this)
            ?: return finish()
        val isLiveSession = intent.getBooleanExtra(IS_LIVE_SESSION, false)
        val seasonYear = SessionGridFragment.findSeasonYear(this)
        val viewModel: SessionBrowseViewModel by viewModels()

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (val session = viewModel.sessionLoaded(sessionId, contentId)) {
                    is SingleChannelSession -> {
                        val intent = ChannelPlaybackActivity.intent(
                            this@SessionBrowseActivity,
                            sessionId,
                            session.channel?.value,
                            session.contentId,
                            isLiveSession || session.isLiveSession,
                            seasonYear
                        )
                        startActivity(intent)
                        finish()
                    }
                    is MultiChannelsSession -> {
                        Log.d("SessionBrowseActivity", "Skipping channel selection and opening playback directly")
                        val intent = ChannelPlaybackActivity.intent(
                            this@SessionBrowseActivity,
                            sessionId,
                            null,
                            session.contentId,
                            isLiveSession || session.isLiveSession,
                            seasonYear
                        )
                        startActivity(intent)
                        finish()
                    }
                }
            }
        }
    }

}
