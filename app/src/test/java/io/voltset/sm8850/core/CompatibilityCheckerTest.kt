package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun systemProbeParserAndReportFormattingWork() {
        val probeBlock = """
            vadj_platform_dev=c426000.spmi:pmk8850@0:qcom,voltage-adj-device
            vadj_driver=qcom_voltage_adj
            vadj_spmi_parent=1-00
            vadj_dt_path=/soc/arbiter@c400000/spmi@c426000/pmk8850@0/qcom,voltage-adj-device
            vadj_module_loaded=1
            vadj_module_files=/vendor_dlkm/lib/modules/qcom_voltage_adj.ko
            debugfs_mounts=/sys/kernel/debug
            debugfs_volt_nodes=/sys/kernel/debug/custom_vadj,/sys/kernel/debug/custom_vadj/get_overrides
            pdp_cdt_partitions=cdt=/dev/block/sdd2,pdp_a=/dev/block/sde36,pdp_cdb_a=/dev/block/sde37
            by_name_dirs=/dev/block/by-name,/dev/block/platform/soc/1d84000.ufshc/by-name
            scmi_drivers=qcom-scmi-driver,scmi-perf-domain
            scmi_device_count=9
            cpufreq_policies=policy0(0-5,384-3628MHz),policy6(6-7,768-4608MHz)
            energy_model_domains=cpu0(0-5,28),cpu6(6-7,29)
            socinfo=chip=SM8850 foundry=1 feature=3 pcode=2 oem=0 pmic_rev=2.0
            sdam2_nvmem=/sys/bus/nvmem/devices/spmi_sdam2/nvmem
            sdam2_pmic=pmk8850
        """.trimIndent()

        val probe = SystemProbeParser.parse(
            probeBlock = probeBlock,
            adjDirPath = "/sys/kernel/debug/custom_vadj",
            regmapNode = "1-00",
        )
        assertTrue(probe.hasAnyData)
        assertEquals("/sys/kernel/debug/custom_vadj", probe.adjDirPath)
        assertEquals("1-00", probe.regmapNode)
        assertEquals("c426000.spmi:pmk8850@0:qcom,voltage-adj-device", probe.vadjPlatformDevice)
        assertEquals("qcom_voltage_adj", probe.vadjDriver)
        assertEquals("1-00", probe.vadjSpmiParent)
        assertEquals(listOf("/vendor_dlkm/lib/modules/qcom_voltage_adj.ko"), probe.vadjModuleFiles)
        assertEquals(listOf("cdt=/dev/block/sdd2", "pdp_a=/dev/block/sde36", "pdp_cdb_a=/dev/block/sde37"), probe.pdpCdtPartitions)
        assertEquals(9, probe.scmiDeviceCount)
        assertEquals(listOf("policy0(0-5,384-3628MHz)", "policy6(6-7,768-4608MHz)"), probe.cpufreqPolicies)
        assertEquals(listOf("cpu0(0-5,28)", "cpu6(6-7,29)"), probe.energyModelDomains)
        assertEquals("chip=SM8850 foundry=1 feature=3 pcode=2 oem=0 pmic_rev=2.0", probe.socInfo)
        assertEquals("/sys/bus/nvmem/devices/spmi_sdam2/nvmem", probe.sdam2NvmemPath)
        assertEquals("pmk8850", probe.sdam2Pmic)

        val input = sampleInput(
            debugfsPath = "/sys/kernel/debug",
            debugfsPrivateNs = false,
            adjBackend = AdjBackend.DEBUGFS,
            scmiSource = ScmiDataSource.ENERGY_MODEL,
            adjDirPath = "/sys/kernel/debug/custom_vadj",
            regmapNode = "1-00",
        )
        val report = CompatibilityChecker.evaluate(input)
        val adjItem = report.items.first { it.id == "adj" }
        val regItem = report.items.first { it.id == "reg7151" }
        assertTrue(adjItem.detail.contains("/sys/kernel/debug/custom_vadj"))
        assertTrue(regItem.detail.contains("1-00"))

        val snapshot = sampleSnapshot(input, report, systemProbe = probe)
        val text = CompatibilityChecker.formatTextReport(snapshot, "0.3.7")
        assertTrue(text.contains("[全机只读自动探测]"))
        assertTrue(text.contains("/vendor_dlkm/lib/modules/qcom_voltage_adj.ko"))
        assertTrue(text.contains("pdp_cdb_a=/dev/block/sde37"))
        assertTrue(text.contains("policy0(0-5,384-3628MHz)"))
        assertTrue(text.contains("cpu0(0-5,28), cpu6(6-7,29)"))
        assertTrue(text.contains("chip=SM8850 foundry=1 feature=3 pcode=2 oem=0 pmic_rev=2.0"))
        assertTrue(text.contains("debugfs/energy_model (零内核模块)"))
        assertTrue(text.contains("/sys/bus/nvmem/devices/spmi_sdam2/nvmem (pmk8850)"))
    }

    @Test
    fun nvmemFallbackPassesWithoutDebugfsOrVoltageAdjDriver() {
        val nvmemPath = "/sys/bus/nvmem/devices/spmi_sdam2/nvmem"
        val input = sampleInput(
            debugfsPath = null,
            debugfsPrivateNs = false,
            adjBackend = AdjBackend.NVMEM,
            adjDirPath = nvmemPath,
            regmapNode = "nvmem:spmi_sdam2",
        )
        val report = CompatibilityChecker.evaluate(input)
        val adjItem = report.items.first { it.id == "adj" }
        val regItem = report.items.first { it.id == "reg7151" }

        assertEquals(CompatibilityLevel.COMPATIBLE, report.level)
        assertEquals(CheckStatus.PASS, adjItem.status)
        assertTrue(adjItem.summary.contains("sysfs nvmem 兜底直通"))
        assertTrue(adjItem.detail.contains(nvmemPath))
        assertTrue(adjItem.detail.contains("0x51"))
        assertEquals(CheckStatus.PASS, regItem.status)
        assertTrue(regItem.detail.contains("nvmem:spmi_sdam2"))

        val probe = SystemProbeInfo(
            adjDirPath = nvmemPath,
            regmapNode = "nvmem:spmi_sdam2",
            sdam2NvmemPath = nvmemPath,
            sdam2Pmic = "pmk8850",
        )
        val text = CompatibilityChecker.formatTextReport(sampleSnapshot(input, report, systemProbe = probe), "0.3.7")
        assertTrue(text.contains("调压读写通道: sysfs spmi_sdam2/nvmem (sdam@7100+0x51)"))
        assertTrue(text.contains("$nvmemPath (pmk8850)"))
    }

    @Test
    fun energyModelScmiSourcePassesWithoutModuleEvenOn16kKernel() {
        val input = sampleInput(
            pageSizeBytes = 16384,
            scmiModuleAsset = null,
            scmiSource = ScmiDataSource.ENERGY_MODEL,
        )
        val report = CompatibilityChecker.evaluate(input)
        val scmiItem = report.items.first { it.id == "scmi" }

        assertEquals(CompatibilityLevel.COMPATIBLE, report.level)
        assertEquals(CheckStatus.PASS, scmiItem.status)
        assertTrue(scmiItem.summary.contains("energy_model 零模块"))
        assertTrue(scmiItem.detail.contains("16K 页"))
    }

    @Test
    fun systemProbeHandlesNoneAndMissingValues() {
        val probe = SystemProbeParser.parse(
            probeBlock = """
                vadj_platform_dev=none
                vadj_driver=none
                vadj_spmi_parent=none
                vadj_dt_path=none
                vadj_module_loaded=0
                vadj_module_files=none
                debugfs_mounts=none
                debugfs_volt_nodes=none
                pdp_cdt_partitions=none
                by_name_dirs=none
                scmi_drivers=none
                scmi_device_count=0
                cpufreq_policies=none
            """.trimIndent(),
        )
        assertFalse(probe.hasAnyData)
        assertEquals(null, probe.vadjPlatformDevice)
        assertEquals(null, probe.adjDirPath)
        assertTrue(probe.vadjModuleFiles.isEmpty())
        assertTrue(probe.pdpCdtPartitions.isEmpty())
        assertEquals(0, probe.scmiDeviceCount)
    }

    @Test
    fun systemProbeDetectsAbnormalBootAndPstoreAndIncludesThemInTextReport() {
        val normalProbe = SystemProbeParser.parse(
            """
            boot_reason=reboot,userrequested
            pstore_crash=none
            """.trimIndent(),
        )
        assertEquals("reboot,userrequested", normalProbe.bootReason)
        assertNull(normalProbe.pstoreCrash)
        assertFalse(normalProbe.isAbnormalBoot)
        assertFalse(normalProbe.hasPstoreCrash)

        val crashProbe = SystemProbeParser.parse(
            """
            boot_reason=kernel_panic
            pstore_crash=console-ramoops-0
            """.trimIndent(),
        )
        assertTrue(crashProbe.isAbnormalBoot)
        assertTrue(crashProbe.hasPstoreCrash)

        val input = sampleInput()
        val report = CompatibilityChecker.evaluate(input)
        val text = CompatibilityChecker.formatTextReport(sampleSnapshot(input, report, crashProbe), "0.6.0")
        assertTrue(text.contains("启动原因 (boot_reason): kernel_panic"))
        assertTrue(text.contains("内核崩溃记录 (pstore): console-ramoops-0"))
    }

    private fun sampleInput(
        pageSizeBytes: Int? = 4096,
        debugfsPath: String? = "/sys/kernel/debug",
        debugfsPrivateNs: Boolean = false,
        adjBackend: AdjBackend = AdjBackend.DEBUGFS,
        pdpFwPath: String? = "/dev/block/by-name/pdp_a",
        pdpHardwareLimits: PdpHardwareLimits? = sampleHardwareLimits(),
        pdpFwError: String? = null,
        scmiModuleAsset: String? = "modules/sm8850/android16-6-4k/qcom-scmi-ro.ko",
        scmiSource: ScmiDataSource = ScmiDataSource.KERNEL_MODULE,
        adjDirPath: String? = null,
        regmapNode: String? = null,
    ): CompatibilityInput = CompatibilityInput(
        socModel = "SM8850",
        isSm8850 = true,
        pageSizeBytes = pageSizeBytes,
        debugfsPath = debugfsPath,
        debugfsPrivateNs = debugfsPrivateNs,
        adjBackend = adjBackend,
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
        scmiModuleAsset = scmiModuleAsset,
        scmiSource = scmiSource,
        scmiError = null,
        scmiPoints = listOf(
            ScmiPoint(domain = 0, levelIndex = 0, freqKhz = 384_000L, powerUw = 9_145L, voltageMv = 488, exactFormulaMatch = true),
            ScmiPoint(domain = 1, levelIndex = 0, freqKhz = 768_000L, powerUw = 30_678L, voltageMv = 516, exactFormulaMatch = true),
        ),
        adjDirPath = adjDirPath,
        regmapNode = regmapNode,
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
        systemProbe: SystemProbeInfo = SystemProbeInfo(),
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
        adjBackend = input.adjBackend,
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
        scmiSource = input.scmiSource,
        scmiError = input.scmiError,
        scmiPoints = input.scmiPoints,
        qualityReport = null,
        baselineSource = BaselineSource.VOLTSET,
        baselinePoints = input.scmiPoints,
        voltageDiffRows = emptyList(),
        setEstimates = emptyMap(),
        systemProbe = systemProbe,
        compatibility = report,
    )
}
