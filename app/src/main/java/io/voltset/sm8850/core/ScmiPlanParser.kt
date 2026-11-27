package io.voltset.sm8850.core

import kotlin.math.round
import kotlin.math.sqrt

/** Decoded row from `qcom-scmi-ro.ko` SCMI PERF snapshot CSV. */
data class ScmiPoint(
    val domain: Int,
    val levelIndex: Int,
    val freqKhz: Long,
    val powerUw: Long,
    val voltageMv: Int,
    val exactFormulaMatch: Boolean,
)

/** Per-frequency comparison row between live SCMI voltage and stored stock baseline. */
data class VoltageDiffRow(
    val domain: Int,
    val freqKhz: Long,
    val currentMv: Int,
    val baselineMv: Int?,
    val deltaMv: Int?,
    val expectedCdbCxMv: Int?,
)

/** Summary statistics across one cluster (PDP0 or PDP1). */
data class ClusterVoltageStats(
    val domain: Int,
    val totalBins: Int,
    val loweredBins: Int,
    val unchangedBins: Int,
    val raisedBins: Int,
    val minDeltaMv: Int?,
    val maxDeltaMv: Int?,
)

/**
 * Pure-Kotlin parser and voltage calculator for SCMI PERF power tables on SM8850 / SM8750.
 *
 * Voltage derivation:
 *   power_uW = coeff * freq_kHz * mV^2 / 1e9
 * where coeff = 100 for PDP0 (domain 0), 150 for SM8850 PDP1 (domain 1), 136 for SM8750 PDP1.
 */
object ScmiPlanParser {
    fun powerCoefficient(domain: Int, isSm8850: Boolean): Long = when (domain) {
        0 -> 100L
        1 -> if (isSm8850) 150L else 136L
        else -> throw IllegalArgumentException("不支持的 SCMI domain: $domain")
    }

    fun parseCsv(
        csv: String,
        isSm8850: Boolean,
        coefficients: Map<Int, Long> = emptyMap(),
    ): List<ScmiPoint> {
        val byKey = linkedMapOf<Pair<Int, Long>, ScmiPoint>()

        for (rawLine in csv.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith("domain,")) continue
            val cols = line.split(',')
            if (cols.size < 7) continue

            val domain = cols[0].trim().toIntOrNull() ?: continue
            if (domain != 0 && domain != 1) continue
            val powerUw = cols[3].trim().toLongOrNull() ?: continue
            val freqKhz = cols[5].trim().toLongOrNull() ?: continue
            val levelIndex = cols[6].trim().toIntOrNull() ?: continue
            if (freqKhz <= 0L || powerUw <= 0L) continue

            val verifiedCoefficient = coefficients[domain]
            val coeff = verifiedCoefficient ?: powerCoefficient(domain, isSm8850)
            val rawMv = estimateVoltage(powerUw, freqKhz, coeff)
            val step = if (verifiedCoefficient != null) 1 else 4
            val roundedMv = (round(rawMv / step) * step).toInt()

            byKey[domain to freqKhz] = ScmiPoint(
                domain = domain,
                levelIndex = levelIndex,
                freqKhz = freqKhz,
                powerUw = powerUw,
                voltageMv = roundedMv,
                exactFormulaMatch = powerForVoltage(freqKhz, roundedMv, coeff) == powerUw,
            )
        }

        return byKey.values.sortedWith(compareBy({ it.domain }, { it.freqKhz }))
    }

    internal fun estimateVoltage(powerUw: Long, frequencyKhz: Long, coefficient: Long): Double =
        sqrt(powerUw.toDouble() * 1_000_000_000.0 / (coefficient * frequencyKhz.toDouble()))

    internal fun powerForVoltage(frequencyKhz: Long, voltageMv: Int, coefficient: Long): Long =
        (coefficient * frequencyKhz * voltageMv * voltageMv) / 1_000_000_000L

    fun diff(
        current: List<ScmiPoint>,
        baseline: List<ScmiPoint>?,
        activeSet: VoltageSetProfile?,
    ): List<VoltageDiffRow> {
        val baselineByKey = baseline?.associateBy { it.domain to it.freqKhz }.orEmpty()
        return current.map { point ->
            val base = baselineByKey[point.domain to point.freqKhz]
            VoltageDiffRow(
                domain = point.domain,
                freqKhz = point.freqKhz,
                currentMv = point.voltageMv,
                baselineMv = base?.voltageMv,
                deltaMv = base?.takeIf { point.exactFormulaMatch && it.exactFormulaMatch }
                    ?.let { point.voltageMv - it.voltageMv },
                expectedCdbCxMv = activeSet?.predictedCxOffsetForBin(point.domain, point.freqKhz, point.levelIndex),
            )
        }
    }

    fun summarizeByCluster(rows: List<VoltageDiffRow>, domain: Int): ClusterVoltageStats {
        val filtered = rows.filter { it.domain == domain }
        val deltas = filtered.mapNotNull { it.deltaMv }
        return ClusterVoltageStats(
            domain = domain,
            totalBins = filtered.size,
            loweredBins = deltas.count { it < 0 },
            unchangedBins = deltas.count { it == 0 },
            raisedBins = deltas.count { it > 0 },
            minDeltaMv = deltas.minOrNull(),
            maxDeltaMv = deltas.maxOrNull(),
        )
    }
}
