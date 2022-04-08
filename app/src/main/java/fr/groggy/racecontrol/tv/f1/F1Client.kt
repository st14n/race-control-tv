package fr.groggy.racecontrol.tv.f1

import android.net.Uri
import com.auth0.android.jwt.JWT
import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
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
    private val settingsRepository: SettingsRepository,
    moshi: Moshi
) {

    companion object {
        const val API_KEY = "fCUCjWrKPu9ylJwRAv8BpGLEgiAuThx7"

        private const val PLAY_URL = "https://f1tv.formula1.com/1.0/R/ENG/BIG_SCREEN_%s/ALL/CONTENT/PLAY?contentId=%s"
    }

    private val viewingResponseJsonAdapter = moshi.adapter(F1TvViewingResponse::class.java)

    suspend fun getViewing(
        channelId: String?,
        contentId: String,
        token: JWT
    ): F1TvViewing {
        val streamType = settingsRepository.getCurrent().streamType

        val request = Request.Builder()
            .url(PLAY_URL.format(streamType.name, contentId) + if (channelId != null) "&channelId=$channelId" else "")
            .get()
            .header("apiKey", API_KEY)
            .header("User-Agent", BuildConfig.DEFAULT_USER_AGENT)
            .header("ascendontoken", token.toString())
            .build()
        val response = request.execute(httpClient).parseJsonBody(viewingResponseJsonAdapter)
        return F1TvViewing(
            url = Uri.parse(response.resultObj.url)
        )
    }
}
