package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityCheckerTest {
    @Test
    fun privateNsDebugfsPassesAndIsReported() {
        val input = sampleInput(debugfsPath = "/sys/kernel/debug", debugfsPrivateNs = true)
        val report = CompatibilityChecker.evaluate(input)
        val adjItem = report.items.first { it.id == "adj" }

        assertEquals(CompatibilityLevel.COMPATIBLE, report.level)
        assertEquals(CheckStatus.PASS, adjItem.status)
        assertTrue(adjItem.summary.contains("私有 NS 挂载"))
        assertTrue(adjItem.detail.contains("mount namespace"))

        val text = CompatibilityChecker.formatTextReport(sampleSnapshot(input, report), "0.3.4")
        assertTrue(text.contains("debugfs: /sys/kernel/debug (private-ns)"))
    }

    @Test
    fun defaultDebugfsDoesNotClaimPrivateNs() {
        val input = sampleInput(debugfsPath = "/sys/kernel/debug", debugfsPrivateNs = false)
        val report = CompatibilityChecker.evaluate(input)
        val adjItem = report.items.first { it.id == "adj" }

        assertEquals(CompatibilityLevel.COMPATIBLE, report.level)
        assertEquals(CheckStatus.PASS, adjItem.status)
        assertFalse(adjItem.summary.contains("私有 NS"))

        val text = CompatibilityChecker.formatTextReport(sampleSnapshot(input, report), "0.3.4")
        assertTrue(text.contains("debugfs: /sys/kernel/debug"))
        assertFalse(text.contains("(private-ns)"))
    }

    @Test
    fun pdpFwMatchedPassesAndMissingWarnsWithoutBlocking() {
        val matchedInput = sampleInput(pdpHardwareLimits = sampleHardwareLimits())
        val matchedReport = CompatibilityChecker.evaluate(matchedInput)
        val matchedFwItem = matchedReport.items.first { it.id == "pdp_fw" }

        assertEquals(CheckStatus.PASS, matchedFwItem.status)
        assertFalse(matchedFwItem.blocking)
        assertEquals(CompatibilityLevel.COMPATIBLE, matchedReport.level)

        val missingInput = sampleInput(
            pdpFwPath = "/dev/block/by-name/pdp_a",
            pdpHardwareLimits = null,
            pdpFwError = "未能在 pdp 中匹配",
        )
        val missingReport = CompatibilityChecker.evaluate(missingInput)
        val missingFwItem = missingReport.items.first { it.id == "pdp_fw" }

        assertEquals(CheckStatus.WARN, missingFwItem.status)
        assertFalse(missingFwItem.blocking)
        assertNotEquals(CompatibilityLevel.UNSUPPORTED, missingReport.level)
        assertEquals(CompatibilityLevel.PARTIAL, missingReport.level)
    }

    private fun sampleInput(
        debugfsPath: String? = "/sys/kernel/debug",
        debugfsPrivateNs: Boolean = false,
        pdpFwPath: String? = "/dev/block/by-name/pdp_a",
        pdpHardwareLimits: PdpHardwareLimits? = sampleHardwareLimits(),
        pdpFwError: String? = null,
    ): CompatibilityInput = CompatibilityInput(
        socModel = "SM8850",
        isSm8850 = true,
        pageSizeBytes = 4096,
        debugfsPath = debugfsPath,
        debugfsPrivateNs = debugfsPrivateNs,
        adjAvailable = true,
        adjSetNodePresent = true,
        adjError = null,
        sdamCpussIndex = 1,
        sdamGlobalIndex = 1,
        reg7151Hex = "99",
        pdpCdbPath = "/dev/block/by-name/pdp_cdb_a",
        pdpCdb = samplePdpCdb(),
        pdpCdbError = null,
        pdpFwPath = pdpFwPath,
        pdpHardwareLimits = pdpHardwareLimits,
        pdpFwError = pdpFwError,
        cdtStatus = CdtStatus(
            state = CdtState.EMPTY,
            version = null,
            key7Value = null,
            keyPairs = emptyList(),
            detail = "CDT 分区全零（原厂默认，Key 7 未开启）",
        ),
        scmiModuleAsset = "modules/sm8850/android16-6-4k/qcom-scmi-ro.ko",
        scmiError = null,
        scmiPoints = listOf(
            ScmiPoint(domain = 0, levelIndex = 0, freqKhz = 384_000L, powerUw = 9_145L, voltageMv = 488, exactFormulaMatch = true),
            ScmiPoint(domain = 1, levelIndex = 0, freqKhz = 768_000L, powerUw = 30_678L, voltageMv = 516, exactFormulaMatch = true),
        ),
    )

    private fun samplePdpCdb(): PdpCdbData {
        val set1Clus0 = ClusterSetSummary(0, 0, 0, emptyList(), 0, 0, 0, 0)
        val set1Clus1 = ClusterSetSummary(1, 0, 0, emptyList(), 0, 0, 0, 0)
        val set2Clus0 = ClusterSetSummary(0, -12, -6, emptyList(), -12, -12, -6, -6)
        val set2Clus1 = ClusterSetSummary(1, -12, -6, emptyList(), -12, -12, -6, -6)
        return PdpCdbData(
            overrideSetId = 0,
            clus0Records = emptyList(),
            clus1Records = emptyList(),
            sets = listOf(
                VoltageSetProfile(1, set1Clus0, set1Clus1),
                VoltageSetProfile(2, set2Clus0, set2Clus1),
            ),
        )
    }

    private fun sampleHardwareLimits(): PdpHardwareLimits = PdpHardwareLimits(
        totalDcvsTablesFound = 12,
        clus0 = ClusterHardwareLimits(
            domain = 0,
            exactTableMatch = true,
            limitsByFreqKhz = mapOf(
                384_000L to FreqVoltageLimit(384_000L, 484, 616, 0, 484, 664),
            ),
        ),
        clus1 = ClusterHardwareLimits(
            domain = 1,
            exactTableMatch = true,
            limitsByFreqKhz = mapOf(
                768_000L to FreqVoltageLimit(768_000L, 516, 676, 0, 516, 700),
            ),
        ),
    )

    private fun sampleSnapshot(
        input: CompatibilityInput,
        report: CompatibilityReport,
    ): DeviceSnapshot = DeviceSnapshot(
        bootId = "00000000-0000-0000-0000-000000000000",
        socModel = input.socModel,
        deviceModel = "2509FPN0BC",
        deviceName = "popsicle",
        slotSuffix = "_a",
        kernelRelease = "6.12.0-android16-6",
        pageSizeBytes = input.pageSizeBytes,
        isSm8850 = input.isSm8850,
        debugfsPath = input.debugfsPath,
        debugfsPrivateNs = input.debugfsPrivateNs,
        adjAvailable = input.adjAvailable,
        adjSetNodePresent = input.adjSetNodePresent,
        adjError = input.adjError,
        bootSet = BootSetStatus(1, 1, 1, 0),
        sdamCpussIndex = input.sdamCpussIndex,
        sdamGlobalIndex = input.sdamGlobalIndex,
        reg7151Hex = input.reg7151Hex,
        pdpCdbPath = input.pdpCdbPath,
        pdpCdb = input.pdpCdb,
        pdpCdbError = input.pdpCdbError,
        pdpFwPath = input.pdpFwPath,
        pdpHardwareLimits = input.pdpHardwareLimits,
        pdpFwError = input.pdpFwError,
        cdtPath = "/dev/block/by-name/cdt",
        cdtStatus = input.cdtStatus,
        scmiModuleAsset = input.scmiModuleAsset,
        scmiError = input.scmiError,
        scmiPoints = input.scmiPoints,
        qualityReport = null,
        baselineSource = BaselineSource.VOLTSET,
        baselinePoints = input.scmiPoints,
        voltageDiffRows = emptyList(),
        setEstimates = emptyMap(),
        compatibility = report,
    )
}
