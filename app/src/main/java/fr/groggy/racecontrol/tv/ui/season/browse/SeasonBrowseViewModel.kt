package fr.groggy.racecontrol.tv.ui.season.browse

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.groggy.racecontrol.tv.core.season.SeasonService
import fr.groggy.racecontrol.tv.core.series.forSeries
import fr.groggy.racecontrol.tv.f1tv.Archive
import fr.groggy.racecontrol.tv.f1tv.F1TvChannelId
import fr.groggy.racecontrol.tv.f1tv.F1TvEventId
import fr.groggy.racecontrol.tv.f1tv.F1TvSessionId
import fr.groggy.racecontrol.tv.ui.DataClassByIdDiffCallback
import fr.groggy.racecontrol.tv.ui.session.SessionCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class SeasonBrowseViewModel @Inject constructor(
    private val seasonService: SeasonService,
    private val seriesFilter: fr.groggy.racecontrol.tv.core.series.SeriesFilter
) : ViewModel() {

    suspend fun archiveLoaded(archive: Archive): Season {
        return loaded(seasonService.season(archive))
    }

    /** The season with only the sessions of the class chosen on the home screen. */
    suspend fun getSeason(archive: Archive): Flow<Season> {
        return kotlinx.coroutines.flow.combine(seasonService.season(archive), seriesFilter.selected) { season, series ->
            season.forSeries(series)
        }
    }

    private suspend fun loaded(season: Flow<Season>): Season {
        return season.filter { it.events.isNotEmpty() }.first()
    }
}

data class Season(
    val name: String,
    val events: List<Event>
)

data class Event(
    val id: F1TvEventId,
    val name: String,
    val sessions: List<Session>
)

data class Session(
    val id: F1TvSessionId,
    val contentId: String,
    override val name: String,
    override val contentSubtype: String,
    override val thumbnail: Image?,
    val largePictureUrl:String,
    val channels: List<F1TvChannelId>,
    val series: String = "FORMULA 1"
) : SessionCard {

    companion object {
        val diffCallback = DataClassByIdDiffCallback { session: Session -> session.id }
    }

}

data class Image(
    override val url: Uri
) : SessionCard.Image
