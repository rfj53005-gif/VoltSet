package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScmiQualityReportTest {
    private val voltages = listOf(488, 492, 500, 512, 528, 544, 580, 616, 644, 684, 712, 772, 800, 844)

    @Test
    fun twoStableTablesProduceExpectedVoltagesAndScores() {
        val points = points()
        val report = ScmiQualityReport.evaluate(points, points.toList())
        assertNotNull(report)
        requireNotNull(report)
        assertEquals(mapOf(0 to 100L, 1 to 150L), report.coefficients)
        assertEquals(voltages, report.points.filter { it.domain == 0 }.map { it.voltageMv })
        assertEquals(listOf(4032, 3850), report.clusters.map { it.score })
        assertEquals(3941, report.score)
    }

    @Test
    fun changedOrIncompleteTablesDoNotProduceScores() {
        val points = points()
        val changed = points.toMutableList().also { it[0] = it[0].copy(powerUw = it[0].powerUw + 1) }
        assertNull(ScmiQualityReport.evaluate(points, changed))

        val incomplete = points.filter { it.levelIndex != 4 }
        assertNull(ScmiQualityReport.evaluate(incomplete, incomplete))
    }

    private fun points(): List<ScmiPoint> = (0..1).flatMap { domain ->
        voltages.mapIndexed { index, mv ->
            val frequencyKhz = (384_000L + domain * 384_000L) + 153_600L * index
            val coefficient = if (domain == 0) 100L else 150L
            val voltage = mv + domain * 16
            val powerUw = coefficient * frequencyKhz * voltage * voltage / 1_000_000_000L
            ScmiPoint(domain, index, frequencyKhz, powerUw, 0, false)
        }
    }
}
