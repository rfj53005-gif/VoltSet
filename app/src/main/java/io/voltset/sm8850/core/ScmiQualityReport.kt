package io.voltset.sm8850.core

import kotlin.math.roundToInt

data class ClusterQualityScore(val domain: Int, val coefficient: Long, val score: Int)

data class ScmiQualityReport(val clusters: List<ClusterQualityScore>, val points: List<ScmiPoint>) {
    val score: Int get() = clusters.sumOf { it.score } / clusters.size
    val coefficients: Map<Int, Long> get() = clusters.associate { it.domain to it.coefficient }

    companion object {
        fun evaluate(first: List<ScmiPoint>, second: List<ScmiPoint>): ScmiQualityReport? {
            if (first != second) return null
            val clusters = mutableListOf<ClusterQualityScore>()
            val decoded = mutableListOf<ScmiPoint>()
            for (domain in 0..1) {
                val rows = first.filter { it.domain == domain }
                if (rows.size < 2 || rows.map { it.levelIndex }.sorted() != rows.indices.toList()) return null
                val (coefficient, voltages) = solveCluster(rows, domain) ?: return null
                // Entertainment weighting from jkHXQ/RMX5010-CPU-Voltage, applied to this table's level count.
                val score = voltages.mapIndexed { index, mv ->
                    (1100 - mv) * if (index == voltages.lastIndex) 95 else 5
                }.sum() / voltages.size
                clusters += ClusterQualityScore(domain, coefficient, score)
                decoded += rows.zip(voltages) { row, mv -> row.copy(voltageMv = mv, exactFormulaMatch = true) }
            }
            return ScmiQualityReport(clusters, decoded)
        }

        private fun solveCluster(rows: List<ScmiPoint>, domain: Int): Pair<Long, List<Int>>? {
            val candidates = (80L..200L).mapNotNull { coefficient ->
                val voltages = rows.mapNotNull { row -> uniqueVoltage(row, coefficient) }
                if (voltages.size == rows.size && voltages.zipWithNext().all { (low, high) -> low <= high }) {
                    coefficient to voltages
                } else {
                    null
                }
            }
            val aligned = candidates.filter { (_, voltages) -> voltages.all { it % 4 == 0 } }
            val preferred = aligned.ifEmpty { candidates }
            return preferred.singleOrNull() ?: preferred.firstOrNull { (coefficient, _) ->
                coefficient == ScmiPlanParser.powerCoefficient(domain, isSm8850 = true)
            }
        }

        private fun uniqueVoltage(row: ScmiPoint, coefficient: Long): Int? {
            if (row.freqKhz !in 1..Int.MAX_VALUE.toLong() || row.powerUw !in 1..UInt.MAX_VALUE.toLong()) return null
            val estimate = ScmiPlanParser.estimateVoltage(row.powerUw, row.freqKhz, coefficient).roundToInt()
            if (estimate !in 299..1301) return null
            val matches = (estimate - 2..estimate + 2).filter {
                ScmiPlanParser.powerForVoltage(row.freqKhz, it, coefficient) == row.powerUw
            }
            return matches.singleOrNull()?.takeIf { it in 300..1300 }
        }
    }
}
