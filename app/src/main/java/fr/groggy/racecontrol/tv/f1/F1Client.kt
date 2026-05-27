package fr.groggy.racecontrol.tv.f1

import android.net.Uri
import android.util.Log
import com.auth0.android.jwt.JWT
import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.f1tv.F1TvViewingResponse
import fr.groggy.racecontrol.tv.utils.DeviceInfo
import fr.groggy.racecontrol.tv.utils.http.execute
import fr.groggy.racecontrol.tv.utils.http.parseJsonBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class F1Client @Inject constructor(
    private val httpClient: OkHttpClient,
    moshi: Moshi
) {

    private data class PlayRequestCandidate(
        val playApiVersion: String,
        val platform: String,
        val deviceInfo: String,
        val profile: String
    )

    companion object {
        private val TAG = F1Client::class.simpleName
        const val API_KEY = "fCUCjWrKPu9ylJwRAv8BpGLEgiAuThx7"
        const val PLAY_API_V2 = "2.0"
        const val PLAY_API_V3 = "3.0"
        private const val PLAY_URL = "https://f1tv.formula1.com/%s/R/ENG/%s/ALL/CONTENT/PLAY?contentId=%s"

        fun buildWidevineUrl(
            playApiVersion: String,
            platform: String,
            contentId: String,
            channelId: String?
        ): String {
            return "https://f1tv.formula1.com/$playApiVersion/R/ENG/$platform/ALL/CONTENT/PLAY/WIDEVINE?contentId=$contentId" +
                (if (channelId != null) "&channelId=$channelId" else "")
        }
    }

    private val viewingResponseJsonAdapter = moshi.adapter(F1TvViewingResponse::class.java)

    suspend fun getViewing(
        channelId: String?,
        contentId: String,
        requestedStreamType: Settings.StreamType,
        token: JWT,
        preferHdrManifest: Boolean = true
    ): F1TvViewing {
        var lastError: Exception? = null
        for (candidate in requestedCandidates(requestedStreamType, preferHdrManifest)) {
            for (playApiVersion in requestedPlayApiVersions()) {
                if (playApiVersion != candidate.playApiVersion) {
                    continue
                }
                try {
                    val url = PLAY_URL.format(playApiVersion, candidate.platform, contentId) +
                        (if (channelId != null) "&channelId=$channelId" else "")

                    Log.i(TAG, "Requesting viewing details from: $url")

                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .header("apiKey", API_KEY)
                        .header("User-Agent", DeviceInfo.userAgent)
                        .header("x-f1-device-info", candidate.deviceInfo)
                        .header("ascendontoken", token.toString())
                        .header("Origin", "https://f1tv.formula1.com")
                        .header("Referer", "https://f1tv.formula1.com/")
                        .build()

                    Log.d(TAG, "Executing request to get viewing details for profile=${candidate.profile} api=$playApiVersion platform=${candidate.platform}...")
                    val responsePeek = request.execute(httpClient)
                    val responseBodyString = try { responsePeek.peekBody(Long.MAX_VALUE).string() } catch (e: Exception) { "Error peeking body" }
                    Log.d(TAG, "Raw viewing response body: $responseBodyString")

                    val response = responsePeek.parseJsonBody(viewingResponseJsonAdapter)
                    Log.i(TAG, "Received viewing response. Profile=${candidate.profile} Api=$playApiVersion Platform=${candidate.platform} resultCode=${response.resultCode} URL=${response.resultObj.url}, Actual StreamType=${response.resultObj.streamType}, LA_URL=${response.resultObj.laURL}")

                    if (response.resultCode.uppercase() != "OK") {
                        throw IllegalStateException("F1TV API error for profile=${candidate.profile} api=$playApiVersion platform=${candidate.platform}: ${response.resultCode} — ${response.errorDescription}")
                    }

                    return F1TvViewing(
                        url = Uri.parse(response.resultObj.url),
                        contentId = contentId,
                        channelId = response.resultObj.channelId ?: channelId,
                        platform = candidate.platform,
                        playApiVersion = response.resultObj.playApiVersion ?: playApiVersion,
                        ascendontoken = token.toString(),
                        entitlementtoken = response.resultObj.entitlementToken,
                        streamType = response.resultObj.streamType,
                        laURL = response.resultObj.laURL
                    )
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Viewing request failed for profile=${candidate.profile} api=$playApiVersion platform=${candidate.platform}: ${e.message}")
                }
            }
        }

        throw lastError ?: IllegalStateException("Unable to fetch viewing")
    }

    private fun requestedPlayApiVersions(): List<String> = listOf(PLAY_API_V3, PLAY_API_V2)

    private fun requestedCandidates(
        streamType: Settings.StreamType,
        preferHdrManifest: Boolean
    ): List<PlayRequestCandidate> {
        val standardCandidates = when (streamType) {
            Settings.StreamType.DASH -> listOf(
                PlayRequestCandidate(PLAY_API_V3, "BIG_SCREEN_DASH", DeviceInfo.f1DeviceInfo, "android_tv"),
                PlayRequestCandidate(PLAY_API_V2, "BIG_SCREEN_DASH", DeviceInfo.f1DeviceInfo, "android_tv"),
                PlayRequestCandidate(PLAY_API_V2, "WEB_DASH", DeviceInfo.f1DeviceInfo, "android_tv"),
                PlayRequestCandidate(PLAY_API_V2, "BIG_SCREEN_HLS", DeviceInfo.f1DeviceInfo, "android_tv")
            )
            Settings.StreamType.DASH_HLS -> listOf(
                PlayRequestCandidate(PLAY_API_V3, "BIG_SCREEN_DASH", DeviceInfo.f1DeviceInfo, "android_tv"),
                PlayRequestCandidate(PLAY_API_V2, "WEB_DASH", DeviceInfo.f1DeviceInfo, "android_tv"),
                PlayRequestCandidate(PLAY_API_V2, "BIG_SCREEN_DASH", DeviceInfo.f1DeviceInfo, "android_tv"),
                PlayRequestCandidate(PLAY_API_V2, "BIG_SCREEN_HLS", DeviceInfo.f1DeviceInfo, "android_tv")
            )
            Settings.StreamType.HLS -> listOf(
                PlayRequestCandidate(PLAY_API_V2, "BIG_SCREEN_HLS", DeviceInfo.f1DeviceInfo, "android_tv")
            )
        }

        if (!preferHdrManifest) {
            return standardCandidates
        }

        val hdrCandidate = PlayRequestCandidate(
            playApiVersion = PLAY_API_V3,
            platform = "WEB_HLS",
            deviceInfo = DeviceInfo.tvOsDeviceInfo,
            profile = "tvos"
        )

        return listOf(hdrCandidate) + standardCandidates
    }

    private fun requestedPlatforms(streamType: Settings.StreamType): List<String> {
        return when (streamType) {
            Settings.StreamType.DASH -> listOf("BIG_SCREEN_DASH", "WEB_DASH", "BIG_SCREEN_HLS")
            Settings.StreamType.DASH_HLS -> listOf("WEB_DASH", "BIG_SCREEN_DASH", "BIG_SCREEN_HLS")
            Settings.StreamType.HLS -> listOf("BIG_SCREEN_HLS")
        }
    }
}
