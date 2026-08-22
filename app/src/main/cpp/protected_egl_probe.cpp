// Probes EGL_EXT_protected_content support through the native (NDK) EGL
// entry points, i.e. libEGL.so directly, rather than through Java's EGL14
// wrapper. The official F1TV app's Tiledmedia/ClearVR native renderer
// (libClearVRNativeRendererPlugin.so) does its own protected EGL context
// creation this way; an earlier Java-level probe (ProtectedEglSurfaceProbe.kt,
// using android.opengl.EGL14) found this device reports no support for the
// extension. This probe exists to check whether that's a genuine hardware
// limitation or an artifact of some vendor drivers exposing the extension
// differently to native vs. Java EGL contexts.

#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/log.h>
#include <cstring>
#include <string>

#define LOG_TAG "NativeProtectedEglProbe"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef EGL_PROTECTED_CONTENT_EXT
#define EGL_PROTECTED_CONTENT_EXT 0x32C0
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_fr_groggy_racecontrol_tv_ui_channel_playback_NativeProtectedEglProbe_nativeQueryExtensions(
        JNIEnv *env, jobject /* this */) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed, error=0x%x", eglGetError());
        return env->NewStringUTF("");
    }
    EGLint majorVersion, minorVersion;
    if (!eglInitialize(display, &majorVersion, &minorVersion)) {
        LOGE("eglInitialize failed, error=0x%x", eglGetError());
        return env->NewStringUTF("");
    }
    LOGI("Native EGL initialized version=%d.%d", majorVersion, minorVersion);
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (extensions == nullptr) {
        LOGE("eglQueryString(EGL_EXTENSIONS) returned null, error=0x%x", eglGetError());
        return env->NewStringUTF("");
    }
    LOGI("Native EGL extensions: %s", extensions);
    return env->NewStringUTF(extensions);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fr_groggy_racecontrol_tv_ui_channel_playback_NativeProtectedEglProbe_nativeCanCreateProtectedContext(
        JNIEnv *env, jobject /* this */) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed, error=0x%x", eglGetError());
        return JNI_FALSE;
    }
    EGLint majorVersion, minorVersion;
    if (!eglInitialize(display, &majorVersion, &minorVersion)) {
        LOGE("eglInitialize failed, error=0x%x", eglGetError());
        return JNI_FALSE;
    }

    const EGLint configAttribs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig failed, error=0x%x", eglGetError());
        return JNI_FALSE;
    }

    const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_PROTECTED_CONTENT_EXT, EGL_TRUE,
            EGL_NONE
    };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext with EGL_PROTECTED_CONTENT_EXT failed, error=0x%x", eglGetError());
        return JNI_FALSE;
    }
    LOGI("Native protected eglCreateContext succeeded");

    const EGLint pbufferAttribs[] = {
            EGL_WIDTH, 16,
            EGL_HEIGHT, 16,
            EGL_PROTECTED_CONTENT_EXT, EGL_TRUE,
            EGL_NONE
    };
    EGLSurface surface = eglCreatePbufferSurface(display, config, pbufferAttribs);
    if (surface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface with EGL_PROTECTED_CONTENT_EXT failed, error=0x%x", eglGetError());
        eglDestroyContext(display, context);
        return JNI_FALSE;
    }
    LOGI("Native protected eglCreatePbufferSurface succeeded");

    jboolean madeCurrent = eglMakeCurrent(display, surface, surface, context) ? JNI_TRUE : JNI_FALSE;
    if (!madeCurrent) {
        LOGE("eglMakeCurrent on protected surface/context failed, error=0x%x", eglGetError());
    } else {
        LOGI("Native protected eglMakeCurrent succeeded -- protected EGL context is real and usable");
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }

    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);
    return madeCurrent;
}
