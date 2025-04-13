package fr.groggy.racecontrol.tv.f1

import android.net.Uri
import android.util.Log
import com.auth0.android.jwt.JWT
import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.core.settings.Settings // Keep this import
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
        // v2.0 endpoint path
        private const val PLAY_URL = "https://f1tv.formula1.com/2.0/R/ENG/WEB_HLS/ALL/CONTENT/PLAY?contentId=%s"
        // Fallback DRM URL format - PREFER laURL from response if available
        const val DRM_URL = "https://f1tv.formula1.com/2.0/R/ENG/WEB_HLS/ALL/CONTENT/PLAY/WIDEVINE?contentId=%s"
    }

    private val viewingResponseJsonAdapter = moshi.adapter(F1TvViewingResponse::class.java)

    suspend fun getViewing(
        channelId: String?,
        contentId: String,
        requestedStreamType: Settings.StreamType, // Original preference, less relevant now
        token: JWT
    ): F1TvViewing {

        // *** ALWAYS EXPLICITLY REQUEST HLS STREAM TYPE ***
//        val explicitStreamTypeParam = "&streamType=HLS"

        // Construct URL WITHOUT player_tm // but WITH explicit streamType=HLS
        val url = PLAY_URL.format(contentId) +
                (if (channelId != null) "&channelId=$channelId" else "")
                //+ explicitStreamTypeParam

        // Log the exact URL being requested
        Log.i(TAG, "Requesting viewing details (Explicit type 'HLS') from: $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("apiKey", API_KEY)
            .header("User-Agent", BuildConfig.DEFAULT_USER_AGENT)
            .header("ascendontoken", token.toString())
            .header("Origin", "https://f1tv.formula1.com")
            .header("Referer", "https://f1tv.formula1.com/")
            .build()

        Log.d(TAG, "Executing request to get viewing details...")
        val responsePeek = request.execute(httpClient)
        val responseBodyString = try { responsePeek.peekBody(Long.MAX_VALUE).string() } catch (e: Exception) { "Error peeking body" }
        Log.d(TAG, "Raw viewing response body: $responseBodyString")

        val response = responsePeek.parseJsonBody(viewingResponseJsonAdapter)
        // Log the actual type returned by the API, even though we requested HLS
        Log.i(TAG, "Received viewing response. URL: ${response.resultObj.url}, Actual StreamType: ${response.resultObj.streamType}, LA_URL: ${response.resultObj.laURL}")

        // Create F1TvViewing object with whatever the API returned
        return F1TvViewing(
            url = Uri.parse(response.resultObj.url), // Hopefully a valid .m3u8 now for Practice sessions
            contentId = contentId,
            channelId = response.resultObj.channelId ?: channelId,
            ascendontoken = token.toString(),
            entitlementtoken = response.resultObj.entitlementToken,
            streamType = response.resultObj.streamType, // Store the actual type returned
            laURL = response.resultObj.laURL // Store laURL if provided
        )
    }
}
