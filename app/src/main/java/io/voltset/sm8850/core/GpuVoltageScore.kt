package io.voltset.sm8850.core

internal data class GpuVoltageCorner(val index: Int, val level: Int)

internal class GpuVoltageCorners private constructor(private val levels: List<Int>) {
    val availableLevels: List<Int> get() = levels

    fun indexOfLevel(level: Int): Int? = levels.indexOf(level).takeIf { it >= 0 }

    fun resolve(requestedLevel: Long): GpuVoltageCorner? {
        if (requestedLevel <= 0) return null
        // KGSL takes the first gfx.lvl level at or above the request.
        val index = levels.indexOfFirst { it >= requestedLevel }
        return if (index >= 0) GpuVoltageCorner(index, levels[index]) else null
    }

    companion object {
        private val entry = Regex("""0[xX][0-9a-fA-F]+:\s+gfx\.lvl\s+\[([0-9a-fA-F\s]+)]""")
        private val whitespace = Regex("""\s+""")

        fun parse(line: String): GpuVoltageCorners? {
            val hex = entry.matchEntire(line.trim())?.groupValues?.get(1)?.trim() ?: return null
            val tokens = hex.split(whitespace)
            if (tokens.size !in 4..128 || tokens.size % 2 != 0 || tokens.any { it.length != 2 }) return null
            val bytes = tokens.map { it.toInt(16) }
            return fromLevels(bytes.chunked(2).map { (low, high) -> low or (high shl 8) })
        }

        fun fromLevels(values: List<Int>): GpuVoltageCorners? {
            val levels = values.dropWhile { it == 0 }.dropLastWhile { it == 0 }
            if (levels.any { it <= 0 } || levels.zipWithNext().any { (low, high) -> low > high }) return null
            val distinct = levels.distinct()
            return if (distinct.size >= 2) GpuVoltageCorners(distinct) else null
        }
    }
}

internal data class GpuVoltagePoint(
    val factoryLevel: Int,
    val effectiveLevel: Int,
    val reduction: Int,
)

internal data class GpuVoltageSegment(
    val startHz: Long,
    val endHz: Long,
    val startRank: Int,
    val endRank: Int,
) {
    fun rankAt(hz: Long): Double =
        startRank + (endRank - startRank) * (hz - startHz).toDouble() / (endHz - startHz)
}

internal data class GpuVoltageEstimate(
    val score: Double,
    val coveredHz: Long,
    val referenceSpanHz: Long,
    val referenceCurve: List<GpuVoltageSegment>,
    val currentCurve: List<GpuVoltageSegment>,
) {
    val coverage: Double get() = coveredHz.toDouble() / referenceSpanHz
}

internal data class GpuVoltageScore(
    val stockFrequencyCount: Int,
    val points: Map<Long, GpuVoltagePoint>,
    val estimate: GpuVoltageEstimate? = null,
) {
    val exactScore: Double?
        get() = if (points.isEmpty()) null
        else 100.0 + points.values.sumOf { it.reduction }.toDouble() / stockFrequencyCount

    val score: Double? get() = exactScore ?: estimate?.score

    companion object {
        fun evaluate(
            rows: List<GpuRow>,
            stockLevels: Map<Long, Long>,
            corners: GpuVoltageCorners,
            referenceCorners: GpuVoltageCorners = corners,
        ): GpuVoltageScore {
            val reference = stockLevels.filterKeys { it > 0 }.mapValues { (_, level) -> referenceCorners.resolve(level) }
            val effective = rows.map { it.value }.filter { it.frequencyHz > 0 }.groupBy { it.frequencyHz }
                .mapValues { (_, values) ->
                    val request = values.singleOrNull()?.voltageLevel ?: return@mapValues null
                    val current = corners.resolve(request) ?: return@mapValues null
                    val index = referenceCorners.indexOfLevel(current.level) ?: return@mapValues null
                    GpuVoltageCorner(index, current.level)
                }
            val matched = buildMap {
                for ((hz, stock) in reference) {
                    val current = effective[hz] ?: continue
                    if (stock != null) put(hz, GpuVoltagePoint(stock.level, current.level, stock.index - current.index))
                }
            }
            val estimate = estimate(reference, effective)
            return GpuVoltageScore(reference.size, matched, estimate)
        }

        private fun estimate(
            reference: Map<Long, GpuVoltageCorner?>,
            effective: Map<Long, GpuVoltageCorner?>,
        ): GpuVoltageEstimate? {
            if (reference.size < 2) return null
            val referenceSpanHz = reference.keys.max() - reference.keys.min()
            val referenceCurve = segments(reference)
            val currentCurve = segments(effective)
            var coveredHz = 0L
            var integral = 0.0
            for (stock in referenceCurve) {
                for (current in currentCurve) {
                    val start = maxOf(stock.startHz, current.startHz)
                    val end = minOf(stock.endHz, current.endHz)
                    if (start >= end) continue
                    val width = end - start
                    val startDelta = stock.rankAt(start) - current.rankAt(start)
                    val endDelta = stock.rankAt(end) - current.rankAt(end)
                    // The difference is linear on the overlap, so the trapezoid gives its exact integral.
                    integral += (startDelta + endDelta) * width / 2.0
                    coveredHz += width
                }
            }
            return if (coveredHz == 0L) null else GpuVoltageEstimate(
                100.0 + integral / referenceSpanHz, coveredHz, referenceSpanHz, referenceCurve, currentCurve,
            )
        }

        private fun segments(points: Map<Long, GpuVoltageCorner?>): List<GpuVoltageSegment> =
            points.entries.sortedBy { it.key }.zipWithNext().mapNotNull { (start, end) ->
                val from = start.value ?: return@mapNotNull null
                val to = end.value ?: return@mapNotNull null
                GpuVoltageSegment(start.key, end.key, from.index, to.index)
            }
    }
}
