package fr.groggy.racecontrol.tv.ui.channel.playback

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.f1.F1Client
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing

/**
 * Temp solution to generate media items
 * This will be moved on future to a view model
 */
object MediaSourceItemFactory {
    fun newMediaItem(viewing: F1TvViewing): MediaItem {
        return MediaItem.fromUri(viewing.url)
            .buildUpon()
            .setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseRequestHeaders(mutableMapOf(
                        "User-Agent" to BuildConfig.DEFAULT_USER_AGENT,
                        "ascendontoken" to viewing.ascendontoken,
                        "entitlementtoken" to viewing.entitlementtoken
                    ))
                    .setLicenseUri(F1Client.DRM_URL.format(viewing.contentId) + if (viewing.channelId != null) "&channelId=${viewing.channelId}" else "")
                    .setMultiSession(true)
                    .build()
            )
            .build()
    }
}