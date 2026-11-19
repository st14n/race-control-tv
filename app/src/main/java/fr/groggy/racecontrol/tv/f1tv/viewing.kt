package fr.groggy.racecontrol.tv.f1tv

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize // Use parcelize for easier argument passing
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json

@JsonClass(generateAdapter = true)
data class F1TvViewingResponse(
    @param:Json(name = "resultCode") val resultCode: String,
    @param:Json(name = "message") val message: String,
    @param:Json(name = "errorDescription") val errorDescription: String,
    @param:Json(name = "resultObj") val resultObj: ResultObj,
    @param:Json(name = "systemTime") val systemTime: Long
) {
    @JsonClass(generateAdapter = true)
    data class ResultObj(
        @param:Json(name = "entitlementToken") val entitlementToken: String? = null,
        @param:Json(name = "url") val url: String? = null,
        @param:Json(name = "streamType") val streamType: String? = null, // e.g., "SDR_HD_DASHWV" or "HLS"
        @param:Json(name = "drmType") val drmType: String? = null,
        @param:Json(name = "laURL") val laURL: String? = null, // License URL (useful for checking)
        @param:Json(name = "playApiVersion") val playApiVersion: String? = null,
        @param:Json(name = "channelId") val channelId: String? = null,
        @param:Json(name = "tme") val tme: Map<String, Any?>? = null
    )
}

@JsonClass(generateAdapter = true)
data class F1TvViewingResponseResultObject(
    val url: String,
    val entitlementToken: String
)

@Parcelize
data class F1TvViewing(
    val url: Uri,
    val contentId: String,
    val channelId: String?,
    val platform: String,
    val playApiVersion: String = "2.0",
    val ascendontoken: String,
    val entitlementtoken: String,
    val streamType: String?,
    val requestedOverrideStreamType: String? = null,
    val laURL: String?,
    // External audio (PRES/F1Live channel) for MergingMediaSource — populated by ChannelPlaybackActivity
    val externalAudioUri: Uri? = null,
    val externalAudioStreamType: String? = null,
    val externalAudioLaURL: String? = null,
    val externalAudioPlayApiVersion: String? = null,
    val externalAudioEntitlementtoken: String? = null,
    val externalAudioContentId: String? = null,
    val externalAudioChannelId: String? = null,
    val externalAudioRequired: Boolean = false,
    /** The external audio is the other main feed's commentary (International <-> F1 LIVE), added to the menu. */
    val externalAudioIsCompanion: Boolean = false,
    /** Name of this stream's feed and of the companion feed, to tell same-language tracks apart in the audio menu. */
    val feedLabel: String? = null,
    val companionFeedLabel: String? = null,
    /**
     * The same feed's standard stream, merged in for its audio only: F1's 4K stream carries just English (and
     * Spanish), the standard stream every commentary language. Lets a 4K viewer pick any language.
     */
    val languageAudioUri: Uri? = null,
    val languageAudioStreamType: String? = null,
    val languageAudioLaURL: String? = null,
    val languageAudioPlayApiVersion: String? = null,
    val languageAudioEntitlementtoken: String? = null,
    val languageAudioContentId: String? = null,
    val languageAudioChannelId: String? = null,
    /** Device identity to use on the Widevine license request for this route. */
    val drmDeviceInfo: String = ""
) : Parcelable
