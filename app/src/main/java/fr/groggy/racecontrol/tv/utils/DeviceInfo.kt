package fr.groggy.racecontrol.tv.utils

import android.os.Build

/**
 * Runtime device identifiers used in F1TV API and DRM licence requests.
 *
 * Using [Build] values here ensures the headers reflect the actual hardware
 * running the app rather than a hardcoded compile-time string.
 */
object DeviceInfo {

    private const val SPOOFED_BRAND = "Google"
    private const val SPOOFED_PRODUCT = "sabrina_prod_stable"
    private const val SPOOFED_MODEL = "sabrina_prod_stable"
    private const val SPOOFED_APP_VERSION = "30410001"
    private const val SPOOFED_PLAYER_VERSION = "3.112.0"

    const val tvOsDeviceInfo = "device=tvos"

    /**
     * WebView-style User-Agent that matches this device's real model and Android version.
     * Keeps the Chrome/WebKit tokens that F1TV expects.
     */
    val userAgent: String = buildString {
        append("Mozilla/5.0 (Linux; Android ")
        append(Build.VERSION.RELEASE)
        append("; ")
        append(SPOOFED_BRAND)
        append(' ')
        append(SPOOFED_PRODUCT)
        append(" Build/")
        append(Build.ID)
        append("; wv) AppleWebKit/537.36 (KHTML, like Gecko)")
        append(" Version/4.0 Chrome/128.0.6613.114 Mobile Safari/537.36")
    }

    /**
     * Value for the F1TV `x-f1-device-info` request header.
     *
     * Uses the key schema observed in the official F1TV Android TV app:
     *   device=android_tv;screen=bigscreen;os=android;model=<model>;
     *   osVersion=<api>;appVersion=30410001;playerVersion=3.112.0;p=true;u=false;
     *
     * The spoofed brand/product pair mirrors the Google/sabrina identity discussed
     * in recent 4K patch experiments. appVersion / playerVersion stay aligned with
     * the last known-working Android TV release from the same thread.
     */
    val f1DeviceInfo: String = buildString {
        append("device=android_tv")
        append(";screen=bigscreen")
        append(";os=android")
        append(";model=").append(SPOOFED_MODEL)
        append(";osVersion=").append(Build.VERSION.SDK_INT)
        append(";appVersion=").append(SPOOFED_APP_VERSION)
        append(";playerVersion=").append(SPOOFED_PLAYER_VERSION)
        append(";p=true")
        append(";u=false")
        append(";")
    }
}
