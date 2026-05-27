package fr.groggy.racecontrol.tv.ui.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import androidx.annotation.Keep
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.f1tv.F1TvBasicChannelType
import fr.groggy.racecontrol.tv.ui.session.browse.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Keep
@AndroidEntryPoint
class ChannelSelectionDialog : DialogFragment() {

    private val viewModel: SessionBrowseViewModel by viewModels()
    private var channels = emptyList<Channel>()
    private var listAdapter: ArrayAdapter<String>? = null
    private var onDialogDismissed: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        listAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.channel_selection_switch_channel)
            .setAdapter(listAdapter) { _, i ->
                val channel = channels.getOrNull(i) ?: return@setAdapter
                (activity as? ChannelManagerListener)?.onSwitchChannel(channel)
                dismiss()
            }
            .create().apply {
                setOnShowListener {
                    listView?.post {
                        val list = listView ?: return@post
                        if (list.count > 0) {
                            list.setSelection(0)
                            (list.getChildAt(0) ?: list).requestFocus()
                        } else {
                            list.requestFocus()
                        }
                    }
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
                    ) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
            }
    }

    override fun onStart() {
        super.onStart()
        val sessionId = arguments?.getString(EXTRA_SESSION_ID) ?: return
        val contentId = arguments?.getString(EXTRA_CONTENT_ID) ?: return
        lifecycleScope.launch {
            viewModel.session(sessionId, contentId).collect { session ->
                if (session is MultiChannelsSession) {
                    channels = session.channels
                    listAdapter?.run {
                        clear()
                        addAll(session.channels.map { channelDisplayName(it) })
                    }
                    (dialog as? AlertDialog)?.listView?.let { list ->
                        if (list.count > 0) {
                            list.post {
                                list.setSelection(0)
                                (list.getChildAt(0) ?: list).requestFocus()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun channelDisplayName(channel: Channel): String = when (channel) {
        is BasicChannel -> when (channel.type) {
            F1TvBasicChannelType.F1Live  -> "F1 Live"
            F1TvBasicChannelType.Wif     -> "International"
            F1TvBasicChannelType.PitLane -> "Pit Lane"
            F1TvBasicChannelType.Tracker -> "Tracker"
            F1TvBasicChannelType.Data    -> "Data"
            is F1TvBasicChannelType.Unknown -> channel.type.name
        }
        is OnboardChannel -> buildString {
            channel.driver?.racingNumber?.let { append("$it — ") }
            append(channel.name)
        }
    }

    fun show(fragmentManager: FragmentManager) {
        show(fragmentManager, TAG)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDialogDismissed?.invoke()
    }

    fun onDialogDismissed(listener: () -> Unit): ChannelSelectionDialog {
        onDialogDismissed = listener
        return this
    }

    interface ChannelManagerListener {
        fun onSwitchChannel(channel: Channel)
    }

    companion object {
        internal const val EXTRA_SESSION_ID = "extras.sessionId"
        internal const val EXTRA_CONTENT_ID = "extras.contentId"
        private const val TAG = "ChannelSelectionDialog"

        fun newInstance(sessionId: String, contentId: String): ChannelSelectionDialog {
            return ChannelSelectionDialog().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_SESSION_ID, sessionId)
                    putString(EXTRA_CONTENT_ID, contentId)
                }
            }
        }
    }
}
