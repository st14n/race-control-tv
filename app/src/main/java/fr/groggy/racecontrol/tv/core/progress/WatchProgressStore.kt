package fr.groggy.racecontrol.tv.core.progress

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where each replay was left (for "Continue watching" and the progress bar on the cards).
 * Small and local: a few dozen entries in shared preferences, newest first.
 */
@Singleton
class WatchProgressStore @Inject constructor(@ApplicationContext context: Context) {

    data class Entry(
        val sessionId: String,
        val contentId: String,
        val channelId: String?,
        val feedLabel: String?,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long
    ) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
    }

    companion object {
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 40
        /** Less than this watched: not worth continuing. */
        private const val MIN_WATCHED_MS = 60_000L
        /** Less than this left (or 97 % watched): seen, it leaves the list. */
        private const val FINISHED_REMAINING_MS = 3 * 60_000L
    }

    private val prefs = context.applicationContext.getSharedPreferences("watch_progress", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries

    fun forContent(contentId: String): Entry? = _entries.value.firstOrNull { it.contentId == contentId }

    /** A replay stopped at [positionMs] of [durationMs]: remembered, or forgotten when (almost) finished. */
    fun record(
        sessionId: String,
        contentId: String,
        channelId: String?,
        feedLabel: String?,
        positionMs: Long,
        durationMs: Long
    ) {
        if (durationMs <= 0 || positionMs < 0) return
        val others = _entries.value.filterNot { it.contentId == contentId }
        val finished = durationMs - positionMs < FINISHED_REMAINING_MS || positionMs > durationMs * 0.97
        val next = when {
            finished -> others
            positionMs < MIN_WATCHED_MS -> return
            else -> listOf(
                Entry(sessionId, contentId, channelId, feedLabel, positionMs, durationMs, System.currentTimeMillis())
            ) + others
        }.take(MAX_ENTRIES)
        _entries.value = next
        save(next)
    }

    private fun load(): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(
                sessionId = o.getString("s"),
                contentId = o.getString("c"),
                channelId = o.optString("ch").takeIf { it.isNotEmpty() },
                feedLabel = o.optString("f").takeIf { it.isNotEmpty() },
                positionMs = o.getLong("p"),
                durationMs = o.getLong("d"),
                updatedAtMs = o.getLong("u")
            )
        }
    }.getOrDefault(emptyList())

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("s", e.sessionId); put("c", e.contentId)
                put("ch", e.channelId ?: ""); put("f", e.feedLabel ?: "")
                put("p", e.positionMs); put("d", e.durationMs); put("u", e.updatedAtMs)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
