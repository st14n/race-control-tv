package fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr

import fr.groggy.racecontrol.tv.f1tv.F1TvViewing

object ProtectedHdrStreamClassifier {

    fun looksLikeHdrUhdWidevine(viewing: F1TvViewing): Boolean {
        val streamIdentity = buildString {
            append(viewing.url)
            append(' ')
            append(viewing.streamType.orEmpty())
            append(' ')
            append(viewing.requestedOverrideStreamType.orEmpty())
        }
        val looksUhdHdr = streamIdentity.contains("UHD", ignoreCase = true) ||
            streamIdentity.contains("HDR", ignoreCase = true)
        // Not the URL: its signed base64 segment contains "wv" by chance often enough to send the
        // unencrypted HDR_UHD_CMAF stream down the Widevine-only path
        val streamTypes = viewing.streamType.orEmpty() + " " + viewing.requestedOverrideStreamType.orEmpty()
        val looksWidevine = streamTypes.contains("WV", ignoreCase = true) ||
            !viewing.laURL.isNullOrBlank()
        return looksUhdHdr && looksWidevine
    }
}
