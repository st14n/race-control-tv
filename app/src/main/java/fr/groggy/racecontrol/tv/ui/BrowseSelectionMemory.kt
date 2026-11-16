package fr.groggy.racecontrol.tv.ui

import android.content.Context
import androidx.core.content.edit
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.Presenter

/**
 * Remembers which card was selected in a browse screen (row title + item key), also across the app
 * being closed, so a refresh of the catalogue or reopening the screen comes back to the same session
 * instead of the first card.
 */
class BrowseSelectionMemory(context: Context, private val screen: String) {

    data class Selection(val row: String, val item: String)

    private val prefs = context.applicationContext.getSharedPreferences("browse_selection", Context.MODE_PRIVATE)

    /** The selection the viewer made last (null: none yet). */
    var current: Selection? = load()
        private set

    fun remember(row: String, item: String) {
        val selection = Selection(row, item)
        if (selection == current) return
        current = selection
        prefs.edit { putString(screen, "$row\u0000$item") }
    }

    private fun load(): Selection? {
        val raw = prefs.getString(screen, null) ?: return null
        val parts = raw.split('\u0000')
        return if (parts.size == 2) Selection(parts[0], parts[1]) else null
    }

    /**
     * Where [selection] is in [rows] now: row index and item index, or null if that row or item is gone.
     * [itemKey] gives an item's key (null: not selectable this way).
     */
    fun locate(rows: ArrayObjectAdapter, selection: Selection, itemKey: (Any?) -> String?): Pair<Int, Int>? {
        for (rowIndex in 0 until rows.size()) {
            val row = rows.get(rowIndex) as? ListRow ?: continue
            if (row.headerItem?.name != selection.row) continue
            val items = row.adapter ?: return null
            for (itemIndex in 0 until items.size()) {
                if (itemKey(items.get(itemIndex)) == selection.item) return rowIndex to itemIndex
            }
            return null
        }
        return null
    }

    companion object {
        fun selectTask(itemIndex: Int): Presenter.ViewHolderTask = ListRowPresenter.SelectItemViewHolderTask(itemIndex)
    }
}
