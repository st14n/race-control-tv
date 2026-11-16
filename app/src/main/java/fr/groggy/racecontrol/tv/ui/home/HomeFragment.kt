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
import fr.groggy.racecontrol.tv.core.progress.WatchProgressStore
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.ui.BrowseSelectionMemory
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackActivity
import fr.groggy.racecontrol.tv.ui.channel.playback.ChannelPlaybackFragment
import fr.groggy.racecontrol.tv.ui.season.archive.SeasonArchiveActivity
import fr.groggy.racecontrol.tv.ui.season.browse.Season
import fr.groggy.racecontrol.tv.ui.season.browse.SeasonBrowseActivity
import fr.groggy.racecontrol.tv.ui.season.browse.Session
import fr.groggy.racecontrol.tv.ui.session.SessionCardPresenter
import fr.groggy.racecontrol.tv.ui.session.browse.SessionBrowseActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.threeten.bp.Year
import javax.inject.Inject

@Keep
@AndroidEntryPoint
class HomeFragment : RowsSupportFragment(), OnItemViewClickedListener {
    @Inject internal lateinit var sessionCardPresenter: SessionCardPresenter
    @Inject internal lateinit var watchProgressStore: WatchProgressStore
    @Inject internal lateinit var settingsRepository: SettingsRepository

    private val heroPresenter by lazy {
        HeroRowPresenter(
            showThumbnails = { settingsRepository.getCurrent().displayThumbnailsEnabled },
            onSessionClicked = ::openSession,
            onContinueClicked = ::continueWatching
        )
    }
    private val homeEntriesAdapter by lazy {
        ArrayObjectAdapter(
            ClassPresenterSelector()
                .addClassPresenter(HeroRow::class.java, heroPresenter)
                .addClassPresenter(ListRow::class.java, ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
                    shadowEnabled = false
                    selectEffectEnabled = false
                })
        )
    }
    private val currentYear = Year.now().value
    private var archivesRow: ListRow? = null
    /** The row with the latest weekend's sessions: there is only ever one. */
    private var latestEventRow: ListRow? = null
    private var heroRow: HeroRow? = null
    private var heroKey: String? = null
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

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        keepTopVisible()
    }

    /**
     * Leanback scrolls every selected row to the top of the screen, so selecting the weekend row
     * pushed the big card away. Rows near the top now stay where they are (the list only scrolls once
     * the selection gets past the middle).
     */
    private fun keepTopVisible() {
        verticalGridView?.apply {
            windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
            windowAlignmentOffset = 0
            windowAlignmentOffsetPercent = 55f
            itemAlignmentOffset = 0
            itemAlignmentOffsetPercent = 0f
        }
    }

    override fun setAlignment(windowAlignOffsetTop: Int) {
        super.setAlignment(windowAlignOffsetTop)
        keepTopVisible()
    }

    private fun buildRowsAdapter() {
        val viewModel: HomeViewModel by viewModels()

        archivesRow = getArchiveRow(viewModel.listArchive())

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.getCurrentSeason(Archive(currentYear))
                    .combine(watchProgressStore.entries) { season, progress -> season to progress }
                    .collect { (season, progress) -> onUpdatedSeason(season, progress) }
            }
        }
    }

    private fun onUpdatedSeason(season: Season, progress: List<WatchProgressStore.Entry>) {
        // What was selected before this update: an update must not move the selection
        val keep = selectionMemory.current
        val events = season.events.filter { it.sessions.isNotEmpty() }
        if (events.isNotEmpty()) {
            val event = events.first()
            val headerName = getString(R.string.home_weekend_row, event.name)
            // A weekend not shown before (a new race weekend, or another class) is selected
            val newWeekend = latestSeenWeekend() != headerName
            if (newWeekend) rememberLatestSeenWeekend(headerName)
            updateHero(season, event.name, event.sessions, newWeekend, progress)

            val current = latestEventRow
            if (current != null && current.headerItem.name == headerName) {
                // Same weekend: update its sessions in place (diffed, focus stays where it is)
                getLastSessionsRow(event.sessions, headerName, current)
                // The progress bars on the cards
                (current.adapter as ArrayObjectAdapter).let { it.notifyArrayItemRangeChanged(0, it.size()) }
            } else {
                // A new weekend (or the first data): it replaces the previous weekend's row
                val row = getLastSessionsRow(event.sessions, headerName, null)
                val index = current?.let { homeEntriesAdapter.indexOf(it) } ?: -1
                if (index >= 0) {
                    homeEntriesAdapter.replace(index, row)
                } else {
                    val hero = heroRow
                    val at = if (hero != null && homeEntriesAdapter.indexOf(hero) == 0) 1 else 0
                    homeEntriesAdapter.add(at, row)
                }
                latestEventRow = row
            }
            ensureArchiveRowPresent()
            if (!hasLoadedDataOnce) {
                hasLoadedDataOnce = true
                if (newWeekend || !restoreSelection(keep, focus = true)) selectTop(focus = true)
            } else if (newWeekend) {
                selectTop(focus = false)
            } else {
                restoreSelection(keep)
            }
        } else {
            onEmptySeason()
        }
    }

    /** The big card: the session that is live now, else the newest replay of the newest weekend. */
    private fun updateHero(
        season: Season,
        eventName: String,
        sessions: List<Session>,
        newWeekend: Boolean,
        progress: List<WatchProgressStore.Entry>
    ) {
        // Live first; otherwise the newest full replay (a race or qualifying, not a show after it)
        val session = sessions.firstOrNull { SessionCardPresenter.isLive(it.contentSubtype) }
            ?: sessions.firstOrNull { it.contentSubtype.equals("REPLAY", ignoreCase = true) }
            ?: sessions.first()
        val byContent = season.events.flatMap { e -> e.sessions.map { it.contentId to (e.name to it) } }.toMap()
        val continueItems = progress.mapNotNull { entry ->
            byContent[entry.contentId]?.let { (event, s) -> ContinueItem(s, event, entry) }
        }.take(3)
        val key = listOf(session.id.value, session.name, session.contentSubtype, session.largePictureUrl, eventName, newWeekend,
            continueItems.joinToString { "${it.session.id.value}:${it.progress.positionMs}" }).joinToString("|")
        if (key == heroKey) return
        heroKey = key
        val row = HeroRow(session, eventName, newWeekend, continueItems)
        val old = heroRow
        val index = old?.let { homeEntriesAdapter.indexOf(it) } ?: -1
        if (index >= 0) homeEntriesAdapter.replace(index, row) else homeEntriesAdapter.add(0, row)
        heroRow = row
    }

    private fun selectTop(focus: Boolean) {
        view?.post {
            setSelectedPosition(0, !focus)
            if (focus) view?.requestFocus()
        }
    }

    private fun onEmptySeason() {
        /* Session wasn't started yet (or this class has none this season): only the archive */
        listOfNotNull(heroRow, latestEventRow).forEach { row ->
            val index = homeEntriesAdapter.indexOf(row)
            if (index >= 0) homeEntriesAdapter.removeItems(index, 1)
        }
        heroRow = null
        heroKey = null
        latestEventRow = null
        ensureArchiveRowPresent()
    }

    private val weekendPrefs by lazy {
        requireContext().getSharedPreferences("home_latest_weekend", android.content.Context.MODE_PRIVATE)
    }
    private val viewModelForSeries: HomeViewModel by viewModels()

    /** The latest weekend the home screen showed for the chosen class. */
    private fun latestSeenWeekend(): String? = weekendPrefs.getString(viewModelForSeries.selectedSeries().name, null)

    private fun rememberLatestSeenWeekend(header: String) {
        weekendPrefs.edit().putString(viewModelForSeries.selectedSeries().name, header).apply()
    }

    private fun ensureArchiveRowPresent() {
        val archive = archivesRow ?: return
        if (homeEntriesAdapter.indexOf(archive) < 0) {
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

    private fun openSession(session: Session) {
        startActivity(SessionBrowseActivity.intent(requireActivity(), session.id.value, session.contentId, currentYear))
    }

    /** Straight into the player, on the feed and at the moment the replay was left. */
    private fun continueWatching(item: ContinueItem) {
        val intent = ChannelPlaybackActivity.intent(
            requireActivity(),
            item.session.id.value,
            item.progress.channelId,
            item.session.contentId,
            false,
            currentYear
        )
        ChannelPlaybackFragment.putStartPositionMs(intent, item.progress.positionMs)
        startActivity(intent)
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?
    ) {
        when (item) {
            is Session -> openSession(item)
            is HomeItem -> startActivity(
                when (item.type) {
                    HomeItemType.ARCHIVE -> SeasonBrowseActivity.intent(requireContext(), Archive(item.text.toInt()))
                    HomeItemType.ARCHIVE_ALL -> SeasonArchiveActivity.intent(requireContext())
                }
            )
        }
    }
}
