package io.voltset.sm8850.core

/** High-level classification of CDT Key 7 (SLT+ACD) status on disk. */
enum class CdtState {
    EMPTY,
    STOCK,
    SLT_ENABLED,
    CORRUPT,
    UNAVAILABLE,
}

/** Read-only CDT inspection result focused on detecting Key 7 (SLT) stacking risk. */
data class CdtStatus(
    val state: CdtState,
    val version: Int?,
    val key7Value: Int?,
    val keyPairs: List<Pair<Int, Int>>,
    val detail: String,
) {
    val key7Enabled: Boolean
        get() = state == CdtState.SLT_ENABLED || (key7Value != null && key7Value != 0)
}

/**
 * Read-only parser for Qualcomm CDT partition header + Platform Info block (v3..v6).
 * Handles all-zero empty partitions as well as Xiaomi OEM 9-byte abbreviated v6 blocks.
 */
object CdtInspector {
    fun inspect(bytes: ByteArray?): CdtStatus {
        if (bytes == null || bytes.isEmpty()) {
            return CdtStatus(
                state = CdtState.UNAVAILABLE,
                version = null,
                key7Value = null,
                keyPairs = emptyList(),
                detail = "未能读取 CDT 分区",
            )
        }

        if (bytes.all { it == 0.toByte() }) {
            return CdtStatus(
                state = CdtState.EMPTY,
                version = null,
                key7Value = null,
                keyPairs = emptyList(),
                detail = "CDT 分区全零（原厂默认，Key 7 未开启）",
            )
        }

        val hasMagic = bytes.size >= 18 &&
            bytes[0] == 'C'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == 'T'.code.toByte() &&
            bytes[3] == 0.toByte()
        if (!hasMagic) {
            return corrupt("CDT 头魔数无效")
        }

        val off0 = u16Le(bytes, 14)
        val size0 = u16Le(bytes, 16)
        if (off0 < 14 || size0 < 6 || off0 + size0 > bytes.size) {
            return corrupt("CDT Platform Info 块越界 (off=$off0, size=$size0)")
        }

        val version = bytes[off0].toInt() and 0xFF
        if (version == 6 && size0 == 9) {
            return CdtStatus(
                state = CdtState.STOCK,
                version = version,
                key7Value = null,
                keyPairs = emptyList(),
                detail = "原厂精简 v6 CDT（9 字节，Key 7 未开启）",
            )
        }

        val hdrLen = when {
            version == 3 -> 6
            version >= 6 -> 10
            else -> 7
        }
        if (size0 < hdrLen) {
            return corrupt("CDT v$version 块头长度不足 ($size0 < $hdrLen)")
        }

        val keyCount = bytes[off0 + hdrLen - 1].toInt() and 0xFF
        if (hdrLen + keyCount * 2 > size0) {
            return corrupt("CDT v$version 键值数组越界 (keyCount=$keyCount, size=$size0)")
        }

        val pairs = ArrayList<Pair<Int, Int>>(keyCount)
        var key7Value: Int? = null
        for (i in 0 until keyCount) {
            val k = bytes[off0 + hdrLen + i * 2].toInt() and 0xFF
            val v = bytes[off0 + hdrLen + i * 2 + 1].toInt() and 0xFF
            pairs += k to v
            if (k == 7) {
                key7Value = v
            }
        }

        return if (key7Value != null && key7Value != 0) {
            CdtStatus(
                state = CdtState.SLT_ENABLED,
                version = version,
                key7Value = key7Value,
                keyPairs = pairs,
                detail = "检测到 CDT v$version Key 7=$key7Value（SLT+ACD 已开启，将与 OEM Set 叠加！）",
            )
        } else {
            CdtStatus(
                state = CdtState.STOCK,
                version = version,
                key7Value = key7Value,
                keyPairs = pairs,
                detail = "原厂 v$version CDT（Key 7=${key7Value ?: "无"}，未开启）",
            )
        }
    }

    private fun corrupt(reason: String) = CdtStatus(
        state = CdtState.CORRUPT,
        version = null,
        key7Value = null,
        keyPairs = emptyList(),
        detail = reason,
    )

    private fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
