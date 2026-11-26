package io.voltset.sm8850.core

internal data class GpuVoltageCorner(val index: Int, val level: Int)

internal class GpuVoltageCorners private constructor(private val levels: List<Int>) {
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
            val levels = bytes.chunked(2).map { (low, high) -> low or (high shl 8) }
                .dropWhile { it == 0 }.dropLastWhile { it == 0 }
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

internal data class GpuVoltageScore(
    val stockFrequencyCount: Int,
    val points: Map<Long, GpuVoltagePoint>,
) {
    val score: Double?
        get() = if (points.isEmpty()) null else 100.0 + points.values.sumOf { it.reduction }.toDouble() / stockFrequencyCount

    companion object {
        fun evaluate(rows: List<GpuRow>, stockLevels: Map<Long, Long>, corners: GpuVoltageCorners): GpuVoltageScore {
            val frequencies = stockLevels.keys.filter { it > 0 }
            val matched = buildMap {
                for (hz in frequencies) {
                    val row = rows.singleOrNull { it.value.frequencyHz == hz } ?: continue
                    val stock = corners.resolve(stockLevels.getValue(hz)) ?: continue
                    val current = corners.resolve(row.value.voltageLevel) ?: continue
                    put(hz, GpuVoltagePoint(stock.level, current.level, stock.index - current.index))
                }
            }
            return GpuVoltageScore(frequencies.size, matched)
        }
    }
}
