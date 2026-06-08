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
            capabilities.canCreateProtectedHlgEglSurface ->
                "trying Media3 protected HLG video graph; EGL preflight says protected HLG is available"
            else ->
                "trying Media3 HLG video graph despite EGL preflight=false; provider will fall back and log exact EGL failures"
        }

        val decision = ProtectedHdrRendererDecision(
            shouldUseProtectedRenderer = looksLikeProtectedHdr && capabilities.canCreateProtectedHlgEglSurface,
            reason = reason,
            capabilities = capabilities
        )
        Log.i(
            TAG,
            "Protected HDR renderer decision " +
                "streamType=${viewing.streamType} requestedOverride=${viewing.requestedOverrideStreamType} " +
                "shouldUseProtectedRenderer=${decision.shouldUseProtectedRenderer} " +
                "protectedContent=${capabilities.hasProtectedContent} " +
                "bt2020Hlg=${capabilities.hasBt2020HlgColorSpace} " +
                "canCreateProtectedHlg=${capabilities.canCreateProtectedHlgEglSurface} " +
                "reason=$reason"
        )
        return decision
    }
}
