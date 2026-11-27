package io.voltset.sm8850.core

/** Where the stock SCMI baseline CSV was loaded from. */
enum class BaselineSource {
    NONE,
    VOLTSET,
    SUNTUNER,
}

/** Where the CPU SCMI planned-voltage table was read from. */
enum class ScmiDataSource {
    NONE,
    ENERGY_MODEL,
    KERNEL_MODULE,
}

/** Which kernel interface is used to read/write PMK8850 SDAM2 (0x7151). */
enum class AdjBackend {
    NONE,
    DEBUGFS,
    NVMEM,
}

/** Immediate readback after writing `set_overrides`. */
data class OverrideWriteResult(
    val sdamCpussIndex: Int,
    val sdamGlobalIndex: Int,
    val reg7151Hex: String?,
)

/** Complete runtime snapshot returned by `voltset.sh status` and parsed in Kotlin. */
data class DeviceSnapshot(
    val bootId: String,
    val socModel: String,
    val deviceModel: String,
    val deviceName: String,
    val slotSuffix: String,
    val kernelRelease: String,
    val pageSizeBytes: Int?,
    val isSm8850: Boolean,
    val debugfsPath: String?,
    val debugfsPrivateNs: Boolean = false,
    val adjBackend: AdjBackend = AdjBackend.NONE,
    val adjAvailable: Boolean,
    val adjSetNodePresent: Boolean,
    val adjError: String?,
    val bootSet: BootSetStatus,
    val sdamCpussIndex: Int?,
    val sdamGlobalIndex: Int?,
    val reg7151Hex: String?,
    val pdpCdbPath: String?,
    val pdpCdbSha256: String? = null,
    val pdpCdb: PdpCdbData?,
    val pdpCdbError: String?,
    val pdpFwPath: String?,
    val pdpHardwareLimits: PdpHardwareLimits?,
    val pdpFwError: String?,
    val cdtPath: String?,
    val cdtStatus: CdtStatus,
    val scmiModuleAsset: String?,
    val scmiSource: ScmiDataSource = ScmiDataSource.NONE,
    val scmiError: String?,
    val scmiPoints: List<ScmiPoint>,
    val qualityReport: ScmiQualityReport?,
    val baselineSource: BaselineSource,
    val baselinePoints: List<ScmiPoint>?,
    val voltageDiffRows: List<VoltageDiffRow>,
    val setEstimates: Map<Int, SetDeviceEstimate>,
    val systemProbe: SystemProbeInfo = SystemProbeInfo(),
    val compatibility: CompatibilityReport,
) {
    val bootCpussIndex: Int? get() = bootSet.runningIndex

    val pendingSetIndex: Int? get() = bootSet.pendingIndex(sdamCpussIndex)

    val pendingReboot: Boolean get() = pendingSetIndex != null

    /** Effective Set profile currently running on CPU hardware (based on bootCpussIndex). */
    val activeBootSet: VoltageSetProfile?
        get() = bootCpussIndex?.let { id -> pdpCdb?.sets?.firstOrNull { it.setId == id } }

    /** Target Set profile stored in PMK8850 SDAM2 `0x7151`. */
    val sdamTargetSet: VoltageSetProfile?
        get() = sdamCpussIndex?.let { id -> pdpCdb?.sets?.firstOrNull { it.setId == id } }

    /** Both read and write nodes of `qcom_voltage_adj` are present in debugfs. */
    val canWriteOverrides: Boolean
        get() = adjAvailable && adjSetNodePresent

    /** True when `pdp_cdb` scalar `OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE` forces a fixed Set. */
    val overrideLockedByFirmware: Boolean
        get() = (pdpCdb?.overrideSetId ?: 0) != 0

    fun canSelectSet(setId: Int): Boolean =
        canWriteOverrides && sdamCpussIndex != null && sdamGlobalIndex != null &&
            !overrideLockedByFirmware && setId != sdamCpussIndex &&
            (setId == 1 || (compatibility.level != CompatibilityLevel.UNSUPPORTED &&
                pdpCdb?.sets?.any { it.setId == setId } == true))

    /** Safe conditions under which live SCMI snapshot represents true unmodified stock mV. */
    val canSaveStockBaseline: Boolean
        get() = bootCpussIndex == 1 && bootSet.firstSdamIndex == 1 && !bootSet.rebootReadbackMismatch &&
            !cdtStatus.key7Enabled &&
            (cdtStatus.state == CdtState.EMPTY || cdtStatus.state == CdtState.STOCK) &&
            (pdpCdb?.overrideSetId == null || pdpCdb.overrideSetId == 0 || pdpCdb.overrideSetId == 1) &&
            scmiPoints.isNotEmpty()
}
