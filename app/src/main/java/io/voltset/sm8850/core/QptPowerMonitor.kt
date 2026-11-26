package io.voltset.sm8850.core

import kotlin.math.roundToInt

/** Raw cumulative energy snapshot (`uJ`) read from `/sys/class/powercap/qpt:0:*`. */
data class QptRawSample(
    val timestampNs: Long,
    val energyUjByRail: Map<String, Long>,
    val powerUwByRail: Map<String, Long> = emptyMap(),
    val cpu0FreqKhz: Int = 0,
    val cpu1FreqKhz: Int = 0,
    val gpuFreqHz: Long = 0L,
)

/** Per-interval average hardware power (`mW`) computed between two QPT energy samples. */
data class QptInstantPower(
    val cpuMidMw: Double?,
    val cpuLargeMw: Double?,
    val gpuMw: Double?,
    val nspMw: Double?,
    val dtMs: Long,
    val cpu0FreqKhz: Int = 0,
    val cpu1FreqKhz: Int = 0,
    val gpuFreqHz: Long = 0L,
) {
    val cpuTotalMw: Double?
        get() = if (cpuMidMw == null && cpuLargeMw == null) {
            null
        } else {
            (cpuMidMw ?: 0.0) + (cpuLargeMw ?: 0.0)
        }

    val socTotalMw: Double?
        get() = if (cpuMidMw == null && cpuLargeMw == null && gpuMw == null && nspMw == null) {
            null
        } else {
            (cpuMidMw ?: 0.0) + (cpuLargeMw ?: 0.0) + (gpuMw ?: 0.0) + (nspMw ?: 0.0)
        }
}

/** Accumulated session average power (`mW`) weighted by each sample interval duration. */
data class QptSessionStats(
    val sampleCount: Int,
    val elapsedSec: Double,
    val avgCpuMidMw: Double?,
    val avgCpuLargeMw: Double?,
    val avgCpuTotalMw: Double?,
    val avgGpuMw: Double?,
    val avgNspMw: Double?,
)

/** Saved hardware average power snapshot for a given OEM Set ID, used for Set-to-Set comparison. */
data class SavedSetPowerRecord(
    val setId: Int,
    val sampleCount: Int,
    val elapsedSec: Int,
    val avgCpuMidMw: Int,
    val avgCpuLargeMw: Int,
    val avgCpuTotalMw: Int,
    val avgGpuMw: Int,
    val avgNspMw: Int,
    val recordedAtMs: Long,
)

/** Parser and calculator for Qualcomm QPT hardware powercap energy counters. */
object QptPowerParser {
    private const val RAIL_CPU_MID = "cpu-m"
    private const val RAIL_CPU_LARGE = "cpu-l"
    private const val RAIL_GPU = "gpu"
    private const val RAIL_NSP = "nsp"
    private const val MIN_DT_NS = 50_000_000L

    fun parseSample(output: String, timestampNs: Long): QptRawSample {
        val energyByRail = linkedMapOf<String, Long>()
        val powerByRail = linkedMapOf<String, Long>()
        var cpu0Freq = 0
        var cpu1Freq = 0
        var gpuFreq = 0L

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            when (key) {
                "QPT_RAIL" -> {
                    val colon = value.indexOf(':')
                    if (colon > 0) {
                        val rail = value.substring(0, colon).trim()
                        val uj = value.substring(colon + 1).trim().toLongOrNull()
                        if (rail.isNotEmpty() && !rail.startsWith("debug-") && uj != null && uj >= 0L) {
                            energyByRail[rail] = uj
                        }
                    }
                }
                "QPT_POWER_UW" -> {
                    val colon = value.indexOf(':')
                    if (colon > 0) {
                        val rail = value.substring(0, colon).trim()
                        val uw = value.substring(colon + 1).trim().toLongOrNull()
                        if (rail.isNotEmpty() && !rail.startsWith("debug-") && uw != null && uw >= 0L) {
                            powerByRail[rail] = uw
                        }
                    }
                }
                "FREQ_CPU0_KHZ" -> cpu0Freq = value.toIntOrNull()?.coerceAtLeast(0) ?: 0
                "FREQ_CPU1_KHZ" -> cpu1Freq = value.toIntOrNull()?.coerceAtLeast(0) ?: 0
                "FREQ_GPU_HZ" -> gpuFreq = value.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            }
        }

        return QptRawSample(
            timestampNs = timestampNs,
            energyUjByRail = energyByRail,
            powerUwByRail = powerByRail,
            cpu0FreqKhz = cpu0Freq,
            cpu1FreqKhz = cpu1Freq,
            gpuFreqHz = gpuFreq,
        )
    }

    fun computeDelta(prev: QptRawSample, curr: QptRawSample): QptInstantPower? {
        val dtNs = curr.timestampNs - prev.timestampNs
        if (dtNs < MIN_DT_NS) return null

        fun railPowerMw(name: String): Double? {
            val e1 = prev.energyUjByRail[name] ?: return null
            val e2 = curr.energyUjByRail[name] ?: return null
            if (e2 < e1) return null
            if (e2 == e1) {
                val fallbackUw = curr.powerUwByRail[name]
                if (fallbackUw != null && fallbackUw > 0L) {
                    return fallbackUw.toDouble() / 1000.0
                }
            }
            return (e2 - e1).toDouble() * 1_000_000.0 / dtNs.toDouble()
        }

        val mid = railPowerMw(RAIL_CPU_MID)
        val large = railPowerMw(RAIL_CPU_LARGE)
        val gpu = railPowerMw(RAIL_GPU)
        val nsp = railPowerMw(RAIL_NSP)
        if (mid == null && large == null && gpu == null && nsp == null) return null

        return QptInstantPower(
            cpuMidMw = mid,
            cpuLargeMw = large,
            gpuMw = gpu,
            nspMw = nsp,
            dtMs = dtNs / 1_000_000L,
            cpu0FreqKhz = curr.cpu0FreqKhz,
            cpu1FreqKhz = curr.cpu1FreqKhz,
            gpuFreqHz = curr.gpuFreqHz,
        )
    }

    fun summarizeSession(samples: List<QptInstantPower>): QptSessionStats? {
        if (samples.isEmpty()) return null
        val totalMs = samples.sumOf { it.dtMs.coerceAtLeast(1L) }

        fun weightedMean(selector: (QptInstantPower) -> Double?): Double? {
            var weightedSum = 0.0
            var weightMs = 0L
            for (s in samples) {
                val v = selector(s) ?: continue
                val w = s.dtMs.coerceAtLeast(1L)
                weightedSum += v * w
                weightMs += w
            }
            return if (weightMs > 0L) weightedSum / weightMs.toDouble() else null
        }

        val mid = weightedMean { it.cpuMidMw }
        val large = weightedMean { it.cpuLargeMw }
        val cpuTotal = weightedMean { it.cpuTotalMw }
        val gpu = weightedMean { it.gpuMw }
        val nsp = weightedMean { it.nspMw }

        return QptSessionStats(
            sampleCount = samples.size,
            elapsedSec = totalMs.toDouble() / 1000.0,
            avgCpuMidMw = mid,
            avgCpuLargeMw = large,
            avgCpuTotalMw = cpuTotal,
            avgGpuMw = gpu,
            avgNspMw = nsp,
        )
    }

    fun buildSavedRecord(
        setId: Int,
        stats: QptSessionStats,
        recordedAtMs: Long,
    ): SavedSetPowerRecord? {
        val total = stats.avgCpuTotalMw ?: return null
        return SavedSetPowerRecord(
            setId = setId,
            sampleCount = stats.sampleCount,
            elapsedSec = stats.elapsedSec.roundToInt().coerceAtLeast(1),
            avgCpuMidMw = (stats.avgCpuMidMw ?: 0.0).roundToInt(),
            avgCpuLargeMw = (stats.avgCpuLargeMw ?: 0.0).roundToInt(),
            avgCpuTotalMw = total.roundToInt(),
            avgGpuMw = (stats.avgGpuMw ?: 0.0).roundToInt(),
            avgNspMw = (stats.avgNspMw ?: 0.0).roundToInt(),
            recordedAtMs = recordedAtMs,
        )
    }

    fun serializeRecords(records: Map<Int, SavedSetPowerRecord>): String =
        records.values
            .sortedBy { it.setId }
            .joinToString("\n") { r ->
                listOf(
                    r.setId,
                    r.sampleCount,
                    r.elapsedSec,
                    r.avgCpuMidMw,
                    r.avgCpuLargeMw,
                    r.avgCpuTotalMw,
                    r.avgGpuMw,
                    r.avgNspMw,
                    r.recordedAtMs,
                ).joinToString(",")
            }

    fun deserializeRecords(raw: String?): Map<Int, SavedSetPowerRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        val map = linkedMapOf<Int, SavedSetPowerRecord>()
        for (line in raw.lineSequence()) {
            val cols = line.trim().split(',')
            if (cols.size < 9) continue
            val setId = cols[0].toIntOrNull()?.takeIf { it in 1..7 } ?: continue
            val sampleCount = cols[1].toIntOrNull()?.takeIf { it > 0 } ?: continue
            val elapsedSec = cols[2].toIntOrNull()?.takeIf { it > 0 } ?: continue
            val mid = cols[3].toIntOrNull()?.coerceAtLeast(0) ?: continue
            val large = cols[4].toIntOrNull()?.coerceAtLeast(0) ?: continue
            val total = cols[5].toIntOrNull()?.coerceAtLeast(0) ?: continue
            val gpu = cols[6].toIntOrNull()?.coerceAtLeast(0) ?: continue
            val nsp = cols[7].toIntOrNull()?.coerceAtLeast(0) ?: continue
            val ts = cols[8].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            map[setId] = SavedSetPowerRecord(
                setId = setId,
                sampleCount = sampleCount,
                elapsedSec = elapsedSec,
                avgCpuMidMw = mid,
                avgCpuLargeMw = large,
                avgCpuTotalMw = total,
                avgGpuMw = gpu,
                avgNspMw = nsp,
                recordedAtMs = ts,
            )
        }
        return map
    }
}
