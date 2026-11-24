package io.voltset.sm8850.core

/** Per-frequency simulation result under the device's stock baseline and `pdp` hardware limits. */
data class BinVoltageEstimate(
    val freqKhz: Long,
    val baselineMv: Int,
    val estimatedMv: Int,
    val deltaMv: Int,
    val cdbOffsetMv: Int,
    val floorMv: Int,
    val ceilMv: Int,
    val clampedByFloor: Boolean,
    val clampedByMonotonicity: Boolean,
    val atStockCeiling: Boolean,
)

/** Cluster-wide simulated voltage delta summary for one Set profile on the current chip. */
data class ClusterSetEstimate(
    val domain: Int,
    val totalBins: Int,
    val loweredBins: Int,
    val minDeltaMv: Int,
    val maxDeltaMv: Int,
    val floorClampedBins: Int,
    val monotonicityClampedBins: Int,
    val ceilingRiskBins: Int,
    val hasRoundingVariance: Boolean,
    val bins: List<BinVoltageEstimate>,
)

/** Full per-device predicted voltage deltas for one Set profile across PDP0 and PDP1. */
data class SetDeviceEstimate(
    val setId: Int,
    val clus0: ClusterSetEstimate?,
    val clus1: ClusterSetEstimate?,
) {
    fun forDomain(domain: Int): ClusterSetEstimate? = when (domain) {
        0 -> clus0
        1 -> clus1
        else -> null
    }
}

/**
 * Simulates SM8850 PDP voltage calculation (`0x20029b44`) from the device's own Set 1 SCMI baseline,
 * `pdp_cdb` OEM Set rules, and `pdp` firmware hardware `[floor, ceil]` limits:
 *
 *   1. Accumulate `ALL + FREQ + PSTATE` `cx_mv` for the bin;
 *   2. Add to stock Set 1 OLV and clamp to `[cxFloorMv, cxCeilMv]`;
 *   3. Round UP to 4 mV PMIC step;
 *   4. Enforce ascending monotonic voltage across frequency bins.
 */
object VoltageSetEstimator {
    fun estimateAll(
        sets: List<VoltageSetProfile>,
        baselinePoints: List<ScmiPoint>?,
        limits: PdpHardwareLimits?,
    ): Map<Int, SetDeviceEstimate> {
        if (baselinePoints.isNullOrEmpty() || limits == null || sets.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, SetDeviceEstimate>(sets.size)
        for (set in sets) {
            val est0 = limits.clus0?.let { lim ->
                estimateCluster(set.clus0, baselinePoints.filter { it.domain == 0 }, lim)
            }
            val est1 = limits.clus1?.let { lim ->
                estimateCluster(set.clus1, baselinePoints.filter { it.domain == 1 }, lim)
            }
            if (est0 != null || est1 != null) {
                out[set.setId] = SetDeviceEstimate(setId = set.setId, clus0 = est0, clus1 = est1)
            }
        }
        return out
    }

    fun estimateCluster(
        summary: ClusterSetSummary,
        baseline: List<ScmiPoint>,
        limits: ClusterHardwareLimits,
    ): ClusterSetEstimate? {
        if (baseline.isEmpty()) return null
        val sorted = baseline.sortedBy { it.freqKhz }
        if (sorted.any { !it.exactFormulaMatch || limits.limitsByFreqKhz[it.freqKhz] == null }) {
            return null
        }

        var prevMv = 0
        var hasRoundingVariance = false
        var floorClampedBins = 0
        var monotonicityClampedBins = 0
        var ceilingRiskBins = 0
        var loweredBins = 0
        val bins = ArrayList<BinVoltageEstimate>(sorted.size)

        for (point in sorted) {
            val lim = limits.limitsByFreqKhz.getValue(point.freqKhz)
            val offset = summary.predictedCxOffsetForBin(point.freqKhz, point.levelIndex)
            if (offset % 4 != 0) hasRoundingVariance = true

            val atStockCeiling = point.voltageMv >= lim.cxCeilMv && offset < 0
            if (atStockCeiling) ceilingRiskBins++

            val raw = point.voltageMv + offset
            val clampedByFloor = raw < lim.cxFloorMv
            if (clampedByFloor) floorClampedBins++

            val clamped = raw.coerceIn(lim.cxFloorMv, lim.cxCeilMv)
            val rem = clamped and 3
            val aligned = if (rem != 0) clamped + 4 - rem else clamped

            val clampedByMonotonicity = prevMv > 0 && aligned < prevMv
            if (clampedByMonotonicity) monotonicityClampedBins++
            val finalMv = if (clampedByMonotonicity) prevMv else aligned
            prevMv = finalMv

            val delta = finalMv - point.voltageMv
            if (delta < 0) loweredBins++

            bins += BinVoltageEstimate(
                freqKhz = point.freqKhz,
                baselineMv = point.voltageMv,
                estimatedMv = finalMv,
                deltaMv = delta,
                cdbOffsetMv = offset,
                floorMv = lim.cxFloorMv,
                ceilMv = lim.cxCeilMv,
                clampedByFloor = clampedByFloor,
                clampedByMonotonicity = clampedByMonotonicity,
                atStockCeiling = atStockCeiling,
            )
        }

        return ClusterSetEstimate(
            domain = limits.domain,
            totalBins = bins.size,
            loweredBins = loweredBins,
            minDeltaMv = bins.minOf { it.deltaMv },
            maxDeltaMv = bins.maxOf { it.deltaMv },
            floorClampedBins = floorClampedBins,
            monotonicityClampedBins = monotonicityClampedBins,
            ceilingRiskBins = ceilingRiskBins,
            hasRoundingVariance = hasRoundingVariance,
            bins = bins,
        )
    }
}
