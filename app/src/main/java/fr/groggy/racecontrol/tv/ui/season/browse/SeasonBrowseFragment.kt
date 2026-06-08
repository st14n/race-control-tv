package fr.groggy.racecontrol.tv.ui.season.browse

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.ui.event.EventListRowDiffCallback
import fr.groggy.racecontrol.tv.ui.session.SessionCardPresenter
import fr.groggy.racecontrol.tv.ui.session.browse.SessionBrowseActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.threeten.bp.Year
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class SeasonBrowseFragment : BrowseSupportFragment(), OnItemViewClickedListener {
    @Inject internal lateinit var sessionCardPresenter: SessionCardPresenter
    @Inject lateinit var settingsRepository: SettingsRepository

    private val eventListRowDiffCallback = EventListRowDiffCallback()

    private val eventsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setupUIElements()
        setupEventListeners()

        val viewModel: SeasonBrowseViewModel by viewModels({ requireActivity() })
        val archive = findArchive(requireActivity())
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getSeason(archive).asLiveData().observe(viewLifecycleOwner, ::onUpdatedSeason)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (settingsRepository.getCurrent().displayThumbnailsEnabled) {
            initializeBackground()
        }
    }

    private fun setupUIElements() {
        val isTouchDevice = requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        headersState = if (isTouchDevice) HEADERS_DISABLED else HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = !isTouchDevice
        brandColor = ContextCompat.getColor(requireContext(), R.color.fastlane_background)
        adapter = eventsAdapter
    }

    /**
     * Function that will load a [Session.largePictureUrl] as a background image when a card is selected
     */
    private fun initializeBackground() {
        val backgroundManager = BackgroundManager.getInstance(activity).apply {
            attach(activity?.window)
        }

        val metrics = requireContext().resources.displayMetrics

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            if (item is Session) {
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(BACKGROUND_UPDATE_DELAY) // A small delay so that the loading of the image and transition is more smooth

                    Glide.with(requireActivity())
                        .load(item.largePictureUrl)
                        .error(R.drawable.banner)
                        .into<CustomTarget<Drawable>>(object :
                            CustomTarget<Drawable>(metrics.widthPixels, metrics.heightPixels) {

                            override fun onResourceReady(
                                resource: Drawable,
                                transition: Transition<in Drawable>?
                            ) {
                                backgroundManager.drawable = resource
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {

                            }
                        })

                }
            }
        }
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = this
    }

    private fun onUpdatedSeason(season: Season) {
        title = season.name
        val existingListRows = eventsAdapter.unmodifiableList<ListRow>()
        val newEventRows = season.events
            .filter { it.sessions.isNotEmpty() }
            .map { toListRow(it, existingListRows) }

        var needsUpdate = existingListRows.size != newEventRows.size
        if (!needsUpdate) {
            for (i in existingListRows.indices) {
                if (existingListRows[i] !== newEventRows[i]) {
                    needsUpdate = true
                    break
                }
            }
        }

        if (needsUpdate) {
            eventsAdapter.setItems(newEventRows, eventListRowDiffCallback)
        }
    }

    private fun toListRow(event: Event, existingListRows: List<ListRow>): ListRow {
        val existingListRow = existingListRows.find { it.headerItem.name == event.name }
        val (listRow, sessionsAdapter) = if (existingListRow == null) {
            val sessionsAdapter = ArrayObjectAdapter(sessionCardPresenter)
            val listRow = ListRow(HeaderItem(event.name), sessionsAdapter)
            listRow to sessionsAdapter
        } else {
            val sessionsAdapter = existingListRow.adapter as ArrayObjectAdapter
            existingListRow to sessionsAdapter
        }
        val existingSessions = sessionsAdapter.unmodifiableList<Session>()
        var sessionsNeedUpdate = existingSessions.size != event.sessions.size
        if (!sessionsNeedUpdate) {
            for (i in existingSessions.indices) {
                if (existingSessions[i] != event.sessions[i]) {
                    sessionsNeedUpdate = true
                    break
                }
            }
        }

        if (sessionsNeedUpdate) {
            sessionsAdapter.setItems(event.sessions, Session.diffCallback)
        }
        return listRow
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder,
        item: Any,
        rowViewHolder: RowPresenter.ViewHolder,
        row: Row
    ) {
        val session = item as Session
        val intent =
            SessionBrowseActivity.intent(requireActivity(), session.id.value, session.contentId, findArchive(requireActivity()).year)
        startActivity(intent)
    }

    companion object {
        private val TAG = SeasonBrowseFragment::class.simpleName

        private val YEAR = "${SeasonBrowseFragment::class}.YEAR"

        private const val BACKGROUND_UPDATE_DELAY = 300L

        fun putArchive(intent: Intent, archive: Archive) {
            intent.putExtra(YEAR, archive.year)
        }

        fun findArchive(activity: Activity): Archive {
            val year = activity.intent.getIntExtra(
                YEAR, Year.now().value
            )
            return Archive(year)
        }
    }
}