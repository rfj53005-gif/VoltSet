package io.voltset.sm8850.core

/** Where the stock SCMI baseline CSV was loaded from. */
enum class BaselineSource {
    NONE,
    VOLTSET,
    SUNTUNER,
}

/** Immediate readback after writing `set_overrides`. */
data class OverrideWriteResult(
    val bootId: String,
    val bootCpussIndex: Int,
    val sdamCpussIndex: Int,
    val sdamGlobalIndex: Int,
    val reg7151Hex: String?,
    val writtenDecimal: Int,
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
    val adjAvailable: Boolean,
    val adjSetNodePresent: Boolean,
    val adjError: String?,
    val bootCpussIndex: Int?,
    val sdamCpussIndex: Int?,
    val sdamGlobalIndex: Int?,
    val reg7151Hex: String?,
    val pdpCdbPath: String?,
    val pdpCdb: PdpCdbData?,
    val pdpCdbError: String?,
    val cdtPath: String?,
    val cdtStatus: CdtStatus,
    val scmiModuleAsset: String?,
    val scmiError: String?,
    val scmiPoints: List<ScmiPoint>,
    val baselineSource: BaselineSource,
    val baselinePoints: List<ScmiPoint>?,
    val voltageDiffRows: List<VoltageDiffRow>,
    val compatibility: CompatibilityReport,
) {
    /** True when SDAM holds a different Set index than the one booted on this boot_id. */
    val pendingReboot: Boolean
        get() = bootCpussIndex != null && sdamCpussIndex != null && bootCpussIndex != sdamCpussIndex

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

    /** Safe conditions under which live SCMI snapshot represents true unmodified stock mV. */
    val canSaveStockBaseline: Boolean
        get() = bootCpussIndex == 1 &&
            !cdtStatus.key7Enabled &&
            (cdtStatus.state == CdtState.EMPTY || cdtStatus.state == CdtState.STOCK) &&
            (pdpCdb?.overrideSetId == null || pdpCdb.overrideSetId == 0 || pdpCdb.overrideSetId == 1) &&
            scmiPoints.isNotEmpty()
}
