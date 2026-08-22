package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Log

/**
 * Probes EGL_EXT_protected_content support via native (NDK) EGL entry points
 * (libEGL.so directly), matching how Tiledmedia's own native renderer
 * (libClearVRNativeRendererPlugin.so, confirmed via strings extraction from
 * the decompiled official APK) creates its protected EGL context -- as
 * opposed to Java's EGL14 wrapper, which an earlier probe used and found no
 * support through (ProtectedHlgGlObjectsProvider / ProtectedEglSurfaceProbe).
 *
 * Exists to determine whether that earlier "unsupported" result reflects a
 * genuine hardware/driver limitation, or an artifact of this specific
 * device's driver exposing the extension differently to native vs. Java EGL.
 */
object NativeProtectedEglProbe {
    private const val TAG = "NativeProtectedEglProbe"

    private val loaded: Boolean = try {
        System.loadLibrary("protected_egl_probe")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed to load protected_egl_probe native library", e)
        false
    }

    private external fun nativeQueryExtensions(): String
    private external fun nativeCanCreateProtectedContext(): Boolean

    fun logProbeResult() {
        if (!loaded) {
            Log.w(TAG, "Native probe unavailable, native library failed to load")
            return
        }
        try {
            val extensions = nativeQueryExtensions()
            val hasExtensionString = extensions.contains("EGL_EXT_protected_content")
            Log.i(
                TAG,
                "Native EGL extension string check: hasExtensionString=$hasExtensionString"
            )
            val canCreateProtectedContext = nativeCanCreateProtectedContext()
            Log.i(
                TAG,
                "Native EGL protected context probe result: " +
                    "canCreateProtectedContext=$canCreateProtectedContext"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Native protected EGL probe threw", e)
        }
    }
}
