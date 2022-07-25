package fr.groggy.racecontrol.tv.ui.player

import android.os.Bundle
import androidx.annotation.Keep
import androidx.fragment.app.viewModels
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.ui.channel.ChannelListPresenter
import fr.groggy.racecontrol.tv.ui.session.browse.*
import kotlinx.coroutines.flow.collect

@Keep
@AndroidEntryPoint
class ChannelSelectionInnerFragment: VerticalGridSupportFragment(), OnItemViewClickedListener {
    private val channelsAdapter = ArrayObjectAdapter(ChannelListPresenter())
    private var onChannelSelected: ((Channel) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupUIElements()
        setupEventListeners()

        listChannels()
    }

    private fun listChannels() {
        val sessionId = parentFragment?.arguments?.getString(ChannelSelectionDialog.EXTRA_SESSION_ID) ?: return
        val contentId = parentFragment?.arguments?.getString(ChannelSelectionDialog.EXTRA_CONTENT_ID) ?: return
        val viewModel: SessionBrowseViewModel by viewModels()
        lifecycleScope.launchWhenCreated {
            viewModel.session(sessionId, contentId).collect(::onUpdatedSession)
        }
    }

    private fun onUpdatedSession(session: Session) {
        when (session) {
            is SingleChannelSession -> {
                //Not supported yet
            }
            is MultiChannelsSession -> {
                channelsAdapter.setItems(session.channels, Channel.diffCallback)
            }
        }
    }

    private fun setupUIElements() {
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
            numberOfColumns = 1
            shadowEnabled = false
        }
        adapter = channelsAdapter
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = this
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?
    ) {
        val channel = item as Channel
        onChannelSelected?.invoke(channel)
    }

    companion object {
        fun newInstance(channelSelectionListener: (Channel) -> Unit): ChannelSelectionInnerFragment {
            return ChannelSelectionInnerFragment().apply {
                onChannelSelected = channelSelectionListener
            }
        }
    }
}