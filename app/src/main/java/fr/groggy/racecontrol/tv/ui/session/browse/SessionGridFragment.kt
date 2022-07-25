package fr.groggy.racecontrol.tv.ui.session.browse

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.annotation.Keep
import androidx.fragment.app.viewModels
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.ui.channel.ChannelCardPresenter
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackActivity
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class SessionGridFragment : VerticalGridSupportFragment(), OnItemViewClickedListener {

    companion object {
        private const val COLUMNS = 5

        private val CONTENT_ID = "${SessionGridFragment::class}.CONTENT_ID"
        private val SESSION_ID = "${SessionGridFragment::class}.SESSION_ID"

        fun putContentId(intent: Intent, contentId: String) {
            intent.putExtra(CONTENT_ID, contentId)
        }

        fun putSessionId(intent: Intent, sessionId: String) {
            intent.putExtra(SESSION_ID, sessionId)
        }

        fun findSessionId(activity: Activity): String? {
            return activity.intent.getStringExtra(SESSION_ID)
        }

        fun findContentId(activity: Activity): String? {
            return activity.intent.getStringExtra(CONTENT_ID)
        }
    }

    @Inject internal lateinit var settingsRepository: SettingsRepository

    private val channelsAdapter = ArrayObjectAdapter(ChannelCardPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUIElements()
        setupEventListeners()

        val sessionId = findSessionId(requireActivity()) ?: return requireActivity().finish()
        val contentId = findContentId(requireActivity()) ?: return requireActivity().finish()
        val viewModel: SessionBrowseViewModel by viewModels({ requireActivity() })
        lifecycleScope.launchWhenCreated {
            viewModel.session(sessionId, contentId).collect(::onUpdatedSession)
        }
    }

    private fun setupUIElements() {
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
            numberOfColumns = COLUMNS
            shadowEnabled = false
        }
        adapter = channelsAdapter
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = this
    }

    private fun onUpdatedSession(session: Session) {
        when (session) {
            is SingleChannelSession -> {
                goToPlayback(session.contentId, session.channel?.value)
                activity?.finish()
            }
            is MultiChannelsSession -> {
                title = session.name
                channelsAdapter.setItems(session.channels, Channel.diffCallback)

                if (settingsRepository.getCurrent().bypassChannelSelection) {
                    goToPlayback(session.contentId, channelId = null)
                }
            }
        }
    }

    private fun goToPlayback(
        contentId: String,
        channelId: String?
    ) {
        val sessionId = findSessionId(requireActivity()) ?: return requireActivity().finish()

        val intent = ChannelPlaybackActivity.intent(
            requireActivity(),
            sessionId,
            channelId,
            contentId
        )
        startActivity(intent)
    }

    override fun onItemClicked(itemViewHolder: Presenter.ViewHolder?, item: Any, rowViewHolder: RowPresenter.ViewHolder?, row: Row?) {
        val sessionId = findSessionId(requireActivity()) ?: return requireActivity().finish()

        val channel = item as Channel
        val intent = ChannelPlaybackActivity.intent(requireActivity(), sessionId, channel.id?.value, channel.contentId)
        startActivity(intent)
    }

}
