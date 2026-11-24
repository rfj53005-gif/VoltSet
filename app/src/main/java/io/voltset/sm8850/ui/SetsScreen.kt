package io.voltset.sm8850.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.voltset.sm8850.R
import io.voltset.sm8850.VoltSetUiState
import io.voltset.sm8850.core.AdjustMode
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.CdtState
import io.voltset.sm8850.core.CdtStatus
import io.voltset.sm8850.core.ClusterSetEstimate
import io.voltset.sm8850.core.ClusterSetSummary
import io.voltset.sm8850.core.ClusterVoltageStats
import io.voltset.sm8850.core.CompatibilityLevel
import io.voltset.sm8850.core.CompatibilityReport
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.ScmiPlanParser
import io.voltset.sm8850.core.SetDeviceEstimate
import io.voltset.sm8850.core.SetProfileTag
import io.voltset.sm8850.core.VoltageSetProfile
import java.util.Locale

@Composable
internal fun SetsScreen(
    state: VoltSetUiState,
    snapshot: DeviceSnapshot,
    onApplySet: (VoltageSetProfile) -> Unit,
    onResetDefault: () -> Unit,
    onReboot: () -> Unit,
    onToggleExpanded: (Int) -> Unit,
    onOpenCompatTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sets = snapshot.pdpCdb?.sets.orEmpty()
    val pdp0Measured = remember(snapshot.voltageDiffRows) {
        ScmiPlanParser.summarizeByCluster(snapshot.voltageDiffRows, 0)
    }
    val pdp1Measured = remember(snapshot.voltageDiffRows) {
        ScmiPlanParser.summarizeByCluster(snapshot.voltageDiffRows, 1)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") {
            StatusHeroCard(
                snapshot = snapshot,
                pdp0Measured = pdp0Measured,
                pdp1Measured = pdp1Measured,
                controlsEnabled = state.controlsEnabled,
                onResetDefault = onResetDefault,
                onReboot = onReboot,
                onRevertBootSet = {
                    snapshot.activeBootSet?.let(onApplySet)
                },
            )
        }

        item(key = "compat_strip") {
            CompatibilitySummaryStrip(
                report = snapshot.compatibility,
                onClick = onOpenCompatTab,
            )
        }

        if (snapshot.cdtStatus.key7Enabled || snapshot.cdtStatus.state == CdtState.CORRUPT) {
            item(key = "cdt_warn") {
                CdtWarningBanner(cdt = snapshot.cdtStatus)
            }
        }

        val cdbOverride = snapshot.pdpCdb?.overrideSetId
        if (cdbOverride != null && cdbOverride != 0) {
            item(key = "cdb_override_warn") {
                WarningBannerCard(
                    title = "固件 pdp_cdb 已强制锁定 Set $cdbOverride",
                    body = "检测到 OEM_VOLTAGE_ADJUSTMENTS_SET_OVERRIDE = $cdbOverride（非 0），固件会优先使用该档位而非 SDAM 0x7151。",
                )
            }
        }

        if (!snapshot.adjAvailable) {
            item(key = "adj_unavailable") {
                WarningBannerCard(
                    title = "未检测到 qcom_voltage_adj 内核接口",
                    body = snapshot.adjError ?: "当前内核 debugfs 未暴露 qcom_voltage_adj，仅可查看 pdp_cdb 与 SCMI 只读信息。",
                )
            }
        }

        if (snapshot.pdpCdbError != null) {
            item(key = "pdp_cdb_err") {
                WarningBannerCard(
                    title = "pdp_cdb 分区解析异常",
                    body = snapshot.pdpCdbError,
                )
            }
        } else if (sets.isNotEmpty()) {
            item(key = "section_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "OEM 预设档位 (${sets.size} 组)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            items(sets, key = { it.setId }) { profile ->
                val isRunningSet = profile.setId == snapshot.bootCpussIndex
                VoltageSetCard(
                    profile = profile,
                    bootLabel = when (profile.setId) {
                        snapshot.bootSet.firmwareIndex -> "固件锁定"
                        snapshot.bootCpussIndex -> "开机推定"
                        else -> null
                    },
                    isSdamTarget = snapshot.sdamCpussIndex == profile.setId,
                    isPending = snapshot.pendingSetIndex == profile.setId,
                    measuredPdp0 = pdp0Measured.takeIf { isRunningSet && it.minDeltaMv != null && it.maxDeltaMv != null },
                    measuredPdp1 = pdp1Measured.takeIf { isRunningSet && it.minDeltaMv != null && it.maxDeltaMv != null },
                    estimate = snapshot.setEstimates[profile.setId],
                    canApply = state.controlsEnabled && snapshot.canSelectSet(profile.setId),
                    expanded = profile.setId in state.expandedSetIds,
                    onToggleExpand = { onToggleExpanded(profile.setId) },
                    onApply = { onApplySet(profile) },
                )
            }
        }

    }
}

@Composable
private fun CompatibilitySummaryStrip(
    report: CompatibilityReport,
    onClick: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val (bg, fg, border, iconRes) = when (report.level) {
        CompatibilityLevel.COMPATIBLE -> Quad(
            semantic.goodContainer,
            semantic.onGoodContainer,
            semantic.goodAccent.copy(alpha = 0.35f),
            R.drawable.ic_shield_check,
        )
        CompatibilityLevel.PARTIAL -> Quad(
            semantic.warnContainer,
            semantic.onWarnContainer,
            semantic.warnBorder,
            R.drawable.ic_warning,
        )
        CompatibilityLevel.UNSUPPORTED -> Quad(
            semantic.dangerContainer,
            semantic.onDangerContainer,
            semantic.dangerAccent.copy(alpha = 0.45f),
            R.drawable.ic_warning,
        )
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(18.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "设备兼容性：${report.headline}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                    )
                    Text(
                        text = report.subheadline,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = 0.9f),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "详情",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
        }
    }
}

@Composable
private fun StatusHeroCard(
    snapshot: DeviceSnapshot,
    pdp0Measured: ClusterVoltageStats,
    pdp1Measured: ClusterVoltageStats,
    controlsEnabled: Boolean,
    onResetDefault: () -> Unit,
    onReboot: () -> Unit,
    onRevertBootSet: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val boot = snapshot.bootSet
    val bootIndex = boot.runningIndex
    val liveIndex = snapshot.sdamCpussIndex
    val pendingIndex = snapshot.pendingSetIndex
    val hex = snapshot.reg7151Hex?.uppercase(Locale.US)?.let { "0x$it" } ?: "—"

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, semantic.subtleBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("开机档位", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CdtBadgeChip(cdtStatus = snapshot.cdtStatus)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SetReadout(
                    label = "本次开机",
                    value = bootIndex?.let { "Set $it" } ?: "待确认",
                    detail = when {
                        boot.firmwareIndex != null -> "固件锁定"
                        bootIndex != null -> "首次 SDAM 读回推定"
                        else -> "需核对开机档"
                    },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                SetReadout(
                    label = "SDAM 当前读回",
                    value = when (liveIndex) {
                        null -> "未读到"
                        0 -> "索引 0"
                        else -> "Set $liveIndex"
                    },
                    detail = "Global ${snapshot.sdamGlobalIndex ?: "—"}  ·  $hex",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            val previous = boot.previousTargetIndex
            val firstRead = boot.firstSdamIndex
            if (previous != null && firstRead != null) {
                if (boot.rebootReadbackMismatch) {
                    val observed = if (firstRead == 0) "索引 0" else "Set $firstRead"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = semantic.warnContainer,
                        contentColor = semantic.onWarnContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "重启后读回不一致。上次写入 Set $previous，本次首次读回 $observed。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text(
                        text = "重启后 SDAM 读回与上次写入一致 · Set $firstRead",
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.onGoodContainer,
                    )
                }
            }

            if (pdp0Measured.minDeltaMv != null || pdp1Measured.minDeltaMv != null) {
                val hasLowered = pdp0Measured.loweredBins > 0 || pdp1Measured.loweredBins > 0
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasLowered) semantic.goodContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (hasLowered) semantic.onGoodContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "本机实测 SCMI 降幅",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        pdp0Measured.minDeltaMv?.let { min0 ->
                            val max0 = pdp0Measured.maxDeltaMv ?: min0
                            Text(
                                text = "小核 PDP0：${formatRange(min0, max0)}（降压 ${pdp0Measured.loweredBins}/${pdp0Measured.totalBins} 档）",
                                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                            )
                        }
                        pdp1Measured.minDeltaMv?.let { min1 ->
                            val max1 = pdp1Measured.maxDeltaMv ?: min1
                            Text(
                                text = "大核 PDP1：${formatRange(min1, max1)}（降压 ${pdp1Measured.loweredBins}/${pdp1Measured.totalBins} 档）",
                                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                            )
                        }
                    }
                }
            } else if (snapshot.baselineSource == BaselineSource.NONE && snapshot.scmiError == null) {
                Text(
                    text = "切回 Set 1 重启后自动采集原厂基线，首页即可按本机体质显示各档预计/实测降幅。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (pendingIndex != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = semantic.warnContainer,
                    contentColor = semantic.onWarnContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Set $pendingIndex 待重启核对",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onReboot, enabled = controlsEnabled) {
                                Icon(painterResource(R.drawable.ic_restart), null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("立即重启")
                            }
                            if (bootIndex != null && snapshot.activeBootSet != null) {
                                OutlinedButton(
                                    onClick = onRevertBootSet,
                                    enabled = controlsEnabled && snapshot.canSelectSet(bootIndex),
                                ) {
                                    Text("撤回至 Set $bootIndex")
                                }
                            }
                        }
                    }
                }
            }

            if (liveIndex != null && liveIndex != 1 && !snapshot.overrideLockedByFirmware) {
                OutlinedButton(
                    onClick = onResetDefault,
                    enabled = controlsEnabled && snapshot.canSelectSet(1),
                ) {
                    Icon(painterResource(R.drawable.ic_restore), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("还原默认 Set 1")
                }
            }
        }
    }
}

@Composable
private fun SetReadout(
    label: String,
    value: String,
    detail: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall.merge(TabularStyle), fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CdtBadgeChip(cdtStatus: CdtStatus) {
    val semantic = LocalVoltSetColors.current
    val (bg, fg, label, iconRes) = when {
        cdtStatus.key7Enabled -> Quad(
            semantic.dangerContainer,
            semantic.onDangerContainer,
            "CDT Key7 已启用",
            R.drawable.ic_warning,
        )
        cdtStatus.state == CdtState.CORRUPT -> Quad(
            semantic.dangerContainer,
            semantic.onDangerContainer,
            "CDT 异常",
            R.drawable.ic_warning,
        )
        cdtStatus.state == CdtState.EMPTY || cdtStatus.state == CdtState.STOCK -> Quad(
            semantic.goodContainer,
            semantic.onGoodContainer,
            "CDT Key7 未开启",
            R.drawable.ic_shield_check,
        )
        else -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "CDT 未知",
            R.drawable.ic_warning,
        )
    }

    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
        }
    }
}

@Composable
private fun CdtWarningBanner(cdt: CdtStatus) {
    val semantic = LocalVoltSetColors.current
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = semantic.dangerContainer),
        border = BorderStroke(1.dp, semantic.dangerAccent.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = semantic.onDangerContainer,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (cdt.key7Enabled) "警告：CDT Key 7（SLT+ACD）当前已开启！" else "警告：CDT 分区状态异常",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = semantic.onDangerContainer,
                )
                Text(
                    text = if (cdt.key7Enabled) {
                        "${cdt.detail}\n固件实测证明 CDT SLT 会与 OEM Set 降压互相叠加（同一频率双重降压），叠加激进档位极易无法开机。切换更高 Set 前建议先恢复原厂 CDT。"
                    } else {
                        cdt.detail
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.onDangerContainer,
                )
            }
        }
    }
}

@Composable
private fun WarningBannerCard(
    title: String,
    body: String,
) {
    val semantic = LocalVoltSetColors.current
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = semantic.warnContainer),
        border = BorderStroke(1.dp, semantic.warnBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = semantic.onWarnContainer,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = semantic.onWarnContainer,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.onWarnContainer,
                )
            }
        }
    }
}

@Composable
private fun VoltageSetCard(
    profile: VoltageSetProfile,
    bootLabel: String?,
    isSdamTarget: Boolean,
    isPending: Boolean,
    measuredPdp0: ClusterVoltageStats?,
    measuredPdp1: ClusterVoltageStats?,
    estimate: SetDeviceEstimate?,
    canApply: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onApply: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val highlighted = bootLabel != null || isSdamTarget
    val borderColor = when {
        isPending -> semantic.warnBorder
        highlighted -> MaterialTheme.colorScheme.primary
        else -> semantic.subtleBorder
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (highlighted) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Set ${profile.setId}",
                    style = MaterialTheme.typography.titleLarge.merge(TabularStyle),
                    fontWeight = FontWeight.Bold,
                )
                SetTagChip(profile = profile)
                if (profile.isAggressive) {
                    StatusMiniChip("激进", semantic.dangerContainer, semantic.onDangerContainer)
                }
                if (bootLabel != null) {
                    StatusMiniChip(bootLabel, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (isSdamTarget) {
                    StatusMiniChip(if (isPending) "待重启" else "SDAM 读回", semantic.warnContainer, semantic.onWarnContainer)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClusterSummaryBox(
                    title = "小核 PDP0 (CPU0–5)",
                    summary = profile.clus0,
                    measured = measuredPdp0,
                    estimate = estimate?.clus0,
                    modifier = Modifier.weight(1f),
                )
                ClusterSummaryBox(
                    title = "大核 PDP1 (CPU6–7)",
                    summary = profile.clus1,
                    measured = measuredPdp1,
                    estimate = estimate?.clus1,
                    modifier = Modifier.weight(1f),
                )
            }

            val limited0 = estimate?.clus0?.bins.orEmpty().count { it.deltaMv != it.cdbOffsetMv || it.atStockCeiling }
            val limited1 = estimate?.clus1?.bins.orEmpty().count { it.deltaMv != it.cdbOffsetMv || it.atStockCeiling }
            val hasDetails = profile.clus0.entries.isNotEmpty() || profile.clus1.entries.isNotEmpty() ||
                profile.clus0.allCxMv != 0 || profile.clus1.allCxMv != 0 ||
                profile.clus0.allMxMv != 0 || profile.clus1.allMxMv != 0 ||
                limited0 > 0 || limited1 > 0

            if (hasDetails) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleExpand)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val totalPoints = profile.clus0.entries.size + profile.clus1.entries.size
                    val totalLimited = limited0 + limited1
                    val label = buildList {
                        if (totalPoints > 0) add("分频规则 $totalPoints 条") else add("规则明细")
                        if (totalLimited > 0) add("受限频点 $totalLimited 个")
                    }.joinToString(" · ")
                    Text(
                        text = "查看 pdp_cdb $label",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        painter = painterResource(
                            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                        ),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        HorizontalDivider(color = semantic.subtleBorder)
                        ClusterDetailBlock("小核 PDP0 明细", profile.clus0, estimate?.clus0)
                        ClusterDetailBlock("大核 PDP1 明细", profile.clus1, estimate?.clus1)
                    }
                }
            }

            if (!isSdamTarget) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onApply, enabled = canApply, shape = RoundedCornerShape(10.dp)) {
                        Text("设为下次开机")
                    }
                }
            }
        }
    }
}

@Composable
private fun SetTagChip(profile: VoltageSetProfile) {
    val semantic = LocalVoltSetColors.current
    val (label, bg, fg) = when (profile.tag) {
        SetProfileTag.DEFAULT -> Triple(
            "原厂默认",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SetProfileTag.UNIFORM -> Triple(
            "全频均匀",
            semantic.goodContainer,
            semantic.onGoodContainer,
        )
        SetProfileTag.CURVE -> Triple(
            "分频曲线",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    StatusMiniChip(text = label, container = bg, content = fg)
}

@Composable
private fun StatusMiniChip(
    text: String,
    container: Color,
    content: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}

@Composable
private fun ClusterSummaryBox(
    title: String,
    summary: ClusterSetSummary,
    measured: ClusterVoltageStats?,
    estimate: ClusterSetEstimate?,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVoltSetColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                measured != null && measured.minDeltaMv != null && measured.maxDeltaMv != null -> {
                    Text(
                        text = "实测  ${formatRange(measured.minDeltaMv, measured.maxDeltaMv)}",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = if (measured.minDeltaMv < 0) semantic.goodAccent else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "表定 CX ${formatRange(summary.minCxMv, summary.maxCxMv)} · MX ${formatRange(summary.minMxMv, summary.maxMxMv)}",
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "降压 ${measured.loweredBins}/${measured.totalBins} 档",
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                estimate != null -> {
                    val prefix = if (estimate.hasRoundingVariance || estimate.ceilingRiskBins > 0) "预计约" else "预计"
                    Text(
                        text = "$prefix  ${formatRange(estimate.minDeltaMv, estimate.maxDeltaMv)}",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = if (estimate.minDeltaMv < 0) semantic.goodAccent else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "表定 CX ${formatRange(summary.minCxMv, summary.maxCxMv)} · MX ${formatRange(summary.minMxMv, summary.maxMxMv)}",
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatEstimateMicroNote(estimate),
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Text(
                        text = "表定 CX  ${formatRange(summary.minCxMv, summary.maxCxMv)}",
                        style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = if (summary.minCxMv < 0) semantic.goodAccent else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "表定 MX  ${formatRange(summary.minMxMv, summary.maxMxMv)}",
                        style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatEstimateMicroNote(estimate: ClusterSetEstimate): String {
    val notes = buildList {
        if (estimate.floorClampedBins > 0) add("${estimate.floorClampedBins} 档触及下限")
        if (estimate.monotonicityClampedBins > 0) add("${estimate.monotonicityClampedBins} 档受单调限位")
        if (estimate.ceilingRiskBins > 0) add("${estimate.ceilingRiskBins} 档原厂顶上限")
    }
    return when {
        notes.isNotEmpty() -> notes.joinToString(" · ")
        estimate.minDeltaMv < 0 -> "全 ${estimate.totalBins} 档按表定生效"
        else -> "保持原厂（共 ${estimate.totalBins} 档）"
    }
}

private fun formatConstraintReason(bin: io.voltset.sm8850.core.BinVoltageEstimate): String {
    val reasons = buildList {
        if (bin.clampedByFloor) add("触及下限 ${bin.floorMv}")
        if (bin.clampedByMonotonicity) add("单调限位")
        if (bin.atStockCeiling) add("原厂顶上限 ${bin.ceilMv}")
        if (!bin.clampedByFloor && !bin.clampedByMonotonicity && bin.cdbOffsetMv % 4 != 0 && bin.deltaMv != bin.cdbOffsetMv) {
            add("4mV 对齐")
        }
    }
    return if (reasons.isNotEmpty()) reasons.joinToString("·") else "步进收敛"
}

@Composable
private fun ClusterDetailBlock(
    title: String,
    summary: ClusterSetSummary,
    estimate: ClusterSetEstimate?,
) {
    val semantic = LocalVoltSetColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "全局基底 ALL",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "CX ${formatSignedMv(summary.allCxMv)}  /  MX ${formatSignedMv(summary.allMxMv)}",
                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                fontWeight = FontWeight.SemiBold,
            )
        }

        for (entry in summary.entries) {
            val targetLabel = when (entry.mode) {
                AdjustMode.FREQ -> formatFreqMhz(entry.value)
                AdjustMode.PSTATE -> "P-State #${entry.value}"
                else -> "#${entry.value}"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = targetLabel,
                    style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(105.dp),
                )
                Text(
                    text = "叠加 ${formatSignedMv(entry.rawDeltaCxMv)} / ${formatSignedMv(entry.rawDeltaMxMv)}",
                    style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "合计 CX ${formatSignedMv(entry.effectiveCxMv)} · MX ${formatSignedMv(entry.effectiveMxMv)}",
                    style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                    fontWeight = FontWeight.Medium,
                    color = if (entry.effectiveCxMv < 0) semantic.goodAccent else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        val limitedBins = estimate?.bins.orEmpty().filter {
            it.deltaMv != it.cdbOffsetMv || it.atStockCeiling
        }
        if (limitedBins.isNotEmpty()) {
            Text(
                text = "本机受限频点预览 (${limitedBins.size} 档)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            for (bin in limitedBins) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatFreqMhz(bin.freqKhz),
                        style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(92.dp),
                    )
                    Text(
                        text = "${bin.baselineMv}→${bin.estimatedMv} (${formatSignedMv(bin.deltaMv)})",
                        style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        color = if (bin.deltaMv < 0) semantic.goodAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatConstraintReason(bin),
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        color = semantic.onWarnContainer,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ApplySetConfirmDialog(
    snapshot: DeviceSnapshot,
    target: VoltageSetProfile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val globalIdx = snapshot.sdamGlobalIndex ?: 1
    val rawDec = (target.setId shl 4) or globalIdx
    val targetHexByte = rawDec or 0x88
    val currentHex = snapshot.reg7151Hex?.uppercase(Locale.US)?.let { "0x$it" } ?: "--"
    val targetHex = "0x%02X".format(Locale.US, targetHexByte)
    val estimate = snapshot.setEstimates[target.setId]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "确认写入 Set ${target.setId}",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "即将向 qcom_voltage_adj 写入十进制 $rawDec（CPUSS=${target.setId}, Global=$globalIdx），PMK8850 SDAM2 寄存器 0x7151 将从 $currentHex 变为 $targetHex。",
                    style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "表定偏移（pdp_cdb）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "小核 PDP0：CX ${formatRange(target.clus0.minCxMv, target.clus0.maxCxMv)}，MX ${formatRange(target.clus0.minMxMv, target.clus0.maxMxMv)}",
                            style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        )
                        Text(
                            text = "大核 PDP1：CX ${formatRange(target.clus1.minCxMv, target.clus1.maxCxMv)}，MX ${formatRange(target.clus1.minMxMv, target.clus1.maxMxMv)}",
                            style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        )
                        if (estimate != null && (estimate.clus0 != null || estimate.clus1 != null)) {
                            val est0 = estimate.clus0?.let { formatRange(it.minDeltaMv, it.maxDeltaMv) } ?: "--"
                            val est1 = estimate.clus1?.let { formatRange(it.minDeltaMv, it.maxDeltaMv) } ?: "--"
                            Text(
                                text = "本机预计实降：小核 $est0 · 大核 $est1",
                                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                                fontWeight = FontWeight.SemiBold,
                                color = semantic.goodAccent,
                            )
                        }
                    }
                }

                if (snapshot.cdtStatus.key7Enabled || target.isAggressive) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = semantic.dangerContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (snapshot.cdtStatus.key7Enabled) {
                                Text(
                                    text = "高风险：CDT Key 7 当前处于开启状态，会与 Set ${target.setId} 叠加降压，存在不开机/崩溃风险！",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = semantic.onDangerContainer,
                                )
                            }
                            if (target.isAggressive) {
                                Text(
                                    text = "激进提示：Set ${target.setId} 最大 CX 降压达到 ${minOf(target.clus0.minCxMv, target.clus1.minCxMv)} mV，建议先从小档位逐步测试稳定性。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = semantic.onDangerContainer,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "注意：SDAM2 在正常重启与断电前都会保持，且 XBL 无死机看门狗自动回退；若新档位不稳定无法进系统，需借助已备份方式排查或长按电源硬重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("确认写入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
internal fun RebootConfirmDialog(
    snapshot: DeviceSnapshot,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val targetSet = snapshot.pendingSetIndex ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认立即重启？", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = "设备将立即重启。下次打开 VoltSet 时会核对 SDAM 是否仍为 Set $targetSet。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("立即重启")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

internal fun formatSignedMv(mv: Int): String = when {
    mv > 0 -> "+$mv mV"
    mv < 0 -> "$mv mV"
    else -> "0 mV"
}

internal fun formatRange(minMv: Int, maxMv: Int): String =
    if (minMv == maxMv) {
        formatSignedMv(minMv)
    } else {
        "$minMv ~ ${formatSignedMv(maxMv)}"
    }

internal fun formatFreqMhz(freqKhz: Long): String {
    val whole = freqKhz / 1000L
    val tenths = ((freqKhz % 1000L) / 100L)
    return if (tenths == 0L) "$whole MHz" else "$whole.$tenths MHz"
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
