package io.voltset.sm8850.core

import java.util.Locale

enum class CompatibilityLevel {
    COMPATIBLE,
    PARTIAL,
    UNSUPPORTED,
}

enum class CheckStatus {
    PASS,
    WARN,
    FAIL,
}

data class CompatibilityCheckItem(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val summary: String,
    val detail: String,
    val blocking: Boolean,
)

data class CompatibilityReport(
    val level: CompatibilityLevel,
    val headline: String,
    val subheadline: String,
    val items: List<CompatibilityCheckItem>,
    val usableUndervoltSetCount: Int,
    val minCxOffsetMv: Int?,
) {
    val passedCount: Int get() = items.count { it.status == CheckStatus.PASS }
    val totalCount: Int get() = items.size
}

data class CompatibilityInput(
    val socModel: String,
    val isSm8850: Boolean,
    val pageSizeBytes: Int?,
    val debugfsPath: String?,
    val debugfsPrivateNs: Boolean = false,
    val adjBackend: AdjBackend = AdjBackend.NONE,
    val adjAvailable: Boolean,
    val adjSetNodePresent: Boolean,
    val adjError: String?,
    val sdamCpussIndex: Int?,
    val sdamGlobalIndex: Int?,
    val reg7151Hex: String?,
    val pdpCdbPath: String?,
    val pdpCdb: PdpCdbData?,
    val pdpCdbError: String?,
    val pdpFwPath: String?,
    val pdpHardwareLimits: PdpHardwareLimits?,
    val pdpFwError: String?,
    val cdtStatus: CdtStatus,
    val scmiModuleAsset: String?,
    val scmiSource: ScmiDataSource = ScmiDataSource.NONE,
    val scmiError: String?,
    val scmiPoints: List<ScmiPoint>,
    val adjDirPath: String? = null,
    val regmapNode: String? = null,
)

/**
 * Pure-Kotlin compatibility analyzer evaluating whether a device supports OEM Set undervolting
 * via `qcom_voltage_adj` + `pdp_cdb`, CDT safety status, and live SCMI inspection.
 */
object CompatibilityChecker {
    fun evaluate(input: CompatibilityInput): CompatibilityReport {
        val items = mutableListOf<CompatibilityCheckItem>()

        // 1. SoC check (non-blocking: newer or sibling Qualcomm chips using the same interface can pass)
        val socUpper = input.socModel.trim().uppercase(Locale.US)
        items += when {
            input.isSm8850 -> CompatibilityCheckItem(
                id = "soc",
                title = "SoC 平台与架构",
                status = CheckStatus.PASS,
                summary = "已适配平台（${input.socModel}）",
                detail = "已验证 SM8850 XBL -> PMK8850 SDAM2 (0x7151) -> PDP OEM_VOLTAGE_ADJUSTMENTS 链路与 SCMI 电压系数。",
                blocking = false,
            )
            socUpper.startsWith("SM") || socUpper.startsWith("QCS") || socUpper.startsWith("SD") || socUpper.startsWith("QCM") ->
                CompatibilityCheckItem(
                    id = "soc",
                    title = "SoC 平台与架构",
                    status = CheckStatus.WARN,
                    summary = "非 SM8850 高通平台（${input.socModel}）",
                    detail = "本工具主要面向 SM8850；若下方内核接口与 pdp_cdb 检查通过，可谨慎试用温和档位并验证重启后生效情况，SCMI mV 换算系数可能不准确。",
                    blocking = false,
                )
            else -> CompatibilityCheckItem(
                id = "soc",
                title = "SoC 平台与架构",
                status = CheckStatus.WARN,
                summary = "未知或非高通平台（${input.socModel}）",
                detail = "未能识别为 SM8850，具体能否调压仍以下方内核接口与 pdp_cdb 分区实测结果为准。",
                blocking = false,
            )
        }

        // 2. Kernel qcom_voltage_adj / SDAM2 nvmem interface (blocking)
        items += when {
            input.adjAvailable && input.adjSetNodePresent -> {
                val dbgPath = input.debugfsPath ?: "/sys/kernel/debug"
                val adjLoc = input.adjDirPath ?: dbgPath
                val (summary, detail) = when {
                    input.adjBackend == AdjBackend.NVMEM ->
                        "sysfs nvmem 兜底直通（CPUSS=${input.sdamCpussIndex ?: "?"} / Global=${input.sdamGlobalIndex ?: "?"}）" to
                            "未检测到 debugfs/qcom_voltage_adj，已通过已校验的 PMK8850 sdam@7100 (${input.adjDirPath ?: "/sys/bus/nvmem/devices/spmi_sdam2/nvmem"}) 偏移 0x51 读写 0x7151（不依赖 debugfs 与 qcom_voltage_adj 驱动）。"
                    input.debugfsPrivateNs ->
                        "读写节点完整（私有 NS 挂载，CPUSS=${input.sdamCpussIndex ?: "?"} / Global=${input.sdamGlobalIndex ?: "?"}）" to
                            "系统 init 未默认挂载 debugfs，已通过私有 mount namespace 临时挂载至 $dbgPath（不污染全局挂载表），在 $adjLoc 找到 get_overrides 与 set_overrides。"
                    else ->
                        "读写节点完整（CPUSS=${input.sdamCpussIndex ?: "?"} / Global=${input.sdamGlobalIndex ?: "?"}）" to
                            "已在 $adjLoc 找到 get_overrides 与 set_overrides，具备切换 Set 的内核前提。"
                }
                CompatibilityCheckItem(
                    id = "adj",
                    title = "内核 qcom_voltage_adj 接口",
                    status = CheckStatus.PASS,
                    summary = summary,
                    detail = detail,
                    blocking = true,
                )
            }
            input.adjAvailable && !input.adjSetNodePresent -> CompatibilityCheckItem(
                id = "adj",
                title = "内核 qcom_voltage_adj 接口",
                status = CheckStatus.FAIL,
                summary = "缺少 set_overrides 写节点",
                detail = "已读取到 get_overrides，但缺少 set_overrides，当前内核不允许修改 SDAM 调压索引。",
                blocking = true,
            )
            else -> CompatibilityCheckItem(
                id = "adj",
                title = "内核 qcom_voltage_adj 接口",
                status = CheckStatus.FAIL,
                summary = "未检测到 qcom_voltage_adj 接口",
                detail = input.adjError ?: "内核 debugfs 未暴露 qcom_voltage_adj 驱动节点，通常表示厂商内核已裁剪此功能。",
                blocking = true,
            )
        }

        // 3. PMIC regmap 0x7151 cross-check (non-blocking)
        val rmNode = input.regmapNode ?: "0-00"
        val regHexClean = input.reg7151Hex?.trim()
        val regByte = regHexClean?.toIntOrNull(16)
        val expectedByte = if (input.sdamCpussIndex != null && input.sdamGlobalIndex != null) {
            ((input.sdamCpussIndex and 0x7) shl 4) or (input.sdamGlobalIndex and 0x7) or 0x88
        } else {
            null
        }
        items += when {
            regByte != null && expectedByte != null && regByte == expectedByte -> CompatibilityCheckItem(
                id = "reg7151",
                title = "PMIC SDAM 寄存器 (0x7151)",
                status = CheckStatus.PASS,
                summary = "0x${regHexClean.uppercase(Locale.US)}（与 get_overrides 完全一致）",
                detail = "PMIC regmap $rmNode 寄存器 0x7151 实时字节校验匹配目标格式 (CPUSS/Global valid 位均置 1)。",
                blocking = false,
            )
            regByte != null && expectedByte != null -> CompatibilityCheckItem(
                id = "reg7151",
                title = "PMIC SDAM 寄存器 (0x7151)",
                status = CheckStatus.WARN,
                summary = "0x${regHexClean.uppercase(Locale.US)} 与预期 0x%02X 不同".format(Locale.US, expectedByte),
                detail = "此机型的 PMIC regmap ($rmNode) 地址或位掩码布局可能与 Xiaomi SM8850 不同，切换档位后请通过 SCMI 或负载实测确认是否实际生效。",
                blocking = false,
            )
            regByte != null -> CompatibilityCheckItem(
                id = "reg7151",
                title = "PMIC SDAM 寄存器 (0x7151)",
                status = CheckStatus.PASS,
                summary = "0x${regHexClean.uppercase(Locale.US)}",
                detail = "已读取 PMIC regmap $rmNode SDAM 寄存器字节。",
                blocking = false,
            )
            else -> CompatibilityCheckItem(
                id = "reg7151",
                title = "PMIC SDAM 寄存器 (0x7151)",
                status = CheckStatus.WARN,
                summary = "regmap/$rmNode/registers 不可读",
                detail = "无法直接交叉核对 PMIC $rmNode 0x7151 寄存器字节（该接口对 qcom_voltage_adj 本身工作非必需）。",
                blocking = false,
            )
        }

        // 4. pdp_cdb firmware table (blocking)
        val nonZeroSets = input.pdpCdb?.sets.orEmpty().filter { !it.clus0.isNoOp || !it.clus1.isNoOp }
        val undervoltSets = input.pdpCdb?.sets.orEmpty().filter {
            it.clus0.minCxMv < 0 || it.clus1.minCxMv < 0 || it.clus0.minMxMv < 0 || it.clus1.minMxMv < 0
        }
        val minCx = input.pdpCdb?.sets?.minOfOrNull { minOf(it.clus0.minCxMv, it.clus1.minCxMv) }

        items += when {
            input.pdpCdb == null -> CompatibilityCheckItem(
                id = "pdp_cdb",
                title = "pdp_cdb 固件调压表",
                status = CheckStatus.FAIL,
                summary = "缺失或无法解析 pdp_cdb 分区",
                detail = input.pdpCdbError ?: "未找到或无法按 ELF32/CFDB v3 解码 pdp_cdb 分区，无法确定是否存在 OEM 电压档位。",
                blocking = true,
            )
            nonZeroSets.isEmpty() -> CompatibilityCheckItem(
                id = "pdp_cdb",
                title = "pdp_cdb 固件调压表",
                status = CheckStatus.FAIL,
                summary = "所有 Set 档位均为 0 mV",
                detail = "已解出 ${input.pdpCdbPath ?: "pdp_cdb"}，但所有组别未配置任何电压偏移，切换 Set 不会起作用。",
                blocking = true,
            )
            undervoltSets.isEmpty() -> CompatibilityCheckItem(
                id = "pdp_cdb",
                title = "pdp_cdb 固件调压表",
                status = CheckStatus.WARN,
                summary = "仅发现非负/加压配置 (${nonZeroSets.size} 组)",
                detail = "分区中存在非 0 条目，但没有负值 CX/MX 降压配置，不适合用于降压目的。",
                blocking = false,
            )
            else -> CompatibilityCheckItem(
                id = "pdp_cdb",
                title = "pdp_cdb 固件调压表",
                status = CheckStatus.PASS,
                summary = "${undervoltSets.size} 组可用降压档位（最低 CX ${minCx ?: 0} mV）",
                detail = "已成功解码 ${input.pdpCdbPath ?: "pdp_cdb"}（共 ${input.pdpCdb.sets.size} 个 Set），包含真实有效的 CX/MX 偏移规则。",
                blocking = true,
            )
        }

        // 5. pdp_cdb override lock check (blocking)
        val lockVal = input.pdpCdb?.overrideSetId
        items += when {
            input.pdpCdb == null -> CompatibilityCheckItem(
                id = "override_lock",
                title = "pdp_cdb 强制覆盖锁",
                status = CheckStatus.WARN,
                summary = "未能检测（依赖 pdp_cdb）",
                detail = "需先成功解析 pdp_cdb 后才能确认 OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE 状态。",
                blocking = false,
            )
            lockVal == null || lockVal == 0 -> CompatibilityCheckItem(
                id = "override_lock",
                title = "pdp_cdb 强制覆盖锁",
                status = CheckStatus.PASS,
                summary = "未锁定（${lockVal ?: 0}），由 SDAM 控制",
                detail = "OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE = ${lockVal ?: 0}，固件会响应 qcom_voltage_adj 写入的 Set 索引。",
                blocking = true,
            )
            else -> CompatibilityCheckItem(
                id = "override_lock",
                title = "pdp_cdb 强制覆盖锁",
                status = CheckStatus.FAIL,
                summary = "固件强锁 Set $lockVal（禁止外部切换）",
                detail = "pdp_cdb 已硬编码 OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE = $lockVal，固件启动时会无视 SDAM 写入的档位。",
                blocking = true,
            )
        }

        // 6. CDT Key 7 safety check (non-blocking)
        val cdt = input.cdtStatus
        items += when {
            cdt.key7Enabled -> CompatibilityCheckItem(
                id = "cdt_key7",
                title = "CDT Key 7 叠加风险检测",
                status = CheckStatus.WARN,
                summary = "已启用 CDT Key 7=${cdt.key7Value}（危险叠加）",
                detail = "当前 CDT 分区开启了 SLT+ACD 降压，它会与 OEM Set 在固件层累加（双重降压），建议先还回原厂 CDT 再切换高档位。",
                blocking = false,
            )
            cdt.state == CdtState.EMPTY || cdt.state == CdtState.STOCK -> CompatibilityCheckItem(
                id = "cdt_key7",
                title = "CDT Key 7 叠加风险检测",
                status = CheckStatus.PASS,
                summary = "未启用（安全）",
                detail = cdt.detail,
                blocking = false,
            )
            else -> CompatibilityCheckItem(
                id = "cdt_key7",
                title = "CDT Key 7 叠加风险检测",
                status = CheckStatus.WARN,
                summary = "CDT 状态未知或异常",
                detail = cdt.detail,
                blocking = false,
            )
        }

        // 7. SCMI live table capability (optional, non-blocking)
        val pageLabel = input.pageSizeBytes?.let { "${it / 1024}K 页" } ?: "未知页大小"
        items += when {
            input.scmiError == null && input.scmiPoints.isNotEmpty() && input.scmiPoints.all { it.exactFormulaMatch } -> {
                val (summary, detail) = if (input.scmiSource == ScmiDataSource.ENERGY_MODEL) {
                    "正常 · ${input.scmiPoints.size} 个频点（energy_model 零模块，公式 100% 吻合）" to
                        "已直接从内核 debugfs/energy_model 读取 SCMI 功率表（无需加载 .ko 模块，原生兼容 $pageLabel 与任意内核版本），可用 Set 1 原厂基线精确检验实际 mV 变化。"
                } else {
                    "正常 · ${input.scmiPoints.size} 个频点（$pageLabel，公式 100% 吻合）" to
                        "内置模块 ${input.scmiModuleAsset ?: "qcom-scmi-ro.ko"} 工作正常，可用 Set 1 原厂基线精确检验实际 mV 变化。"
                }
                CompatibilityCheckItem(
                    id = "scmi",
                    title = "SCMI 实时电压读表（可选）",
                    status = CheckStatus.PASS,
                    summary = summary,
                    detail = detail,
                    blocking = false,
                )
            }
            input.scmiError == null && input.scmiPoints.isNotEmpty() -> {
                val exactCount = input.scmiPoints.count { it.exactFormulaMatch }
                CompatibilityCheckItem(
                    id = "scmi",
                    title = "SCMI 实时电压读表（可选）",
                    status = CheckStatus.WARN,
                    summary = "已读取 ${input.scmiPoints.size} 个频点（$exactCount/${input.scmiPoints.size} 公式吻合）",
                    detail = "电压表可读（来源：${if (input.scmiSource == ScmiDataSource.ENERGY_MODEL) "debugfs/energy_model" else input.scmiModuleAsset ?: "SCMI 模块"}），但当前平台的功率系数可能与 SM8850 不同，部分反推 mV 数值仅供参考，不影响调压功能本身。",
                    blocking = false,
                )
            }
            else -> {
                val pageHint = if (input.pageSizeBytes != null && input.pageSizeBytes != 4096) {
                    "当前内核为 $pageLabel，内置模块仅针对 4K 页 GKI 编译；"
                } else {
                    ""
                }
                CompatibilityCheckItem(
                    id = "scmi",
                    title = "SCMI 实时电压读表（可选）",
                    status = CheckStatus.WARN,
                    summary = "无法读取 SCMI 电压表（不影响调压）",
                    detail = "${pageHint}未找到可用的 debugfs/energy_model，且回退模块 ${input.scmiModuleAsset ?: "无"} 不可用，原因：${input.scmiError ?: "无快照数据"}。这仅影响「SCMI 电压」实测对比，不影响切换 Set 档位。",
                    blocking = false,
                )
            }
        }

        // 8. pdp firmware hardware [floor, ceil] tables (optional, non-blocking)
        val fwLimits = input.pdpHardwareLimits
        val fwClus0 = fwLimits?.clus0
        val fwClus1 = fwLimits?.clus1
        items += when {
            fwLimits != null && fwClus0 != null && fwClus1 != null -> CompatibilityCheckItem(
                id = "pdp_fw",
                title = "pdp 固件硬件限位表（可选）",
                status = CheckStatus.PASS,
                summary = "已匹配双簇 floor/ceil（PDP0 ${fwClus0.limitsByFreqKhz.size} 档 / PDP1 ${fwClus1.limitsByFreqKhz.size} 档）",
                detail = "已从 ${input.pdpFwPath ?: "pdp"}（${fwLimits.totalDcvsTablesFound} 张 DCVS 表）按本机频点自动匹配硬件上下限，首页支持按个体体质预估各 Set 实降范围。",
                blocking = false,
            )
            fwLimits != null && (fwClus0 != null || fwClus1 != null) -> {
                val matchedDesc = listOfNotNull(
                    fwClus0?.let { "PDP0 ${it.limitsByFreqKhz.size} 档" },
                    fwClus1?.let { "PDP1 ${it.limitsByFreqKhz.size} 档" },
                ).joinToString(" / ")
                CompatibilityCheckItem(
                    id = "pdp_fw",
                    title = "pdp 固件硬件限位表（可选）",
                    status = CheckStatus.WARN,
                    summary = "仅部分匹配（$matchedDesc）",
                    detail = input.pdpFwError ?: "已读取 ${input.pdpFwPath ?: "pdp"}，仅部分集群完成唯一匹配，未匹配集群不影响 Set 切换，但切换前无法预估其钳位范围。",
                    blocking = false,
                )
            }
            else -> CompatibilityCheckItem(
                id = "pdp_fw",
                title = "pdp 固件硬件限位表（可选）",
                status = CheckStatus.WARN,
                summary = "未匹配到本机硬件限位（不影响调压）",
                detail = input.pdpFwError ?: "未能从 ${input.pdpFwPath ?: "pdp"} 匹配本机双簇 floor/ceil 表。这仅影响切换 Set 前的「本机预计实降范围」推算，不影响切换 Set 或 SCMI 实测。",
                blocking = false,
            )
        }

        val hasBlockingFailure = items.any { it.blocking && it.status == CheckStatus.FAIL }
        val nonPassingCount = items.count { it.status != CheckStatus.PASS }

        val (level, headline, subheadline) = when {
            hasBlockingFailure -> {
                val blockers = items.filter { it.blocking && it.status == CheckStatus.FAIL }.joinToString("、") { it.title }
                Triple(
                    CompatibilityLevel.UNSUPPORTED,
                    "不支持本机 OEM Set 调压",
                    "存在阻塞性不兼容项：$blockers",
                )
            }
            nonPassingCount > 0 -> {
                Triple(
                    CompatibilityLevel.PARTIAL,
                    "可用于调压（有 $nonPassingCount 项提示）",
                    "核心条件已具备，建议先查看下方黄色提醒项后再温和测试。",
                )
            }
            else -> {
                Triple(
                    CompatibilityLevel.COMPATIBLE,
                    "完全兼容（${items.size}/${items.size} 项通过）",
                    "支持调节 Set 1–${input.pdpCdb?.sets?.maxOfOrNull { it.setId } ?: 7}，并可实时读取 SCMI 电压对比。",
                )
            }
        }

        return CompatibilityReport(
            level = level,
            headline = headline,
            subheadline = subheadline,
            items = items,
            usableUndervoltSetCount = undervoltSets.size,
            minCxOffsetMv = minCx,
        )
    }

    fun evaluateSnapshot(snapshot: DeviceSnapshot): CompatibilityReport = evaluate(
        CompatibilityInput(
            socModel = snapshot.socModel,
            isSm8850 = snapshot.isSm8850,
            pageSizeBytes = snapshot.pageSizeBytes,
            debugfsPath = snapshot.debugfsPath,
            debugfsPrivateNs = snapshot.debugfsPrivateNs,
            adjBackend = snapshot.adjBackend,
            adjAvailable = snapshot.adjAvailable,
            adjSetNodePresent = snapshot.adjSetNodePresent,
            adjError = snapshot.adjError,
            sdamCpussIndex = snapshot.sdamCpussIndex,
            sdamGlobalIndex = snapshot.sdamGlobalIndex,
            reg7151Hex = snapshot.reg7151Hex,
            pdpCdbPath = snapshot.pdpCdbPath,
            pdpCdb = snapshot.pdpCdb,
            pdpCdbError = snapshot.pdpCdbError,
            pdpFwPath = snapshot.pdpFwPath,
            pdpHardwareLimits = snapshot.pdpHardwareLimits,
            pdpFwError = snapshot.pdpFwError,
            cdtStatus = snapshot.cdtStatus,
            scmiModuleAsset = snapshot.scmiModuleAsset,
            scmiSource = snapshot.scmiSource,
            scmiError = snapshot.scmiError,
            scmiPoints = snapshot.scmiPoints,
            adjDirPath = snapshot.systemProbe.adjDirPath,
            regmapNode = snapshot.systemProbe.regmapNode,
        ),
    )

    fun formatTextReport(snapshot: DeviceSnapshot, appVersion: String): String = buildString {
        val compat = snapshot.compatibility
        appendLine("VoltSet v$appVersion 设备兼容性检测报告")
        appendLine("结论: [${compat.level}] ${compat.headline}")
        appendLine("摘要: ${compat.subheadline}")
        appendLine("机型: ${snapshot.deviceModel} (${snapshot.deviceName}) | SoC: ${snapshot.socModel} | Slot: ${snapshot.slotSuffix.ifBlank { "none" }}")
        appendLine("内核: ${snapshot.kernelRelease} | 页大小: ${snapshot.pageSizeBytes?.toString() ?: "unknown"} B")
        appendLine("debugfs: ${snapshot.debugfsPath ?: "none"}${if (snapshot.debugfsPrivateNs) " (private-ns)" else ""}")
        appendLine("SDAM: ${snapshot.sdamCpussIndex ?: "?"}/${snapshot.sdamGlobalIndex ?: "?"} 0x7151=${snapshot.reg7151Hex ?: "--"}")
        with(snapshot.bootSet) {
            appendLine(
                "开机档推定=${runningIndex ?: "?"} 固件锁档=${firmwareIndex ?: "—"} " +
                    "本次首次读回=${firstSdamIndex ?: "?"} 上次启动写入=${previousTargetIndex ?: "—"} " +
                    "当前待重启=${snapshot.pendingSetIndex ?: "—"}",
            )
        }
        appendLine()
        appendLine("[分项检测结果]")
        for (item in compat.items) {
            appendLine("- [${item.status}] ${item.title}: ${item.summary}")
            appendLine("  ${item.detail}")
        }
        val probe = snapshot.systemProbe
        if (probe.hasAnyData) {
            appendLine()
            appendLine("[全机只读自动探测]")
            val adjBackendStr = when (snapshot.adjBackend) {
                AdjBackend.DEBUGFS -> "debugfs/qcom_voltage_adj"
                AdjBackend.NVMEM -> "sysfs spmi_sdam2/nvmem (sdam@7100+0x51)"
                AdjBackend.NONE -> "none"
            }
            appendLine("调压读写通道: $adjBackendStr")
            appendLine("debugfs 挂载点: ${probe.debugfsMounts.joinToString(", ").ifEmpty { "none" }}")
            appendLine("qcom_voltage_adj 目录: ${probe.adjDirPath ?: "none"}")
            appendLine("debugfs 调压节点: ${probe.debugfsVoltNodes.joinToString(", ").ifEmpty { "none" }}")
            val devSummary = probe.vadjPlatformDevice?.let {
                "$it (driver=${probe.vadjDriver ?: "unknown"}, spmi=${probe.vadjSpmiParent ?: "unknown"})"
            } ?: "none"
            appendLine("平台设备/驱动: $devSummary")
            appendLine("设备树路径: ${probe.vadjDtPath ?: "none"}")
            appendLine(
                "内核模块: ${if (probe.vadjModuleLoaded) "已加载" else "未加载"} | " +
                    "磁盘: ${probe.vadjModuleFiles.joinToString(", ").ifEmpty { "none" }}",
            )
            appendLine("PMIC regmap 节点: ${probe.regmapNode ?: "none"}")
            appendLine(
                "PMK8850 SDAM2 nvmem: " +
                    (probe.sdam2NvmemPath?.let { p -> probe.sdam2Pmic?.let { "$p ($it)" } ?: p } ?: "none"),
            )
            appendLine("GPT 发现分区(pdp/cdt): ${probe.pdpCdtPartitions.joinToString(", ").ifEmpty { "none" }}")
            appendLine("by-name 目录: ${probe.byNameDirs.joinToString(", ").ifEmpty { "none" }}")
            appendLine("SCMI 接口: ${probe.scmiDeviceCount} 个设备 | ${probe.scmiDrivers.joinToString(", ").ifEmpty { "none" }}")
            appendLine("CPU 调频簇: ${probe.cpufreqPolicies.joinToString(", ").ifEmpty { "none" }}")
            val scmiSourceStr = when (snapshot.scmiSource) {
                ScmiDataSource.ENERGY_MODEL -> "debugfs/energy_model (零内核模块)"
                ScmiDataSource.KERNEL_MODULE -> "qcom-scmi-ro.ko"
                ScmiDataSource.NONE -> "none"
            }
            appendLine("电压表来源: $scmiSourceStr")
            appendLine("energy_model 域: ${probe.energyModelDomains.joinToString(", ").ifEmpty { "none" }}")
            appendLine("硅片信息 (socinfo): ${probe.socInfo ?: "none"}")
            appendLine(
                "QFPROM 老化熔丝 (0xA10/0xA20): " + (
                    probe.qfpromAging?.let {
                        "clus0=${it.clus0FusedDelta}, clus1=${it.clus1FusedDelta} (${it.formatRawHex()})"
                    } ?: "none"
                    ),
            )
            appendLine("QPT 硬件功耗轨: ${probe.qptRails.joinToString(", ").ifEmpty { "none" }}")
        }
        val sets = snapshot.pdpCdb?.sets.orEmpty()
        if (sets.isNotEmpty()) {
            appendLine()
            appendLine("[pdp_cdb 档位摘要]")
            for (s in sets) {
                appendLine(
                    "Set ${s.setId} (${s.tag}): " +
                        "PDP0 CX ${s.clus0.minCxMv}..${s.clus0.maxCxMv} MX ${s.clus0.minMxMv}..${s.clus0.maxMxMv} | " +
                        "PDP1 CX ${s.clus1.minCxMv}..${s.clus1.maxCxMv} MX ${s.clus1.minMxMv}..${s.clus1.maxMxMv}",
                )
            }
        }
        val fwLimits = snapshot.pdpHardwareLimits
        if (fwLimits != null || snapshot.setEstimates.isNotEmpty() || snapshot.pdpFwPath != null) {
            appendLine()
            appendLine("[pdp 硬件限位与本机预计范围]")
            appendLine(
                "分区: ${snapshot.pdpFwPath ?: "none"} | DCVS 表: ${fwLimits?.totalDcvsTablesFound ?: 0} | " +
                    "PDP0=${fwLimits?.clus0?.limitsByFreqKhz?.size ?: 0} 档 PDP1=${fwLimits?.clus1?.limitsByFreqKhz?.size ?: 0} 档",
            )
            snapshot.pdpFwError?.let { appendLine("说明: $it") }
            val pdp0Measured = ScmiPlanParser.summarizeByCluster(snapshot.voltageDiffRows, 0)
            val pdp1Measured = ScmiPlanParser.summarizeByCluster(snapshot.voltageDiffRows, 1)
            for (s in sets) {
                val est = snapshot.setEstimates[s.setId] ?: continue
                val c0Str = est.clus0?.let { "${it.minDeltaMv}..${it.maxDeltaMv} mV (下限${it.floorClampedBins} 单调${it.monotonicityClampedBins})" } ?: "—"
                val c1Str = est.clus1?.let { "${it.minDeltaMv}..${it.maxDeltaMv} mV (下限${it.floorClampedBins} 单调${it.monotonicityClampedBins})" } ?: "—"
                val measSuffix = if (s.setId == snapshot.bootCpussIndex && (pdp0Measured.minDeltaMv != null || pdp1Measured.minDeltaMv != null)) {
                    " | 实测 PDP0=${pdp0Measured.minDeltaMv}..${pdp0Measured.maxDeltaMv} PDP1=${pdp1Measured.minDeltaMv}..${pdp1Measured.maxDeltaMv}"
                } else {
                    ""
                }
                appendLine("Set ${s.setId}: 本机预计 PDP0=$c0Str | PDP1=$c1Str$measSuffix")
            }
        }
    }
}
