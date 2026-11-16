package fr.groggy.racecontrol.tv.ui.season.archive

import android.os.Bundle
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.leanback.app.RowsSupportFragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.leanback.widget.*
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.ui.season.browse.SeasonBrowseActivity

@Keep
@AndroidEntryPoint
class SeasonArchiveFragment : RowsSupportFragment(), OnItemViewClickedListener {

    private val archivesAdapter = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_SMALL).apply {
        shadowEnabled = false
        selectEffectEnabled = false
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUIElements()
        setupEventListeners()
        buildRowsAdapter()
    }

    // The redesign: a title above one row of years per decade, no blue side menu
    private fun setupUIElements() {
        adapter = archivesAdapter
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = super.onCreateView(inflater, container, savedInstanceState)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.findViewById<TextView>(R.id.archive_title)?.setText(R.string.choose_a_season)
    }

    private fun setupEventListeners() {
        onItemViewClickedListener = this
    }

    private fun buildRowsAdapter() {
        val viewModel: SeasonArchiveViewModel by viewModels()
        viewModel.listArchive().entries.forEach { decade ->
            val entries = decade.value
            val listRowAdapter = ArrayObjectAdapter(ArchivePresenter())
            listRowAdapter.setItems(entries, null)
            val title = getString(
                R.string.season_archives_title,
                entries[0].year.toString(),
                entries[entries.size - 1].year.toString()
            )
            archivesAdapter.add(ListRow(HeaderItem(title), listRowAdapter))
        }
    }

    override fun onItemClicked(
        itemViewHolder: Presenter.ViewHolder?,
        item: Any?,
        rowViewHolder: RowPresenter.ViewHolder?,
        row: Row?
    ) {
        val browseActivity = SeasonBrowseActivity
            .intent(requireContext(), item as Archive)
        startActivity(browseActivity)
    }
}
