package fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

data class ProtectedEglSurfaceProbeResult(
    val attempted: Boolean,
    val succeeded: Boolean,
    val usedProtectedContext: Boolean,
    val eglError: Int,
    val reason: String
)

object ProtectedEglSurfaceProbe {

    private const val EGL_PROTECTED_CONTENT_EXT = 0x32C0
    private const val EGL_GL_COLORSPACE_KHR = 0x309D
    private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540

    private val TAG = ProtectedEglSurfaceProbe::class.simpleName

    fun probe(surface: Surface, source: String): ProtectedEglSurfaceProbeResult {
        if (!surface.isValid) {
            val result = ProtectedEglSurfaceProbeResult(
                attempted = false,
                succeeded = false,
                usedProtectedContext = false,
                eglError = EGL14.EGL_SUCCESS,
                reason = "surface is not valid"
            )
            Log.w(TAG, "Protected HLG EGL window-surface probe skipped source=$source result=$result")
            return result
        }

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            return logResult(
                source,
                ProtectedEglSurfaceProbeResult(
                    attempted = true,
                    succeeded = false,
                    usedProtectedContext = false,
                    eglError = eglError(),
                    reason = "eglGetDisplay failed"
                )
            )
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            return logResult(
                source,
                ProtectedEglSurfaceProbeResult(
                    attempted = true,
                    succeeded = false,
                    usedProtectedContext = false,
                    eglError = eglError(),
                    reason = "unable to initialize EGL display"
                )
            )
        }

        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var usedProtectedContext = false

        try {
            Log.i(TAG, "Attempting protected EGL context/surface creation (ignoring missing extension strings)")

            val config = chooseWindowConfig(display) ?: return logResult(
                source,
                ProtectedEglSurfaceProbeResult(
                    attempted = true,
                    succeeded = false,
                    usedProtectedContext = false,
                    eglError = eglError(),
                    reason = "unable to choose EGL window config"
                )
            )

            context = createContext(display, config, protectedContent = true)
            usedProtectedContext = context != EGL14.EGL_NO_CONTEXT
            if (!usedProtectedContext) {
                val protectedContextError = eglError()
                Log.w(
                    TAG,
                    "Protected EGL context creation failed source=$source " +
                        "eglError=0x${protectedContextError.toString(16)}; retrying with normal ES2 context"
                )
                context = createContext(display, config, protectedContent = false)
            }
            if (context == EGL14.EGL_NO_CONTEXT) {
                return logResult(
                    source,
                    ProtectedEglSurfaceProbeResult(
                        attempted = true,
                        succeeded = false,
                        usedProtectedContext = false,
                        eglError = eglError(),
                        reason = "unable to create EGL context"
                    )
                )
            }

            eglSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                intArrayOf(
                    EGL_PROTECTED_CONTENT_EXT,
                    EGL14.EGL_TRUE,
                    EGL_GL_COLORSPACE_KHR,
                    EGL_GL_COLORSPACE_BT2020_HLG_EXT,
                    EGL14.EGL_NONE
                ),
                0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                return logResult(
                    source,
                    ProtectedEglSurfaceProbeResult(
                        attempted = true,
                        succeeded = false,
                        usedProtectedContext = usedProtectedContext,
                        eglError = eglError(),
                        reason = "unable to create protected BT.2020 HLG EGL window surface"
                    )
                )
            }

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                return logResult(
                    source,
                    ProtectedEglSurfaceProbeResult(
                        attempted = true,
                        succeeded = false,
                        usedProtectedContext = usedProtectedContext,
                        eglError = eglError(),
                        reason = "created protected HLG EGL surface but could not make it current"
                    )
                )
            }

            return logResult(
                source,
                ProtectedEglSurfaceProbeResult(
                    attempted = true,
                    succeeded = true,
                    usedProtectedContext = usedProtectedContext,
                    eglError = EGL14.EGL_SUCCESS,
                    reason = "created and bound protected BT.2020 HLG EGL window surface"
                )
            )
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface)
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglTerminate(display)
        }
    }

    private fun chooseWindowConfig(display: EGLDisplay): EGLConfig? {
        chooseWindowConfig(display, require10Bit = true)?.let {
            Log.i(TAG, "Using official-like 10-bit EGL window config for protected HLG probe")
            return it
        }
        Log.w(TAG, "10-bit protected HLG EGL probe config unavailable; retrying 8-bit config")
        return chooseWindowConfig(display, require10Bit = false)
    }

    private fun chooseWindowConfig(display: EGLDisplay, require10Bit: Boolean): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val ok = EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                if (require10Bit) 10 else 8,
                EGL14.EGL_GREEN_SIZE,
                if (require10Bit) 10 else 8,
                EGL14.EGL_BLUE_SIZE,
                if (require10Bit) 10 else 8,
                EGL14.EGL_ALPHA_SIZE,
                if (require10Bit) 2 else 8,
                EGL14.EGL_DEPTH_SIZE,
                0,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            ),
            0,
            configs,
            0,
            configs.size,
            numConfigs,
            0
        )
        if (!ok || numConfigs[0] <= 0) return null
        return configs[0]
    }

    private fun createContext(
        display: EGLDisplay,
        config: EGLConfig,
        protectedContent: Boolean
    ): EGLContext {
        val attributes = if (protectedContent) {
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL_PROTECTED_CONTENT_EXT,
                EGL14.EGL_TRUE,
                EGL14.EGL_NONE
            )
        } else {
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                2,
                EGL14.EGL_NONE
            )
        }
        return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attributes, 0)
    }

    private fun logResult(
        source: String,
        result: ProtectedEglSurfaceProbeResult
    ): ProtectedEglSurfaceProbeResult {
        val message = "Protected HLG EGL window-surface probe source=$source result=$result"
        if (result.succeeded) {
            Log.i(TAG, message)
        } else {
            Log.w(TAG, message)
        }
        return result
    }

    private fun eglError(): Int = EGL14.eglGetError()
}
