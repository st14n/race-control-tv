package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Base64
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
        if (uuid == C.WIDEVINE_UUID) {
            // DIAGNOSTIC (2026-09-11): the CDM otherwise opens with
            // "requested_security_level = Default" and F1 issues our content key at
            // SecurityLevel 1 (SW_SECURE_CRYPTO). A SW_SECURE_CRYPTO key cannot be
            // loaded into the TEE key ladder, so c2.mtk.hevc.decoder.secure (SVP,
            // protected output) has no valid path to decrypt into protected memory and
            // emits a zero-filled buffer, which composites as uniform green with no
            // error raised. Ask for L1 explicitly so the key request advertises
            // hardware-secure capability.
            runCatching { delegate.setPropertyString("securityLevel", "L1") }
                .onSuccess { Log.i("DrmKeyStatus", "Requested Widevine securityLevel=L1") }
                .onFailure { Log.w("DrmKeyStatus", "Could not request securityLevel=L1", it) }
            KeyStatusLoggingExoMediaDrm(delegate)
        } else {
            delegate
        }
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
            logRawLicenseResponse(response)
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

        /**
         * DIAGNOSTIC (2026-08-30): dumps the raw licence response bytes returned by F1's
         * server, chunked across log lines (logcat truncates long lines). This is the one
         * piece of data every prior investigation session lacked: the actual
         * `SignedMessage`/`License` protobuf as issued for a specific playback, needed to
         * check offline whether the `KeyContainer.id` (key ID) the server bound the content
         * key to actually matches the KID this content's `pssh`/`tenc` declares. Correlate
         * against "First encrypted sample ... key=" from ClearVrLikeVideoRenderer.
         */
        private fun logRawLicenseResponse(response: ByteArray) {
            try {
                val b64 = Base64.encodeToString(response, Base64.NO_WRAP)
                val chunkSize = 3000
                val chunkCount = (b64.length + chunkSize - 1) / chunkSize
                Log.i(TAG, "RAW_LICENSE_RESPONSE begin size=${response.size} chunks=$chunkCount")
                for (i in 0 until chunkCount) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize, b64.length)
                    Log.i(TAG, "RAW_LICENSE_RESPONSE[$i/$chunkCount] ${b64.substring(start, end)}")
                }
                Log.i(TAG, "RAW_LICENSE_RESPONSE end")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log raw licence response", e)
            }
        }

        private companion object {
            private const val TAG = "DrmKeyStatus"
        }
    }
}
