package io.voltset.sm8850.core

import kotlin.math.max
import kotlin.math.min

/** Adjust mode inside a pdp_cdb 13-byte OEM voltage adjustment record. */
enum class AdjustMode(val code: Int) {
    FREQ(1),
    PSTATE(2),
    ALL(3),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): AdjustMode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Semantic classification derived dynamically from decoded pdp_cdb records. */
enum class SetProfileTag {
    DEFAULT,
    UNIFORM,
    CURVE,
}

/** Single raw 13-byte record inside clus0/clus1.OEM_VOLTAGE_ADJUSTMENTS. */
data class RawVoltageRecord(
    val setId: Int,
    val mode: AdjustMode,
    val rawModeCode: Int,
    val value: Long,
    val cxMv: Int,
    val mxMv: Int,
)

/** Effective per-frequency or per-pstate offset after accumulating matching ALL records. */
data class EffectiveAdjustment(
    val mode: AdjustMode,
    val value: Long,
    val rawDeltaCxMv: Int,
    val rawDeltaMxMv: Int,
    val effectiveCxMv: Int,
    val effectiveMxMv: Int,
)

/** Summary of all adjustments belonging to one (setId, cluster) pair. */
data class ClusterSetSummary(
    val cluster: Int,
    val allCxMv: Int,
    val allMxMv: Int,
    val entries: List<EffectiveAdjustment>,
    val minCxMv: Int,
    val maxCxMv: Int,
    val minMxMv: Int,
    val maxMxMv: Int,
) {
    val isUniform: Boolean get() = entries.isEmpty()
    val isNoOp: Boolean get() = minCxMv == 0 && maxCxMv == 0 && minMxMv == 0 && maxMxMv == 0

    fun predictedCxOffsetForFreq(freqKhz: Long): Int {
        val exact = entries.firstOrNull { it.mode == AdjustMode.FREQ && it.value == freqKhz }
        return exact?.effectiveCxMv ?: allCxMv
    }

    /**
     * Firmware (`0x200290bc` -> `0x20029b44`) accumulates `ALL`, matching `FREQ`,
     * and matching `PSTATE` rules for the same slot.
     */
    fun predictedCxOffsetForBin(freqKhz: Long, levelIndex: Int): Int {
        var delta = allCxMv
        for (entry in entries) {
            when (entry.mode) {
                AdjustMode.FREQ -> if (entry.value == freqKhz) delta += entry.rawDeltaCxMv
                AdjustMode.PSTATE -> if (entry.value == levelIndex.toLong()) delta += entry.rawDeltaCxMv
                AdjustMode.ALL, AdjustMode.UNKNOWN -> Unit
            }
        }
        return delta
    }
}

/** Decoded configuration for one OEM voltage adjustment Set (typically 1..7). */
data class VoltageSetProfile(
    val setId: Int,
    val clus0: ClusterSetSummary,
    val clus1: ClusterSetSummary,
) {
    val tag: SetProfileTag
        get() = when {
            setId == 1 || (clus0.isNoOp && clus1.isNoOp) -> SetProfileTag.DEFAULT
            clus0.isUniform && clus1.isUniform -> SetProfileTag.UNIFORM
            else -> SetProfileTag.CURVE
        }

    val isAggressive: Boolean
        get() = minOf(clus0.minCxMv, clus1.minCxMv) <= -20

    fun predictedCxOffsetForFreq(cluster: Int, freqKhz: Long): Int? = when (cluster) {
        0 -> clus0.predictedCxOffsetForFreq(freqKhz)
        1 -> clus1.predictedCxOffsetForFreq(freqKhz)
        else -> null
    }

    fun predictedCxOffsetForBin(cluster: Int, freqKhz: Long, levelIndex: Int): Int? = when (cluster) {
        0 -> clus0.predictedCxOffsetForBin(freqKhz, levelIndex)
        1 -> clus1.predictedCxOffsetForBin(freqKhz, levelIndex)
        else -> null
    }
}

/** Full decoded pdp_cdb partition payload. */
data class PdpCdbData(
    val overrideSetId: Int?,
    val clus0Records: List<RawVoltageRecord>,
    val clus1Records: List<RawVoltageRecord>,
    val sets: List<VoltageSetProfile>,
)

/**
 * Pure-Kotlin parser for Qualcomm SM8850 `pdp_cdb` partitions (ELF32 -> CFDB v3 -> OEM_VOLTAGE_ADJUSTMENTS).
 */
object PdpCdbParser {
    const val CFDB_MAGIC: UInt = CfdbReader.CFDB_MAGIC
    const val HASH_OVERRIDE: UInt = 0xB47A3C0Bu
    const val HASH_CLUS0: UInt = 0x0E675698u
    const val HASH_CLUS1: UInt = 0x7AC09099u

    private const val RECORD_SIZE = 13

    fun sdbmHash(name: String): UInt {
        var hash = 0u
        for (byte in name.encodeToByteArray()) {
            hash = byte.toUByte().toUInt() + (hash shl 6) + (hash shl 16) - hash
        }
        return hash
    }

    fun parse(bytes: ByteArray): PdpCdbData {
        val doc = CfdbReader.parse(bytes, label = "pdp_cdb", maxEntryCount = 4096)

        var overrideSetId: Int? = null
        var clus0Blob: ByteArray? = null
        var clus1Blob: ByteArray? = null

        for (entry in doc.entries) {
            when (entry.hash) {
                HASH_OVERRIDE -> if (entry.type == 0) {
                    overrideSetId = entry.valueOrOffset.toInt()
                }
                HASH_CLUS0 -> if (entry.type == 2) {
                    clus0Blob = doc.sliceBlob(entry)
                }
                HASH_CLUS1 -> if (entry.type == 2) {
                    clus1Blob = doc.sliceBlob(entry)
                }
            }
        }

        val clus0Records = clus0Blob?.let(::parseRecords) ?: emptyList()
        val clus1Records = clus1Blob?.let(::parseRecords) ?: emptyList()

        val observedIds = (clus0Records.asSequence().map { it.setId } +
            clus1Records.asSequence().map { it.setId } +
            (1..7).asSequence())
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()

        val sets = observedIds.map { setId ->
            VoltageSetProfile(
                setId = setId,
                clus0 = summarizeCluster(0, clus0Records.filter { it.setId == setId }),
                clus1 = summarizeCluster(1, clus1Records.filter { it.setId == setId }),
            )
        }

        return PdpCdbData(
            overrideSetId = overrideSetId,
            clus0Records = clus0Records,
            clus1Records = clus1Records,
            sets = sets,
        )
    }

    private fun parseRecords(blob: ByteArray): List<RawVoltageRecord> {
        val out = mutableListOf<RawVoltageRecord>()
        var pos = 0
        while (pos + RECORD_SIZE <= blob.size) {
            val setId = CfdbReader.u32Le(blob, pos).toInt()
            if (setId == 0) break
            val modeCode = blob[pos + 4].toInt() and 0xFF
            val value = CfdbReader.u32Le(blob, pos + 5).toLong()
            val cxMv = CfdbReader.i16Le(blob, pos + 9)
            val mxMv = CfdbReader.i16Le(blob, pos + 11)
            out += RawVoltageRecord(
                setId = setId,
                mode = AdjustMode.fromCode(modeCode),
                rawModeCode = modeCode,
                value = value,
                cxMv = cxMv,
                mxMv = mxMv,
            )
            pos += RECORD_SIZE
        }
        return out
    }

    private fun summarizeCluster(cluster: Int, records: List<RawVoltageRecord>): ClusterSetSummary {
        var allCx = 0
        var allMx = 0
        val specifics = linkedMapOf<Pair<AdjustMode, Long>, Pair<Int, Int>>()

        for (rec in records) {
            when (rec.mode) {
                AdjustMode.ALL -> {
                    allCx += rec.cxMv
                    allMx += rec.mxMv
                }
                AdjustMode.FREQ, AdjustMode.PSTATE -> {
                    val key = rec.mode to rec.value
                    val prev = specifics[key]
                    specifics[key] = if (prev == null) {
                        rec.cxMv to rec.mxMv
                    } else {
                        (prev.first + rec.cxMv) to (prev.second + rec.mxMv)
                    }
                }
                AdjustMode.UNKNOWN -> Unit
            }
        }

        val entries = specifics.entries
            .sortedWith(compareBy({ it.key.first.code }, { it.key.second }))
            .map { (key, delta) ->
                EffectiveAdjustment(
                    mode = key.first,
                    value = key.second,
                    rawDeltaCxMv = delta.first,
                    rawDeltaMxMv = delta.second,
                    effectiveCxMv = allCx + delta.first,
                    effectiveMxMv = allMx + delta.second,
                )
            }

        var minCx = allCx
        var maxCx = allCx
        var minMx = allMx
        var maxMx = allMx
        for (entry in entries) {
            minCx = min(minCx, entry.effectiveCxMv)
            maxCx = max(maxCx, entry.effectiveCxMv)
            minMx = min(minMx, entry.effectiveMxMv)
            maxMx = max(maxMx, entry.effectiveMxMv)
        }

        return ClusterSetSummary(
            cluster = cluster,
            allCxMv = allCx,
            allMxMv = allMx,
            entries = entries,
            minCxMv = minCx,
            maxCxMv = maxCx,
            minMxMv = minMx,
            maxMxMv = maxMx,
        )
    }
}
