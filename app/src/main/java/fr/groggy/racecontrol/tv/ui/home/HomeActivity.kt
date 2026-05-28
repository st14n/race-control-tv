package fr.groggy.racecontrol.tv.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentContainerView
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.credentials.CredentialsService
import fr.groggy.racecontrol.tv.core.season.SeasonService
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.ui.season.browse.SeasonBrowseActivity
import fr.groggy.racecontrol.tv.ui.settings.SettingsActivity
import fr.groggy.racecontrol.tv.ui.signin.SignInActivity
import fr.groggy.racecontrol.tv.utils.coroutines.schedule
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.threeten.bp.Duration
import org.threeten.bp.Year
import javax.inject.Inject

@AndroidEntryPoint
class HomeActivity : FragmentActivity(R.layout.activity_home) {
    companion object {
        private val TAG = HomeActivity::class.simpleName

        fun intent(context: Context) = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    }

    @Inject internal lateinit var seasonService: SeasonService
    @Inject internal lateinit var credentialsService: CredentialsService
    private var teaserImage: ImageView? = null
    private var teaserContainer: View? = null
    private var settingsButton: View? = null
    private var fragmentContainer: FragmentContainerView? = null
    private var homeHeaderOffsetPx: Int = 0
    private var homeFeedScrollView: RecyclerView? = null
    private val isTouchDevice: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    private val homeFeedScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!isTouchDevice || dy == 0) return
            val maxOffset = maxHomeHeaderOffsetPx()
            if (maxOffset <= 0) return
            applyHomeHeaderOffset((homeHeaderOffsetPx + dy).coerceIn(0, maxOffset))
        }
    }

    private var syncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentYear = Year.now().value
        teaserImage = findViewById(R.id.teaserImage)
        teaserContainer = findViewById(R.id.teaserContainer)
        fragmentContainer = findViewById(R.id.fragment_container)
        teaserImage?.setOnClickListener {
            val activity = SeasonBrowseActivity.intent(this, Archive(currentYear))
            startActivity(activity)
        }

        val teaserImageText = findViewById<TextView>(R.id.teaserImageText)
        teaserImageText.text = resources.getString(R.string.teaser_image_text, currentYear)

        settingsButton = findViewById(R.id.settings)
        settingsButton?.let(::applySettingsButtonInsets)
        settingsButton?.setOnClickListener {
            startActivity(SettingsActivity.intent(this))
        }

        ensureHomeFragmentPresent()

    }

    override fun onStart() {
        Log.d(TAG, "onStart")
        super.onStart()

        syncJob = lifecycleScope.launch {
            schedule(Duration.ofMinutes(1)) {
                Log.d("Fetching new data", "Lifecycle state is ${lifecycle.currentState}")
                try {
                    seasonService.loadSeason(Archive(Year.now().value))
                } catch (_: Exception) {
                    /*
                     * If for whatever reason this doesn't load, just give up
                     * user can retry at next screen
                     */
                }

                ensureHomeFragmentPresent()
            }
        }
    }

    private fun ensureHomeFragmentPresent() {
        if (supportFragmentManager.findFragmentByTag(TAG) !is HomeFragment) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, HomeFragment(), TAG)
            }
        }
        supportFragmentManager.executePendingTransactions()
        bindTouchHomeFeedScrollIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // If the app is brought back from the background after the token refresh interval has
        // elapsed, MainActivity is already finished so the relogin check never runs.
        // Re-check here so a silent re-auth is triggered without requiring a force-close.
        if (credentialsService.shouldReLogin()) {
            startActivity(SignInActivity.intentSilentReAuth(this))
        }
    }

    override fun onPause() {
        syncJob?.cancel()
        syncJob = null
        super.onPause()
    }

    private fun applySettingsButtonInsets(settingsButton: View) {
        val baseTopMargin = (settingsButton.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(settingsButton) { view, insets ->
            val statusBarTopInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = baseTopMargin + statusBarTopInset
            }
            insets
        }
        ViewCompat.requestApplyInsets(settingsButton)
    }

    private fun bindTouchHomeFeedScrollIfNeeded() {
        if (!isTouchDevice) return
        val container = fragmentContainer ?: return
        val gridView = findVerticalGridView(container) ?: return
        if (homeFeedScrollView === gridView) return
        homeFeedScrollView?.removeOnScrollListener(homeFeedScrollListener)
        homeFeedScrollView = gridView
        gridView.addOnScrollListener(homeFeedScrollListener)
    }

    private fun applyHomeHeaderOffset(offsetPx: Int) {
        homeHeaderOffsetPx = offsetPx
        val translationY = -offsetPx.toFloat()
        settingsButton?.translationY = translationY
        teaserContainer?.translationY = translationY
        fragmentContainer?.translationY = translationY
    }

    private fun maxHomeHeaderOffsetPx(): Int {
        return fragmentContainer?.top ?: 0
    }

    private fun findVerticalGridView(view: View): VerticalGridView? {
        if (view is VerticalGridView) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val found = findVerticalGridView(group.getChildAt(index))
            if (found != null) return found
        }
        return null
    }
}