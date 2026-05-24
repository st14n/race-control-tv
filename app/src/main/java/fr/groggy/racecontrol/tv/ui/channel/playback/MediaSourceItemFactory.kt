package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.f1.F1Client
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing

object MediaSourceItemFactory {

    private const val TAG = "MediaSourceItemFactory"

    /** Build a DRM-enabled [MediaItem] for the main video stream. */
    fun newMediaItem(viewing: F1TvViewing): MediaItem {
        Log.d(TAG, "Creating MediaItem for URL: ${viewing.url}")
        return buildMediaItem(
            uri = viewing.url,
            contentId = viewing.contentId,
            channelId = viewing.channelId,
            laURL = viewing.laURL,
            ascendontoken = viewing.ascendontoken,
            entitlementtoken = viewing.entitlementtoken
        )
    }

    /**
     * Build a DRM-enabled [MediaItem] for the external audio (PRES/F1Live) stream.
     * Falls back to the main viewing tokens if the audio-specific ones are absent.
     */
    fun newExternalAudioMediaItem(viewing: F1TvViewing): MediaItem {
        val uri = requireNotNull(viewing.externalAudioUri) { "externalAudioUri must not be null" }
        Log.d(TAG, "Creating external audio MediaItem for URI: $uri")
        return buildMediaItem(
            uri = uri,
            contentId = viewing.externalAudioContentId ?: viewing.contentId,
            channelId = viewing.externalAudioChannelId ?: viewing.channelId,
            laURL = viewing.externalAudioLaURL ?: viewing.laURL,
            ascendontoken = viewing.ascendontoken,
            entitlementtoken = viewing.externalAudioEntitlementtoken ?: viewing.entitlementtoken
        )
    }

    private fun buildMediaItem(
        uri: Uri,
        contentId: String,
        channelId: String?,
        laURL: String?,
        ascendontoken: String,
        entitlementtoken: String
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)

        if (entitlementtoken.isNotBlank()) {
            val licenseUri: Uri? = when {
                !laURL.isNullOrBlank() -> {
                    Log.i(TAG, "Using laURL from API: $laURL")
                    runCatching { Uri.parse(laURL) }.onFailure {
                        Log.e(TAG, "Failed to parse laURL: $laURL", it)
                    }.getOrNull()
                }
                else -> {
                    Log.w(TAG, "laURL missing, constructing fallback DRM URL for contentId=$contentId")
                    runCatching {
                        Uri.parse(F1Client.buildWidevineUrl(viewingPlatform(uri, laURL), contentId, channelId))
                    }.onFailure { Log.e(TAG, "Failed to build fallback DRM URL", it) }.getOrNull()
                }
            }

            if (licenseUri != null) {
                Log.i(TAG, "Configuring Widevine DRM with license URI: $licenseUri")
                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(licenseUri)
                        .setLicenseRequestHeaders(
                            mapOf(
                                "apiKey" to F1Client.API_KEY,
                                "User-Agent" to BuildConfig.DEFAULT_USER_AGENT,
                                "x-f1-device-info" to BuildConfig.F1_DEVICE_INFO,
                                "ascendontoken" to ascendontoken,
                                "entitlementtoken" to entitlementtoken
                            )
                        )
                        .setMultiSession(true)
                        .build()
                )
            } else {
                Log.e(TAG, "No valid license URI — DRM will fail for $uri")
            }
        } else {
            Log.w(TAG, "No entitlement token — DRM not configured for $uri")
        }

        return builder.build()
    }

    private fun viewingPlatform(uri: Uri, laURL: String?): String {
        if (!laURL.isNullOrBlank() && laURL.contains("WEB_DASH", ignoreCase = true)) {
            return "WEB_DASH"
        }
        return if (uri.toString().contains(".mpd", ignoreCase = true)) {
            "BIG_SCREEN_DASH"
        } else {
            "BIG_SCREEN_HLS"
        }
    }
}
