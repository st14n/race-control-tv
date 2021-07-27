package fr.groggy.racecontrol.tv.ui.season.archive

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.groggy.racecontrol.tv.core.season.SeasonService
import fr.groggy.racecontrol.tv.f1tv.Archive
import javax.inject.Inject

@HiltViewModel
class SeasonArchiveViewModel @Inject constructor(
    private val seasonService: SeasonService
): ViewModel() {
    fun listArchive(): Map<Int, List<Archive>> {
        return seasonService.listArchive()
            .groupBy { (it.year / 10) * 10 }
    }
}
