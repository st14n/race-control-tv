package fr.groggy.racecontrol.tv.f1

import android.net.Uri
import android.util.Log
import com.auth0.android.jwt.JWT
import com.squareup.moshi.Moshi
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.f1tv.F1TvEntitlementResponse
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
        val player: String,
        val profile: String,
        val overrideStreamType: String? = null
    )

    companion object {
        private val TAG = F1Client::class.simpleName
        const val API_KEY = "CWkfako6ppvmpxVOaBwCeGZfiY1Kpi1w"
        const val PLAY_API_V2 = "2.0"
        const val PLAY_API_V3 = "3.0"
        private const val TV_PLATFORM = "BIG_SCREEN_HLS"
        private const val TILED_MEDIA_PLAYER = "player_tm"
        private const val BITMOVIN_PLAYER = "player_bm"
        private const val PLAY_URL = "https://f1tv.formula1.com/%s/R/ENG/%s/ALL/CONTENT/PLAY?contentId=%s&player=%s"
        private const val ENTITLEMENT_URL = "https://f1tv.formula1.com/2.0/R/ENG/BIG_SCREEN_HLS/ALL/USER/ENTITLEMENT"
        private const val CD_DEVICE_TYPE = "49"
        private const val CD_DISTRIBUTION_CHANNEL = "e827264e-a81b-4f65-a567-2070850c128e"

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
    private val entitlementResponseJsonAdapter = moshi.adapter(F1TvEntitlementResponse::class.java)

    @Volatile
    private var cachedEntitlementToken: String? = null

    suspend fun getViewing(
        channelId: String?,
        contentId: String,
        requestedStreamType: Settings.StreamType,
        token: JWT,
        preferHdrManifest: Boolean = true
    ): F1TvViewing {
        var lastError: Exception? = null
        var fallbackViewing: F1TvViewing? = null
        val entitlementToken = loadEntitlementToken(token)
        for (candidate in requestedCandidates(requestedStreamType, preferHdrManifest)) {
            for (playApiVersion in requestedPlayApiVersions()) {
                if (playApiVersion != candidate.playApiVersion) {
                    continue
                }
                try {
                    val url = PLAY_URL.format(playApiVersion, candidate.platform, contentId, candidate.player) +
                        (if (channelId != null) "&channelId=$channelId" else "")

                    Log.i(
                        TAG,
                        "Requesting viewing details " +
                            "profile=${candidate.profile} api=$playApiVersion platform=${candidate.platform} " +
                            "player=${candidate.player} deviceInfo=${candidate.deviceInfo} " +
                            "contentId=$contentId channelId=$channelId url=$url"
                    )

                    val requestBuilder = Request.Builder()
                        .url(url)
                        .get()
                        .header("User-Agent", DeviceInfo.userAgent)
                        .header("Origin", "https://f1tv.formula1.com")
                        .header("Referer", "https://f1tv.formula1.com/")
                        .header("x-f1-device-info", candidate.deviceInfo)
                        .header("ascendonToken", token.toString())
                        .header("entitlementToken", entitlementToken.orEmpty())
                        .header("CD-DeviceType", CD_DEVICE_TYPE)
                        .header("CD-DistributionChannel", CD_DISTRIBUTION_CHANNEL)

                    candidate.overrideStreamType?.let {
                        requestBuilder.header("x-f1-override-video-stream", it)
                    }

                    val request = requestBuilder.build()

                    Log.d(TAG, "Executing request to get viewing details for profile=${candidate.profile} api=$playApiVersion platform=${candidate.platform} player=${candidate.player}...")
                    val response = request.execute(httpClient).parseJsonBody(viewingResponseJsonAdapter)
                    Log.i(
                        TAG,
                        "Received viewing response " +
                            "profile=${candidate.profile} api=$playApiVersion platform=${candidate.platform} " +
                            "player=${candidate.player} " +
                            "resultCode=${response.resultCode} actualPlayApi=${response.resultObj.playApiVersion} " +
                            "actualChannelId=${response.resultObj.channelId} actualStreamType=${response.resultObj.streamType} " +
                            "overrideStreamType=${candidate.overrideStreamType} drmType=${response.resultObj.drmType} " +
                            "tmePresent=${response.resultObj.tme != null} laUrl=${response.resultObj.laURL} " +
                            "manifestUrl=${response.resultObj.url}"
                    )

                    if (response.resultCode.uppercase() != "OK") {
                        throw IllegalStateException("F1TV API error for profile=${candidate.profile} api=$playApiVersion platform=${candidate.platform}: ${response.resultCode} - ${response.errorDescription}")
                    }

                    val drmType = response.resultObj.drmType?.lowercase()
                    val unsupportedDrm = drmType != null && drmType != "widevine"
                    if (unsupportedDrm) {
                        throw UnsupportedOperationException(
                            "Unsupported DRM for Android playback: drmType=${response.resultObj.drmType} " +
                                "streamType=${response.resultObj.streamType} profile=${candidate.profile} " +
                                "platform=${candidate.platform}"
                        )
                    }

                    val manifestUrl = response.resultObj.url.orEmpty()
                    if (manifestUrl.isBlank()) {
                        throw UnsupportedOperationException(
                            "Content play returned no manifest URL for ExoPlayer " +
                                "profile=${candidate.profile} player=${candidate.player} " +
                                "streamType=${response.resultObj.streamType} tmePresent=${response.resultObj.tme != null}"
                        )
                    }

                    if (candidate.player == TILED_MEDIA_PLAYER && response.resultObj.tme != null) {
                        throw UnsupportedOperationException(
                            "Tiled-media TME response is not playable by ExoPlayer " +
                                "streamType=${response.resultObj.streamType} manifestUrl=$manifestUrl"
                        )
                    }

                    val viewing = F1TvViewing(
                        url = Uri.parse(manifestUrl),
                        contentId = contentId,
                        channelId = response.resultObj.channelId ?: channelId,
                        platform = candidate.platform,
                        playApiVersion = response.resultObj.playApiVersion ?: playApiVersion,
                        ascendontoken = token.toString(),
                        entitlementtoken = response.resultObj.entitlementToken ?: entitlementToken.orEmpty(),
                        streamType = response.resultObj.streamType,
                        requestedOverrideStreamType = candidate.overrideStreamType,
                        laURL = response.resultObj.laURL
                    )

                    val acceptedHdrCandidate = isAcceptedHdrCandidate(
                        candidate = candidate,
                        streamType = response.resultObj.streamType,
                        drmType = drmType,
                        laURL = response.resultObj.laURL
                    )
                    if (preferHdrManifest && !acceptedHdrCandidate) {
                        // Only keep true standard responses as the last-resort fallback.
                        // Rejected HDR override probes must not become the final viewing,
                        // otherwise a mismatched SDR response can short-circuit the rest
                        // of the HDR candidate search.
                        if (candidate.overrideStreamType == null && fallbackViewing == null) {
                            fallbackViewing = viewing
                        }
                        Log.i(
                            TAG,
                            "Candidate rejected for HDR playback streamType=${response.resultObj.streamType}; " +
                                "overrideStreamType=${candidate.overrideStreamType} drmType=${response.resultObj.drmType} " +
                                "laUrlPresent=${!response.resultObj.laURL.isNullOrBlank()}; " +
                                "keepingAsFallback=${candidate.overrideStreamType == null}; " +
                                "continuing search for a Widevine UHD variant"
                        )
                        continue
                    }

                    return viewing
                } catch (e: Exception) {
                    lastError = e
                    Log.w(
                        TAG,
                        "Viewing request failed for profile=${candidate.profile} api=$playApiVersion " +
                            "platform=${candidate.platform} contentId=$contentId channelId=$channelId: ${e.message}"
                    )
                }
            }
        }

        return fallbackViewing ?: throw lastError ?: IllegalStateException("Unable to fetch viewing")
    }

    private suspend fun loadEntitlementToken(token: JWT): String? {
        cachedEntitlementToken?.takeIf { it.isNotBlank() }?.let { return it }

        return try {
            val request = Request.Builder()
                .url(ENTITLEMENT_URL)
                .get()
                .header("User-Agent", DeviceInfo.userAgent)
                .header("Origin", "https://f1tv.formula1.com")
                .header("Referer", "https://f1tv.formula1.com/")
                .header("x-f1-device-info", DeviceInfo.f1DeviceInfo)
                .header("ascendonToken", token.toString())
                .header("CD-DeviceType", CD_DEVICE_TYPE)
                .header("CD-DistributionChannel", CD_DISTRIBUTION_CHANNEL)
                .build()

            Log.d(TAG, "Loading entitlement token from official TV entitlement endpoint")
            val response = request.execute(httpClient).parseJsonBody(entitlementResponseJsonAdapter)
            val entitlementToken = response.token().orEmpty()
            Log.i(TAG, "Entitlement response resultCode=${response.resultCode} tokenPresent=${entitlementToken.isNotBlank()}")
            if (response.resultCode?.uppercase() != "OK" || entitlementToken.isBlank()) {
                null
            } else {
                cachedEntitlementToken = entitlementToken
                entitlementToken
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load entitlement token: ${e.message}")
            null
        }
    }

    private fun requestedPlayApiVersions(): List<String> = listOf(PLAY_API_V3, PLAY_API_V2)

    private fun requestedCandidates(
        streamType: Settings.StreamType,
        preferHdrManifest: Boolean
    ): List<PlayRequestCandidate> {
        val standardCandidates = when (streamType) {
            Settings.StreamType.DASH -> listOf(
                PlayRequestCandidate(PLAY_API_V3, TV_PLATFORM, DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_bitmovin"),
                PlayRequestCandidate(PLAY_API_V3, "WEB_DASH", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_web_dash"),
                PlayRequestCandidate(PLAY_API_V3, "WEB_HLS", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_web_hls"),
                PlayRequestCandidate(PLAY_API_V2, "BIG_SCREEN_DASH", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_dash_v2"),
                PlayRequestCandidate(PLAY_API_V2, "WEB_DASH", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_web_dash_v2"),
                PlayRequestCandidate(PLAY_API_V2, TV_PLATFORM, DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_hls_v2")
            )
            Settings.StreamType.DASH_HLS -> listOf(
                PlayRequestCandidate(PLAY_API_V3, TV_PLATFORM, DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_bitmovin"),
                PlayRequestCandidate(PLAY_API_V3, "WEB_HLS", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_web_hls"),
                PlayRequestCandidate(PLAY_API_V3, "WEB_DASH", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_web_dash"),
                PlayRequestCandidate(PLAY_API_V2, "WEB_DASH", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_web_dash_v2"),
                PlayRequestCandidate(PLAY_API_V2, "BIG_SCREEN_DASH", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_dash_v2"),
                PlayRequestCandidate(PLAY_API_V2, TV_PLATFORM, DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_hls_v2")
            )
            Settings.StreamType.HLS -> listOf(
                // Regular Android TV BIG_SCREEN_HLS can currently return SDR_HD_DASHWV_SINGLE.
                // For explicit HLS fallback/companion audio requests, prefer WEB_HLS first.
                PlayRequestCandidate(PLAY_API_V3, "WEB_HLS", DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_web_hls"),
                PlayRequestCandidate(PLAY_API_V3, TV_PLATFORM, DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_bitmovin"),
                PlayRequestCandidate(PLAY_API_V2, TV_PLATFORM, DeviceInfo.f1DeviceInfo, BITMOVIN_PLAYER, "android_tv_hls_v2")
            )
        }

        if (!preferHdrManifest) {
            return standardCandidates
        }

        val hdrCandidates = hdrOverrideStreamTypes(streamType).flatMap { overrideStreamType ->
            listOf(
                PlayRequestCandidate(
                    PLAY_API_V3,
                    TV_PLATFORM,
                    DeviceInfo.f1DeviceInfo,
                    BITMOVIN_PLAYER,
                    "android_tv_bitmovin_$overrideStreamType",
                    overrideStreamType
                ),
                PlayRequestCandidate(
                    PLAY_API_V3,
                    "WEB_HLS",
                    DeviceInfo.f1DeviceInfo,
                    BITMOVIN_PLAYER,
                    "android_tv_web_hls_$overrideStreamType",
                    overrideStreamType
                ),
                PlayRequestCandidate(
                    PLAY_API_V3,
                    "WEB_DASH",
                    DeviceInfo.f1DeviceInfo,
                    BITMOVIN_PLAYER,
                    "android_tv_web_dash_$overrideStreamType",
                    overrideStreamType
                )
            )
        } + listOf(
            PlayRequestCandidate(PLAY_API_V3, TV_PLATFORM, DeviceInfo.f1DeviceInfo, TILED_MEDIA_PLAYER, "android_tv_tiled_probe")
        )

        Log.i(
            TAG,
            "HDR manifest preference requested; probing DASH Widevine HDR before HLS CMAF Widevine HDR"
        )

        return hdrCandidates + standardCandidates
    }

    private fun requestedPlatforms(streamType: Settings.StreamType): List<String> {
        return when (streamType) {
            Settings.StreamType.DASH -> listOf(TV_PLATFORM)
            Settings.StreamType.DASH_HLS -> listOf(TV_PLATFORM)
            Settings.StreamType.HLS -> listOf(TV_PLATFORM)
        }
    }

    private fun isAcceptedHdrCandidate(
        candidate: PlayRequestCandidate,
        streamType: String?,
        drmType: String?,
        laURL: String?
    ): Boolean {
        val overrideLooksHdr = looksLikeUhdOrHdr(candidate.overrideStreamType)
        if (!overrideLooksHdr && !looksLikeUhdOrHdr(streamType)) {
            return false
        }

        // Do not accept the non-WV HDR_UHD_CMAF variants for the internal player.
        // In the logs these returned drmType=null/laURL=null and the CDN playlist
        // immediately 403'd. The Exo-compatible path is the Widevine variant.
        val override = candidate.overrideStreamType.orEmpty().uppercase()
        val returned = streamType.orEmpty().uppercase()
        val isCmafHls = "CMAF" in override || "CMAF" in returned
        if (isCmafHls) {
            return ("WV" in override || "WV" in returned) &&
                drmType == "widevine" &&
                !laURL.isNullOrBlank()
        }

        // DASH HDR must also be Widevine for Android TV playback.
        val isDash = "DASH" in override || "DASH" in returned
        if (isDash) {
            return drmType == "widevine" && !laURL.isNullOrBlank()
        }

        return false
    }

    private fun looksLikeUhdOrHdr(streamType: String?): Boolean {
        val normalized = streamType?.uppercase() ?: return false
        return "UHD" in normalized || "2160" in normalized || "HDR" in normalized
    }

    private fun hdrOverrideStreamTypes(streamType: Settings.StreamType): List<String> {
        val dashWidevine = listOf(
            "HDR_UHD_DASHWV",
            "HDR_UHD_DASHWV_SINGLE",
            "HDR_UHD_DASH",
            "HDR_UHD_DASH_SINGLE"
        )
        val hlsWidevine = listOf(
            // WV first/only for the internal player. Plain HDR_UHD_CMAF returned
            // drmType=null/laURL=null and 403s on the CDN playlist.
            "HDR_UHD_CMAFWV",
            "HDR_UHD_CMAFWV_SINGLE"
        )
        return when (streamType) {
            Settings.StreamType.DASH -> dashWidevine + hlsWidevine
            Settings.StreamType.DASH_HLS -> dashWidevine + hlsWidevine
            // Media3's DASH Widevine path is the next HDR presentation hypothesis after
            // HLS CMAF-WV still rendered green on Google TV Streamer.
            Settings.StreamType.HLS -> dashWidevine + hlsWidevine
        }
    }
}
