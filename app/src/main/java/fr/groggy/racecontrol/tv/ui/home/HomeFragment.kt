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
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.flow.onEmpty
import org.threeten.bp.Year
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class HomeFragment : RowsSupportFragment(), OnItemViewClickedListener {
    @Inject internal lateinit var sessionCardPresenter: SessionCardPresenter

    private val homeEntriesAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val currentYear = Year.now().value
    private var archivesRow: ListRow? = null

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

        lifecycleScope.launchWhenCreated {
            viewModel.getCurrentSeason(Archive(currentYear))
                .onEmpty { onEmptySeason() }
                .collect(::onUpdatedSeason)
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

            if (existingListRow == null) {
                homeEntriesAdapter.add(sessionsListRow)
                homeEntriesAdapter.add(archivesRow)
            } else {
                /* Makes the adapter blink :| */
                homeEntriesAdapter.replace(0, sessionsListRow)
            }
        }
    }

    private fun onEmptySeason() {
        /* Session wasn't started yet, just add the archive */
        homeEntriesAdapter.add(archivesRow)
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
                SessionBrowseActivity.intent(requireActivity(), item.id.value, item.contentId)
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
        startActivity(activity)
    }
}
