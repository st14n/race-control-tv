package fr.groggy.racecontrol.tv

import android.opengl.EGL14
import android.util.Log

fun dumpEglExtensions() {
    val TAG = "EglDumper"
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (display == EGL14.EGL_NO_DISPLAY) {
        Log.e(TAG, "No display")
        return
    }
    val version = IntArray(2)
    EGL14.eglInitialize(display, version, 0, version, 1)

    val clientExt = EGL14.eglQueryString(EGL14.EGL_NO_DISPLAY, EGL14.EGL_EXTENSIONS)
    Log.i(TAG, "Client extensions: $clientExt")

    val displayExt = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
    Log.i(TAG, "Display extensions: $displayExt")

    EGL14.eglTerminate(display)
}
