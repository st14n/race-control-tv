package fr.groggy.racecontrol.tv.ui.player

import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.media.PlaybackTransportControlGlue
import androidx.leanback.widget.*
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackActivity
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackFragment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ExoPlayerPlaybackTransportControlGlue(
    private val activity: FragmentActivity,
    private val exoPlayer: ExoPlayer,
    private val trackSelector: DefaultTrackSelector,
    private val onGrandPrixRadioRequested: (() -> Unit)? = null,
    private val onCustomRadioSyncRequested: (() -> Unit)? = null,
    private val onAudioTrackSelected: (() -> Unit)? = null,
    private val isCustomRadioActive: (() -> Boolean)? = null,
    private val currentAudioLabelOverride: (() -> String?)? = null
) : PlaybackTransportControlGlue<LeanbackPlayerAdapter>(
    activity,
    LeanbackPlayerAdapter(activity, exoPlayer, 1_000)
), AnalyticsListener {

    companion object {
        private val TAG = ExoPlayerPlaybackTransportControlGlue::class.simpleName
        private const val DEFAULT_SEEK_OFFSET = 15_000L
    }

    private val rewindAction = PlaybackControlsRow.RewindAction(activity)
    private val fastFormatAction = PlaybackControlsRow.FastForwardAction(activity)
    private val selectAudioAction = Action(
        Action.NO_ID,
        activity.getString(R.string.audio_selection_dialog_title),
        null,
        ContextCompat.getDrawable(context, R.drawable.lb_ic_search_mic_out)
    )
    private val customRadioSyncAction = Action(
        Action.NO_ID,
        activity.getString(R.string.custom_radio_sync_action),
        null,
        ContextCompat.getDrawable(context, R.drawable.ic_settings)
    )
    private val switchChannelAction = Action(
        Action.NO_ID,
        activity.getString(R.string.channel_selection_switch_channel),
        null,
        ContextCompat.getDrawable(context, R.drawable.ic_switch_channel)
    )
    private val resolutionSelectionAction = Action(
        Action.NO_ID,
        activity.getText(R.string.video_selection_dialog_title),
        null,
        ContextCompat.getDrawable(context, R.drawable.ic_video_settings)
    )
    private val closedCaptionAction = PlaybackControlsRow.ClosedCaptioningAction(activity)

    private val closedCaptionsTextView: TextView by lazy {
        activity.findViewById(R.id.closed_captions)
    }

    private var currentVideoFormat: Format? = null
    private var currentAudioFormat: Format? = null

    init {
        exoPlayer.addAnalyticsListener(this)
        isSeekEnabled = true
        isControlsOverlayAutoHideEnabled = true

        // Cue events moved out of AnalyticsListener in Media3 — handle via Player.Listener
        exoPlayer.addListener(object : Player.Listener {
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (closedCaptionAction.index == PlaybackControlsRow.ClosedCaptioningAction.INDEX_ON) {
                    closedCaptionsTextView.text =
                        cueGroup.cues.joinToString(" ") { it.text ?: "" }
                }
            }
        })
    }

    override fun onCreatePrimaryActions(adapter: ArrayObjectAdapter) {
        Log.d(TAG, "onCreatePrimaryActions")
        adapter.apply {
            super.onCreatePrimaryActions(this)
            add(rewindAction)
            add(fastFormatAction)
            add(selectAudioAction)
            add(customRadioSyncAction)
            add(switchChannelAction)
            add(resolutionSelectionAction)
            add(closedCaptionAction)
        }
    }

    override fun onActionClicked(action: Action) {
        Log.d(TAG, "onActionClicked")
        when (action) {
            rewindAction -> playerAdapter.seekOffset(-DEFAULT_SEEK_OFFSET)
            fastFormatAction -> playerAdapter.seekOffset(DEFAULT_SEEK_OFFSET)
            selectAudioAction -> openAudioSelectionDialog()
            customRadioSyncAction -> onCustomRadioSyncRequested?.invoke()
            closedCaptionAction -> toggleClosedCaptions()
            resolutionSelectionAction -> openResolutionSelectionDialog()
            switchChannelAction -> openChannelSwitchDialog()
            else -> super.onActionClicked(action)
        }
    }

    private fun openChannelSwitchDialog() {
        val sessionId = ChannelPlaybackFragment.findSessionId(activity) ?: return
        val contentId = ChannelPlaybackFragment.findContentId(activity) ?: return
        ChannelSelectionDialog.newInstance(sessionId, contentId)
            .show(activity.supportFragmentManager)
    }

    private fun openResolutionSelectionDialog() {
        ResolutionSelectionDialog(exoPlayer.currentTracks)
            .setResolutionSelectedListener { width, height ->
                trackSelector.setParameters(
                    trackSelector.buildUponParameters().setMaxVideoSize(width, height)
                )
            }.show(activity.supportFragmentManager, null)
    }

    private fun toggleClosedCaptions() {
        if (closedCaptionAction.index == PlaybackControlsRow.ClosedCaptioningAction.INDEX_OFF) {
            closedCaptionAction.index = PlaybackControlsRow.ClosedCaptioningAction.INDEX_ON
            closedCaptionsTextView.visibility = View.VISIBLE
        } else {
            closedCaptionAction.index = PlaybackControlsRow.ClosedCaptioningAction.INDEX_OFF
            closedCaptionsTextView.visibility = View.GONE
        }
    }

    private fun openAudioSelectionDialog() {
        val audioGroups = exoPlayer.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
        val dialog = AudioSelectionDialogFragment(audioGroups)
        dialog.onAudioLanguageSelected { language ->
            onAudioTrackSelected?.invoke()
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setPreferredAudioLanguage(language)
                    .setPreferredTextLanguage(language)
            )
            updateSubtitle()
        }
        dialog.onGrandPrixRadioSelected {
            onGrandPrixRadioRequested?.invoke()
            updateSubtitle()
        }
        dialog.show(activity.supportFragmentManager, null)
    }

    // ── AnalyticsListener overrides ──────────────────────────────────────────

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long
    ) {
        Log.i(TAG, "onRenderedFirstFrame: output=$output renderTimeMs=${renderTimeMs}ms")
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: androidx.media3.common.PlaybackException
    ) {
        super.onPlayerError(eventTime, error)
        Log.e(TAG, "Player error: ${error.errorCodeName} (${error.errorCode})", error)
        (activity as? ChannelPlaybackActivity)
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        Log.d(TAG, "onTracksChanged")
        val selectedAudioGroup = tracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_AUDIO && it.isSelected
        }
        selectedAudioGroup?.let { group ->
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    currentAudioFormat = group.getTrackFormat(i)
                    break
                }
            }
            updateSubtitle()
        }
    }

    override fun onDownstreamFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        mediaLoadData: MediaLoadData
    ) {
        Log.d(TAG, "onDownstreamFormatChanged")
        val trackFormat = mediaLoadData.trackFormat ?: return
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
        if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
            currentVideoFormat = trackFormat
            updateSubtitle()
        }
    }

    private fun updateSubtitle() {
        val videoQuality = currentVideoFormat?.let {
            context.getString(R.string.video_quality, it.height, it.frameRate.roundToInt())
        }
        val audioLanguage = currentAudioLabelOverride?.invoke()
            ?: currentAudioFormat?.label
            ?: currentAudioFormat?.language?.uppercase()
        subtitle = listOfNotNull(videoQuality, audioLanguage).joinToString(separator = " / ")
    }

    fun refreshSubtitle() {
        updateSubtitle()
    }

    private fun LeanbackPlayerAdapter.seekOffset(offset: Long) {
        val position = max(min(currentPosition + offset, duration), 0)
        seekTo(position)
    }
}
