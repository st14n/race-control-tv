package fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.DefaultGlObjectsProvider

class ProtectedHlgGlObjectsProvider : GlObjectsProvider {

    private val delegate = DefaultGlObjectsProvider()
    private val protectedContexts = mutableListOf<EGLContext>()
    private var lastProtectedContextError: Int = EGL14.EGL_SUCCESS

    override fun createEglContext(
        eglDisplay: EGLDisplay,
        openGlVersion: Int,
        configAttributes: IntArray
    ): EGLContext {
        val protectedContext = createProtectedEglContext(eglDisplay, openGlVersion, configAttributes)
        if (protectedContext != EGL14.EGL_NO_CONTEXT) {
            protectedContexts += protectedContext
            Log.i(TAG, "Created protected EGL context for Media3 protected HLG graph")
            return protectedContext
        }

        Log.w(
            TAG,
            "Protected EGL context creation failed; falling back to Media3 default context " +
                "eglError=0x${lastProtectedContextError.toString(16)}"
        )
        return delegate.createEglContext(eglDisplay, openGlVersion, configAttributes)
    }

    override fun createEglSurface(
        eglDisplay: EGLDisplay,
        surface: Any,
        colorTransfer: Int,
        isEncoderInputSurface: Boolean
    ): EGLSurface {
        if (isEncoderInputSurface || colorTransfer != C.COLOR_TRANSFER_HLG) {
            return delegate.createEglSurface(eglDisplay, surface, colorTransfer, isEncoderInputSurface)
        }

        val config = chooseProtectedHlgWindowConfig(eglDisplay)
            ?: throw GlUtil.GlException("Unable to choose EGL RGBA_1010102 config for protected HLG output")
        clearEglError()
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
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
        val error = EGL14.eglGetError()
        if (eglSurface == EGL14.EGL_NO_SURFACE || error != EGL14.EGL_SUCCESS) {
            throw GlUtil.GlException(
                "Unable to create protected BT.2020 HLG EGL output surface " +
                    "eglError=0x${error.toString(16)}"
            )
        }

        Log.i(TAG, "Created protected BT.2020 HLG EGL output surface for Media3 graph")
        return eglSurface
    }

    override fun createFocusedPlaceholderEglSurface(
        eglContext: EGLContext,
        eglDisplay: EGLDisplay
    ): EGLSurface {
        return delegate.createFocusedPlaceholderEglSurface(eglContext, eglDisplay)
    }

    override fun createBuffersForTexture(texId: Int, width: Int, height: Int): GlTextureInfo {
        return delegate.createBuffersForTexture(texId, width, height)
    }

    override fun release(eglDisplay: EGLDisplay) {
        protectedContexts.forEach { context ->
            EGL14.eglDestroyContext(eglDisplay, context)
        }
        protectedContexts.clear()
        delegate.release(eglDisplay)
    }

    private fun createProtectedEglContext(
        eglDisplay: EGLDisplay,
        openGlVersion: Int,
        configAttributes: IntArray
    ): EGLContext {
        val config = chooseConfig(eglDisplay, configAttributes)
            ?: chooseConfig(eglDisplay, protectedHlgWindowConfigAttributes(require10Bit = true))
            ?: chooseConfig(eglDisplay, protectedHlgWindowConfigAttributes(require10Bit = false))
            ?: return EGL14.EGL_NO_CONTEXT
        clearEglError()
        val context = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                openGlVersion,
                EGL_PROTECTED_CONTENT_EXT,
                EGL14.EGL_TRUE,
                EGL14.EGL_NONE
            ),
            0
        )
        val error = EGL14.eglGetError()
        lastProtectedContextError = error
        if (context == EGL14.EGL_NO_CONTEXT || error != EGL14.EGL_SUCCESS) {
            return EGL14.EGL_NO_CONTEXT
        }
        return context
    }

    private fun chooseProtectedHlgWindowConfig(eglDisplay: EGLDisplay): EGLConfig? {
        chooseConfig(eglDisplay, protectedHlgWindowConfigAttributes(require10Bit = true))?.let {
            Log.i(TAG, "Using official-like 10-bit EGL window config for protected HLG output")
            return it
        }
        Log.w(TAG, "10-bit protected HLG EGL window config unavailable; retrying 8-bit config")
        return chooseConfig(eglDisplay, protectedHlgWindowConfigAttributes(require10Bit = false))
    }

    private fun protectedHlgWindowConfigAttributes(require10Bit: Boolean): IntArray {
        return intArrayOf(
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
        )
    }

    private fun chooseConfig(eglDisplay: EGLDisplay, configAttributes: IntArray): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val ok = EGL14.eglChooseConfig(
            eglDisplay,
            configAttributes,
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

    private fun clearEglError() {
        while (EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
            // Drain stale EGL errors before measuring the next operation.
        }
    }

    private companion object {
        private const val EGL_PROTECTED_CONTENT_EXT = 0x32C0
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540

        private val TAG = ProtectedHlgGlObjectsProvider::class.simpleName
    }
}
