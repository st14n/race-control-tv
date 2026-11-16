package fr.groggy.racecontrol.tv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.Keep
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.ui.BrowseSelectionMemory
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.ui.season.archive.SeasonArchiveActivity
import fr.groggy.racecontrol.tv.ui.season.browse.Season
import fr.groggy.racecontrol.tv.ui.season.browse.SeasonBrowseActivity
import fr.groggy.racecontrol.tv.ui.season.browse.Session
import fr.groggy.racecontrol.tv.ui.session.SessionCardPresenter
import fr.groggy.racecontrol.tv.ui.session.browse.SessionBrowseActivity
import kotlinx.coroutines.flow.collect
import org.threeten.bp.Year
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class HomeFragment : RowsSupportFragment(), OnItemViewClickedListener {
    @Inject internal lateinit var sessionCardPresenter: SessionCardPresenter

    private val homeEntriesAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val currentYear = Year.now().value
    private var archivesRow: ListRow? = null
    /** The row with the latest weekend's sessions: there is only ever one. */
    private var latestEventRow: ListRow? = null
    private var hasLoadedDataOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUIElements()
        setupEventListeners()
        buildRowsAdapter()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        view?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            val dimensionPixelSize =
                inflater.context.resources.getDimensionPixelSize(R.dimen.lb_browse_rows_fading_edge)
            val horizontalMargin = -dimensionPixelSize * 2 - 4

            leftMargin = horizontalMargin
            rightMargin = horizontalMargin
        }

        return view
    }

    private fun buildRowsAdapter() {
        val viewModel: HomeViewModel by viewModels()

        archivesRow = getArchiveRow(viewModel.listArchive())

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.getCurrentSeason(Archive(currentYear))
                    .collect(::onUpdatedSeason)
            }
        }
    }

    private fun onUpdatedSeason(season: Season) {
        // What was selected before this update: an update must not move the selection
        val keep = selectionMemory.current
        val events = season.events.filter { it.sessions.isNotEmpty() }
        if (events.isNotEmpty()) {
            val event = events.first()
            val headerName =
                getString(R.string.season_last_event, event.name, currentYear.toString())
            val current = latestEventRow
            if (current != null && current.headerItem.name == headerName) {
                // Same weekend: update its sessions in place (diffed, focus stays where it is)
                getLastSessionsRow(event.sessions, headerName, current)
            } else {
                // A new weekend (or the first data): it replaces the previous weekend's row. Before, it
                // was added above it, so the screen kept showing the old weekend (the new one was one
                // row up) and every weekend the app stayed open added another row.
                val row = getLastSessionsRow(event.sessions, headerName, null)
                val index = current?.let { homeEntriesAdapter.indexOf(it) } ?: -1
                if (index >= 0) homeEntriesAdapter.replace(index, row) else homeEntriesAdapter.add(0, row)
                latestEventRow = row
                if (current != null) selectLatestEvent()
            }
            ensureArchiveRowPresent()
            if (!hasLoadedDataOnce) {
                hasLoadedDataOnce = true
                // The card selected last time, if that weekend is still the one shown; else the latest weekend
                if (!restoreSelection(keep, focus = true)) maybeSelectLatestEventByDefault()
            } else if (current?.headerItem?.name == headerName) {
                restoreSelection(keep)
            }
        } else {
            onEmptySeason()
        }
    }

    private fun selectLatestEvent() {
        view?.post { setSelectedPosition(0, true) }
    }

    private fun maybeSelectLatestEventByDefault() {
        view?.post {
            setSelectedPosition(0, false)
            view?.requestFocus()
        }
    }

    private fun onEmptySeason() {
        /* Session wasn't started yet, just add the archive */
        ensureArchiveRowPresent()
    }

    private fun ensureArchiveRowPresent() {
        val archive = archivesRow ?: return
        val hasArchiveRow = homeEntriesAdapter.unmodifiableList<ListRow>()
            .any { it.headerItem.name == archive.headerItem.name }
        if (!hasArchiveRow) {
            homeEntriesAdapter.add(archive)
        }
    }

    private fun getLastSessionsRow(
        sessions: List<Session>,
        headerName: String,
        existingListRow: ListRow?
    ): ListRow {
        val (listRow, listRowAdapter) = if (existingListRow == null) {
            val listRowAdapter = ArrayObjectAdapter(sessionCardPresenter)
            val listRow = ListRow(HeaderItem(headerName), listRowAdapter)
            listRow to listRowAdapter
        } else {
            val listRowAdapter = existingListRow.adapter as ArrayObjectAdapter
            existingListRow to listRowAdapter
        }
        listRowAdapter.setItems(sessions, Session.diffCallback)
        return listRow
    }

    private fun getArchiveRow(archives: List<Archive>): ListRow {
        val subArchives = archives.map { archive -> HomeItem(HomeItemType.ARCHIVE, archive.year.toString()) }

        val listRowAdapter = ArrayObjectAdapter(HomeItemPresenter())
        listRowAdapter.setItems(subArchives, HomeItem.diffCallback)
        listRowAdapter.add(
            HomeItem(
                HomeItemType.ARCHIVE_ALL,
                resources.getString(R.string.home_all)
            )
        )

        return ListRow(HeaderItem(resources.getString(R.string.home_archive)), listRowAdapter)
    }

    private fun setupUIElements() {
        adapter = homeEntriesAdapter
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = this
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            val header = (row as? ListRow)?.headerItem?.name ?: return@OnItemViewSelectedListener
            itemKey(item)?.let { selectionMemory.remember(header, it) }
        }
    }

    private val selectionMemory by lazy { BrowseSelectionMemory(requireContext(), "home") }

    private fun itemKey(item: Any?): String? = when (item) {
        is Session -> "session:" + item.id.value
        is HomeItem -> "home:" + item.type + ":" + item.text
        else -> null
    }

    /** Back to the card selected before (after a refresh or reopening); false if it is gone. */
    private fun restoreSelection(selection: BrowseSelectionMemory.Selection?, focus: Boolean = false): Boolean {
        val target = selection ?: return false
        val (row, item) = selectionMemory.locate(homeEntriesAdapter, target, ::itemKey) ?: return false
        view?.post {
            setSelectedPosition(row, false, BrowseSelectionMemory.selectTask(item))
            if (focus) view?.requestFocus()
        }
        return true
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?
    ) {
        val activity = when (item) {
            is Session -> {
                SessionBrowseActivity.intent(requireActivity(), item.id.value, item.contentId, currentYear)
            }
            is HomeItem -> when (item.type) {
                HomeItemType.ARCHIVE -> {
                    SeasonBrowseActivity.intent(requireContext(), Archive(item.text.toInt()))
                }
                HomeItemType.ARCHIVE_ALL -> {
                    SeasonArchiveActivity.intent(requireContext())
                }
            }
            else -> null
        }
        activity?.let { startActivity(it) }
    }
}
