package fr.groggy.racecontrol.tv.f1tv

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize // Use parcelize for easier argument passing
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json

@JsonClass(generateAdapter = true)
data class F1TvViewingResponse(
    @Json(name = "resultCode") val resultCode: String,
    @Json(name = "message") val message: String,
    @Json(name = "errorDescription") val errorDescription: String,
    @Json(name = "resultObj") val resultObj: ResultObj,
    @Json(name = "systemTime") val systemTime: Long
) {
    @JsonClass(generateAdapter = true)
    data class ResultObj(
        @Json(name = "entitlementToken") val entitlementToken: String,
        @Json(name = "url") val url: String,
        @Json(name = "streamType") val streamType: String? = null, // e.g., "SDR_HD_DASHWV" or "HLS"
        @Json(name = "drmType") val drmType: String?,
        @Json(name = "laURL") val laURL: String?, // License URL (useful for checking)
        @Json(name = "channelId") val channelId: String? = null
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
    val ascendontoken: String,
    val entitlementtoken: String,
    val streamType: String?,
    val laURL: String?,
    // External audio (PRES/F1Live channel) for MergingMediaSource — populated by ChannelPlaybackActivity
    val externalAudioUri: Uri? = null,
    val externalAudioStreamType: String? = null,
    val externalAudioLaURL: String? = null,
    val externalAudioEntitlementtoken: String? = null,
    val externalAudioContentId: String? = null,
    val externalAudioChannelId: String? = null
) : Parcelable