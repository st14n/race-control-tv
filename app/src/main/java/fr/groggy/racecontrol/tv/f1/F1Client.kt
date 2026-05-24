package fr.groggy.racecontrol.tv.f1

import android.net.Uri
import android.util.Log
import com.auth0.android.jwt.JWT
import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing
import fr.groggy.racecontrol.tv.f1tv.F1TvViewingResponse
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

    companion object {
        private val TAG = F1Client::class.simpleName
        const val API_KEY = "fCUCjWrKPu9ylJwRAv8BpGLEgiAuThx7"
        private const val PLAY_URL = "https://f1tv.formula1.com/2.0/R/ENG/%s/ALL/CONTENT/PLAY?contentId=%s"

        fun buildWidevineUrl(platform: String, contentId: String, channelId: String?): String {
            return "https://f1tv.formula1.com/2.0/R/ENG/$platform/ALL/CONTENT/PLAY/WIDEVINE?contentId=$contentId" +
                (if (channelId != null) "&channelId=$channelId" else "")
        }
    }

    private val viewingResponseJsonAdapter = moshi.adapter(F1TvViewingResponse::class.java)

    suspend fun getViewing(
        channelId: String?,
        contentId: String,
        requestedStreamType: Settings.StreamType,
        token: JWT
    ): F1TvViewing {
        var lastError: Exception? = null
        for (platform in requestedPlatforms(requestedStreamType)) {
            try {
                val url = PLAY_URL.format(platform, contentId) +
                    (if (channelId != null) "&channelId=$channelId" else "")

                Log.i(TAG, "Requesting viewing details from: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("apiKey", API_KEY)
                    .header("User-Agent", BuildConfig.DEFAULT_USER_AGENT)
                    .header("ascendontoken", token.toString())
                    .header("Origin", "https://f1tv.formula1.com")
                    .header("Referer", "https://f1tv.formula1.com/")
                    .build()

                Log.d(TAG, "Executing request to get viewing details for platform=$platform...")
                val responsePeek = request.execute(httpClient)
                val responseBodyString = try { responsePeek.peekBody(Long.MAX_VALUE).string() } catch (e: Exception) { "Error peeking body" }
                Log.d(TAG, "Raw viewing response body: $responseBodyString")

                val response = responsePeek.parseJsonBody(viewingResponseJsonAdapter)
                Log.i(TAG, "Received viewing response. Platform=$platform URL=${response.resultObj.url}, Actual StreamType=${response.resultObj.streamType}, LA_URL=${response.resultObj.laURL}")

                return F1TvViewing(
                    url = Uri.parse(response.resultObj.url),
                    contentId = contentId,
                    channelId = response.resultObj.channelId ?: channelId,
                    platform = platform,
                    ascendontoken = token.toString(),
                    entitlementtoken = response.resultObj.entitlementToken,
                    streamType = response.resultObj.streamType,
                    laURL = response.resultObj.laURL
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Viewing request failed for platform=${platform}: ${e.message}")
            }
        }

        throw lastError ?: IllegalStateException("Unable to fetch viewing")
    }
    private fun requestedPlatforms(streamType: Settings.StreamType): List<String> {
        return when (streamType) {
            Settings.StreamType.DASH -> listOf("BIG_SCREEN_DASH", "WEB_DASH", "BIG_SCREEN_HLS")
            Settings.StreamType.DASH_HLS -> listOf("WEB_DASH", "BIG_SCREEN_DASH", "BIG_SCREEN_HLS")
            Settings.StreamType.HLS -> listOf("BIG_SCREEN_HLS")
        }
    }
}
