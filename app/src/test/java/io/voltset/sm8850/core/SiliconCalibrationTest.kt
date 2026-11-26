package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiliconCalibrationTest {
    @Test
    fun parseRealDeviceQfpromAgingFuses() {
        val parsed = QfpromAgingFuses.parse("daa822e1,21006ecb,0000001c")
        assertNotNull(parsed)
        assertEquals(-2, parsed!!.clus0FusedDelta)
        assertEquals(8, parsed.clus1FusedDelta)
        assertEquals("0xdaa822e1 / 0x21006ecb / 0x0000001c", parsed.formatRawHex())
    }

    @Test
    fun parseQfpromHandlesPositiveClus0AndNegativeClus1() {
        // clus0 = +5 -> bits [18:12] = 5 << 12 = 0x5000, bit 19 = 0
        // clus1 = -12 -> 0x24 = 0xc -> bit1=0? Wait: mag1=(w1<<6)|(w0>>26)
        // Set w0 = 0, w1 = 0x02 (sign=1, bit0=0), Wait: if clus1=-3 -> mag1=3 => w1 bit0=0, w0>>26 = 3 => w0=0x0c000000, w1=0x2
        val parsed = QfpromAgingFuses.parse("00005000,0c000000,00000002")!!
        assertEquals(5, parsed.clus0FusedDelta)
        assertEquals(-3, parsed.clus1FusedDelta)

        assertNull(QfpromAgingFuses.parse("none"))
        assertNull(QfpromAgingFuses.parse("bad,data"))
    }

    @Test
    fun parseSocInfoFields() {
        val parsed = SocInfoFields.parse("chip=676 foundry=1 feature=0 pcode=0 oem=0 pmic_rev=1")
        assertNotNull(parsed)
        assertEquals("676", parsed!!.chipId)
        assertEquals("1", parsed.foundryId)
        assertEquals("0", parsed.pcode)
        assertEquals("1", parsed.pmicRev)
        assertNull(SocInfoFields.parse("none"))
    }

    @Test
    fun assessCprhDriftDetectsSet1SmallStepChangesOnly() {
        val driftRows = listOf(
            VoltageDiffRow(0, 883200, 628, 624, 4, 0),
            VoltageDiffRow(0, 1094400, 656, 656, 0, 0),
            VoltageDiffRow(1, 2496000, 820, 824, -4, 0),
        )
        val drift = CprhDriftAssessment.assess(bootCpussIndex = 1, diffRows = driftRows)
        assertTrue(drift.isLikelyCprhDrift)
        assertEquals(2, drift.affectedBins)
        assertEquals(-4, drift.minDeltaMv)
        assertEquals(4, drift.maxDeltaMv)

        // When on Set 6, negative shifts should NOT be flagged as Set 1 CPRH drift
        val set6 = CprhDriftAssessment.assess(bootCpussIndex = 6, diffRows = driftRows)
        assertFalse(set6.isLikelyCprhDrift)

        // When Set 1 has a huge -20 mV jump, it is NOT normal CPRH jitter
        val bigDiff = CprhDriftAssessment.assess(
            bootCpussIndex = 1,
            diffRows = listOf(VoltageDiffRow(0, 883200, 604, 624, -20, 0)),
        )
        assertFalse(bigDiff.isLikelyCprhDrift)
    }
}
