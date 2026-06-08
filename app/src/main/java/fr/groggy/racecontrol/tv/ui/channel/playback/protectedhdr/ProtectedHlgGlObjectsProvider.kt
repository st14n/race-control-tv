package fr.groggy.racecontrol.tv.ui.channel.playback.protectedhdr

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.DefaultGlObjectsProvider

class ProtectedHlgGlObjectsProvider(
    private val forceSdrOutput: Boolean = false
) : GlObjectsProvider {

    private val delegate = DefaultGlObjectsProvider()
    private val protectedContexts = mutableListOf<EGLContext>()
    private var lastProtectedContextError: Int = EGL14.EGL_SUCCESS

    override fun createEglContext(
        eglDisplay: EGLDisplay,
        openGlVersion: Int,
        configAttributes: IntArray
    ): EGLContext {
        Log.i(TAG, "Attempting to create protected EGL context for Media3 HLG graph (ignoring extension string)")
        val protectedContext = createProtectedEglContext(eglDisplay, openGlVersion, configAttributes)
        if (protectedContext != EGL14.EGL_NO_CONTEXT) {
            protectedContexts += protectedContext
            Log.i(TAG, "Successfully created protected EGL context for Media3 protected HLG graph")
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
        Log.i(TAG, "Attempting to create EGL surface. colorTransfer=$colorTransfer isEncoderInputSurface=$isEncoderInputSurface")

        if (isEncoderInputSurface) {
            Log.i(TAG, "Delegating encoder input EGL surface without protected/HLG output attributes")
            return delegate.createEglSurface(eglDisplay, surface, colorTransfer, isEncoderInputSurface)
        }

        // Always attempt HLG output surface by default — colorTransfer from Media3 reflects internal 
        // graph format, not the display color space we actually want.
        val attempt = if (forceSdrOutput) {
            Log.i(TAG, "Creating SDR EGL window surface for output (tone-mapping active)")
            EglSurfaceAttempt("sdr-output", protectedContent = true, bt2020Hlg = false)
        } else {
            Log.i(TAG, "Creating HLG EGL window surface for output")
            EglSurfaceAttempt("hlg-output", protectedContent = true, bt2020Hlg = true)
        }

        createEglWindowSurface(eglDisplay, surface, attempt)?.let { return it }

        // Fallback to Media3 default if even plain creation failed.
        Log.w(TAG, "Protected EGL window surface creation failed; delegating to Media3 default")
        return delegate.createEglSurface(eglDisplay, surface, C.COLOR_TRANSFER_SDR, isEncoderInputSurface)
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
        // ALWAYS prioritize the 10-bit window config! The default configAttributes from ExoPlayer 
        // request an 8-bit config (RGBA_8888). If we use an 8-bit context, any attempt to attach 
        // the EGL_GL_COLORSPACE_BT2020_HLG_EXT surface attribute later will fail with EGL_BAD_MATCH!
        val config = chooseConfig(eglDisplay, protectedHlgWindowConfigAttributes(require10Bit = true))
            ?: chooseConfig(eglDisplay, configAttributes)
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

    private fun createEglWindowSurface(
        eglDisplay: EGLDisplay,
        surface: Any,
        attempt: EglSurfaceAttempt
    ): EGLSurface? {
        val config = chooseProtectedHlgWindowConfig(eglDisplay) ?: run {
            Log.w(TAG, "Unable to choose EGL window config for ${attempt.name}")
            return null
        }
        val attributes = attempt.attributes()
        clearEglError()
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            surface,
            attributes,
            0
        )
        val error = EGL14.eglGetError()
        if (eglSurface == EGL14.EGL_NO_SURFACE || error != EGL14.EGL_SUCCESS) {
            Log.w(
                TAG,
                "Unable to create ${attempt.name} EGL output surface for Media3 graph " +
                    "eglError=0x${error.toString(16)}"
            )
            return null
        }

        Log.i(TAG, "Created ${attempt.name} EGL output surface for Media3 protected HLG graph")
        return eglSurface
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

    private fun eglExtensions(eglDisplay: EGLDisplay): Set<String> {
        return EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS)
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()
    }

    private fun extensionSummary(extensions: Set<String>): String {
        val important = listOf(
            EGL_EXT_PROTECTED_CONTENT_NAME,
            EGL_EXT_GL_COLORSPACE_BT2020_HLG_NAME,
            "EGL_KHR_gl_colorspace",
            "EGL_EXT_gl_colorspace_bt2020_linear",
            "EGL_EXT_gl_colorspace_display_p3"
        ).filter { it in extensions }
        return "count=${extensions.size} important=$important"
    }

    private fun preferredOutputColorTransfer(colorTransfer: Int, hasHlgColorSpace: Boolean): Int {
        if (hasHlgColorSpace) return C.COLOR_TRANSFER_HLG
        return when (colorTransfer) {
            Format.NO_VALUE,
            C.COLOR_TRANSFER_LINEAR,
            C.COLOR_TRANSFER_SDR -> C.COLOR_TRANSFER_SDR
            else -> colorTransfer
        }
    }

    private fun colorTransferName(colorTransfer: Int): String {
        return when (colorTransfer) {
            Format.NO_VALUE -> "UNSET"
            C.COLOR_TRANSFER_LINEAR -> "LINEAR"
            C.COLOR_TRANSFER_SDR -> "SDR"
            C.COLOR_TRANSFER_ST2084 -> "ST2084"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "UNKNOWN"
        }
    }

    private data class EglSurfaceAttempt(
        val name: String,
        val protectedContent: Boolean,
        val bt2020Hlg: Boolean
    ) {
        fun attributes(): IntArray {
            val attributes = mutableListOf<Int>()
            if (protectedContent) {
                attributes += EGL_PROTECTED_CONTENT_EXT
                attributes += EGL14.EGL_TRUE
            }
            if (bt2020Hlg) {
                attributes += EGL_GL_COLORSPACE_KHR
                attributes += EGL_GL_COLORSPACE_BT2020_HLG_EXT
            }
            attributes += EGL14.EGL_NONE
            return attributes.toIntArray()
        }
    }

    private companion object {
        private const val EGL_PROTECTED_CONTENT_EXT = 0x32C0
        private const val EGL_GL_COLORSPACE_KHR = 0x309D
        private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
        private const val EGL_EXT_PROTECTED_CONTENT_NAME = "EGL_EXT_protected_content"
        private const val EGL_EXT_GL_COLORSPACE_BT2020_HLG_NAME = "EGL_EXT_gl_colorspace_bt2020_hlg"

        private val TAG = ProtectedHlgGlObjectsProvider::class.simpleName
    }
}
