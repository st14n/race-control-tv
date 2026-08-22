package fr.groggy.racecontrol.tv.ui.channel.playback

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
 * DIAGNOSTIC (2026-08-22): logs the key IDs the CDM actually holds after a
 * licence is applied, via `queryKeyStatus`.
 *
 * Heuristic protobuf parsing of the licence blobs suggested the CDM receives
 * keys for different KIDs than the ones the content asks for -- the video's
 * CryptoInfo requests KID cbacc212..., while the licence appeared to carry
 * a03b3d4d... If true, decryption silently produces garbage (no error is ever
 * raised) and the decoder emits the uniformly zero-filled buffer we measured
 * as green. `queryKeyStatus` is authoritative, unlike parsing the blobs.
 *
 * Cross-reference the logged key IDs against the `CryptoInfo #n ... key=` lines
 * from [HdrToneMappingRenderersFactory]: they must intersect, or nothing can
 * decrypt correctly.
 */
@UnstableApi
class KeyStatusLoggingDrmSessionManagerProvider(
    private val httpDataSourceFactory: DataSource.Factory
) : DrmSessionManagerProvider {

    private val exoMediaDrmProvider = ExoMediaDrm.Provider { uuid ->
        val delegate = FrameworkMediaDrm.newInstance(uuid)
        if (uuid == C.WIDEVINE_UUID) KeyStatusLoggingExoMediaDrm(delegate) else delegate
    }

    override fun get(mediaItem: MediaItem): DrmSessionManager {
        val drmConfiguration = mediaItem.localConfiguration?.drmConfiguration
            ?: return DrmSessionManager.DRM_UNSUPPORTED
        val callback = HttpMediaDrmCallback(drmConfiguration.licenseUri.toString(), httpDataSourceFactory)
        drmConfiguration.licenseRequestHeaders.forEach { (k, v) -> callback.setKeyRequestProperty(k, v) }
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(drmConfiguration.scheme, exoMediaDrmProvider)
            .setMultiSession(drmConfiguration.multiSession)
            .build(callback)
    }

    private class KeyStatusLoggingExoMediaDrm(
        private val delegate: ExoMediaDrm
    ) : ExoMediaDrm by delegate {

        override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray? {
            val result = delegate.provideKeyResponse(scope, response)
            try {
                val status = delegate.queryKeyStatus(scope)
                Log.i(
                    TAG,
                    "CDM key status after licence (${status.size} entries): " +
                        status.entries.joinToString { "${it.key}=${it.value}" }
                )
            } catch (e: Exception) {
                Log.e(TAG, "queryKeyStatus failed", e)
            }
            return result
        }

        private companion object {
            private const val TAG = "DrmKeyStatus"
        }
    }
}
