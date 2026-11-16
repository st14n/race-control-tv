package fr.groggy.racecontrol.tv.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.groggy.racecontrol.tv.core.season.SeasonService
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.ui.season.browse.Season
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import fr.groggy.racecontrol.tv.core.series.Series
import fr.groggy.racecontrol.tv.core.series.SeriesFilter
import fr.groggy.racecontrol.tv.core.series.forSeries
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val seasonService: SeasonService,
    private val seriesFilter: SeriesFilter
): ViewModel() {

    fun listArchive(): List<Archive> {
        return seasonService.listArchive()
            .subList(1, 6)
    }

    /** The current season, with only the sessions of the chosen class (F1, F2, F3, F1 Academy). */
    suspend fun getCurrentSeason(archive: Archive): Flow<Season> {
        return combine(seasonService.season(archive), seriesFilter.selected) { season, series ->
            season.forSeries(series)
        }
    }

    fun selectedSeries(): Series = seriesFilter.selected.value
}
