package fr.groggy.racecontrol.tv.ui.channel.playback

import android.hardware.DataSpace
import android.os.Build
import android.util.Log
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.i(TAG, "BT.2020 HLG SurfaceControl dataspace unavailable on this API source=$source")
            return
        }

        runCatching {
            val surfaceControl = surfaceView.surfaceControl ?: run {
                Log.w(TAG, "Skipping BT.2020 HLG dataspace; SurfaceControl unavailable source=$source")
                return
            }
            if (!surfaceControl.isValid) {
                Log.w(TAG, "Skipping BT.2020 HLG dataspace; SurfaceControl invalid source=$source")
                return
            }
            SurfaceControl.Transaction()
                .setDataSpace(surfaceControl, DataSpace.DATASPACE_BT2020_HLG)
                .apply()
            Log.i(
                TAG,
                "Applied BT.2020 HLG dataspace to SurfaceView layer " +
                    "source=$source view=${System.identityHashCode(surfaceView)}"
            )
        }.onFailure {
            Log.w(TAG, "Unable to apply BT.2020 HLG dataspace source=$source", it)
        }
    }
}
