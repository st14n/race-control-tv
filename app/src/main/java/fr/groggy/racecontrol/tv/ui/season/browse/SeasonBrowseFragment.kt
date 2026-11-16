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
import androidx.leanback.app.RowsSupportFragment
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
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
import fr.groggy.racecontrol.tv.ui.BrowseSelectionMemory
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
class SeasonBrowseFragment : RowsSupportFragment(), OnItemViewClickedListener {
    @Inject internal lateinit var sessionCardPresenter: SessionCardPresenter
    @Inject lateinit var settingsRepository: SettingsRepository

    private val eventListRowDiffCallback = EventListRowDiffCallback()

    private val eventsAdapter = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
        shadowEnabled = false
        selectEffectEnabled = false
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setupUIElements()
        setupEventListeners()

    }

    private fun collectSeason() {
        val viewModel: SeasonBrowseViewModel by viewModels({ requireActivity() })
        val archive = findArchive(requireActivity())
        // One collector, alive while the view is started (a LiveData observer added on every start piled up)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getSeason(archive).collect(::onUpdatedSeason)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectSeason()

        if (settingsRepository.getCurrent().displayThumbnailsEnabled) {
            initializeBackground()
        }
    }

    // The redesign: no blue side menu with the weekends, just one row per weekend under a title
    private fun setupUIElements() {
        adapter = eventsAdapter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        view?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            val fadingEdge = inflater.context.resources.getDimensionPixelSize(R.dimen.lb_browse_rows_fading_edge)
            leftMargin = -fadingEdge * 2 - 4
            rightMargin = -fadingEdge * 2 - 4
        }
        return view
    }

    private fun showTitle(text: String) {
        activity?.findViewById<TextView>(R.id.season_title)?.text = text
    }

    /**
     * Function that will load a [Session.largePictureUrl] as a background image when a card is selected
     */
    private fun initializeBackground() {
        val backgroundManager = BackgroundManager.getInstance(activity).apply {
            attach(activity?.window)
        }

        val metrics = requireContext().resources.displayMetrics

        // One target and one pending job: scrolling cancels the previous picture instead of decoding
        // a full-screen bitmap for every card passed
        val backgroundTarget = object : CustomTarget<Drawable>(metrics.widthPixels, metrics.heightPixels) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                backgroundManager.drawable = resource
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
        }
        onCardSelected = { item ->
            if (item is Session) {
                backgroundJob?.cancel()
                backgroundJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(BACKGROUND_UPDATE_DELAY) // A small delay so that the loading of the image and transition is more smooth
                    if (!isAdded) return@launch
                    Glide.with(this@SeasonBrowseFragment)
                        .load(item.largePictureUrl)
                        .error(R.drawable.banner)
                        .into(backgroundTarget)
                }
            }
        }
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = this
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            val header = (row as? ListRow)?.headerItem?.name
            if (item is Session && header != null) selectionMemory.remember(header, item.id.value)
            onCardSelected?.invoke(item)
        }
    }

    private var backgroundJob: kotlinx.coroutines.Job? = null

    /** Extra work when a card is selected (the background picture), set when thumbnails are on. */
    private var onCardSelected: ((Any?) -> Unit)? = null

    private val selectionMemory by lazy {
        BrowseSelectionMemory(requireContext(), "season:" + findArchive(requireActivity()).year)
    }
    private var restoredOnce = false

    /** Back to the session selected before (after a refresh or reopening the season). */
    private fun restoreSelection(selection: BrowseSelectionMemory.Selection?) {
        val target = selection ?: return
        val (row, item) = selectionMemory.locate(eventsAdapter, target) { (it as? Session)?.id?.value } ?: return
        view?.post {
            // Posted: the screen can be gone by now (back pressed right after opening)
            if (!isAdded || isStateSaved || view == null) return@post
            setSelectedPosition(row, false, BrowseSelectionMemory.selectTask(item))
        }
    }

    private fun onUpdatedSeason(season: Season) {
        showTitle(season.name)
        val keep = selectionMemory.current
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
        if (!restoredOnce || needsUpdate) {
            restoredOnce = true
            restoreSelection(keep)
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