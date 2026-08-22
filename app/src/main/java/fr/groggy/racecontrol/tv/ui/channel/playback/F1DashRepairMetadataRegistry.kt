package fr.groggy.racecontrol.tv.ui.channel.playback

/**
 * Manifest-derived per-representation metadata for [F1DashInitSegmentFixingDataSource],
 * keyed by the same resolved init-segment URI used by [DynamicHvcCExtractor]. Populated
 * synchronously during [F1DynamicHvcCDashManifestParser.parse] -- pure manifest reading,
 * no network I/O -- so unlike the async hvcC extraction cache, it's always fully
 * populated before any chunk fetch for that representation can begin; no waiting needed.
 *
 * Exists so the DataSource never has to guess: whether a given init segment belongs to
 * a genuinely HDR representation, and if so what its real CICP color characteristics
 * are, comes from what the manifest actually declared -- not from URL keyword
 * heuristics or a hardcoded BT.2020/HLG assumption.
 */
object F1DashRepairMetadataRegistry {
    private const val MAX_ENTRIES = 12

    data class ColorInfo(val primaries: Int, val transfer: Int, val matrix: Int)

    data class RepairMetadata(
        val expectedWidth: Int,
        val expectedHeight: Int,
        val colorInfo: ColorInfo?
    )

    // Bounded LRU for the same reason as DynamicHvcCExtractor's cache: F1 signs
    // manifest URLs per-session, so entries are rarely reused across sessions.
    private val entries = object : LinkedHashMap<String, RepairMetadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RepairMetadata>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun register(initSegmentUri: String, metadata: RepairMetadata) {
        entries[initSegmentUri] = metadata
    }

    @Synchronized
    fun get(initSegmentUri: String): RepairMetadata? = entries[initSegmentUri]
}
