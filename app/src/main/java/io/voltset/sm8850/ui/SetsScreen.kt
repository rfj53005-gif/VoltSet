package io.voltset.sm8850.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ButtonDefaults
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
import io.voltset.sm8850.core.CdtState
import io.voltset.sm8850.core.CdtStatus
import io.voltset.sm8850.core.ClusterSetSummary
import io.voltset.sm8850.core.CompatibilityLevel
import io.voltset.sm8850.core.CompatibilityReport
import io.voltset.sm8850.core.DeviceSnapshot
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
    val canApplyUndervoltSet = snapshot.canWriteOverrides &&
        !snapshot.overrideLockedByFirmware &&
        snapshot.compatibility.level != CompatibilityLevel.UNSUPPORTED

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") {
            StatusHeroCard(
                snapshot = snapshot,
                busy = state.busy,
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
                        text = "pdp_cdb 预设档位 (${sets.size} 组)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "动态解析自设备分区",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(sets, key = { it.setId }) { profile ->
                val allowApplyThisSet = if (profile.setId == 1) {
                    snapshot.canWriteOverrides
                } else {
                    canApplyUndervoltSet
                }
                VoltageSetCard(
                    profile = profile,
                    isBootActive = snapshot.bootCpussIndex == profile.setId,
                    isSdamTarget = snapshot.sdamCpussIndex == profile.setId,
                    pendingReboot = snapshot.pendingReboot,
                    adjAvailable = allowApplyThisSet,
                    busy = state.busy,
                    expanded = profile.setId in state.expandedSetIds,
                    onToggleExpand = { onToggleExpanded(profile.setId) },
                    onApply = { onApplySet(profile) },
                )
            }
        }

        item(key = "footer_note") {
            Text(
                text = "说明：qcom_voltage_adj 将档位写入 PMK8850 SDAM2 (0x7151)，重启后由 XBL/PDP 固件读取并下发电压偏移；本应用不会写入任何开机自启脚本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
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
    busy: Boolean,
    onResetDefault: () -> Unit,
    onReboot: () -> Unit,
    onRevertBootSet: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val bootIdx = snapshot.bootCpussIndex
    val sdamIdx = snapshot.sdamCpussIndex
    val hexText = snapshot.reg7151Hex?.uppercase(Locale.US)?.let { "0x$it" } ?: "--"

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (snapshot.pendingReboot) "当前运行 Set ${bootIdx ?: "?"} → 待重启 Set ${sdamIdx ?: "?"}"
                        else "当前 OEM 调压状态",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when {
                            sdamIdx != null -> if (sdamIdx == 1 && !snapshot.pendingReboot) {
                                "Set 1（原厂默认）"
                            } else {
                                "Set ${bootIdx ?: sdamIdx}"
                            }
                            else -> "不可用"
                        },
                        style = MaterialTheme.typography.headlineMedium.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                    )
                }

                CdtBadgeChip(cdtStatus = snapshot.cdtStatus)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelemetryPill(
                    label = "0x7151",
                    value = hexText,
                    modifier = Modifier.weight(1f),
                )
                TelemetryPill(
                    label = "CPUSS",
                    value = sdamIdx?.toString() ?: "--",
                    modifier = Modifier.weight(1f),
                )
                TelemetryPill(
                    label = "Global",
                    value = snapshot.sdamGlobalIndex?.toString() ?: "--",
                    modifier = Modifier.weight(1f),
                )
                TelemetryPill(
                    label = "槽位",
                    value = snapshot.slotSuffix.ifBlank { "none" },
                    modifier = Modifier.weight(1f),
                )
            }

            snapshot.activeBootSet?.let { active ->
                Text(
                    text = "生效偏移：PDP0 CX ${formatRange(active.clus0.minCxMv, active.clus0.maxCxMv)} · PDP1 CX ${formatRange(active.clus1.minCxMv, active.clus1.maxCxMv)}",
                    style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (snapshot.pendingReboot && sdamIdx != null && bootIdx != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = semantic.warnContainer,
                    border = BorderStroke(1.dp, semantic.warnBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "SDAM 已写入 Set $sdamIdx，重启后生效",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = semantic.onWarnContainer,
                        )
                        Text(
                            text = "当前开机仍运行在 Set $bootIdx。重启设备后 XBL/PDP 将读取 0x7151=$hexText 应用新配置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.onWarnContainer,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onReboot,
                                enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_restart),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("立即重启")
                            }
                            if (snapshot.activeBootSet != null) {
                                OutlinedButton(
                                    onClick = onRevertBootSet,
                                    enabled = !busy && snapshot.canWriteOverrides,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text("撤回至 Set $bootIdx")
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onResetDefault,
                    enabled = !busy && snapshot.canWriteOverrides && sdamIdx != 1,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restore),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (sdamIdx == 1) "已是 Set 1" else "还原默认 Set 1")
                }

                Button(
                    onClick = onReboot,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (snapshot.pendingReboot) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_restart),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("重启生效")
                }
            }
        }
    }
}

@Composable
private fun TelemetryPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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
    isBootActive: Boolean,
    isSdamTarget: Boolean,
    pendingReboot: Boolean,
    adjAvailable: Boolean,
    busy: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onApply: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val highlighted = isBootActive || isSdamTarget
    val borderColor = when {
        isSdamTarget && pendingReboot -> semantic.warnBorder
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Set ${profile.setId}",
                        style = MaterialTheme.typography.titleLarge.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                    )
                    SetTagChip(profile = profile)
                    if (profile.isAggressive) {
                        StatusMiniChip(
                            text = "激进",
                            container = semantic.dangerContainer,
                            content = semantic.onDangerContainer,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isBootActive) {
                        StatusMiniChip(
                            text = if (pendingReboot) "当前运行" else "当前生效",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (isSdamTarget && pendingReboot) {
                        StatusMiniChip(
                            text = "待重启生效",
                            container = semantic.warnContainer,
                            content = semantic.onWarnContainer,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClusterSummaryBox(
                    title = "小核 PDP0 (CPU0–5)",
                    summary = profile.clus0,
                    modifier = Modifier.weight(1f),
                )
                ClusterSummaryBox(
                    title = "大核 PDP1 (CPU6–7)",
                    summary = profile.clus1,
                    modifier = Modifier.weight(1f),
                )
            }

            val hasDetails = profile.clus0.entries.isNotEmpty() || profile.clus1.entries.isNotEmpty() ||
                profile.clus0.allCxMv != 0 || profile.clus1.allCxMv != 0 ||
                profile.clus0.allMxMv != 0 || profile.clus1.allMxMv != 0

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
                    Text(
                        text = if (totalPoints > 0) "查看 pdp_cdb 分频明细 ($totalPoints 条频点规则)" else "查看 pdp_cdb 规则明细",
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
                        ClusterDetailBlock("小核 PDP0 明细", profile.clus0)
                        ClusterDetailBlock("大核 PDP1 明细", profile.clus1)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                val isCurrentSdam = isSdamTarget
                Button(
                    onClick = onApply,
                    enabled = adjAvailable && !busy && !isCurrentSdam,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (isCurrentSdam) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_circle),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("已写入 SDAM")
                    } else {
                        Text("应用 Set ${profile.setId}")
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
            Text(
                text = "CX  ${formatRange(summary.minCxMv, summary.maxCxMv)}",
                style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                fontWeight = FontWeight.SemiBold,
                color = if (summary.minCxMv < 0) semantic.goodAccent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "MX  ${formatRange(summary.minMxMv, summary.maxMxMv)}",
                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClusterDetailBlock(
    title: String,
    summary: ClusterSetSummary,
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
                            text = "小核 PDP0：CX ${formatRange(target.clus0.minCxMv, target.clus0.maxCxMv)}，MX ${formatRange(target.clus0.minMxMv, target.clus0.maxMxMv)}",
                            style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        )
                        Text(
                            text = "大核 PDP1：CX ${formatRange(target.clus1.minCxMv, target.clus1.maxCxMv)}，MX ${formatRange(target.clus1.minMxMv, target.clus1.maxMxMv)}",
                            style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        )
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
    val targetSet = snapshot.sdamCpussIndex
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认立即重启？", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                text = if (snapshot.pendingReboot && targetSet != null) {
                    "设备将立即重启，下次开机时 XBL/PDP 会从 SDAM 加载 Set $targetSet。"
                } else {
                    "设备将立即重启。当前 SDAM 设定为 Set ${targetSet ?: "?"}。"
                },
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
