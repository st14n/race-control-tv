package fr.groggy.racecontrol.tv.ui.player

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Keep
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.f1tv.F1TvBasicChannelType
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackFragment
import fr.groggy.racecontrol.tv.ui.session.browse.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Feed picker in the player: the main feeds as tiles (the one playing marked), then the onboard
 * cameras in a grid with their team colour. A panel on the right: the picture stays visible.
 */
@Keep
@AndroidEntryPoint
class ChannelSelectionDialog : DialogFragment() {

    private val viewModel: SessionBrowseViewModel by viewModels()
    private var onDialogDismissed: (() -> Unit)? = null
    private var content: View? = null
    private var shownChannels: List<Channel>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated by the system (its callbacks are gone): close instead of showing a dead panel
        if (savedInstanceState != null) dismissAllowingStateLoss()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.feed_picker, null)
        content = view
        return Dialog(requireContext(), SIDE_PANEL_THEME).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(view)
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
        dialog?.showAsSidePanel(widthFraction = 0.5f, maxWidthDp = 620)
        val sessionId = arguments?.getString(EXTRA_SESSION_ID) ?: return
        val contentId = arguments?.getString(EXTRA_CONTENT_ID) ?: return
        lifecycleScope.launch {
            viewModel.session(sessionId, contentId).collect { session ->
                if (session is MultiChannelsSession && session.channels != shownChannels) {
                    shownChannels = session.channels
                    bind(session.channels)
                }
            }
        }
    }

    private fun bind(channels: List<Channel>) {
        val root = content ?: return
        val activity = activity ?: return
        val inflater = LayoutInflater.from(root.context)
        // The feed that plays now: the requested channel id (none = International)
        val playingId = ChannelPlaybackFragment.findChannelId(activity)
        val main = root.findViewById<LinearLayout>(R.id.feed_picker_main)
        val grid = root.findViewById<GridLayout>(R.id.feed_picker_onboard)
        main.removeAllViews()
        grid.removeAllViews()
        root.findViewById<View>(R.id.feed_picker_loading).visibility = View.GONE

        var focusTarget: View? = null
        var firstTile: View? = null
        channels.filterIsInstance<BasicChannel>().forEach { channel ->
            val tile = inflater.inflate(R.layout.feed_tile, main, false)
            tile.findViewById<TextView>(R.id.feed_tile_name).text = basicName(channel.type)
            tile.findViewById<TextView>(R.id.feed_tile_detail).text = basicDetail(channel.type)
            val playing = channel.id?.value == playingId
            tile.findViewById<View>(R.id.feed_tile_now).visibility = if (playing) View.VISIBLE else View.GONE
            tile.setOnClickListener { pick(channel) }
            main.addView(tile)
            if (firstTile == null) firstTile = tile
            if (playing) focusTarget = tile
        }

        val onboard = channels.filterIsInstance<OnboardChannel>()
        root.findViewById<View>(R.id.feed_picker_onboard_header).visibility =
            if (onboard.isEmpty()) View.GONE else View.VISIBLE
        val gap = (10 * root.resources.displayMetrics.density).toInt()
        onboard.forEachIndexed { index, channel ->
            val tile = inflater.inflate(R.layout.onboard_tile, grid, false)
            tile.findViewById<TextView>(R.id.onboard_name).text = channel.name
            tile.findViewById<TextView>(R.id.onboard_detail).text = listOfNotNull(
                channel.driver?.racingNumber?.let { "#$it" },
                channel.subTitle
            ).joinToString(" · ")
            val teamColor = channel.background?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            tile.findViewById<View>(R.id.onboard_team_bar).background?.mutate()?.setTint(teamColor ?: Color.GRAY)
            tile.setOnClickListener { pick(channel) }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / COLUMNS),
                GridLayout.spec(index % COLUMNS, 1f)
            ).apply {
                width = 0
                setMargins(0, 0, if (index % COLUMNS == COLUMNS - 1) 0 else gap, gap)
            }
            grid.addView(tile, params)
            if (channel.id.value == playingId) focusTarget = tile
        }
        (focusTarget ?: firstTile ?: grid.getChildAt(0))?.let { target -> target.post { target.requestFocus() } }
    }

    private fun pick(channel: Channel) {
        (activity as? ChannelManagerListener)?.onSwitchChannel(channel)
        dismiss()
    }

    private fun basicName(type: F1TvBasicChannelType): String = when (type) {
        F1TvBasicChannelType.F1Live -> "F1 Live"
        F1TvBasicChannelType.Wif -> "International"
        F1TvBasicChannelType.PitLane -> "Pit Lane"
        F1TvBasicChannelType.Tracker -> "Tracker"
        F1TvBasicChannelType.Data -> "Data"
        is F1TvBasicChannelType.Unknown -> type.name
    }

    private fun basicDetail(type: F1TvBasicChannelType): String = when (type) {
        F1TvBasicChannelType.F1Live -> getString(R.string.feed_detail_f1live)
        F1TvBasicChannelType.Wif -> getString(R.string.feed_detail_international)
        F1TvBasicChannelType.PitLane -> getString(R.string.feed_detail_pitlane)
        F1TvBasicChannelType.Tracker -> getString(R.string.feed_detail_tracker)
        F1TvBasicChannelType.Data -> getString(R.string.feed_detail_data)
        is F1TvBasicChannelType.Unknown -> ""
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
        private const val COLUMNS = 3

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
