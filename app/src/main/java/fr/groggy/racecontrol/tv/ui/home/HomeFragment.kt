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
    private var hasAppliedInitialLatestEventSelection = false

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
        val events = season.events.filter { it.sessions.isNotEmpty() }
        if (events.isNotEmpty()) {
            val event = events.first()
            val existingListRows = homeEntriesAdapter.unmodifiableList<ListRow>()
            val headerName =
                getString(R.string.season_last_event, event.name, currentYear.toString())
            val existingListRow = existingListRows.find { it.headerItem.name == headerName }
            val sessionsListRow = getLastSessionsRow(event.sessions, headerName, existingListRow)
            val existingIndex = existingListRows.indexOfFirst { it.headerItem.name == headerName }

            if (existingListRow == null) {
                if (existingIndex >= 0) {
                    homeEntriesAdapter.replace(existingIndex, sessionsListRow)
                } else {
                    homeEntriesAdapter.add(0, sessionsListRow)
                }
            } else {
                /* Compare the old list to the new to see if it needs updating */
                if (!hasMatchingSessions(existingListRow, sessionsListRow)) {
                    homeEntriesAdapter.replace(existingIndex, sessionsListRow)
                }
            }
            ensureArchiveRowPresent()
            maybeSelectLatestEventByDefault()
        } else {
            onEmptySeason()
        }
    }

    private fun maybeSelectLatestEventByDefault() {
        if (hasAppliedInitialLatestEventSelection) return
        hasAppliedInitialLatestEventSelection = true
        view?.post {
            setSelectedPosition(0, false)
            view?.requestFocus()
        }
    }

    private fun hasMatchingSessions(
        existingListRow: ListRow,
        sessionsListRow: ListRow
    ) = (existingListRow.adapter.size() == sessionsListRow.adapter.size() // Do we have the same number of items
            && (0 until existingListRow.adapter.size()).all { index -> // If so, do the sessions in each match in order?
        existingListRow.adapter[index] as Session == sessionsListRow.adapter[index] as Session
    })

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
