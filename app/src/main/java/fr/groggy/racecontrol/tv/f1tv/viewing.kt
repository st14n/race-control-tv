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
    @Json(name = "resultObj") val resultObj: ResultObj
) {
    @JsonClass(generateAdapter = true)
    data class ResultObj(
        @Json(name = "entitlementToken") val entitlementToken: String,
        @Json(name = "url") val url: String,
        // Add this field to capture the stream type from the JSON response
        @Json(name = "streamType") val streamType: String? = null, // e.g., "SDR_HD_DASHWV" or "HLS"
        @Json(name = "laURL") val laURL: String?, // License URL (useful for checking)
        @Json(name = "channelId") val channelId: String? = null // Channel ID if applicable
        // Add other fields from response if needed
    )
}

@JsonClass(generateAdapter = true)
data class F1TvViewingResponseResultObject(
    val url: String,
    val entitlementToken: String
)

@Parcelize // Make it Parcelable
data class F1TvViewing(
    val url: Uri, // The .mpd or .m3u8 URL
    val contentId: String,
    val channelId: String?,
    val ascendontoken: String, // Auth token (needed for DRM license request header)
    val entitlementtoken: String, // Entitlement token (needed for DRM license request header)
    val streamType: String?, // Actual stream type (e.g., "SDR_HD_DASHWV")
    val laURL: String? // <<< ADDED: License Acquisition URL from API
) : Parcelable // Implement Parcelable