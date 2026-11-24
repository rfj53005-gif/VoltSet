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
    const val CFDB_MAGIC: UInt = 0x0003DBCFu
    const val HASH_OVERRIDE: UInt = 0xB47A3C0Bu
    const val HASH_CLUS0: UInt = 0x0E675698u
    const val HASH_CLUS1: UInt = 0x7AC09099u

    private const val CFDB_HEADER_SIZE = 16
    private const val CFDB_ENTRY_SIZE = 16
    private const val RECORD_SIZE = 13

    fun sdbmHash(name: String): UInt {
        var hash = 0u
        for (byte in name.encodeToByteArray()) {
            hash = byte.toUByte().toUInt() + (hash shl 6) + (hash shl 16) - hash
        }
        return hash
    }

    fun parse(bytes: ByteArray): PdpCdbData {
        require(bytes.size >= CFDB_HEADER_SIZE) { "pdp_cdb 数据长度不足 (${bytes.size} B)" }

        val (cfdbOffset, cfdbLength) = locateCfdb(bytes)
        require(cfdbOffset >= 0 && cfdbLength >= CFDB_HEADER_SIZE && cfdbOffset + cfdbLength <= bytes.size) {
            "CFDB 段边界异常 (offset=$cfdbOffset, len=$cfdbLength, total=${bytes.size})"
        }
        require(u32Le(bytes, cfdbOffset) == CFDB_MAGIC) {
            "缺少 CFDB v3 魔数"
        }

        val entryCount = u32Le(bytes, cfdbOffset + 4).toInt()
        require(entryCount in 0..4096) { "CFDB 目录项数量异常 ($entryCount)" }
        val tableBytes = entryCount.toLong() * CFDB_ENTRY_SIZE.toLong()
        require(CFDB_HEADER_SIZE + tableBytes <= cfdbLength) {
            "CFDB 目录表超出段长度"
        }

        var overrideSetId: Int? = null
        var clus0Blob: ByteArray? = null
        var clus1Blob: ByteArray? = null

        for (i in 0 until entryCount) {
            val base = cfdbOffset + CFDB_HEADER_SIZE + i * CFDB_ENTRY_SIZE
            val hash = u32Le(bytes, base)
            val valueOrOffset = u32Le(bytes, base + 4)
            val typeAndSize = u32Le(bytes, base + 8)
            val type = (typeAndSize and 0xFFu).toInt()
            val size = (typeAndSize shr 8).toInt()

            when (hash) {
                HASH_OVERRIDE -> if (type == 0) {
                    overrideSetId = valueOrOffset.toInt()
                }
                HASH_CLUS0 -> if (type == 2) {
                    clus0Blob = sliceBlob(bytes, cfdbOffset, cfdbLength, valueOrOffset, size)
                }
                HASH_CLUS1 -> if (type == 2) {
                    clus1Blob = sliceBlob(bytes, cfdbOffset, cfdbLength, valueOrOffset, size)
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

    private fun locateCfdb(bytes: ByteArray): Pair<Int, Int> {
        if (u32Le(bytes, 0) == CFDB_MAGIC) {
            return 0 to bytes.size
        }

        require(
            bytes.size >= 52 &&
                bytes[0] == 0x7F.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[4] == 1.toByte() &&
                bytes[5] == 1.toByte(),
        ) {
            "无法识别的 pdp_cdb 格式（非 ELF32 LE 且非 CFDB）"
        }

        val phoff = u32Le(bytes, 28).toLong()
        val phentsize = u16Le(bytes, 42)
        val phnum = u16Le(bytes, 44)
        require(phentsize >= 32 && phoff >= 0 && phoff + phnum.toLong() * phentsize <= bytes.size) {
            "ELF32 Program Header 越界"
        }

        for (i in 0 until phnum) {
            val ph = (phoff + i.toLong() * phentsize).toInt()
            val pType = u32Le(bytes, ph)
            if (pType != 1u) continue // PT_LOAD
            val offset = u32Le(bytes, ph + 4).toLong()
            val filesz = u32Le(bytes, ph + 16).toLong()
            if (offset < 0 || filesz < CFDB_HEADER_SIZE || offset + filesz > bytes.size) continue
            val offInt = offset.toInt()
            if (u32Le(bytes, offInt) == CFDB_MAGIC) {
                return offInt to filesz.toInt()
            }
        }
        throw IllegalArgumentException("ELF32 中未找到包含 CFDB v3 的 PT_LOAD 段")
    }

    private fun sliceBlob(
        bytes: ByteArray,
        cfdbOffset: Int,
        cfdbLength: Int,
        relOffset: UInt,
        size: Int,
    ): ByteArray {
        val rel = relOffset.toLong()
        require(size >= 0 && rel >= 0 && rel + size <= cfdbLength) {
            "CFDB Blob 越界 (rel=$rel, size=$size, cfdbLength=$cfdbLength)"
        }
        val start = cfdbOffset + rel.toInt()
        return bytes.copyOfRange(start, start + size)
    }

    private fun parseRecords(blob: ByteArray): List<RawVoltageRecord> {
        val out = mutableListOf<RawVoltageRecord>()
        var pos = 0
        while (pos + RECORD_SIZE <= blob.size) {
            val setId = u32Le(blob, pos).toInt()
            if (setId == 0) break
            val modeCode = blob[pos + 4].toInt() and 0xFF
            val value = u32Le(blob, pos + 5).toLong()
            val cxMv = i16Le(blob, pos + 9)
            val mxMv = i16Le(blob, pos + 11)
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

    private fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun i16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)

    private fun u32Le(bytes: ByteArray, offset: Int): UInt =
        (bytes[offset].toUByte().toUInt()) or
            (bytes[offset + 1].toUByte().toUInt() shl 8) or
            (bytes[offset + 2].toUByte().toUInt() shl 16) or
            (bytes[offset + 3].toUByte().toUInt() shl 24)
}
