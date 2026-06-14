package fr.groggy.racecontrol.tv.ui.channel.playback

import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView

object HdrSurfaceHints {

    private const val TAG = "HdrSurfaceHints"

    fun applyAndroid14SurfaceLifecycle(surfaceView: SurfaceView?, source: String) {
        if (surfaceView == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        runCatching {
            surfaceView.setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            Log.i(
                TAG,
                "Applied attachment-based SurfaceView lifecycle " +
                    "source=$source view=${System.identityHashCode(surfaceView)}"
            )
        }.onFailure {
            Log.w(TAG, "Unable to apply attachment-based SurfaceView lifecycle source=$source", it)
        }
    }

    fun applyBt2020HlgDataSpace(surfaceView: SurfaceView?, source: String) {
        if (surfaceView == null) return
        Log.i(
            TAG,
            "Skipping explicit BT.2020 HLG SurfaceControl dataspace " +
                "source=$source view=${System.identityHashCode(surfaceView)}"
        )
    }

    fun requestFrameRate(surfaceView: SurfaceView?, frameRate: Float, source: String) {
        if (surfaceView == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        runCatching {
            val surfaceControl = surfaceView.surfaceControl ?: run {
                Log.w(TAG, "Skipping SurfaceControl frame-rate vote; SurfaceControl unavailable source=$source")
                return
            }
            if (!surfaceControl.isValid) {
                Log.w(TAG, "Skipping SurfaceControl frame-rate vote; SurfaceControl invalid source=$source")
                return
            }
            SurfaceControl.Transaction()
                .setFrameRate(
                    surfaceControl,
                    frameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
                .apply()
            Log.i(
                TAG,
                "Requested SurfaceControl frame-rate vote " +
                    "fps=$frameRate compatibility=default change=always source=$source " +
                    "view=${System.identityHashCode(surfaceView)}"
            )
        }.onFailure {
            Log.w(TAG, "Unable to request SurfaceControl frame-rate vote source=$source", it)
        }
    }
}
