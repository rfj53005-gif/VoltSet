package io.voltset.sm8850

import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class CpuStressTest {

    @Test
    fun singleCoreCompletesAndVerifiesDeterministicBlocks() = runBlocking {
        // Each nanoClock poll after worker/monitor starts advances slightly, completing cleanly in <1s.
        val ticks = AtomicLong(0L)
        val stress = CpuStress(
            thermalStatusProvider = { PowerManager.THERMAL_STATUS_NONE },
            nanoClock = { ticks.getAndAdd(800_000_000L) },
        )

        val stats = stress.run(CpuStressMode.SINGLE_CORE, seconds = 5) {}
        assertEquals(CpuStressMode.SINGLE_CORE, stats.mode)
        assertEquals(1, stats.threads)
        assertTrue(stats.completedBlocks > 0)
        assertEquals(stats.completedBlocks, stats.verifiedBlocks)
        assertFalse(stats.stoppedForThermal)
        assertTrue(stats.opsPerSec > 0.0)
    }

    @Test
    fun allCoresAndBurstModesRunMultipleWorkersAndReportProgress() = runBlocking {
        val t0 = System.nanoTime()
        var progressUpdates = 0
        val allCores = CpuStress(
            thermalStatusProvider = { PowerManager.THERMAL_STATUS_NONE },
            nanoClock = { (System.nanoTime() - t0) * 8L },
        )
        val allStats = allCores.run(CpuStressMode.ALL_CORES, seconds = 5) {
            progressUpdates++
        }
        assertEquals(CpuStressMode.ALL_CORES, allStats.mode)
        assertTrue(allStats.threads >= 2)
        assertTrue(allStats.completedBlocks >= 1)
        assertEquals(allStats.completedBlocks, allStats.verifiedBlocks)
        assertTrue(progressUpdates >= 1)

        val burstTicks = AtomicLong(0L)
        val burst = CpuStress(
            thermalStatusProvider = { PowerManager.THERMAL_STATUS_NONE },
            nanoClock = { burstTicks.getAndAdd(900_000_000L) },
        )
        val burstStats = burst.run(CpuStressMode.BURST_SWITCH, seconds = 5) {}
        assertEquals(CpuStressMode.BURST_SWITCH, burstStats.mode)
        assertTrue(burstStats.threads >= 2)
        assertEquals(burstStats.completedBlocks, burstStats.verifiedBlocks)
        assertFalse(burstStats.stoppedForThermal)
    }

    @Test
    fun severeThermalStopsStressImmediatelyBeforeRunningBlocks() = runBlocking {
        val stress = CpuStress(
            thermalStatusProvider = { PowerManager.THERMAL_STATUS_SEVERE },
            nanoClock = { 1_000_000L },
        )

        val stats = stress.run(CpuStressMode.ALL_CORES, seconds = 30) {}
        assertTrue(stats.stoppedForThermal)
        assertEquals(0L, stats.completedBlocks)
        assertEquals(0L, stats.verifiedBlocks)
    }

    @Test
    fun coroutineCancellationAbortsCleanly() = runBlocking {
        var now = 0L
        val stress = CpuStress(
            thermalStatusProvider = { PowerManager.THERMAL_STATUS_NONE },
            nanoClock = {
                now += 10_000L
                now
            },
        )
        var cancelled = false
        val job = launch {
            try {
                stress.run(CpuStressMode.SINGLE_CORE, seconds = 60) {}
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        delay(80L)
        job.cancelAndJoin()
        assertTrue(cancelled)
    }
}
