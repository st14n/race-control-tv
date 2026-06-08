package fr.groggy.racecontrol.tv.ui.channel.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.HdrConversionMode
import android.os.Build
import android.util.Log
import android.view.Display

object HdrPresentationDiagnostics {

    private const val TAG = "HdrPresentationDiagnostics"

    fun logDisplaySnapshot(context: Context?, source: String) {
        if (context == null) return
        runCatching {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            } else {
                @Suppress("DEPRECATION")
                displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            } ?: return

            val mode = display.mode
            val hdrCapabilities = display.hdrCapabilities
            val hdrTypes = hdrCapabilities.supportedHdrTypes
                .joinToString(separator = ",", transform = ::hdrTypeName)
            val hdrConversion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hdrConversionName(displayManager?.hdrConversionMode)
            } else {
                "unavailable"
            }
            val hdrRuntimeState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val isHdr = runCatching { display.isHdr }.getOrElse { false }
                val hdrSdrRatio = runCatching { display.hdrSdrRatio }.getOrElse { Float.NaN }
                val highestHdrSdrRatio = if (Build.VERSION.SDK_INT >= 35) {
                    runCatching { display.highestHdrSdrRatio }.getOrElse { Float.NaN }
                } else {
                    Float.NaN
                }
                "isHdr=$isHdr hdrSdrRatio=$hdrSdrRatio highestHdrSdrRatio=$highestHdrSdrRatio"
            } else {
                "isHdr=unavailable"
            }

            Log.i(
                TAG,
                "Display snapshot source=$source " +
                    "mode=${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate} " +
                    "appDisplay=${display.width}x${display.height}@${display.refreshRate} " +
                    "wideColor=${display.isWideColorGamut} preferredWideGamut=${display.preferredWideGamutColorSpace} " +
                    "hdrTypes=[$hdrTypes] hdrConversion=$hdrConversion $hdrRuntimeState"
            )
        }.onFailure {
            Log.w(TAG, "Unable to log HDR presentation display snapshot source=$source", it)
        }
    }

    private fun hdrTypeName(type: Int): String {
        return when (type) {
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
            Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
            else -> type.toString()
        }
    }

    private fun hdrConversionName(mode: HdrConversionMode?): String {
        return when (mode?.conversionMode) {
            HdrConversionMode.HDR_CONVERSION_SYSTEM -> "SYSTEM"
            HdrConversionMode.HDR_CONVERSION_FORCE -> "FORCE:${hdrTypeName(mode.preferredHdrOutputType)}"
            HdrConversionMode.HDR_CONVERSION_PASSTHROUGH -> "PASSTHROUGH"
            HdrConversionMode.HDR_CONVERSION_UNSUPPORTED -> "UNSUPPORTED"
            null -> "null"
            else -> mode.conversionMode.toString()
        }
    }
}
