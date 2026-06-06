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
    val externalAudioRequired: Boolean = false
) : Parcelable
