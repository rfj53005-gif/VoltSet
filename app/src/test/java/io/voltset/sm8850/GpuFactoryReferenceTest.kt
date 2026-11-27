package io.voltset.sm8850

import io.voltset.sm8850.core.GpuLevelKey
import io.voltset.sm8850.core.GpuLevelValue
import io.voltset.sm8850.core.GpuRow
import io.voltset.sm8850.core.GpuVoltageCorners
import io.voltset.sm8850.core.GpuVoltageScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuFactoryReferenceTest {
    private val popsicle = GpuImageSource(
        "popsicle", "OS4.0.6.0.XPBCNXM", "_a", 2, 100663296, "test", "/dev/block/by-name/vendor_boot_a",
    )

    @Test
    fun identifiesSm8850BySocModelOrSocId() {
        val reference = checkNotNull(GpuFactoryReference.voltageForSoc("SM8850", 660))
        assertSame(reference, GpuFactoryReference.voltageForSoc("SM8850-AC", null))
        assertSame(reference, GpuFactoryReference.voltageForSoc("Qualcomm Technologies, Inc. sm8850-1-ad", null))
        assertSame(reference, GpuFactoryReference.voltageForSoc("", 660))
        assertEquals(448L, reference.levelsFor(252L)[1_200_000_000L])
        assertNull(GpuFactoryReference.voltageForSoc("SM8845", null))
        assertNull(GpuFactoryReference.voltageForSoc("SM8750", 660))
        assertNull(GpuFactoryReference.voltageForSoc("canoe", null))
        assertNull(GpuFactoryReference.voltageForSoc("", 0))
    }

    @Test
    fun scoresTheVisibleMyronLevelsAgainstTheSm8850Reference() {
        val reference = checkNotNull(GpuFactoryReference.voltageForSoc("SM8850", null))
        val local = checkNotNull(GpuVoltageCorners.fromLevels(listOf(384, 416, 432, 448)))
        val points = listOf(1200 to 448L, 1100 to 432L, 1050 to 416L, 1000 to 384L)
            .mapIndexed { index, (mhz, level) -> GpuRow(GpuLevelKey(1, index), GpuLevelValue(mhz * 1_000_000L, level)) }
        val report = GpuVoltageScore.evaluate(points, reference.levelsFor(252L), local, reference.corners)

        assertEquals(4, report.points.size)
        assertEquals(18, report.stockFrequencyCount)
        assertEquals(100.0, checkNotNull(report.score), 0.0)

        val shared = GpuVoltageScore.evaluate(points, reference.levelsFor(99L), local, reference.corners)
        assertEquals(report.points, shared.points)
        assertEquals(19, shared.stockFrequencyCount)
        assertEquals(100.0, checkNotNull(shared.score), 0.0)
    }

    @Test
    fun acdStillRequiresItsVerifiedDeviceBuildAndDtb() {
        val myron = popsicle.copy(
            device = "myron",
            build = "OS4.0.8.0.XPMCNXM",
            slot = "_b",
            partition = "/dev/block/by-name/vendor_boot_b",
        )
        assertTrue(GpuFactoryReference.acdForSource(myron).isEmpty())
        assertTrue(GpuFactoryReference.acdForSource(popsicle.copy(dtbIndex = 1)).isEmpty())
        assertTrue(GpuFactoryReference.acdForSource(popsicle.copy(build = "OS4.0.8.0.XPBCNXM")).isEmpty())
        assertEquals(0xa02e5ffdL, GpuFactoryReference.acdForSource(popsicle).getValue(252L).getValue(1_200_000_000L).code)
    }
}
