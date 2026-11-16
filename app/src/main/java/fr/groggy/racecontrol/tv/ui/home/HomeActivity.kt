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
import fr.groggy.racecontrol.tv.core.series.Series
import fr.groggy.racecontrol.tv.core.series.SeriesFilter
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.update.UpdateManager
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
    @Inject internal lateinit var updateManager: UpdateManager
    @Inject internal lateinit var seriesFilter: SeriesFilter
    private val seriesButtons = mutableMapOf<Series, TextView>()
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
    private var createdInYear = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentYear = Year.now().value
        createdInYear = currentYear
        teaserContainer = findViewById(R.id.teaserContainer)
        fragmentContainer = findViewById(R.id.fragment_container)
        buildSeriesButtons(findViewById(R.id.seriesButtons), currentYear)

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
        if (Year.now().value != createdInYear) {
            // Left open over New Year: the screen is built around the year it was opened in
            recreate()
            return
        }

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

    /**
     * One button per class (F1, F2, F3, F1 Academy): the rows below show that class. The last button
     * opens the whole current season (of the chosen class), which the season picture used to do.
     */
    private fun buildSeriesButtons(container: ViewGroup, currentYear: Int) {
        val density = resources.displayMetrics.density
        fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.series_button_background)
            val h = (20 * density).toInt()
            val v = (10 * density).toInt()
            setPadding(h, v, h, v)
            setOnClickListener { onClick() }
            container.addView(this, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (12 * density).toInt() })
        }
        for (series in Series.entries) {
            seriesButtons[series] = button(series.label) {
                seriesFilter.select(series)
                markSelectedSeries()
            }
        }
        button(getString(R.string.home_season_button, currentYear)) {
            startActivity(SeasonBrowseActivity.intent(this, Archive(currentYear)))
        }
        markSelectedSeries()
    }

    private fun markSelectedSeries() {
        val selected = seriesFilter.selected.value
        seriesButtons.forEach { (series, view) -> view.isActivated = series == selected }
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
            return
        }
        // MainActivity finishes right after launching this screen, so this is where "app opened" lands
        updateManager.onLandingResumed(this)
    }

    override fun onStop() {
        // Paired with onStart (it was onPause: anything that paused the screen without stopping it,
        // like a dialog, ended the refresh until the app was reopened)
        syncJob?.cancel()
        syncJob = null
        super.onStop()
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