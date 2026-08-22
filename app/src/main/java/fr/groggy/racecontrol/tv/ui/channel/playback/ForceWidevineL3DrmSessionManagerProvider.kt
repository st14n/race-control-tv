package fr.groggy.racecontrol.tv.ui.channel.playback

import android.media.MediaDrm
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback

/**
 * DIAGNOSTIC (2026-08-22): forces the Widevine CDM session to Security Level 3
 * (software decode, no MediaTek secure hardware/TEE path) instead of the L1
 * the device would otherwise negotiate.
 *
 * Every application-observable layer of the green-screen problem has been
 * verified correct and byte-identical to the official app's working playback
 * (parameter sets, decoder Codec2 config, CryptoInfo pattern/key/IV against
 * the real tenc box, SurfaceFlinger compositing). What's left is entirely
 * inside OEMCrypto/the TEE, which no Android API exposes. Forcing L3 removes
 * that whole layer from the picture: if this plays correctly, the bug is
 * proven to be specific to MediaTek's secure hardware decode path; if it's
 * still green, the secure path is exonerated too and the cause is elsewhere.
 *
 * Media3 itself has a private `FrameworkMediaDrm.forceWidevineL3()` for
 * exactly this scenario on some devices (confirmed via decompilation), gated
 * to specific hardcoded devices we don't control. This replicates the same
 * mechanism -- `MediaDrm.setPropertyString("securityLevel", "L3")`, a public
 * platform API -- generally, via a custom [ExoMediaDrm.Provider].
 */
@UnstableApi
class ForceWidevineL3DrmSessionManagerProvider(
    private val httpDataSourceFactory: DataSource.Factory
) : DrmSessionManagerProvider {

    private val exoMediaDrmProvider = ExoMediaDrm.Provider { uuid ->
        val drm = FrameworkMediaDrm.newInstance(uuid)
        if (uuid == C.WIDEVINE_UUID) {
            forceSecurityLevelL3(drm)
        }
        drm
    }

    override fun get(mediaItem: MediaItem): DrmSessionManager {
        val drmConfiguration = mediaItem.localConfiguration?.drmConfiguration
            ?: return DrmSessionManager.DRM_UNSUPPORTED
        val callback = HttpMediaDrmCallback(drmConfiguration.licenseUri.toString(), httpDataSourceFactory)
        drmConfiguration.licenseRequestHeaders.forEach { (key, value) ->
            callback.setKeyRequestProperty(key, value)
        }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(drmConfiguration.scheme, exoMediaDrmProvider)
            .setMultiSession(drmConfiguration.multiSession)
            .build(callback)
    }

    private fun forceSecurityLevelL3(drm: FrameworkMediaDrm) {
        try {
            val mediaDrmField = FrameworkMediaDrm::class.java.getDeclaredField("mediaDrm")
            mediaDrmField.isAccessible = true
            val mediaDrm = mediaDrmField.get(drm) as MediaDrm
            mediaDrm.setPropertyString("securityLevel", "L3")
            Log.i(TAG, "Forced Widevine securityLevel=L3 (software decode diagnostic)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force Widevine securityLevel=L3 via reflection", e)
        }
    }

    private companion object {
        private const val TAG = "ForceWidevineL3Drm"
    }
}
