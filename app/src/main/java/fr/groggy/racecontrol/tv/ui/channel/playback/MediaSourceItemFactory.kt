package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.MediaItem
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.f1.F1Client
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing

object MediaSourceItemFactory {
    fun newMediaItem(viewing: F1TvViewing): MediaItem {
        Log.d("MediaSourceItemFactory", "Creating MediaItem for URL: ${viewing.url}")
        val mediaItemBuilder = MediaItem.Builder().setUri(viewing.url)

        // Setup DRM only if entitlement token is present
        if (!viewing.entitlementtoken.isNullOrBlank()) {
            // *** USE laURL FROM VIEWING OBJECT IF AVAILABLE ***
            val licenseUri: Uri? = if (!viewing.laURL.isNullOrBlank()) {
                try {
                    Log.i("MediaSourceItemFactory", "Using specific laURL from API: ${viewing.laURL}")
                    Uri.parse(viewing.laURL)
                } catch (e: Exception) {
                    Log.e("MediaSourceItemFactory", "Error parsing laURL: ${viewing.laURL}", e)
                    null // Fallback if parsing fails
                }
            } else {
                // Fallback: Construct URL if laURL is missing
                Log.w("MediaSourceItemFactory", "laURL missing in viewing object! Falling back to constructing license URL.")
                try {
                    Uri.parse(
                        F1Client.DRM_URL.format(viewing.contentId) +
                                (if (viewing.channelId != null) "&channelId=${viewing.channelId}" else "")
                    )
                } catch (e: Exception) {
                    Log.e("MediaSourceItemFactory", "Error parsing fallback license URL", e)
                    null
                }
            }

            if (licenseUri != null) {
                Log.i("MediaSourceItemFactory", "Configuring DRM with License URI: $licenseUri")
                mediaItemBuilder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(licenseUri)
                        .setLicenseRequestHeaders(
                            mutableMapOf(
                                "User-Agent" to BuildConfig.DEFAULT_USER_AGENT,
                                "ascendontoken" to viewing.ascendontoken,
                                "entitlementtoken" to viewing.entitlementtoken
                            )
                        )
                        .setMultiSession(true)
                        .build()
                )
            } else {
                Log.e("MediaSourceItemFactory", "Could not determine a valid license URL. DRM will likely fail.")
            }
        } else {
            Log.w("MediaSourceItemFactory", "Entitlement token is missing for contentId: ${viewing.contentId}. DRM cannot be configured.")
        }
        return mediaItemBuilder.build()
    }
}
