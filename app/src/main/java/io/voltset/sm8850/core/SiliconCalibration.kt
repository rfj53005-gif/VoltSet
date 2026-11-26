package io.voltset.sm8850.core

import kotlin.math.abs

/**
 * Decoded SM8850 QFPROM factory CPRH aging delta fuses (`0xa10`, `0xa20`, `0xa24`).
 *
 * Verified against `pdp_a.bin` (`cprh_aging_init`):
 * - clus0 (`0xa10`): signed magnitude in bits `[19:12]` (sign at bit 19, 7-bit magnitude at bits `[18:12]`)
 * - clus1 (`0xa20`/`0xa24`): 7-bit magnitude across bits `[31:26]` of `0xa20` + `[0]` of `0xa24`, sign at bit `1` of `0xa24`
 */
data class QfpromAgingFuses(
    val rawA10: Long,
    val rawA20: Long,
    val rawA24: Long,
    val clus0FusedDelta: Int,
    val clus1FusedDelta: Int,
) {
    fun formatRawHex(): String =
        "0x%08x / 0x%08x / 0x%08x".format(
            java.util.Locale.US,
            rawA10 and 0xFFFF_FFFFL,
            rawA20 and 0xFFFF_FFFFL,
            rawA24 and 0xFFFF_FFFFL,
        )

    companion object {
        fun parse(raw: String?): QfpromAgingFuses? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() && it != "none" } ?: return null
            val parts = clean.split(',').map { it.trim() }
            if (parts.size < 3) return null
            val a10 = parts[0].removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
            val a20 = parts[1].removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
            val a24 = parts[2].removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null

            val mag0 = ((a10 ushr 12) and 0x7FL).toInt()
            val clus0 = if ((a10 and 0x80000L) != 0L) -mag0 else mag0

            val mag1 = ((((a24 shl 6) and 0xFFL) or (a20 ushr 26)) and 0x7FL).toInt()
            val clus1 = if ((a24 and 0x2L) != 0L) -mag1 else mag1

            return QfpromAgingFuses(
                rawA10 = a10 and 0xFFFF_FFFFL,
                rawA20 = a20 and 0xFFFF_FFFFL,
                rawA24 = a24 and 0xFFFF_FFFFL,
                clus0FusedDelta = clus0,
                clus1FusedDelta = clus1,
            )
        }
    }
}

/** Structured fields parsed from `SystemProbeInfo.socInfo`. */
data class SocInfoFields(
    val chipId: String?,
    val foundryId: String?,
    val featureCode: String?,
    val pcode: String?,
    val oemVariant: String?,
    val pmicRev: String?,
) {
    companion object {
        fun parse(raw: String?): SocInfoFields? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() && it != "none" } ?: return null
            val kv = linkedMapOf<String, String>()
            for (token in clean.split(Regex("\\s+"))) {
                val eq = token.indexOf('=')
                if (eq > 0) {
                    val k = token.substring(0, eq).trim()
                    val v = token.substring(eq + 1).trim()
                    if (v.isNotEmpty()) kv[k] = v
                }
            }
            val parsed = SocInfoFields(
                chipId = kv["chip"],
                foundryId = kv["foundry"],
                featureCode = kv["feature"],
                pcode = kv["pcode"],
                oemVariant = kv["oem"],
                pmicRev = kv["pmic_rev"],
            )
            return parsed.takeIf {
                it.chipId != null || it.foundryId != null || it.featureCode != null ||
                    it.pcode != null || it.oemVariant != null || it.pmicRev != null
            }
        }
    }
}

/** Evaluates whether Set 1 baseline discrepancies match cold-boot CPRH sensor rounding jitter (`±4..8 mV`). */
data class CprhDriftAssessment(
    val isLikelyCprhDrift: Boolean,
    val affectedBins: Int,
    val minDeltaMv: Int,
    val maxDeltaMv: Int,
) {
    companion object {
        fun assess(bootCpussIndex: Int?, diffRows: List<VoltageDiffRow>): CprhDriftAssessment {
            val nonZero = diffRows.mapNotNull { it.deltaMv }.filter { it != 0 }
            if (bootCpussIndex != 1 || nonZero.isEmpty()) {
                return CprhDriftAssessment(
                    isLikelyCprhDrift = false,
                    affectedBins = 0,
                    minDeltaMv = 0,
                    maxDeltaMv = 0,
                )
            }
            val matchesCprhStep = nonZero.all { abs(it) <= 8 && it % 4 == 0 }
            return CprhDriftAssessment(
                isLikelyCprhDrift = matchesCprhStep,
                affectedBins = nonZero.size,
                minDeltaMv = nonZero.minOrNull() ?: 0,
                maxDeltaMv = nonZero.maxOrNull() ?: 0,
            )
        }
    }
}
