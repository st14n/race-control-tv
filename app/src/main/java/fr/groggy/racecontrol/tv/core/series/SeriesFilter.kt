package fr.groggy.racecontrol.tv.core.series

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.groggy.racecontrol.tv.ui.season.browse.Season
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The racing classes F1 TV carries (the "Series" of each session in F1's catalogue). */
enum class Series(val apiName: String, val label: String) {
    F1("FORMULA 1", "F1"),
    F2("FORMULA 2", "F2"),
    F3("FORMULA 3", "F3"),
    F1_ACADEMY("F1 ACADEMY", "F1 Academy");

    companion object {
        fun fromApi(name: String?): Series =
            entries.firstOrNull { it.apiName.equals(name?.trim(), ignoreCase = true) } ?: F1
    }
}

/** Which class the catalogue shows; remembered across app starts. */
@Singleton
class SeriesFilter @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("series_filter", Context.MODE_PRIVATE)
    private val _selected = MutableStateFlow(
        Series.entries.firstOrNull { it.name == prefs.getString(KEY, null) } ?: Series.F1
    )
    val selected: StateFlow<Series> = _selected.asStateFlow()

    fun select(series: Series) {
        if (_selected.value == series) return
        _selected.value = series
        prefs.edit { putString(KEY, series.name) }
    }

    private companion object {
        const val KEY = "selected"
    }
}

/** Only the sessions of [series]; events without any are left out. */
fun Season.forSeries(series: Series): Season = copy(
    events = events
        .map { event -> event.copy(sessions = event.sessions.filter { Series.fromApi(it.series) == series }) }
        .filter { it.sessions.isNotEmpty() }
)
