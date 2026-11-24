package io.voltset.sm8850.core

/** Per-frequency hardware voltage bounds & ACD margin extracted from `pdp` firmware DCVS tables. */
data class FreqVoltageLimit(
    val freqKhz: Long,
    val cxFloorMv: Int,
    val cxCeilMv: Int,
    val olvAcdMarginMv: Int,
    val mxFloorMv: Int,
    val mxCeilMv: Int,
)

/** Matched hardware floor/ceil limits for one CPU cluster (PDP0 or PDP1). */
data class ClusterHardwareLimits(
    val domain: Int,
    val exactTableMatch: Boolean,
    val limitsByFreqKhz: Map<Long, FreqVoltageLimit>,
)

/** Hardware limits matched from the active-slot `pdp` firmware partition for both clusters. */
data class PdpHardwareLimits(
    val totalDcvsTablesFound: Int,
    val clus0: ClusterHardwareLimits?,
    val clus1: ClusterHardwareLimits?,
) {
    fun forDomain(domain: Int): ClusterHardwareLimits? = when (domain) {
        0 -> clus0
        1 -> clus1
        else -> null
    }

    val isComplete: Boolean get() = clus0 != null && clus1 != null
}

/** Single 0x9C-byte entry inside a CFDB DCVS table in `pdp` firmware. */
data class PdpDcvsEntry(
    val corner: Int,
    val enableFlags: BooleanArray,
    val limit: FreqVoltageLimit,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PdpDcvsEntry) return false
        return corner == other.corner && enableFlags.contentEquals(other.enableFlags) && limit == other.limit
    }

    override fun hashCode(): Int {
        var result = corner
        result = 31 * result + enableFlags.contentHashCode()
        result = 31 * result + limit.hashCode()
        return result
    }
}

/** Unique structurally valid DCVS table parsed from `pdp` firmware CFDB v3. */
data class PdpDcvsTable(
    val entries: List<PdpDcvsEntry>,
)

/**
 * Pure-Kotlin structural parser for Qualcomm SM8850 `pdp` firmware partitions
 * (ELF32 LE -> CFDB v3 -> 0x9C-byte DCVS voltage-plan records).
 *
 * Rather than hardcoding SoC/speed-bin/VPID key strings (which vary across devices),
 * tables are recognized structurally and matched against the device's observed SCMI frequencies.
 */
object PdpFirmwareParser {
    private const val DCVS_RECORD_SIZE = 0x9C
    private const val SENTINEL_CORNER = 0x7FFFFFFFu

    fun parse(bytes: ByteArray): List<PdpDcvsTable> {
        val doc = CfdbReader.parse(bytes, label = "pdp", maxEntryCount = 16_384)
        val seenOffsets = HashSet<UInt>()
        val tables = mutableListOf<PdpDcvsTable>()

        for (entry in doc.entries) {
            if (entry.type != 2 || entry.size < 2 * DCVS_RECORD_SIZE || entry.size % DCVS_RECORD_SIZE != 0) {
                continue
            }
            if (!seenOffsets.add(entry.valueOrOffset)) continue
            val blob = doc.sliceBlob(entry)
            parseDcvsBlob(blob)?.let { tables += it }
        }

        require(tables.isNotEmpty()) { "pdp 固件中未找到有效的 DCVS 硬件限位表" }
        return tables
    }

    fun matchDeviceLimits(
        tables: List<PdpDcvsTable>,
        baselinePoints: List<ScmiPoint>?,
        livePoints: List<ScmiPoint>,
    ): PdpHardwareLimits {
        fun pointsFor(domain: Int): List<ScmiPoint> =
            baselinePoints?.filter { it.domain == domain }?.takeIf { it.isNotEmpty() }
                ?: livePoints.filter { it.domain == domain }

        return PdpHardwareLimits(
            totalDcvsTablesFound = tables.size,
            clus0 = matchClusterLimits(tables, domain = 0, clusterPoints = pointsFor(0)),
            clus1 = matchClusterLimits(tables, domain = 1, clusterPoints = pointsFor(1)),
        )
    }

    fun matchClusterLimits(
        tables: List<PdpDcvsTable>,
        domain: Int,
        clusterPoints: List<ScmiPoint>,
    ): ClusterHardwareLimits? {
        if (clusterPoints.isEmpty() || tables.isEmpty()) return null
        val sortedPoints = clusterPoints.sortedBy { it.freqKhz }
        val targetFreqs = sortedPoints.map { it.freqKhz }

        // Pass 1: exact ordered frequency list match across all (table, flexId)
        val exactCandidates = mutableListOf<MatchCandidate>()
        for (table in tables) {
            for (flexId in 0 until 8) {
                val active = table.entries.filter { it.enableFlags[flexId] }
                if (active.map { it.limit.freqKhz } != targetFreqs) continue
                exactCandidates += buildCandidate(active, sortedPoints, extraCount = 0)
            }
        }
        resolveUnambiguous(exactCandidates, domain, exactTableMatch = true)?.let { return it }
        if (exactCandidates.isNotEmpty()) return null

        // Pass 2: superset fallback in case OEM runtime DT filtered boost frequencies
        val subsetCandidates = mutableListOf<MatchCandidate>()
        for (table in tables) {
            for (flexId in 0 until 8) {
                val active = table.entries.filter { it.enableFlags[flexId] }
                if (active.size < targetFreqs.size) continue
                val byFreq = active.associateBy { it.limit.freqKhz }
                val matched = targetFreqs.mapNotNull { byFreq[it] }
                if (matched.size != targetFreqs.size) continue
                subsetCandidates += buildCandidate(
                    matchedEntries = matched,
                    sortedPoints = sortedPoints,
                    extraCount = active.size - targetFreqs.size,
                )
            }
        }
        return resolveUnambiguous(subsetCandidates, domain, exactTableMatch = false)
    }

    private fun parseDcvsBlob(blob: ByteArray): PdpDcvsTable? {
        val count = blob.size / DCVS_RECORD_SIZE
        if (count < 2) return null
        val tailOffset = (count - 1) * DCVS_RECORD_SIZE
        if (CfdbReader.u32Le(blob, tailOffset) != SENTINEL_CORNER) return null

        val entries = ArrayList<PdpDcvsEntry>(count - 1)
        for (i in 0 until (count - 1)) {
            val base = i * DCVS_RECORD_SIZE
            val corner = CfdbReader.u32Le(blob, base).toInt()
            if (corner !in 1..2048) return null

            val flags = BooleanArray(8)
            for (bit in 0 until 8) {
                val rawFlag = blob[base + 6 + bit].toInt() and 0xFF
                if (rawFlag != 0 && rawFlag != 1) return null
                flags[bit] = rawFlag == 1
            }

            val freqKhz = CfdbReader.u32Le(blob, base + 0x10).toLong()
            if (freqKhz !in 100_000L..7_000_000L) return null

            val cxCeilMv = CfdbReader.i16Le(blob, base + 0x7C)
            val cxFloorMv = CfdbReader.i16Le(blob, base + 0x7E)
            if (cxFloorMv !in 300..1600 || cxCeilMv !in cxFloorMv..1600) return null

            val olvAcdMarginMv = CfdbReader.i16Le(blob, base + 0x84)
            val mxCeilMv = CfdbReader.i16Le(blob, base + 0x8C)
            val mxFloorMv = CfdbReader.i16Le(blob, base + 0x8E)

            entries += PdpDcvsEntry(
                corner = corner,
                enableFlags = flags,
                limit = FreqVoltageLimit(
                    freqKhz = freqKhz,
                    cxFloorMv = cxFloorMv,
                    cxCeilMv = cxCeilMv,
                    olvAcdMarginMv = olvAcdMarginMv,
                    mxFloorMv = mxFloorMv,
                    mxCeilMv = mxCeilMv,
                ),
            )
        }
        return PdpDcvsTable(entries)
    }

    private fun buildCandidate(
        matchedEntries: List<PdpDcvsEntry>,
        sortedPoints: List<ScmiPoint>,
        extraCount: Int,
    ): MatchCandidate {
        var inBounds = 0
        for (idx in matchedEntries.indices) {
            val lim = matchedEntries[idx].limit
            val mv = sortedPoints[idx].voltageMv
            if (mv > 0 && mv in lim.cxFloorMv..lim.cxCeilMv) {
                inBounds++
            }
        }
        val limits = matchedEntries.map { it.limit }
        return MatchCandidate(
            limits = limits,
            boundsSignature = limits.map { it.cxFloorMv to it.cxCeilMv },
            inBoundsCount = inBounds,
            extraCount = extraCount,
        )
    }

    private fun resolveUnambiguous(
        candidates: List<MatchCandidate>,
        domain: Int,
        exactTableMatch: Boolean,
    ): ClusterHardwareLimits? {
        if (candidates.isEmpty()) return null
        val minExtra = candidates.minOf { it.extraCount }
        val narrowest = candidates.filter { it.extraCount == minExtra }
        val bestInBounds = narrowest.maxOf { it.inBoundsCount }
        val top = narrowest.filter { it.inBoundsCount == bestInBounds }

        val signature = top.first().boundsSignature
        if (top.any { it.boundsSignature != signature }) {
            return null
        }

        return ClusterHardwareLimits(
            domain = domain,
            exactTableMatch = exactTableMatch,
            limitsByFreqKhz = top.first().limits.associateBy { it.freqKhz },
        )
    }

    private data class MatchCandidate(
        val limits: List<FreqVoltageLimit>,
        val boundsSignature: List<Pair<Int, Int>>,
        val inBoundsCount: Int,
        val extraCount: Int,
    )
}
