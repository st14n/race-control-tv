package fr.groggy.racecontrol.tv.core.channel

import android.util.Log
import fr.groggy.racecontrol.tv.f1tv.F1TvClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelService @Inject constructor(
    private val repository: ChannelRepository,
    private val f1Tv: F1TvClient
) {

    companion object {
        private val TAG = ChannelService::class.simpleName
    }

    suspend fun loadChannelsWithDrivers(contentId: String) {
        Log.d(TAG, "loadChannelsWithDrivers")
        val channels = f1Tv.getChannels(contentId)
        repository.save(contentId, channels)
    }
}
