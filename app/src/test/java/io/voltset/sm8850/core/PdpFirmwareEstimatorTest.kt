package io.voltset.sm8850.core

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PdpFirmwareEstimatorTest {
    @Test
    fun scmiComparisonIncludesFrequencyAndPstateOffsetsForTheSameBin() {
        val cluster = ClusterSetSummary(
            cluster = 0,
            allCxMv = -5,
            allMxMv = 0,
            entries = listOf(
                EffectiveAdjustment(AdjustMode.FREQ, 384_000L, -3, 0, -8, 0),
                EffectiveAdjustment(AdjustMode.PSTATE, 4L, -7, 0, -12, 0),
            ),
            minCxMv = -12,
            maxCxMv = -5,
            minMxMv = 0,
            maxMxMv = 0,
        )
        val points = listOf(
            scmiPoint(0, 4, 384_000, 100L, 488),
            scmiPoint(0, 5, 537_600, 100L, 492),
        )
        val set = VoltageSetProfile(6, cluster, cluster.copy(cluster = 1))
        assertEquals(listOf(-15, -5), ScmiPlanParser.diff(points, null, set).map { it.expectedCdbCxMv })
    }

    @Test
    fun syntheticElfParseAndClusterMatchingWorkAcrossFlexIds() {
        val table1 = buildDcvsBlob(
            listOf(
                TestDcvsRow(corner = 52, freqKhz = 384_000, cxCeil = 616, cxFloor = 484, flexMask = 0b0000_0011),
                TestDcvsRow(corner = 56, freqKhz = 537_600, cxCeil = 644, cxFloor = 484, flexMask = 0b0000_0011),
                TestDcvsRow(corner = 64, freqKhz = 691_200, cxCeil = 676, cxFloor = 500, flexMask = 0b0000_0010),
            ),
        )
        val table2 = buildDcvsBlob(
            listOf(
                TestDcvsRow(corner = 100, freqKhz = 768_000, cxCeil = 676, cxFloor = 516, flexMask = 0b0000_0001),
                TestDcvsRow(corner = 128, freqKhz = 883_200, cxCeil = 740, cxFloor = 516, flexMask = 0b0000_0001),
            ),
        )
        val elf = buildSyntheticElfCfdb(listOf(0x11111111u to table1, 0x22222222u to table2))
        val parsed = PdpFirmwareParser.parse(elf)
        assertEquals(2, parsed.size)

        // Exact frequency list match (flex 1 in table1 has 3 bins)
        val clus0Points = listOf(
            scmiPoint(0, 0, 384_000, 100L, 488),
            scmiPoint(0, 1, 537_600, 100L, 488),
            scmiPoint(0, 2, 691_200, 100L, 520),
        )
        val matched0 = PdpFirmwareParser.matchClusterLimits(parsed, 0, clus0Points)
        assertNotNull(matched0)
        assertTrue(matched0!!.exactTableMatch)
        assertEquals(500, matched0.limitsByFreqKhz.getValue(691_200).cxFloorMv)

        // Superset fallback (table1 flex 1 contains subset [384_000, 691_200])
        val subsetPoints = listOf(
            scmiPoint(0, 0, 384_000, 100L, 488),
            scmiPoint(0, 1, 691_200, 100L, 520),
        )
        val matchedSubset = PdpFirmwareParser.matchClusterLimits(parsed, 0, subsetPoints)
        assertNotNull(matchedSubset)
        assertFalse(matchedSubset!!.exactTableMatch)
        assertEquals(2, matchedSubset.limitsByFreqKhz.size)
    }

    @Test
    fun ambiguousFloorConflictReturnsNull() {
        val tableA = buildDcvsBlob(
            listOf(
                TestDcvsRow(corner = 52, freqKhz = 384_000, cxCeil = 616, cxFloor = 484, flexMask = 0b0000_0001),
                TestDcvsRow(corner = 56, freqKhz = 537_600, cxCeil = 644, cxFloor = 484, flexMask = 0b0000_0001),
            ),
        )
        val tableB = buildDcvsBlob(
            listOf(
                TestDcvsRow(corner = 52, freqKhz = 384_000, cxCeil = 616, cxFloor = 500, flexMask = 0b0000_0001),
                TestDcvsRow(corner = 56, freqKhz = 537_600, cxCeil = 644, cxFloor = 484, flexMask = 0b0000_0001),
            ),
        )
        val cfdb = buildCfdb(listOf(0x1u to tableA, 0x2u to tableB))
        val tables = PdpFirmwareParser.parse(cfdb)
        // Baseline voltage 512 is inside BOTH [484, 616] and [500, 616], so both tie on score -> ambiguous!
        val points = listOf(
            scmiPoint(0, 0, 384_000, 100L, 512),
            scmiPoint(0, 1, 537_600, 100L, 520),
        )
        assertNull(PdpFirmwareParser.matchClusterLimits(tables, 0, points))
    }

    @Test
    fun simulatorEnforcesFloorRoundingUpMonotonicityPstateAndCeilingRisk() {
        val limits = ClusterHardwareLimits(
            domain = 0,
            exactTableMatch = true,
            limitsByFreqKhz = mapOf(
                384_000L to FreqVoltageLimit(384_000, 484, 616, 0, 484, 664),
                537_600L to FreqVoltageLimit(537_600, 484, 616, 0, 484, 664),
                691_200L to FreqVoltageLimit(691_200, 500, 644, 0, 484, 664),
                998_400L to FreqVoltageLimit(998_400, 500, 644, -8, 484, 664),
            ),
        )
        val baseline = listOf(
            scmiPoint(0, 0, 384_000, 100L, 488),
            scmiPoint(0, 1, 537_600, 100L, 492),
            scmiPoint(0, 2, 691_200, 100L, 504),
            scmiPoint(0, 3, 998_400, 100L, 644), // sits at ceil!
        )
        val summary = ClusterSetSummary(
            cluster = 0,
            allCxMv = -10, // non-multiple of 4 -> rounds up
            allMxMv = 0,
            entries = listOf(
                // PSTATE #0 gets extra +10 so raw is 488; PSTATE #1 stays -10 -> clamped to 484 -> pulled up to 488 by monotonicity?
                // Let's test: PSTATE #0 gets +10 (eff 0 -> 488); #1 has -10 (eff -10 -> 482 -> floor 484 -> monotonic -> 488!)
                EffectiveAdjustment(AdjustMode.PSTATE, 0L, 10, 0, 0, 0),
                // #2 has -10 -> 494 -> floor 500 -> exact 500
                // #3 has extra -8 (eff -18) -> 626 -> roundUp(626) = 628 -> delta = -16, atStockCeiling = true
                EffectiveAdjustment(AdjustMode.FREQ, 998_400L, -8, 0, -18, 0),
            ),
            minCxMv = -18,
            maxCxMv = 0,
            minMxMv = 0,
            maxMxMv = 0,
        )

        val est = VoltageSetEstimator.estimateCluster(summary, baseline, limits)
        assertNotNull(est)
        est!!
        assertTrue(est.hasRoundingVariance)
        assertEquals(2, est.floorClampedBins) // bin 1 (492-10=482 < 484) and bin 2 (504-10=494 < 500)
        assertEquals(1, est.monotonicityClampedBins) // bin 1 pulled from 484 up to 488
        assertEquals(1, est.ceilingRiskBins) // bin 3 baseline == ceil (644)
        assertEquals(listOf(0, -4, -4, -16), est.bins.map { it.deltaMv })
        assertEquals(-16, est.minDeltaMv)
        assertEquals(0, est.maxDeltaMv)
    }

    @Test
    fun realXiaomi17ProFirmwareDumpsMatchMeasuredSet2AndSet6Behavior() {
        val dir = System.getenv("VOLTSET_LOCAL_FW_DIR")?.takeIf { it.isNotBlank() }
        assumeTrue("VOLTSET_LOCAL_FW_DIR unset; skipping", dir != null)
        val pdpFile = File(dir!!, "pdp_a.bin")
        val cdbFile = File(dir, "pdp_cdb_a.bin")
        assumeTrue("Local Xiaomi 17 Pro dumps absent; skipping", pdpFile.isFile && cdbFile.isFile)

        val tables = PdpFirmwareParser.parse(pdpFile.readBytes())
        val cdb = PdpCdbParser.parse(cdbFile.readBytes())
        val baseline = ScmiPlanParser.parseCsv(SAMPLE_2509FPN0BC_STOCK_CSV, isSm8850 = true)
        assertEquals(57, baseline.size)

        val limits = PdpFirmwareParser.matchDeviceLimits(tables, baseline, baseline)
        assertTrue(limits.isComplete)
        assertTrue(limits.clus0!!.exactTableMatch)
        assertTrue(limits.clus1!!.exactTableMatch)
        assertEquals(28, limits.clus0.limitsByFreqKhz.size)
        assertEquals(29, limits.clus1.limitsByFreqKhz.size)

        val estimates = VoltageSetEstimator.estimateAll(cdb.sets, baseline, limits)

        val set2 = estimates.getValue(2)
        val s2c0 = set2.clus0!!
        val s2c1 = set2.clus1!!
        assertEquals(-12, s2c0.minDeltaMv)
        assertEquals(-4, s2c0.maxDeltaMv)
        assertEquals(3, s2c0.floorClampedBins)
        assertEquals(28, s2c0.loweredBins)
        assertEquals(-12, s2c1.minDeltaMv)
        assertEquals(0, s2c1.maxDeltaMv)
        assertEquals(1, s2c1.floorClampedBins)
        assertEquals(28, s2c1.loweredBins)

        val set6 = estimates.getValue(6)
        val s6c0 = set6.clus0!!
        val s6c1 = set6.clus1!!
        assertEquals(-20, s6c0.minDeltaMv)
        assertEquals(-4, s6c0.maxDeltaMv)
        assertEquals(3, s6c0.floorClampedBins)
        assertEquals(-20, s6c1.minDeltaMv)
        assertEquals(0, s6c1.maxDeltaMv)
        assertEquals(2, s6c1.floorClampedBins)
        assertEquals(0, s6c1.bins.first { it.freqKhz == 768_000L }.deltaMv)
        assertEquals(-12, s6c1.bins.first { it.freqKhz == 883_200L }.deltaMv)
    }

    private fun scmiPoint(domain: Int, levelIndex: Int, freqKhz: Long, coeff: Long, voltageMv: Int): ScmiPoint {
        val powerUw = coeff * freqKhz * voltageMv * voltageMv / 1_000_000_000L
        return ScmiPoint(
            domain = domain,
            levelIndex = levelIndex,
            freqKhz = freqKhz,
            powerUw = powerUw,
            voltageMv = voltageMv,
            exactFormulaMatch = true,
        )
    }

    private data class TestDcvsRow(
        val corner: Int,
        val freqKhz: Long,
        val cxCeil: Int,
        val cxFloor: Int,
        val flexMask: Int,
    )

    private fun buildDcvsBlob(rows: List<TestDcvsRow>): ByteArray {
        val total = (rows.size + 1) * 0x9C
        val out = ByteArray(total)
        for ((idx, r) in rows.withIndex()) {
            val base = idx * 0x9C
            putU32Le(out, base, r.corner.toUInt())
            for (bit in 0 until 8) {
                out[base + 6 + bit] = if (((r.flexMask ushr bit) and 1) != 0) 1 else 0
            }
            putU32Le(out, base + 0x10, r.freqKhz.toUInt())
            putI16Le(out, base + 0x7C, r.cxCeil)
            putI16Le(out, base + 0x7E, r.cxFloor)
            putI16Le(out, base + 0x8C, r.cxCeil)
            putI16Le(out, base + 0x8E, r.cxFloor)
        }
        putU32Le(out, rows.size * 0x9C, 0x7FFFFFFFu)
        return out
    }

    private fun buildCfdb(entries: List<Pair<UInt, ByteArray>>): ByteArray {
        val headerSize = 16 + entries.size * 16
        val data = ByteArrayOutputStream()
        val offsets = IntArray(entries.size)
        var cursor = headerSize
        for ((idx, pair) in entries.withIndex()) {
            offsets[idx] = cursor
            data.write(pair.second)
            cursor += pair.second.size
        }
        val out = ByteArray(headerSize + data.size())
        putU32Le(out, 0, CfdbReader.CFDB_MAGIC)
        putU32Le(out, 4, entries.size.toUInt())
        for ((idx, pair) in entries.withIndex()) {
            val dir = 16 + idx * 16
            putU32Le(out, dir, pair.first)
            putU32Le(out, dir + 4, offsets[idx].toUInt())
            putU32Le(out, dir + 8, ((pair.second.size shl 8) or 2).toUInt())
        }
        System.arraycopy(data.toByteArray(), 0, out, headerSize, data.size())
        return out
    }

    private fun buildSyntheticElfCfdb(entries: List<Pair<UInt, ByteArray>>): ByteArray {
        val cfdb = buildCfdb(entries)
        val elfHeaderSize = 52
        val phdrOffset = elfHeaderSize
        val phdrSize = 32
        val payloadOffset = phdrOffset + phdrSize
        val out = ByteArray(payloadOffset + cfdb.size)
        out[0] = 0x7F
        out[1] = 'E'.code.toByte()
        out[2] = 'L'.code.toByte()
        out[3] = 'F'.code.toByte()
        out[4] = 1
        out[5] = 1
        out[6] = 1
        putU16Le(out, 16, 2)
        putU16Le(out, 18, 40)
        putU32Le(out, 20, 1u)
        putU32Le(out, 28, phdrOffset.toUInt())
        putU16Le(out, 40, elfHeaderSize)
        putU16Le(out, 42, phdrSize)
        putU16Le(out, 44, 1)
        // Single PT_LOAD PHDR
        putU32Le(out, phdrOffset, 1u)
        putU32Le(out, phdrOffset + 4, payloadOffset.toUInt())
        putU32Le(out, phdrOffset + 16, cfdb.size.toUInt())
        putU32Le(out, phdrOffset + 20, cfdb.size.toUInt())
        System.arraycopy(cfdb, 0, out, payloadOffset, cfdb.size)
        return out
    }

    private fun putU16Le(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putI16Le(buf: ByteArray, off: Int, v: Int) = putU16Le(buf, off, v)

    private fun putU32Le(buf: ByteArray, off: Int, v: UInt) {
        buf[off] = (v and 0xFFu).toByte()
        buf[off + 1] = ((v shr 8) and 0xFFu).toByte()
        buf[off + 2] = ((v shr 16) and 0xFFu).toByte()
        buf[off + 3] = ((v shr 24) and 0xFFu).toByte()
    }

    companion object {
        private val SAMPLE_2509FPN0BC_STOCK_CSV = """
            0,0,384000,9144,30,384000,0
            0,1,537600,12802,30,537600,1
            0,2,691200,16460,30,691200,2
            0,3,787200,19996,30,787200,3
            0,4,883200,23515,30,883200,4
            0,5,998400,27833,30,998400,5
            0,6,1113600,32472,30,1113600,6
            0,7,1228800,36901,30,1228800,7
            0,8,1324800,41545,30,1324800,8
            0,9,1440000,46457,30,1440000,9
            0,10,1555200,52316,30,1555200,10
            0,11,1670400,58541,30,1670400,11
            0,12,1785600,65141,30,1785600,12
            0,13,1900800,73066,30,1900800,13
            0,14,1996800,78750,30,1996800,14
            0,15,2112000,86507,30,2112000,15
            0,16,2227200,95844,30,2227200,16
            0,17,2361600,107919,30,2361600,17
            0,18,2496000,118146,30,2496000,18
            0,19,2611200,127948,30,2611200,19
            0,20,2745600,142331,30,2745600,20
            0,21,2899200,160481,30,2899200,21
            0,22,3033600,188369,30,3033600,22
            0,23,3187200,208080,30,3187200,23
            0,24,3302400,228600,30,3302400,24
            0,25,3398400,249013,30,3398400,25
            0,26,3513600,272093,30,3513600,26
            0,27,3628800,299181,30,3628800,27
            1,0,768000,30672,30,768000,0
            1,1,883200,36933,30,883200,1
            1,2,1017600,43852,30,1017600,2
            1,3,1132800,51027,30,1132800,3
            1,4,1267200,58760,30,1267200,4
            1,5,1382400,66899,30,1382400,5
            1,6,1497600,75568,30,1497600,6
            1,7,1632000,85793,30,1632000,7
            1,8,1747200,94348,30,1747200,8
            1,9,1862400,103269,30,1862400,9
            1,10,1977600,114028,30,1977600,10
            1,11,2112000,128144,30,2112000,11
            1,12,2227200,140281,30,2227200,12
            1,13,2438400,165171,30,2438400,13
            1,14,2668800,193921,30,2668800,14
            1,15,2880000,223948,30,2880000,15
            1,16,3129600,265470,30,3129600,16
            1,17,3398400,316532,30,3398400,17
            1,18,3648000,371535,30,3648000,18
            1,19,3763200,402099,30,3763200,19
            1,20,3859200,424166,30,3859200,20
            1,21,3974400,457478,30,3974400,21
            1,22,4070400,485800,30,4070400,22
            1,23,4185600,522202,30,4185600,23
            1,24,4262400,550606,30,4262400,24
            1,25,4339200,580022,30,4339200,25
            1,26,4396800,607813,30,4396800,26
            1,27,4512000,676800,30,4512000,27
            1,28,4608000,741862,30,4608000,28
        """.trimIndent()
    }
}
