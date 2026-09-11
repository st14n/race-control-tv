package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri

/**
 * Stable key for signed F1 DASH resources.
 *
 * The manifest and the subsequent init request can select different CDN
 * authorities (for example ott-video-fl versus ott-video-cf) while retaining
 * the same signed resource path. The path/query identify the representation;
 * the CDN host does not.
 */
object F1DashUriKey {
    private val liveInitSequence = Regex("_[0-9]+_init\\.mp4$", RegexOption.IGNORE_CASE)
    private val stableAssetRoot = Regex("/out/v1/", RegexOption.IGNORE_CASE)

    fun of(value: String): String {
        val uri = Uri.parse(value)
        val rawPath = uri.encodedPath
        val path = rawPath?.let { pathValue ->
            // The manifest host and the resolved request can use different
            // CDN wrappers (pa_... versus hdntl=...). The asset path after
            // /out/v1/ is the stable representation identity; the wrapper
            // contains expiring authorization and must not be part of the key.
            val root = stableAssetRoot.find(pathValue)
            if (root != null) pathValue.substring(root.range.first) else pathValue
        }
        if (path.isNullOrEmpty()) return value
        // Live MPDs can advance the init/segment number between the manifest
        // parse and the actual init request. The representation identity is
        // the stable prefix; the sequence number is only a window position.
        val normalizedPath = path.replace(liveInitSequence, "_init.mp4")
        return buildString {
            append(normalizedPath)
        }
    }
}
