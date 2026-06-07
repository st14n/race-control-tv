package fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr

import android.util.Log
import fr.groggy.racecontrol.tv.f1tv.F1TvViewing

data class ProtectedHdrRendererDecision(
    val shouldUseProtectedRenderer: Boolean,
    val reason: String,
    val capabilities: ProtectedHdrCapabilities
)

object ProtectedHdrRendererRouter {

    private val TAG = ProtectedHdrRendererRouter::class.simpleName

    fun decide(viewing: F1TvViewing): ProtectedHdrRendererDecision {
        val capabilities = ProtectedHdrCapabilitiesProbe.probe()
        val looksLikeProtectedHdr = ProtectedHdrStreamClassifier.looksLikeHdrUhdWidevine(viewing)
        val reason = when {
            !looksLikeProtectedHdr ->
                "stream is not UHD/HDR Widevine"
            else ->
                "using official-like bare Surface HDR fragment to isolate pipeline"
        }

        val decision = ProtectedHdrRendererDecision(
            shouldUseProtectedRenderer = looksLikeProtectedHdr,
            reason = reason,
            capabilities = capabilities
        )
        Log.i(
            TAG,
            "Protected HDR renderer decision " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                "shouldUseProtectedRenderer=${decision.shouldUseProtectedRenderer} reason=$reason"
        )
        return decision
    }
}
