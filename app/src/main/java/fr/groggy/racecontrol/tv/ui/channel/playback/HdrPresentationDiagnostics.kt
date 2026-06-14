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
        snapshot(context, source)
    }

    fun snapshot(context: Context?, source: String): HdrPresentationSnapshot? {
        if (context == null) return null
        return runCatching {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            } else {
                @Suppress("DEPRECATION")
                displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            } ?: return null

            val mode = display.mode
            val hdrCapabilities = display.hdrCapabilities
            val supportedHdrTypes = hdrCapabilities.supportedHdrTypes
            val hdrTypes = supportedHdrTypes.joinToString(separator = ",", transform = ::hdrTypeName)
            val hdrConversion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hdrConversionName(displayManager?.hdrConversionMode)
            } else {
                "unavailable"
            }
            val isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { display.isHdr }.getOrNull()
            } else {
                null
            }
            val hdrSdrRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { display.hdrSdrRatio }.getOrNull()
            } else {
                null
            }
            val highestHdrSdrRatio = if (Build.VERSION.SDK_INT >= 35) {
                runCatching { display.highestHdrSdrRatio }.getOrNull()
            } else {
                null
            }
            val snapshot = HdrPresentationSnapshot(
                source = source,
                displayMode = "${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate}",
                appDisplay = "${display.width}x${display.height}@${display.refreshRate}",
                supportsHlg = Display.HdrCapabilities.HDR_TYPE_HLG in supportedHdrTypes,
                hdrTypes = hdrTypes,
                hdrConversion = hdrConversion,
                isWideColorGamut = display.isWideColorGamut,
                isHdr = isHdr,
                hdrSdrRatio = hdrSdrRatio,
                highestHdrSdrRatio = highestHdrSdrRatio
            )

            Log.i(
                TAG,
                "Display snapshot source=$source " +
                    "mode=${snapshot.displayMode} " +
                    "appDisplay=${snapshot.appDisplay} " +
                    "wideColor=${snapshot.isWideColorGamut} preferredWideGamut=${display.preferredWideGamutColorSpace} " +
                    "hdrTypes=[$hdrTypes] hdrConversion=$hdrConversion " +
                    "isHdr=${isHdr ?: "unavailable"} " +
                    "hdrSdrRatio=${hdrSdrRatio ?: "unavailable"} " +
                    "highestHdrSdrRatio=${highestHdrSdrRatio ?: "unavailable"}"
            )
            snapshot
        }.onFailure {
            Log.w(TAG, "Unable to log HDR presentation display snapshot source=$source", it)
        }.getOrNull()
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

    data class HdrPresentationSnapshot(
        val source: String,
        val displayMode: String,
        val appDisplay: String,
        val supportsHlg: Boolean,
        val hdrTypes: String,
        val hdrConversion: String,
        val isWideColorGamut: Boolean,
        val isHdr: Boolean?,
        val hdrSdrRatio: Float?,
        val highestHdrSdrRatio: Float?
    ) {
        val canDiagnoseNativeHdrPresentation: Boolean
            get() = isHdr != null

        val confirmsNativeHlgPresentation: Boolean
            get() {
                return supportsHlg && isHdr == true
            }

        fun nativeHlgFailureReason(): String {
            return when {
                !supportsHlg -> "display does not advertise HLG"
                isHdr != true -> "display runtime isHdr=$isHdr"
                else -> "display did not confirm native HLG presentation"
            }
        }
    }
}
