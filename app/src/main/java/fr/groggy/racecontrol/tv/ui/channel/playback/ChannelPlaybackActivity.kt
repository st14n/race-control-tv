package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log // Import Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog // Ensure AppCompat AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.ViewingService
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.ui.player.ChannelSelectionDialog
import fr.groggy.racecontrol.tv.ui.session.browse.Channel
import fr.groggy.racecontrol.tv.ui.signin.SignInActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChannelPlaybackActivity : FragmentActivity(R.layout.activity_channel_playback),
    ChannelSelectionDialog.ChannelManagerListener {

    @Inject internal lateinit var viewingService: ViewingService
    @Inject internal lateinit var settingsRepository: SettingsRepository
    // Injections kept for potential future use
    // @Inject internal lateinit var okHttpClient: OkHttpClient
    // @Inject internal lateinit var moshi: Moshi

    companion object {
        private val TAG = ChannelPlaybackActivity::class.simpleName

        fun intent(context: Context, sessionId: String, channelId: String?, contentId: String): Intent {
            val intent = Intent(context, ChannelPlaybackActivity::class.java)
            // Pass IDs needed to *fetch* viewing details initially
            ChannelPlaybackFragment.putChannelId(intent, channelId)
            ChannelPlaybackFragment.putContentId(intent, contentId)
            ChannelPlaybackFragment.putSessionId(intent, sessionId)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launchWhenCreated {
            // We request HLS but the F1Client/API might return DASH now
            attachViewingIfNeeded(Settings.StreamType.HLS)
        }
    }

    override fun onSwitchChannel(channel: Channel) {
        val sessionId = ChannelPlaybackFragment.findSessionId(this) ?: return
        startActivity(intent(this, sessionId, channel.id?.value, channel.contentId))
        finish()
    }

    private suspend fun attachViewingIfNeeded(streamType: Settings.StreamType) {
        // Check if fragment exists *before* fetching data to avoid duplicates on config change/retry
        if (supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG) == null) {
            val contentId = ChannelPlaybackFragment.findContentId(this) ?: return finish()
            val channelId = ChannelPlaybackFragment.findChannelId(this)
            Log.d(TAG, "Attempting to get viewing for contentId: $contentId, channelId: $channelId")
            try {
                // Fetch viewing details (hoping for direct playable URL)
                val viewing = viewingService.getViewing(channelId, contentId, streamType)
                Log.i(TAG, "Viewing details received: URL=${viewing.url}, Type=${viewing.streamType}")

                // Proceed with the received viewing object
                onViewingCreated(viewing) // Pass the whole viewing object

            } catch (e: ViewingService.TokenExpiredException) {
                Log.e(TAG, "Token expired while getting viewing", e)
                handleError(R.string.unable_to_play_video_session_expired) {
                    startActivity(SignInActivity.intentClearTask(this))
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting viewing details", e)
                handleError(R.string.unable_to_play_video_message, ::finish)
            }
        } else {
            Log.d(TAG, "Playback fragment already exists, skipping viewing fetch.")
        }
    }

    // Removed streamType parameter, accept the full viewing object
    private fun onViewingCreated(viewing: F1TvViewing) {
        Log.d(TAG, "Proceeding to create player with viewing: $viewing")
        if (settingsRepository.getCurrent().openWithExternalPlayer) {
            Log.d(TAG, "Opening with external player.")
            openWithExternalPlayer(viewing)
        } else {
            Log.d(TAG, "Opening with internal player.")
            openWithInternalPlayer(viewing) // Pass viewing object
        }
    }

    private fun openWithExternalPlayer(viewing: F1TvViewing) {
        supportFragmentManager.commit {
            replace(R.id.fragment_container, OpenedWithExternalPlayerFragment(), ChannelPlaybackFragment.TAG)
            setReorderingAllowed(true)
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(viewing.url, "video/*") // Use the final URL
            Log.i(TAG, "Starting external player intent for URL: ${viewing.url}")
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No external player found.", e)
            handleError(R.string.unable_to_open_with_external_player, ::finish)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening external player", e)
            handleError(R.string.unable_to_open_with_external_player, ::finish)
        }
    }

    // Removed streamType parameter
    private fun openWithInternalPlayer(viewing: F1TvViewing) {
        Log.d(TAG, "Committing internal player fragment.")
        supportFragmentManager.commit {
            // Pass the whole viewing object to newInstance
            replace(R.id.fragment_container, ChannelPlaybackFragment.newInstance(viewing), ChannelPlaybackFragment.TAG)
            setReorderingAllowed(true)
        }
    }

    private fun handleError(@StringRes errorMessage: Int, cancelAction: () -> Unit) {
        // Ensure this runs on the main thread
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                try {
                    // Use AppCompat AlertDialog
                    AlertDialog.Builder(this)
                        .setMessage(errorMessage)
                        .setPositiveButton(android.R.string.ok) { dialog, _ ->
                            dialog.dismiss()
                            cancelAction.invoke()
                        }
                        .setCancelable(false) // Prevent dismissal on back press during error
                        .show()
                } catch (e: Exception) {
                    // Catch potential exceptions during dialog show (like theme issues)
                    Log.e(TAG, "Error showing AlertDialog", e)
                    // Fallback or just finish
                    cancelAction.invoke()
                }
            } else {
                Log.w(TAG, "Activity finishing, not showing error dialog.")
            }
        }
    }

    // Method called by fragment on player error
    fun playerError() {
        Log.e(TAG, "Player reported an error. Finishing activity.")
        // Simple strategy: Show error and finish. Retrying might loop if the URL is bad.
        handleError(R.string.unable_to_play_video_message, ::finish)

        // --- Alternative Retry Logic (use with caution) ---
        /*
        Log.e(TAG, "Player reported an error. Attempting to remove fragment and retry.")
        val fragment = supportFragmentManager.findFragmentByTag(ChannelPlaybackFragment.TAG)
        if (fragment != null && !isFinishing && !isDestroyed) {
            supportFragmentManager.commit {
                remove(fragment)
                runOnCommit { // Ensure fragment is removed before trying again
                    lifecycleScope.launch {
                        Log.d(TAG, "Retrying attachViewingIfNeeded after player error.")
                        // Delay slightly before retry?
                        // kotlinx.coroutines.delay(1000)
                        attachViewingIfNeeded(Settings.StreamType.HLS) // Or try DASH?
                    }
                }
            }
        } else if (fragment == null && !isFinishing && !isDestroyed) {
            // Fragment already gone, maybe retry directly?
            lifecycleScope.launch {
                 Log.d(TAG, "Retrying attachViewingIfNeeded after player error (no fragment found).")
                attachViewingIfNeeded(Settings.StreamType.HLS)
            }
        } else {
             Log.w(TAG, "Not retrying player error: fragment=$fragment, isFinishing=$isFinishing, isDestroyed=$isDestroyed")
             // If retry logic fails, ensure we still show error/finish
             handleError(R.string.unable_to_play_video_message, ::finish)
        }
        */
    }
}
