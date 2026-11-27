package io.voltset.sm8850

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal enum class CpuStressMode(val label: String, val summary: String) {
    SINGLE_CORE("单核高频", "锁定单线程满载，侧重最高加速电压稳定性"),
    ALL_CORES("全核满载", "全部 CPU 核心满负荷并发，测试多核降压与功耗"),
    BURST_SWITCH("高低频跳变", "交替突发负载与短暂休眠，测试升降频电压切换"),
}

internal data class CpuStressStats(
    val mode: CpuStressMode,
    val elapsedSeconds: Int,
    val threads: Int,
    val completedBlocks: Long,
    val verifiedBlocks: Long,
    val opsPerSec: Double,
    val stoppedForThermal: Boolean = false,
)

internal class CpuStress(
    context: Context?,
    private val thermalStatusProvider: () -> Int = {
        context?.getSystemService(PowerManager::class.java)?.currentThermalStatus
            ?: PowerManager.THERMAL_STATUS_NONE
    },
    private val nanoClock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {
    internal constructor(
        thermalStatusProvider: () -> Int,
        nanoClock: () -> Long = { System.nanoTime() },
    ) : this(context = null, thermalStatusProvider = thermalStatusProvider, nanoClock = nanoClock)
    suspend fun run(
        mode: CpuStressMode,
        seconds: Int,
        onProgress: (CpuStressStats) -> Unit,
    ): CpuStressStats = withContext(Dispatchers.Default) {
        require(seconds in 5..300) { "测试时长须在 5..300 秒之间" }
        val threads = when (mode) {
            CpuStressMode.SINGLE_CORE -> 1
            CpuStressMode.ALL_CORES, CpuStressMode.BURST_SWITCH ->
                Runtime.getRuntime().availableProcessors().coerceIn(2, 16)
        }
        if (isThermalSevere()) {
            return@withContext CpuStressStats(mode, 0, threads, 0L, 0L, 0.0, stoppedForThermal = true)
        }

        // Compute deterministic golden digests before starting worker threads.
        val seeds = LongArray(threads) { idx -> BASE_SEED + idx * 0x9E37_79B9_7F4A_7C15UL.toLong() }
        val expectedDigests = LongArray(threads) { idx -> computeBlock(seeds[idx], BLOCK_ITERATIONS) }

        val stopFlag = AtomicBoolean(false)
        val completedBlocks = AtomicLong(0L)
        val verifiedBlocks = AtomicLong(0L)
        val mismatchError = AtomicBoolean(false)
        val startedNs = nanoClock()
        val deadlineNs = startedNs + seconds * 1_000_000_000L

        coroutineScope {
            val workers = (0 until threads).map { idx ->
                async(Dispatchers.Default) {
                    val seed = seeds[idx]
                    val expected = expectedDigests[idx]
                    while (isActive && !stopFlag.get() && nanoClock() < deadlineNs) {
                        val actual = computeBlock(seed, BLOCK_ITERATIONS)
                        if (actual != expected) {
                            mismatchError.set(true)
                            stopFlag.set(true)
                            error("CPU 计算结果校验不一致（线程 #$idx，可能欠压不稳定）")
                        }
                        completedBlocks.incrementAndGet()
                        verifiedBlocks.incrementAndGet()
                        if (mode == CpuStressMode.BURST_SWITCH &&
                            !stopFlag.get() &&
                            nanoClock() < deadlineNs
                        ) {
                            delay(BURST_IDLE_MS)
                        }
                    }
                }
            }

            try {
                var stoppedForThermal = false
                while (isActive && nanoClock() < deadlineNs && !stopFlag.get()) {
                    delay(PROGRESS_INTERVAL_MS)
                    val nowNs = nanoClock()
                    if (isThermalSevere()) {
                        stoppedForThermal = true
                        stopFlag.set(true)
                        break
                    }
                    onProgress(
                        buildStats(
                            mode = mode,
                            threads = threads,
                            startedNs = startedNs,
                            nowNs = nowNs,
                            completedBlocks = completedBlocks.get(),
                            verifiedBlocks = verifiedBlocks.get(),
                            stoppedForThermal = false,
                        ),
                    )
                }
                stopFlag.set(true)
                workers.awaitAll()
                check(!mismatchError.get()) { "CPU 计算结果校验不一致（可能欠压不稳定）" }
                buildStats(
                    mode = mode,
                    threads = threads,
                    startedNs = startedNs,
                    nowNs = nanoClock(),
                    completedBlocks = completedBlocks.get(),
                    verifiedBlocks = verifiedBlocks.get(),
                    stoppedForThermal = stoppedForThermal,
                )
            } finally {
                stopFlag.set(true)
            }
        }
    }

    private fun isThermalSevere(): Boolean =
        thermalStatusProvider() >= PowerManager.THERMAL_STATUS_SEVERE

    private fun buildStats(
        mode: CpuStressMode,
        threads: Int,
        startedNs: Long,
        nowNs: Long,
        completedBlocks: Long,
        verifiedBlocks: Long,
        stoppedForThermal: Boolean,
    ): CpuStressStats {
        val elapsedSec = ((nowNs - startedNs).coerceAtLeast(1L)) / 1e9
        val ops = completedBlocks * BLOCK_ITERATIONS.toDouble()
        return CpuStressStats(
            mode = mode,
            elapsedSeconds = elapsedSec.toInt(),
            threads = threads,
            completedBlocks = completedBlocks,
            verifiedBlocks = verifiedBlocks,
            opsPerSec = ops / elapsedSec,
            stoppedForThermal = stoppedForThermal,
        )
    }

    companion object {
        private const val BASE_SEED = 0x243F_6A88_85A3_08D3L
        internal const val BLOCK_ITERATIONS = 4096
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val BURST_IDLE_MS = 75L

        internal fun computeBlock(seed: Long, iterations: Int): Long {
            var x = seed xor 0x517C_C1B7_2722_0A95L
            var acc = 0.3125
            for (i in 0 until iterations) {
                x = x xor (x shl 13)
                x = x xor (x ushr 7)
                x = x xor (x shl 17)
                x *= 0x2545_F491_4F6C_DD1DL
                val phase = ((x and 0xFFFFL).toDouble() * 0.0001) + (i and 0x3F) * 0.015
                acc = acc * 0.99999 + sin(phase) * cos(phase * 0.73)
            }
            return x xor java.lang.Double.doubleToRawLongBits(acc)
        }
    }
}
