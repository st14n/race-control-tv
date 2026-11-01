package fr.groggy.racecontrol.tv.update

import fr.groggy.racecontrol.tv.BuildConfig
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A GitHub release that can be installed over the running app. */
data class ReleaseInfo(
    val tag: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** Looks up the newest GitHub release (prereleases included) and picks the APK for this build. */
object UpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/st14n/race-control-tv/releases"
    private const val TIMEOUT_MS = 15_000

    /** Blocking; call off the main thread. Returns the newest release if it is newer than [currentVersion], else null. */
    fun fetchNewerRelease(
        currentVersion: String = BuildConfig.VERSION_NAME,
        variant: String = BuildConfig.UPDATE_VARIANT
    ): ReleaseInfo? {
        val release = parseLatest(fetchReleasesJson(), variant) ?: return null
        return release.takeIf { compareVersions(it.tag, currentVersion) > 0 }
    }

    private fun fetchReleasesJson(): String {
        val connection = URL("$RELEASES_URL?per_page=15").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "f1tv-player-updater")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** First (newest) non-draft release, prereleases included, that carries an APK for [variant]. */
    internal fun parseLatest(json: String, variant: String): ReleaseInfo? {
        val releases = JSONArray(json)
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name", "")
            if (tag.isBlank()) continue
            val assetsJson = release.optJSONArray("assets") ?: continue
            val assets = (0 until assetsJson.length()).map {
                val asset = assetsJson.getJSONObject(it)
                ReleaseAsset(
                    name = asset.optString("name", ""),
                    url = asset.optString("browser_download_url", ""),
                    size = asset.optLong("size", -1L)
                )
            }
            val chosen = pickAsset(assets, variant) ?: continue
            return ReleaseInfo(tag, chosen.name, chosen.url, chosen.size)
        }
        return null
    }

    /** The "-<variant>.apk" asset; when none matches but the release has exactly one APK, that one. */
    internal fun pickAsset(assets: List<ReleaseAsset>, variant: String): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.url.isNotBlank() }
        return apks.firstOrNull { it.name.endsWith("-$variant.apk", ignoreCase = true) }
            ?: apks.singleOrNull()
    }

    /** Numeric comparison of the dotted number in each version ("v1.0.10" > "1.0.9"). */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = numericParts(a)
        val pb = numericParts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0L }.compareTo(pb.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    private fun numericParts(version: String): List<Long> {
        val match = Regex("""\d+(\.\d+)*""").find(version) ?: return emptyList()
        return match.value.split('.').map { it.toLongOrNull() ?: 0L }
    }
}
