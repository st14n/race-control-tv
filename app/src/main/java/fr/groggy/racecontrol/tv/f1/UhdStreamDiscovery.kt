package fr.groggy.racecontrol.tv.f1

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds F1's unencrypted 4K stream also when its name changes.
 *
 * Today it is `HDR_UHD_CMAF` (what F1's own TV app plays). Should F1 rename or replace it, the app
 * tries the names F1's scheme allows (`HDR|SDR` x `UHD` x `CMAF|HLS|DASH`, with and without `_SINGLE`),
 * keeps the first whose manifest really is unencrypted 4K/HDR, and tries that one first from then on.
 * After a full round without result the long list waits a few hours, so playback does not keep paying
 * for requests that find nothing.
 */
@Singleton
class UhdStreamDiscovery @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("uhd_stream_discovery", Context.MODE_PRIVATE)

    /** Stream types to ask for, most likely first. */
    fun candidates(): List<String> {
        val remembered = prefs.getString(KEY_WINNER, null)
        val known = listOf(KNOWN)
        val explore = if (SystemClock.elapsedRealtime() < exploreAfterMs) emptyList() else GENERATED
        return (listOfNotNull(remembered) + known + explore).distinct()
    }

    /** [streamType] gave a working unencrypted UHD stream: ask for it first next time. */
    fun remember(streamType: String) {
        exploreAfterMs = 0L
        if (prefs.getString(KEY_WINNER, null) == streamType) return
        Log.i(TAG, "Unencrypted UHD stream type in use: $streamType")
        prefs.edit { putString(KEY_WINNER, streamType) }
    }

    /** None of the candidates worked: leave the long list alone for a while. */
    fun nothingFound() {
        exploreAfterMs = SystemClock.elapsedRealtime() + EXPLORE_PAUSE_MS
    }

    fun isCandidate(streamType: String?): Boolean = streamType != null && streamType in candidates()

    /**
     * Whether [manifestUrl] is an unencrypted stream with a 4K or HDR rendition: HLS without a key,
     * DASH without ContentProtection. Reads at most the first 512 KB.
     */
    suspend fun isClearUhdManifest(httpClient: OkHttpClient, manifestUrl: String, userAgent: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(manifestUrl)
                    .header("User-Agent", userAgent)
                    .header("Origin", "https://f1tv.formula1.com")
                    .header("Referer", "https://f1tv.formula1.com/")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching false
                    val source = response.body?.source() ?: return@runCatching false
                    source.request(MAX_MANIFEST_BYTES)
                    val text = source.buffer.snapshot().utf8()
                    looksClearUhd(text)
                }
            }.onFailure { Log.w(TAG, "Checking $manifestUrl failed: ${it.message}") }.getOrDefault(false)
        }

    companion object {
        private val TAG = UhdStreamDiscovery::class.simpleName
        private const val KEY_WINNER = "winner"
        private const val KNOWN = "HDR_UHD_CMAF"
        private const val EXPLORE_PAUSE_MS = 6 * 60 * 60 * 1000L
        private const val MAX_MANIFEST_BYTES = 512L * 1024L
        @Volatile private var exploreAfterMs = 0L

        /** The names F1's stream types are made of; HLS/CMAF first (the DASH ones need init repairs). */
        val GENERATED: List<String> = buildList {
            for (format in listOf("CMAF", "HLS", "DASH")) {
                for (range in listOf("HDR", "SDR")) {
                    for (suffix in listOf("", "_SINGLE")) add("${range}_UHD_$format$suffix")
                }
            }
        }

        fun looksClearUhd(manifest: String): Boolean {
            val text = manifest.trimStart()
            return if (text.startsWith("#EXTM3U")) {
                val encrypted = Regex("""#EXT-X-(SESSION-)?KEY:[^\n]*METHOD=(?!NONE)""").containsMatchIn(text)
                val heights = Regex("""RESOLUTION=\d+x(\d+)""").findAll(text).map { it.groupValues[1].toInt() }
                val hdr = Regex("""VIDEO-RANGE=(HLG|PQ)""").containsMatchIn(text)
                !encrypted && (heights.any { it >= 2160 } || hdr)
            } else if (text.contains("<MPD")) {
                val encrypted = text.contains("<ContentProtection")
                val heights = Regex("""height="(\d+)"""").findAll(text).map { it.groupValues[1].toInt() }
                !encrypted && heights.any { it >= 2160 }
            } else {
                false
            }
        }
    }
}
