package fr.groggy.racecontrol.tv.ui.channel

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.f1tv.F1TvBasicChannelType

class ChannelListPresenter: Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.channel_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val view = viewHolder.view.findViewById<TextView>(R.id.driverName)
        when(item) {
            is BasicChannelCard -> {
                val type = item.type
                view.text = when(type) {
                    F1TvBasicChannelType.Companion.Wif -> "International"
                    F1TvBasicChannelType.Companion.F1Live -> "F1 Live"
                    F1TvBasicChannelType.Companion.PitLane -> "Pit Lane"
                    F1TvBasicChannelType.Companion.Tracker -> "Tracker"
                    F1TvBasicChannelType.Companion.Data -> "Data"
                    is F1TvBasicChannelType.Companion.Unknown -> type.name
                }
            }
            is OnboardChannelCard -> {
                view.text = item.name
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder?) { }
}