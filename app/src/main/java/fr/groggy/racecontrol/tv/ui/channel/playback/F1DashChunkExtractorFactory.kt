package fr.groggy.racecontrol.tv.ui.channel.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor
import androidx.media3.exoplayer.source.chunk.ChunkExtractor
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Track
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

@UnstableApi
class F1DashChunkExtractorFactory : ChunkExtractor.Factory {

    private val delegate = BundledChunkExtractor.Factory()
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var parseSubtitlesDuringExtraction = false
    private var codecsToParseWithinGopSampleDependencies = C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ChunkExtractor.Factory {
        this.subtitleParserFactory = subtitleParserFactory
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalParseSubtitlesDuringExtraction(
        parseSubtitlesDuringExtraction: Boolean
    ): ChunkExtractor.Factory {
        this.parseSubtitlesDuringExtraction = parseSubtitlesDuringExtraction
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int
    ): ChunkExtractor.Factory {
        this.codecsToParseWithinGopSampleDependencies = codecsToParseWithinGopSampleDependencies
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun getOutputTextFormat(sourceFormat: Format): Format {
        return delegate.getOutputTextFormat(sourceFormat)
    }

    override fun createProgressiveMediaExtractor(
        primaryTrackType: Int,
        representationFormat: Format,
        enableEventMessageTrack: Boolean,
        closedCaptionFormats: MutableList<Format>,
        playerEmsgTrackOutput: TrackOutput?,
        playerId: PlayerId
    ): ChunkExtractor? {
        if (!looksLikeF1HevcVideo(primaryTrackType, representationFormat)) {
            return delegate.createProgressiveMediaExtractor(
                primaryTrackType,
                representationFormat,
                enableEventMessageTrack,
                closedCaptionFormats,
                playerEmsgTrackOutput,
                playerId
            )
        }

        var flags = 0
        if (enableEventMessageTrack) {
            flags = flags or FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK
        }
        if (!parseSubtitlesDuringExtraction) {
            flags = flags or FragmentedMp4Extractor.FLAG_EMIT_RAW_SUBTITLE_DATA
        }
        flags = flags or FragmentedMp4Extractor.codecsToParseWithinGopSampleDependenciesAsFlags(
            codecsToParseWithinGopSampleDependencies
        )

        Log.i(
            TAG,
            "Using F1 DASH HEVC chunk extractor id=${representationFormat.id} " +
                "codecs=${representationFormat.codecs} color=${representationFormat.colorInfo}"
        )

        val extractor = F1HevcFragmentedMp4Extractor(
            subtitleParserFactory = subtitleParserFactory,
            flags = flags,
            closedCaptionFormats = closedCaptionFormats,
            playerEmsgTrackOutput = playerEmsgTrackOutput,
            manifestFormat = representationFormat
        )
        return BundledChunkExtractor(extractor, primaryTrackType, representationFormat)
    }

    private fun looksLikeF1HevcVideo(primaryTrackType: Int, format: Format): Boolean {
        if (primaryTrackType != C.TRACK_TYPE_VIDEO) return false
        val descriptor = listOfNotNull(format.id, format.codecs).joinToString(separator = " ")
        return format.sampleMimeType == MimeTypes.VIDEO_H265 ||
            descriptor.contains("HEVC", ignoreCase = true) ||
            descriptor.contains("hev1", ignoreCase = true) ||
            descriptor.contains("hvc1", ignoreCase = true)
    }

    private class F1HevcFragmentedMp4Extractor(
        subtitleParserFactory: SubtitleParser.Factory,
        flags: Int,
        closedCaptionFormats: List<Format>,
        playerEmsgTrackOutput: TrackOutput?,
        private val manifestFormat: Format
    ) : FragmentedMp4Extractor(
        subtitleParserFactory,
        flags,
        null as TimestampAdjuster?,
        null as Track?,
        closedCaptionFormats,
        playerEmsgTrackOutput
    ) {

        override fun modifyTrack(track: Track?): Track? {
            track ?: return null
            if (!looksLikeHevc(track.format)) return track

            val manifestColorInfo = manifestFormat.colorInfo
            val mergedFormatBuilder = track.format.buildUpon()
                .setId(manifestFormat.id ?: track.format.id)
                .setLabel(manifestFormat.label ?: track.format.label)
                .setCodecs(manifestFormat.codecs ?: track.format.codecs)
                .setDrmInitData(manifestFormat.drmInitData ?: track.format.drmInitData)
                .setColorInfo(
                    if (manifestColorInfo?.isValid == true) {
                        manifestColorInfo
                    } else {
                        track.format.colorInfo
                    }
                )

            val codedDimensions = parseCodedDimensions(track.format.initializationData)
            if (codedDimensions != null) {
                mergedFormatBuilder
                    .setDecodedWidth(codedDimensions.first)
                    .setDecodedHeight(codedDimensions.second)
                Log.i(
                    TAG,
                    "Parsed F1 DASH HEVC coded dimensions " +
                        "${codedDimensions.first}x${codedDimensions.second} " +
                        "display=${track.format.width}x${track.format.height} id=${manifestFormat.id}"
                )
            }

            val mergedFormat = mergedFormatBuilder.build()

            Log.i(
                TAG,
                "Merged F1 DASH HEVC manifest format into parsed track " +
                    "manifestId=${manifestFormat.id} parsedCodecs=${track.format.codecs} " +
                    "mergedCodecs=${mergedFormat.codecs} parsedColor=${track.format.colorInfo} " +
                    "mergedColor=${mergedFormat.colorInfo} initData=${mergedFormat.initializationData.size}"
            )

            return track.copyWithFormat(mergedFormat)
        }

        private fun looksLikeHevc(format: Format): Boolean {
            val descriptor = listOfNotNull(format.id, format.codecs).joinToString(separator = " ")
            return format.sampleMimeType == MimeTypes.VIDEO_H265 ||
                descriptor.contains("HEVC", ignoreCase = true) ||
                descriptor.contains("hev", ignoreCase = true) ||
                descriptor.contains("hvc", ignoreCase = true)
        }

        private fun parseCodedDimensions(initializationData: List<ByteArray>): Pair<Int, Int>? {
            var vps: ByteArray? = null
            var sps: ByteArray? = null

            for (block in initializationData) {
                var position = 0
                while (position + 4 <= block.size) {
                    if (block[position] != 0.toByte() ||
                        block[position + 1] != 0.toByte() ||
                        block[position + 2] != 0.toByte() ||
                        block[position + 3] != 1.toByte()
                    ) {
                        position++
                        continue
                    }

                    val nalStart = position + 4
                    var nalEnd = nalStart
                    while (nalEnd + 4 <= block.size &&
                        !(block[nalEnd] == 0.toByte() &&
                            block[nalEnd + 1] == 0.toByte() &&
                            block[nalEnd + 2] == 0.toByte() &&
                            block[nalEnd + 3] == 1.toByte())
                    ) {
                        nalEnd++
                    }
                    if (nalEnd > nalStart) {
                        val nal = block.copyOfRange(nalStart, nalEnd)
                        when ((nal[0].toInt() and 0x7E) ushr 1) {
                            32 -> vps = nal
                            33 -> sps = nal
                        }
                    }
                    position = nalEnd
                }
            }

            val vpsData = vps ?: return null
            val spsData = sps ?: return null
            return runCatching {
                val parsedVps = NalUnitUtil.parseH265VpsNalUnit(vpsData, 0, vpsData.size)
                val parsedSps = NalUnitUtil.parseH265SpsNalUnit(
                    spsData,
                    0,
                    spsData.size,
                    parsedVps
                )
                parsedSps.decodedWidth to parsedSps.decodedHeight
            }.getOrNull()
        }
    }

    private companion object {
        private const val TAG = "F1DashChunkExtractor"
    }
}
