package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QptPowerMonitorTest {
    @Test
    fun parseSampleSkipsDebugRailsAndParsesFrequencies() {
        val raw = """
            QPT_RAIL=cpu-m:503016512
            QPT_RAIL=cpu-l:27654080
            QPT_RAIL=gpu:115128192
            QPT_RAIL=nsp:0
            QPT_RAIL=debug-0:98602816
            FREQ_CPU0_KHZ=1900800
            FREQ_CPU1_KHZ=2841600
            FREQ_GPU_HZ=440000000
        """.trimIndent()

        val parsed = QptPowerParser.parseSample(raw, timestampNs = 1_000_000_000L)
        assertEquals(4, parsed.energyUjByRail.size)
        assertFalse("debug-0" in parsed.energyUjByRail)
        assertEquals(503016512L, parsed.energyUjByRail["cpu-m"])
        assertEquals(1900800, parsed.cpu0FreqKhz)
        assertEquals(2841600, parsed.cpu1FreqKhz)
        assertEquals(440000000L, parsed.gpuFreqHz)
    }

    @Test
    fun computeDeltaConvertsEnergyToMwAndGuardsCounterWrap() {
        val prev = QptRawSample(
            timestampNs = 1_000_000_000L,
            energyUjByRail = mapOf("cpu-m" to 1_000_000L, "cpu-l" to 2_000_000L, "gpu" to 500_000L, "nsp" to 10_000L),
        )
        val curr = QptRawSample(
            timestampNs = 2_000_000_000L,
            energyUjByRail = mapOf("cpu-m" to 1_600_000L, "cpu-l" to 2_900_000L, "gpu" to 300_000L, "nsp" to 10_000L),
            cpu0FreqKhz = 1200000,
        )

        val delta = QptPowerParser.computeDelta(prev, curr)
        assertNotNull(delta)
        assertEquals(600.0, delta!!.cpuMidMw!!, 0.01)
        assertEquals(900.0, delta.cpuLargeMw!!, 0.01)
        assertEquals(1500.0, delta.cpuTotalMw!!, 0.01)
        assertNull(delta.gpuMw)
        assertEquals(0.0, delta.nspMw!!, 0.01)
        assertEquals(1000L, delta.dtMs)

        // Too-short sampling interval returns null
        assertNull(QptPowerParser.computeDelta(prev, curr.copy(timestampNs = 1_010_000_000L)))
    }

    @Test
    fun summarizeSessionComputesDurationWeightedAverageAndRecordsRoundTrip() {
        val intervals = listOf(
            QptInstantPower(cpuMidMw = 400.0, cpuLargeMw = 600.0, gpuMw = 200.0, nspMw = 0.0, dtMs = 1000L),
            QptInstantPower(cpuMidMw = 700.0, cpuLargeMw = 900.0, gpuMw = 400.0, nspMw = 0.0, dtMs = 2000L),
        )
        val stats = QptPowerParser.summarizeSession(intervals)!!
        assertEquals(2, stats.sampleCount)
        assertEquals(3.0, stats.elapsedSec, 0.001)
        assertEquals(600.0, stats.avgCpuMidMw!!, 0.01)
        assertEquals(800.0, stats.avgCpuLargeMw!!, 0.01)
        assertEquals(1400.0, stats.avgCpuTotalMw!!, 0.01)
        assertEquals(333.33, stats.avgGpuMw!!, 0.1)

        val rec1 = QptPowerParser.buildSavedRecord(1, stats, 1700000000000L)!!
        val rec6 = rec1.copy(setId = 6, avgCpuTotalMw = 1260)
        val serialized = QptPowerParser.serializeRecords(mapOf(6 to rec6, 1 to rec1))
        val restored = QptPowerParser.deserializeRecords(serialized)

        assertEquals(2, restored.size)
        assertTrue(restored.containsKey(1))
        assertTrue(restored.containsKey(6))
        assertEquals(1400, restored[1]!!.avgCpuTotalMw)
        assertEquals(1260, restored[6]!!.avgCpuTotalMw)
    }

    @Test
    fun computeDeltaUsesPowerUwFallbackOnlyWhenEnergyDoesNotAdvance() {
        val rawPrev = """
            QPT_RAIL=cpu-m:500000000
            QPT_RAIL=gpu:100000000
            QPT_RAIL=debug-0:999
            QPT_POWER_UW=cpu-m:100000
            QPT_POWER_UW=gpu:50000
            QPT_POWER_UW=debug-0:12345
        """.trimIndent()
        val rawCurr = """
            QPT_RAIL=cpu-m:500250000
            QPT_RAIL=gpu:100000000
            QPT_POWER_UW=cpu-m:999000
            QPT_POWER_UW=gpu:42000
        """.trimIndent()

        val prev = QptPowerParser.parseSample(rawPrev, timestampNs = 1_000_000_000L)
        val curr = QptPowerParser.parseSample(rawCurr, timestampNs = 2_000_000_000L)
        assertFalse("debug-0" in prev.powerUwByRail)

        val delta = QptPowerParser.computeDelta(prev, curr)!!
        // cpu-m advanced by 250,000 uJ in 1s -> 250 mW takes precedence over QPT_POWER_UW=999 mW
        assertEquals(250.0, delta.cpuMidMw!!, 0.01)
        // gpu did not advance -> falls back to QPT_POWER_UW=42,000 uW -> 42 mW
        assertEquals(42.0, delta.gpuMw!!, 0.01)
    }
}
