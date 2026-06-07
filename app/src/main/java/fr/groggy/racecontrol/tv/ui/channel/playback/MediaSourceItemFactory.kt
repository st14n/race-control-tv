package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import fr.groggy.racecontrol.tv.f1.F1Client
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.utils.DeviceInfo

object MediaSourceItemFactory {

    private const val TAG = "MediaSourceItemFactory"

    /** Build a DRM-enabled [MediaItem] for the main video stream. */
    fun newMediaItem(viewing: F1TvViewing): MediaItem {
        Log.d(TAG, "Creating MediaItem for URL: ${viewing.url}")
        return buildMediaItem(
            uri = viewing.url,
            contentId = viewing.contentId,
            channelId = viewing.channelId,
            playApiVersion = viewing.playApiVersion,
            platform = viewing.platform,
            streamType = viewing.streamType,
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
            playApiVersion = viewing.externalAudioPlayApiVersion ?: viewing.playApiVersion,
            platform = viewing.platform,
            streamType = viewing.externalAudioStreamType ?: viewing.streamType,
            laURL = viewing.externalAudioLaURL ?: viewing.laURL,
            ascendontoken = viewing.ascendontoken,
            entitlementtoken = viewing.externalAudioEntitlementtoken ?: viewing.entitlementtoken
        )
    }

    private fun buildMediaItem(
        uri: Uri,
        contentId: String,
        channelId: String?,
        playApiVersion: String,
        platform: String,
        streamType: String?,
        laURL: String?,
        ascendontoken: String,
        entitlementtoken: String
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        val isDash = streamType?.contains("DASH", ignoreCase = true) == true ||
            uri.toString().contains(".mpd", ignoreCase = true)

        if (entitlementtoken.isNotBlank()) {
            val licenseUri: Uri? = when {
                !laURL.isNullOrBlank() -> {
                    Log.i(TAG, "Using laURL from API: $laURL")
                    runCatching { Uri.parse(laURL) }.onFailure {
                        Log.e(TAG, "Failed to parse laURL: $laURL", it)
                    }.getOrNull()
                }
                isDash -> {
                    Log.w(TAG, "laURL missing, constructing fallback DRM URL for contentId=$contentId")
                    runCatching {
                        Uri.parse(
                            F1Client.buildWidevineUrl(
                                playApiVersion = playApiVersion,
                                platform = platform,
                                contentId = contentId,
                                channelId = channelId
                            )
                        )
                    }.onFailure { Log.e(TAG, "Failed to build fallback DRM URL", it) }.getOrNull()
                }
                else -> {
                    Log.i(TAG, "Skipping DRM configuration for non-DASH stream without laURL: $uri")
                    null
                }
            }

            if (licenseUri != null) {
                Log.i(TAG, "Configuring Widevine DRM with license URI: $licenseUri")
                builder.setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                        .setLicenseUri(licenseUri)
                        .setForceDefaultLicenseUri(true)
                        .setLicenseRequestHeaders(
                            mapOf(
                                "x-f1-device-info" to DeviceInfo.f1DeviceInfo,
                                "ascendonToken" to ascendontoken,
                                "entitlementToken" to entitlementtoken
                            )
                        )
                        .build()
                )
            } else if (isDash) {
                Log.e(TAG, "No valid license URI — DRM will fail for $uri")
            }
        } else {
            Log.w(TAG, "No entitlement token — DRM not configured for $uri")
        }

        return builder.build()
    }

}
