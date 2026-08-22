package fr.groggy.racecontrol.tv.ui.channel.playback

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Descriptor
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

class F1DynamicHvcCDashManifestParser : ParsingLoadable.Parser<DashManifest> {

    private val defaultParser = DashManifestParser()

    override fun parse(uri: Uri, inputStream: InputStream): DashManifest {
        val manifest = defaultParser.parse(uri, inputStream)

        try {
            extractDynamicParameters(manifest, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract dynamic HEVC parameter sets", e)
        }

        return manifest
    }

    private fun extractDynamicParameters(manifest: DashManifest, manifestUri: Uri) {
        for (i in 0 until manifest.periodCount) {
            val period = manifest.getPeriod(i)

            for (adaptationSet in period.adaptationSets) {
                if (adaptationSet.type == C.TRACK_TYPE_VIDEO) {
                    for (representation in adaptationSet.representations) {
                        val id = representation.format.id
                        // Only care about UHD HDR representations that might need the fix
                        if (id == null || !id.contains("HDR") || !id.contains("UHD")) {
                            continue
                        }
                        val initUri = representation.getInitializationUri()
                        if (initUri == null) {
                            Log.w(TAG, "Representation $id has no initialization URI, cannot key dynamic extraction")
                            continue
                        }
                        val index = representation.index
                        if (index == null) {
                            Log.w(TAG, "Representation $id has no index, cannot extract parameters")
                            continue
                        }
                        // Resolve against the representation's own <BaseURL> (falling back to
                        // the first entry, matching DashUtil/DefaultDashChunkSource's own
                        // default before any BaseUrl exclusion happens), not the manifest
                        // fetch URI -- F1's manifest and segment/init hosts are not
                        // guaranteed to be the same, and this must produce the exact same
                        // string DefaultDashChunkSource resolves for the init segment so
                        // F1DashInitSegmentFixingDataSource can key off it.
                        val baseUrl = representation.baseUrls.first().url
                        val absoluteInitUri = initUri.resolveUriString(baseUrl)
                        val firstSegmentNum = index.getFirstSegmentNum()
                        val segmentUrl = index.getSegmentUrl(firstSegmentNum)
                        val absoluteSegmentUrl = segmentUrl.resolveUriString(baseUrl)

                        registerRepairMetadata(absoluteInitUri, representation)

                        // Keyed by the resolved init-segment URI (not representation id
                        // parsed back out of a CDN URL) so F1DashInitSegmentFixingDataSource
                        // can look it up with the exact same URI it sees when opening the
                        // init segment, regardless of CDN URL shape.
                        DynamicHvcCExtractor.extractAsync(
                            initSegmentUri = absoluteInitUri,
                            representationId = id,
                            sampleSegmentUrl = absoluteSegmentUrl,
                            expectedWidth = representation.format.width,
                            expectedHeight = representation.format.height
                        )
                    }
                }
            }
        }
    }

    /**
     * Reads the manifest's real CICP color descriptors (if present) instead of
     * ever assuming BT.2020/HLG. Requires all three of ColourPrimaries,
     * TransferCharacteristics and MatrixCoefficients to be present -- F1's
     * manifests declare all three together for HDR representations -- so a
     * partial/ambiguous signal never gets turned into a guess.
     */
    private fun registerRepairMetadata(initUri: String, representation: Representation) {
        val descriptors = representation.essentialProperties + representation.supplementalProperties
        val primaries = descriptors.cicpValue(CICP_COLOUR_PRIMARIES)
        val transfer = descriptors.cicpValue(CICP_TRANSFER_CHARACTERISTICS)
        val matrix = descriptors.cicpValue(CICP_MATRIX_COEFFICIENTS)

        val colorInfo = if (primaries != null && transfer != null && matrix != null) {
            F1DashRepairMetadataRegistry.ColorInfo(primaries, transfer, matrix)
        } else {
            null
        }

        F1DashRepairMetadataRegistry.register(
            initUri,
            F1DashRepairMetadataRegistry.RepairMetadata(
                expectedWidth = representation.format.width,
                expectedHeight = representation.format.height,
                colorInfo = colorInfo
            )
        )
    }

    private fun List<Descriptor>.cicpValue(schemeIdUri: String): Int? {
        return firstOrNull { it.schemeIdUri.equals(schemeIdUri, ignoreCase = true) }
            ?.value
            ?.toIntOrNull()
    }

    companion object {
        private const val TAG = "F1DynamicHvcCParser"
        private const val CICP_COLOUR_PRIMARIES = "urn:mpeg:mpegB:cicp:ColourPrimaries"
        private const val CICP_TRANSFER_CHARACTERISTICS = "urn:mpeg:mpegB:cicp:TransferCharacteristics"
        private const val CICP_MATRIX_COEFFICIENTS = "urn:mpeg:mpegB:cicp:MatrixCoefficients"
    }
}
