package fr.groggy.racecontrol.tv.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * Runtime device identifiers used in F1TV API and DRM licence requests.
 *
 * Using [Build] values here ensures the headers reflect the actual hardware
 * running the app rather than a hardcoded compile-time string.
 */
object DeviceInfo {

    private val TAG = DeviceInfo::class.simpleName

    private const val SPOOFED_USER_AGENT_MODEL = "Google TV Streamer"
    private const val SPOOFED_ANDROID_RELEASE = "14"
    private const val SPOOFED_BUILD_ID = "UTTK.250729.004"
    private const val SPOOFED_F1_DEVICE_INFO_MODEL = "kirkwood"
    private const val SPOOFED_APP_VERSION = "30482001"
    private const val SPOOFED_PLAYER_VERSION = "3.112.0"

    /**
     * WebView-style User-Agent that matches this device's real model and Android version.
     * Keeps the Chrome/WebKit tokens that F1TV expects.
     */
    val userAgent: String = buildString {
        append("Mozilla/5.0 (Linux; Android ")
        append(SPOOFED_ANDROID_RELEASE)
        append("; ")
        append(SPOOFED_USER_AGENT_MODEL)
        append(" Build/")
        append(SPOOFED_BUILD_ID)
        append("; wv) AppleWebKit/537.36 (KHTML, like Gecko)")
        append(" Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36")
    }

    /**
     * Value for the F1TV `x-f1-device-info` request header.
     *
     * Uses the key schema observed in the official F1TV Android TV app:
     *   device=android_tv;screen=bigscreen;os=android;model=<model>;
     *   osVersion=34;appVersion=30482001;playerVersion=3.112.0;tms=1;
     *
     * The spoofed model mirrors the official UHD-supported Google/kirkwood identity
     * for Google TV Streamer. appVersion / playerVersion stay aligned with the last
     * known-working Android TV release from the same thread.
     */
    val f1DeviceInfo: String = buildString {
        append("device=android_tv")
        append(";screen=bigscreen")
        append(";os=android")
        append(";model=").append(SPOOFED_F1_DEVICE_INFO_MODEL)
        append(";osVersion=34")
        append(";appVersion=").append(SPOOFED_APP_VERSION)
        append(";playerVersion=").append(SPOOFED_PLAYER_VERSION)
        append(";tms=1")
        append(";")
    }

    /**
     * Returns true when the active/default display advertises HLG support.
     *
     * The app uses this to decide whether it should request F1TV's HDR manifest.
     * If the platform cannot output HLG, staying on the SDR manifest avoids the
     * initial playback failure/retry path entirely.
     */
    fun supportsHlgDisplay(context: Context): Boolean {
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return false
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val supportedHdrTypes = supportedHdrTypes(display)
        val supportsHlg = Display.HdrCapabilities.HDR_TYPE_HLG in supportedHdrTypes
        Log.i(
            TAG,
            "supportsHlgDisplay displayId=${display.displayId} hdrTypes=${supportedHdrTypes.joinToString()} supportsHlg=$supportsHlg"
        )
        return supportsHlg
    }

    private fun supportedHdrTypes(display: Display): IntArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return display.mode.supportedHdrTypes
        }

        val hdrCapabilities = display.hdrCapabilities ?: return IntArray(0)
        val legacySupportedHdrTypes = runCatching {
            hdrCapabilities.javaClass
                .getMethod("getSupportedHdrTypes")
                .invoke(hdrCapabilities) as? IntArray
        }.getOrNull()

        return legacySupportedHdrTypes ?: IntArray(0)
    }

    fun supportsHdrToSdrToneMapping(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private fun isGoogleTvStreamer(): Boolean {
        val identity = listOf(
            Build.BRAND,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT
        ).joinToString(separator = " ").lowercase()
        return "google tv streamer" in identity || "kirkwood" in identity
    }

    fun shouldPreferSdrUhdFallbackForHdrPlayback(): Boolean {
        val preferSdrUhd = isGoogleTvStreamer()
        Log.i(
            TAG,
            "shouldPreferSdrUhdFallbackForHdrPlayback preferSdrUhd=$preferSdrUhd " +
                "brand=${Build.BRAND} manufacturer=${Build.MANUFACTURER} " +
                "model=${Build.MODEL} device=${Build.DEVICE} product=${Build.PRODUCT}"
        )
        return preferSdrUhd
    }

    fun shouldRequestHdrManifest(context: Context): Boolean {
        val supportsHlg = supportsHlgDisplay(context)
        val supportsToneMapping = supportsHdrToSdrToneMapping()
        val isNvidia = Build.BRAND.equals("NVIDIA", ignoreCase = true) || Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true)
        val shouldRequestHdr = supportsHlg || supportsToneMapping
        Log.i(
            TAG,
            "shouldRequestHdrManifest supportsHlg=$supportsHlg supportsToneMapping=$supportsToneMapping isNvidia=$isNvidia shouldRequestHdr=$shouldRequestHdr"
        )
        return shouldRequestHdr
    }

    fun shouldToneMapHdrToSdr(context: Context): Boolean {
        val shouldToneMap = !supportsHlgDisplay(context) && supportsHdrToSdrToneMapping()
        Log.i(TAG, "shouldToneMapHdrToSdr=$shouldToneMap")
        return shouldToneMap
    }

    fun shouldTryHdrToSdrToneMappingPlayback(context: Context): Boolean {
        val supportsHlg = supportsHlgDisplay(context)
        val shouldTryToneMapping = !supportsHlg && supportsHdrToSdrToneMapping()
        Log.i(
            TAG,
            "shouldTryHdrToSdrToneMappingPlayback supportsHlg=$supportsHlg " +
                "supportsToneMapping=${supportsHdrToSdrToneMapping()} shouldTry=$shouldTryToneMapping"
        )
        return shouldTryToneMapping
    }
}
