package fr.groggy.racecontrol.tv.ui.home

import androidx.leanback.widget.DiffCallback

enum class HomeItemType {
    ARCHIVE, ARCHIVE_ALL
}

data class HomeItem(
    val type: HomeItemType,
    val text: String
) {
    companion object {
        val diffCallback = object : DiffCallback<HomeItem>() {
            override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean {
                return areContentsTheSame(oldItem, newItem)
            }

            override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean {
                return oldItem != newItem
            }
        }
    }
}