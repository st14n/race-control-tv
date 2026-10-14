package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Base64
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import fr.groggy.racecontrol.tv.BuildConfig

/**
 * DRM diagnostic provider.
 *
 * Diagnostics performed here:
 *
 * 1. Requests the desired Widevine security level (L1 by default,
 *    optionally L3).
 *
 * 2. Records the signing identity of this build using hardcoded values
 *    independently verified with Android SDK apksigner.
 *
 * 3. Logs the raw Widevine license response as Base64.
 *
 * 4. Applies the license through the actual ExoMediaDrm delegate.
 *
 * 5. Queries the CDM's key status immediately after the license is applied.
 *
 * The hardcoded certificate hash is diagnostic metadata only. It does not
 * modify or override the identity reported by Android/Widevine.
 */
@UnstableApi
class KeyStatusLoggingDrmSessionManagerProvider(
    private val httpDataSourceFactory: DataSource.Factory,

    /**
     * Diagnostic switch:
     *
     * false -> request Widevine L1
     * true  -> request Widevine L3
     */
    private val forceL3: Boolean = false,

    /**
     * When true, the Widevine `app_info` identity is rewritten to the official
     * F1 TV app before the request is sent.
     *
     * PROVEN 2026-10-12 (host replay, matrix.py): this rewrite makes F1's
     * licence server reject the request with 403 INVALID_SIGNATURE (app_info
     * KVs are signature-covered) or 403 LICENSE_REQUEST_PARSE_ERROR (the
     * device block is NOT signature-covered but has a strict parse schema).
     * Only the PRISTINE (unrewritten) request is granted a license. So the
     * default is OFF: the CDM sends its own clean, natively-signed attestation,
     * which is what a real L1 device (Google TV Streamer) must present to
     * receive a HW_SECURE_DECODE (L1) grant.
     */
    private val spoofOfficialIdentity: Boolean = false,
) : DrmSessionManagerProvider {

    private val exoMediaDrmProvider = ExoMediaDrm.Provider { uuid ->

        val delegate = FrameworkMediaDrm.newInstance(uuid)

        if (uuid == C.WIDEVINE_UUID) {

            // -------------------------------------------------------------
            // Hardcoded identity for this APK.
            //
            // Verified with:
            //
            //   apksigner verify --print-certs \
            //       com.st14n.f1-1.0.3-debug.apk
            //
            // This is diagnostic metadata only. It does NOT override the
            // certificate identity used by Android or the Widevine CDM.
            // -------------------------------------------------------------

            Log.i(
                TAG,
                "DRM diagnostic app identity: " +
                    "app_name=$OUR_APP_NAME " +
                    "cert_sha256=$OUR_APP_CERT_SHA256 " +
                    "spoof_official=$spoofOfficialIdentity"
            )

            // -------------------------------------------------------------
            // Widevine security level diagnostic
            // -------------------------------------------------------------

            if (forceL3) {

                try {
                    val mediaDrmField =
                        FrameworkMediaDrm::class.java
                            .getDeclaredField("mediaDrm")

                    mediaDrmField.isAccessible = true

                    val mediaDrm =
                        mediaDrmField.get(delegate)
                            as android.media.MediaDrm

                    mediaDrm.setPropertyString(
                        "securityLevel",
                        "L3"
                    )

                    Log.i(
                        TAG,
                        "Forced Widevine securityLevel=L3 " +
                            "(software decode diagnostic)"
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Failed to force Widevine " +
                            "securityLevel=L3 via reflection",
                        e
                    )
                }

            } else {

                runCatching {
                    delegate.setPropertyString(
                        "securityLevel",
                        "L1"
                    )
                }.onSuccess {

                    Log.i(
                        TAG,
                        "Requested Widevine securityLevel=L1"
                    )

                }.onFailure {

                    Log.w(
                        TAG,
                        "Could not request " +
                            "Widevine securityLevel=L1",
                        it
                    )
                }
            }

            // Wrap the real Widevine implementation with our diagnostic
            // delegate.
            KeyStatusLoggingExoMediaDrm(delegate, spoofOfficialIdentity)

        } else {

            delegate
        }
    }

    override fun get(
        mediaItem: MediaItem
    ): DrmSessionManager {

        val drmConfiguration =
            mediaItem.localConfiguration?.drmConfiguration
                ?: return DrmSessionManager.DRM_UNSUPPORTED

        val callback = HttpMediaDrmCallback(
            drmConfiguration.licenseUri.toString(),
            httpDataSourceFactory
        )

        drmConfiguration.licenseRequestHeaders.forEach { (key, value) ->
            callback.setKeyRequestProperty(
                key,
                value
            )
        }

        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(
                drmConfiguration.scheme,
                exoMediaDrmProvider
            )
            .setMultiSession(
                drmConfiguration.multiSession
            )
            .build(callback)
    }

    private companion object {

        private const val TAG = "DrmKeyStatus"

        /**
         * Diagnostic application identifier.
         *
         * This is the package/application identity associated with the APK
         * being tested (our own identity, as reported by Android/Widevine).
         */
        private val OUR_APP_NAME: String
            get() = BuildConfig.APPLICATION_ID

        /**
         * SHA-256 of our APK signing certificate.
         *
         * Verified independently with:
         *
         *   apksigner verify --print-certs
         *
         * Result:
         *
         *   Signer #1 certificate SHA-256 digest:
         *   6eef550fc36d1a916d134dde9aec840b3ade2e5137bdf0e4e36a6a19a9735320
         */
        private const val OUR_APP_CERT_SHA256 =
            "6eef550fc36d1a916d134dde9aec840b3ade2e5137bdf0e4e36a6a19a9735320"
    }

    /**
     * Diagnostic wrapper around the real ExoMediaDrm implementation.
     *
     * Calls are forwarded to the actual Widevine implementation. When
     * [spoofOfficialIdentity] is set, the Widevine request's `app_info`
     * identity is rewritten to the official F1 TV app before it is sent, so
     * the licence server grants a hardware-secure (HW_SECURE_DECODE / L1)
     * licence.
     */
    private class KeyStatusLoggingExoMediaDrm(
        private val delegate: ExoMediaDrm,
        private val spoofOfficialIdentity: Boolean = false
    ) : ExoMediaDrm by delegate {

        override fun getKeyRequest(
            scope: ByteArray,
            schemeDatas: List<DrmInitData.SchemeData>?,
            keyType: Int,
            optionalParameters: HashMap<String, String>?
        ): ExoMediaDrm.KeyRequest {
            var request = delegate.getKeyRequest(
                scope,
                schemeDatas,
                keyType,
                optionalParameters
            )
            if (spoofOfficialIdentity) {
                request = maybeRewriteIdentity(request)
            }
            logRawKeyRequest(request.data)
            return request
        }

        /**
         * Rewrites the client identity in the Widevine [ExoMediaDrm.KeyRequest]
         * to the official F1 TV app so the licence server grants
         * HW_SECURE_DECODE (L1) instead of SW_SECURE_CRYPTO (L2).
         *
         * Returns the original request unchanged when the rewriter does not
         * recognise the request (e.g. it is not a Widevine request carrying
         * our package name).
         */
        private fun maybeRewriteIdentity(
            request: ExoMediaDrm.KeyRequest
        ): ExoMediaDrm.KeyRequest {
            val rewritten = WidevineIdentityRewriter.rewrite(
                request.data,
                OUR_APP_NAME
            )
            if (rewritten !== request.data) {
                Log.i(
                    TAG,
                    "Widevine identity rewritten: ${request.data.size}B -> " +
                        "${rewritten.size}B " +
                        "(original=$OUR_APP_NAME target=$TARGET_APP_NAME)"
                )
                return ExoMediaDrm.KeyRequest(
                    rewritten,
                    request.licenseServerUrl,
                    request.requestType
                )
            }
            return request
        }

        override fun provideKeyResponse(
            scope: ByteArray,
            response: ByteArray
        ): ByteArray? {

            // -------------------------------------------------------------
            // Application identity diagnostics
            // -------------------------------------------------------------

            Log.i(
                TAG,
                "DRM licence identity: " +
                    "original=$OUR_APP_NAME/" +
                    "${OUR_APP_CERT_SHA256.take(16)}... " +
                    "target=$TARGET_APP_NAME/" +
                    "${TARGET_APP_CERT_SHA256.take(16)}... " +
                    "spoof=$spoofOfficialIdentity"
            )

            // -------------------------------------------------------------
            // Raw license response
            // -------------------------------------------------------------

            logRawLicenseResponse(response)

            // -------------------------------------------------------------
            // Apply license through the real CDM.
            // -------------------------------------------------------------

            val result = delegate.provideKeyResponse(
                scope,
                response
            )

            // -------------------------------------------------------------
            // Query the CDM after applying the license.
            //
            // This is more useful than trying to infer the loaded keys
            // from the raw protobuf ourselves.
            // -------------------------------------------------------------

            try {

                val status =
                    delegate.queryKeyStatus(scope)

                Log.i(
                    TAG,
                    "CDM key status after licence " +
                        "(${status.size} entries): " +
                        status.entries.joinToString {
                            "${it.key}=${it.value}"
                        }
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "queryKeyStatus failed",
                    e
                )
            }

            return result
        }

        private fun logRawKeyRequest(request: ByteArray) {
            val b64 = Base64.encodeToString(request, Base64.NO_WRAP)
            val chunkSize = 3000
            val chunkCount = (b64.length + chunkSize - 1) / chunkSize
            Log.i(
                TAG,
                "RAW_KEY_REQUEST begin size=${request.size} chunks=$chunkCount"
            )
            for (i in 0 until chunkCount) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, b64.length)
                Log.i(
                    TAG,
                    "RAW_KEY_REQUEST[$i/$chunkCount] ${b64.substring(start, end)}"
                )
            }
            Log.i(TAG, "RAW_KEY_REQUEST end")
        }

        /**
         * Dump the raw license response as Base64.
         *
         * Logcat lines are deliberately kept below typical line-size limits.
         */
        private fun logRawLicenseResponse(
            response: ByteArray
        ) {

            try {

                val b64 =
                    Base64.encodeToString(
                        response,
                        Base64.NO_WRAP
                    )

                val chunkSize = 3000

                val chunkCount =
                    (b64.length + chunkSize - 1) /
                        chunkSize

                Log.i(
                    TAG,
                    "RAW_LICENSE_RESPONSE begin " +
                        "size=${response.size} " +
                        "chunks=$chunkCount"
                )

                for (i in 0 until chunkCount) {

                    val start =
                        i * chunkSize

                    val end =
                        minOf(
                            start + chunkSize,
                            b64.length
                        )

                    Log.i(
                        TAG,
                        "RAW_LICENSE_RESPONSE[$i/$chunkCount] " +
                            b64.substring(
                                start,
                                end
                            )
                    )
                }

                Log.i(
                    TAG,
                    "RAW_LICENSE_RESPONSE end"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to log raw licence response",
                    e
                )
            }
        }

        private companion object {

            private const val TAG =
                "DrmKeyStatus"

            /**
             * Identity we rewrite the request to (the official F1 TV app), so
             * the licence server grants HW_SECURE_DECODE (L1).
             */
            private const val TARGET_APP_NAME =
                WidevineIdentityRewriter.OFFICIAL_PACKAGE_NAME

            private const val TARGET_APP_CERT_SHA256 =
                WidevineIdentityRewriter.OFFICIAL_CERT_SHA256_HEX
        }
    }
}
