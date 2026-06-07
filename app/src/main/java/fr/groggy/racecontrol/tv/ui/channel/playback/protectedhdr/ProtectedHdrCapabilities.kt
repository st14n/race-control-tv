package fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr

import android.opengl.EGL14
import android.util.Log

data class ProtectedHdrCapabilities(
    val eglDisplayExtensions: Set<String>,
    val hasProtectedContent: Boolean,
    val hasBt2020HlgColorSpace: Boolean
) {
    val canCreateProtectedHlgEglSurface: Boolean
        get() = hasProtectedContent && hasBt2020HlgColorSpace
}

object ProtectedHdrCapabilitiesProbe {

    private val TAG = ProtectedHdrCapabilitiesProbe::class.simpleName
    @Volatile private var cachedCapabilities: ProtectedHdrCapabilities? = null

    fun probe(forceRefresh: Boolean = false): ProtectedHdrCapabilities {
        if (!forceRefresh) {
            cachedCapabilities?.let { return it }
        }
        return synchronized(this) {
            if (!forceRefresh) {
                cachedCapabilities?.let { return it }
            }
            probeUncached().also { cachedCapabilities = it }
        }
    }

    private fun probeUncached(): ProtectedHdrCapabilities {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "Unable to get EGL default display for protected HDR capability probe")
            return ProtectedHdrCapabilities(emptySet(), hasProtectedContent = false, hasBt2020HlgColorSpace = false)
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.w(TAG, "Unable to initialize EGL display for protected HDR capability probe")
            return ProtectedHdrCapabilities(emptySet(), hasProtectedContent = false, hasBt2020HlgColorSpace = false)
        }

        val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

        EGL14.eglTerminate(display)

        val capabilities = ProtectedHdrCapabilities(
            eglDisplayExtensions = extensions,
            hasProtectedContent = "EGL_EXT_protected_content" in extensions,
            hasBt2020HlgColorSpace = "EGL_EXT_gl_colorspace_bt2020_hlg" in extensions
        )
        Log.i(
            TAG,
            "Protected HDR EGL capabilities " +
                "protectedContent=${capabilities.hasProtectedContent} " +
                "bt2020Hlg=${capabilities.hasBt2020HlgColorSpace} " +
                "canCreateProtectedHlgEglSurface=${capabilities.canCreateProtectedHlgEglSurface}"
        )
        return capabilities
    }
}
