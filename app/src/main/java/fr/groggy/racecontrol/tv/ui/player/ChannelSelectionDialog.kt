package fr.groggy.racecontrol.tv.ui.player

import android.os.Bundle
import android.view.View
import androidx.annotation.Keep
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.ui.session.browse.Channel

@Keep
@AndroidEntryPoint
class ChannelSelectionDialog: DialogFragment(R.layout.fragment_channel_selection) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        childFragmentManager.commit {
            replace(R.id.inner_channel_selection, ChannelSelectionInnerFragment.newInstance {
                val listener = activity as? ChannelManagerListener
                listener?.onSwitchChannel(it)

                dismiss()
            })
        }
    }

    fun show(fragmentManager: FragmentManager) {
        show(fragmentManager, TAG)
    }

    interface ChannelManagerListener {
        fun onSwitchChannel(channel: Channel)
    }

    companion object {
        internal const val EXTRA_SESSION_ID = "extras.sessionId"
        internal const val EXTRA_CONTENT_ID = "extras.contentId"

        private const val TAG = "ChannelSelectionDialog"

        fun newInstance(
            sessionId: String,
            contentId: String
        ): ChannelSelectionDialog {
            return ChannelSelectionDialog().apply {
                arguments = bundleOf(
                    EXTRA_SESSION_ID to sessionId,
                    EXTRA_CONTENT_ID to contentId
                )
            }
        }
    }
}