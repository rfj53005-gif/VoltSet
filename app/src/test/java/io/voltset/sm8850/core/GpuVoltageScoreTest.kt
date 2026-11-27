package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GpuVoltageScoreTest {
    private val corners = checkNotNull(GpuVoltageCorners.parse(
        "0x30050: gfx.lvl [00 00 32 00 3c 00 50 00 80 01 a0 01 c0 01 00 00]",
    ))
    private val stock = mapOf(1200L to 448L, 1050L to 416L, 578L to 80L, 160L to 50L)
    private val linearStock = mapOf(100L to 50L, 200L to 80L, 300L to 416L)

    @Test
    fun roundsUpToTheReachableGfxLevelBeforeScoring() {
        val rows = points(1050L to 288L, 578L to 76L, 160L to 16L, 1130L to 384L)
        val report = GpuVoltageScore.evaluate(rows, stock, corners)

        assertEquals(GpuVoltagePoint(416, 384, 1), report.points[1050L])
        assertEquals(GpuVoltagePoint(80, 80, 0), report.points[578L])
        assertEquals(GpuVoltagePoint(50, 50, 0), report.points[160L])
        assertNull(report.points[1130L])
        assertEquals(3, report.points.size)
        assertEquals(4, report.stockFrequencyCount)
        assertEquals(100.25, checkNotNull(report.exactScore), 0.0)
        assertEquals(970.0 / 1040, checkNotNull(report.estimate).coverage, 1e-9)
    }

    @Test
    fun usesAFixedStockDenominatorAndRequiresComparableFrequencies() {
        val original = GpuVoltageScore.evaluate(points(*stock.toList().toTypedArray()), stock, corners)
        val higherVote = GpuVoltageScore.evaluate(points(578L to 384L), stock, corners)
        val unmatched = GpuVoltageScore.evaluate(points(1250L to 432L, 1300L to 448L), stock, corners)
        val single = GpuVoltageScore.evaluate(points(967L to 80L), stock, corners)
        assertEquals(100.0, checkNotNull(original.score), 0.0)
        assertEquals(100.0, checkNotNull(original.estimate).score, 0.0)
        assertEquals(99.75, checkNotNull(higherVote.score), 0.0)
        assertNull(unmatched.score)
        assertNull(single.score)
    }

    @Test
    fun integratesWithoutExactMatchesAndIgnoresExtraCollinearKnots() {
        val rows = points(125L to 50L, 225L to 80L, 275L to 384L)
        val report = GpuVoltageScore.evaluate(rows, linearStock, corners)
        val subdivided = GpuVoltageScore.evaluate(rows + points(175L to 60L), linearStock, corners)

        assertEquals(emptyMap<Long, GpuVoltagePoint>(), report.points)
        assertNull(report.exactScore)
        val estimate = checkNotNull(report.estimate)
        assertEquals(0.75, estimate.coverage, 0.0)
        assertEquals(100.375, checkNotNull(report.score), 1e-9)
        assertEquals(checkNotNull(report.score), checkNotNull(subdivided.score), 1e-9)
    }

    @Test
    fun clipsIntegrationToTheReferenceRangeAndSplitsAtFactoryKnots() {
        val report = GpuVoltageScore.evaluate(points(150L to 50L, 1250L to 50L), stock, corners)

        val estimate = checkNotNull(report.estimate)
        assertEquals(1.0, estimate.coverage, 0.0)
        assertEquals(102.4125, checkNotNull(report.score), 1e-9)
    }

    @Test
    fun leavesAGapAroundAnUnresolvedGfxVote() {
        val rows = points(125L to 50L, 175L to 449L, 225L to 80L, 275L to 384L)
        val report = GpuVoltageScore.evaluate(rows, linearStock, corners)

        assertEquals(0.25, checkNotNull(report.estimate).coverage, 0.0)
        assertEquals(100.125, checkNotNull(report.score), 1e-9)
    }

    @Test
    fun measuresSharedEffectiveLevelsOnTheReferenceLadder() {
        val otherDevice = checkNotNull(GpuVoltageCorners.fromLevels(listOf(50, 72, 80, 128, 256, 384, 416, 448)))
        val rows = points(1050L to 320L, 160L to 60L, 1130L to 384L)
        val report = GpuVoltageScore.evaluate(rows, stock, otherDevice, corners)

        assertEquals(mapOf(1050L to GpuVoltagePoint(416, 384, 1)), report.points)
        assertEquals(4, report.stockFrequencyCount)
        assertEquals(100.25, checkNotNull(report.score), 0.0)
    }

    @Test
    fun rejectsMalformedTablesAndUnresolvableRequests() {
        assertNull(GpuVoltageCorners.parse("0x30050: gfx.lvl [00 00 50 00 32 00]"))
        assertNull(GpuVoltageCorners.parse("0x30050: gfx.lvl [00 00 32 00 00 00 50 00]"))
        assertNull(GpuVoltageCorners.parse("0x30050: gfx.lvl [00 00 32 00 50]"))
        assertNull(corners.resolve(449L))
        assertNull(corners.resolve(0L))
    }

    private fun points(vararg requests: Pair<Long, Long>): List<GpuRow> =
        requests.mapIndexed { index, (hz, request) -> GpuRow(GpuLevelKey(1, index), GpuLevelValue(hz, request)) }
}
