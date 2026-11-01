package fr.groggy.racecontrol.tv.update

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the in-app updater: detects "the app was opened" (first activity started after none was),
 * checks GitHub releases (at most once per [CHECK_INTERVAL_MS]), and shows the update dialog.
 * An update that is already known is offered again on every open, also while the install
 * permission is missing, until the user picks "Don't remind me".
 */
@Singleton
class UpdateManager @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private val TAG = UpdateManager::class.simpleName
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

        private val ACCENT = Color.parseColor("#0096A6")
    }

    private var startedActivities = 0
    private var openPending = false
    private var pendingRelease: ReleaseInfo? = null
    private var lastCheckAtMs: Long? = null
    private var checking = false
    private var dialog: AlertDialog? = null

    /** Call once from Application.onCreate. */
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) openPending = true
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Call from the landing activity's onResume; acts only once per app open. */
    fun onLandingResumed(activity: FragmentActivity) {
        if (!openPending) return
        if (UpdateInstaller.installing || dialog?.isShowing == true || checking) return
        openPending = false

        settingsRepository.applySettings()
        if (!settingsRepository.getCurrent().autoUpdateCheck) return

        val now = SystemClock.elapsedRealtime()
        val last = lastCheckAtMs
        val throttled = last != null && now - last < CHECK_INTERVAL_MS
        activity.lifecycleScope.launch {
            if (!throttled) {
                try {
                    checkNetwork()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Update check failed", e)
                    // keep any previously known pending update
                }
            }
            val release = pendingRelease ?: return@launch
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                showUpdateDialog(activity, release)
            } else {
                openPending = true // activity went away meanwhile; offer on the next resume
            }
        }
    }

    /** "Check for updates now": always hits the network and reports the outcome. */
    fun checkNow(activity: FragmentActivity) {
        if (checking || UpdateInstaller.installing) return
        Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                checkNetwork()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_check_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val release = pendingRelease
            if (release == null) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                    Toast.LENGTH_LONG
                ).show()
            } else if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                showUpdateDialog(activity, release)
            }
        }
    }

    private suspend fun checkNetwork() {
        checking = true
        try {
            val release = withContext(Dispatchers.IO) { UpdateChecker.fetchNewerRelease() }
            pendingRelease = release
            lastCheckAtMs = SystemClock.elapsedRealtime()
        } finally {
            checking = false
        }
    }

    private fun showUpdateDialog(activity: FragmentActivity, release: ReleaseInfo) {
        if (dialog?.isShowing == true) return
        val built = darkBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title, release.tag))
            .setMessage(activity.getString(R.string.update_available_message, BuildConfig.VERSION_NAME, release.tag))
            .setPositiveButton(R.string.update_install_now) { _, _ -> onInstallChosen(activity, release) }
            .setNeutralButton(R.string.update_later, null)
            .setNegativeButton(R.string.update_dont_remind) { _, _ ->
                settingsRepository.updateAutoUpdateCheck(false)
                pendingRelease = null
                Toast.makeText(activity, R.string.update_dont_remind_toast, Toast.LENGTH_LONG).show()
            }
            .create()
        show(built, AlertDialog.BUTTON_POSITIVE)
    }

    private fun onInstallChosen(activity: FragmentActivity, release: ReleaseInfo) {
        if (!canInstallPackages(activity)) {
            val explain = darkBuilder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_ok) { _, _ -> openUnknownSourcesSettings(activity) }
                .setNegativeButton(R.string.update_cancel, null)
                .create()
            show(explain, AlertDialog.BUTTON_POSITIVE)
            return
        }
        downloadAndInstall(activity, release)
    }

    private fun canInstallPackages(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun openUnknownSourcesSettings(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, e.message ?: "", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAndInstall(activity: FragmentActivity, release: ReleaseInfo) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(activity).apply { setTextColor(Color.WHITE); text = release.assetName }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(label, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        lateinit var job: Job
        val progressDialog = darkBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading_title, release.tag))
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(R.string.update_cancel) { _, _ -> job.cancel() }
            .create()
        show(progressDialog, AlertDialog.BUTTON_NEGATIVE)

        job = activity.lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    UpdateInstaller.download(
                        activity.applicationContext,
                        release,
                        isCancelled = { !isActive }
                    ) { percent ->
                        activity.runOnUiThread {
                            if (percent >= 0) {
                                bar.isIndeterminate = false
                                bar.progress = percent
                                label.text = "${release.assetName}  $percent%"
                            }
                        }
                    }
                }
                progressDialog.dismiss()
                Toast.makeText(activity, R.string.update_installing, Toast.LENGTH_LONG).show()
                withContext(Dispatchers.IO) { UpdateInstaller.install(activity.applicationContext, apk) }
            } catch (e: CancellationException) {
                progressDialog.dismiss()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update download/install failed", e)
                progressDialog.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.update_download_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun darkBuilder(activity: Activity) =
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)

    /**
     * Shows [built] and makes sure D-pad users see where focus is: the buttons get a clear
     * focused state (white text on the accent colour) and [focusedButton] starts focused.
     */
    private fun show(built: AlertDialog, focusedButton: Int) {
        dialog = built
        built.setOnShowListener {
            val textColors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Color.WHITE, Color.parseColor("#B0BEC5"))
            )
            for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)) {
                built.getButton(which)?.apply {
                    isFocusable = true
                    setTextColor(textColors)
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(ACCENT))
                        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(ACCENT))
                        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                    }
                }
            }
            built.getButton(focusedButton)?.requestFocus()
        }
        built.setOnDismissListener { if (dialog === built) dialog = null }
        built.show()
    }
}
