package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Descriptor

@UnstableApi
class F1DashManifestParser : DashManifestParser() {

    override fun buildFormat(
        id: String?,
        containerMimeType: String?,
        width: Int,
        height: Int,
        frameRate: Float,
        audioChannels: Int,
        audioSamplingRate: Int,
        bitrate: Int,
        language: String?,
        roleDescriptors: MutableList<Descriptor>,
        accessibilityDescriptors: MutableList<Descriptor>,
        codecs: String?,
        supplementalCodecs: String?,
        supplementalProfiles: String?,
        essentialProperties: MutableList<Descriptor>,
        supplementalProperties: MutableList<Descriptor>
    ): Format {
        val format = super.buildFormat(
            id,
            containerMimeType,
            width,
            height,
            frameRate,
            audioChannels,
            audioSamplingRate,
            bitrate,
            language,
            roleDescriptors,
            accessibilityDescriptors,
            codecs,
            supplementalCodecs,
            supplementalProfiles,
            essentialProperties,
            supplementalProperties
        )

        if (!looksLikeHevcVideo(format, codecs)) {
            return format
        }

        val cicpColorInfo = colorInfoFromCicp(essentialProperties + supplementalProperties)
            ?: return format
        val existingColorInfo = format.colorInfo
        if (existingColorInfo?.isValid == true) {
            return format
        }

        val fixedFormat = format.buildUpon()
            .setColorInfo(cicpColorInfo)
            .build()
        Log.i(
            TAG,
            "Injected DASH CICP color into HEVC format " +
                "id=${fixedFormat.id} codecs=${fixedFormat.codecs} color=${fixedFormat.colorInfo}"
        )
        return fixedFormat
    }

    private fun colorInfoFromCicp(descriptors: List<Descriptor>): ColorInfo? {
        val colorPrimaries = descriptors.cicpValue(CICP_COLOUR_PRIMARIES)
        val transferCharacteristics = descriptors.cicpValue(CICP_TRANSFER_CHARACTERISTICS)

        if (colorPrimaries != ISO_COLOR_PRIMARIES_BT2020 ||
            transferCharacteristics != ISO_TRANSFER_CHARACTERISTICS_HLG
        ) {
            return null
        }

        return ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            // F1's HDR streams are hev1.2.4.* Main10 / 10-bit. Without an
            // explicit luma/chroma bit depth here, the MediaTek secure HEVC
            // decoder (c2.mtk.hevc.decoder.secure) falls back to 8-bit
            // Gralloc buffer allocation. Writing 10-bit HEVC slices into
            // 8-bit buffers renders as a solid green plane.
            .setLumaBitdepth(10)
            .setChromaBitdepth(10)
            .build()
    }

    private fun List<Descriptor>.cicpValue(schemeIdUri: String): Int? {
        return firstOrNull { it.schemeIdUri.equals(schemeIdUri, ignoreCase = true) }
            ?.value
            ?.toIntOrNull()
    }

    private fun looksLikeHevcVideo(format: Format, codecs: String?): Boolean {
        return format.sampleMimeType == MimeTypes.VIDEO_H265 ||
            codecs?.startsWith("hev", ignoreCase = true) == true ||
            codecs?.startsWith("hvc", ignoreCase = true) == true
    }

    private companion object {
        private const val TAG = "F1DashManifestParser"
        private const val CICP_COLOUR_PRIMARIES = "urn:mpeg:mpegB:cicp:ColourPrimaries"
        private const val CICP_TRANSFER_CHARACTERISTICS = "urn:mpeg:mpegB:cicp:TransferCharacteristics"
        private const val ISO_COLOR_PRIMARIES_BT2020 = 9
        private const val ISO_TRANSFER_CHARACTERISTICS_HLG = 18
    }
}
