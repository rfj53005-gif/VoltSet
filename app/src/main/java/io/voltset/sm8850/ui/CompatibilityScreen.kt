package io.voltset.sm8850.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.voltset.sm8850.R
import io.voltset.sm8850.VoltSetUiState
import io.voltset.sm8850.core.CheckStatus
import io.voltset.sm8850.core.CompatibilityCheckItem
import io.voltset.sm8850.core.CompatibilityChecker
import io.voltset.sm8850.core.CompatibilityLevel
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.SystemProbeInfo

@Composable
internal fun CompatibilityScreen(
    state: VoltSetUiState,
    snapshot: DeviceSnapshot,
    onRefresh: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalVoltSetColors.current
    val context = LocalContext.current
    val report = snapshot.compatibility

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "compat_verdict") {
            VerdictHeroCard(
                snapshot = snapshot,
                busy = state.busy,
                onRefresh = onRefresh,
                onCopyReport = {
                    val appVersion = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
                    }.getOrDefault("未知")
                    val text = CompatibilityChecker.formatTextReport(snapshot, appVersion)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("VoltSet Compatibility Report", text))
                    onShowMessage("已复制设备兼容性报告到剪贴板")
                },
            )
        }

        item(key = "compat_checklist") {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, semantic.subtleBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "分项兼容性检测（${report.passedCount}/${report.totalCount} 项通过）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    report.items.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = semantic.subtleBorder.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                        CheckItemRow(item = item)
                    }
                }
            }
        }

        if (snapshot.systemProbe.hasAnyData) {
            item(key = "compat_system_probe") {
                SystemProbeCard(snapshot = snapshot)
            }
        }

        item(key = "compat_guide") {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "跨机型验证指引",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "1. 先以「调压档位」页中动态解出的本机 pdp_cdb 数值为准，切勿直接套用其他机型的 Set 编号经验。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "2. 未知机型首次试水请从温和档位（如 -4 ~ -12 mV）开始，重启后核对 SDAM 索引保持且 SCMI 实测电压确实下降，再逐步尝试更低档位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "3. 避免同时开启 CDT Key 7 (SLT+ACD)，并知悉 SDAM2 无 XBL 看门狗自动回退，切勿跳过确认盲目写入激进配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun VerdictHeroCard(
    snapshot: DeviceSnapshot,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCopyReport: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val report = snapshot.compatibility
    val (bg, fg, border, iconRes) = when (report.level) {
        CompatibilityLevel.COMPATIBLE -> CompatColors(
            semantic.goodContainer,
            semantic.onGoodContainer,
            semantic.goodAccent.copy(alpha = 0.45f),
            R.drawable.ic_shield_check,
        )
        CompatibilityLevel.PARTIAL -> CompatColors(
            semantic.warnContainer,
            semantic.onWarnContainer,
            semantic.warnBorder,
            R.drawable.ic_warning,
        )
        CompatibilityLevel.UNSUPPORTED -> CompatColors(
            semantic.dangerContainer,
            semantic.onDangerContainer,
            semantic.dangerAccent.copy(alpha = 0.5f),
            R.drawable.ic_warning,
        )
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = bg,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(22.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = report.headline,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = fg,
                        )
                        Text(
                            text = report.subheadline,
                            style = MaterialTheme.typography.bodySmall,
                            color = fg,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetaBox("SoC", snapshot.socModel, Modifier.weight(1f))
                MetaBox("机型", snapshot.deviceModel, Modifier.weight(1f))
                MetaBox(
                    "内核页",
                    snapshot.pageSizeBytes?.let { "${it / 1024}K" } ?: "--",
                    Modifier.weight(0.75f),
                )
            }

            Text(
                text = "内核：${snapshot.kernelRelease}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("重新检测")
                }

                Button(
                    onClick = onCopyReport,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("复制检测报告")
                }
            }
        }
    }
}

@Composable
private fun MetaBox(
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
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CheckItemRow(item: CompatibilityCheckItem) {
    val semantic = LocalVoltSetColors.current
    val (badgeLabel, bg, fg) = when (item.status) {
        CheckStatus.PASS -> Triple("通过", semantic.goodContainer, semantic.onGoodContainer)
        CheckStatus.WARN -> Triple("注意", semantic.warnContainer, semantic.onWarnContainer)
        CheckStatus.FAIL -> Triple("不兼容", semantic.dangerContainer, semantic.onDangerContainer)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
            }
        }

        Text(
            text = item.summary,
            style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = item.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SystemProbeCard(snapshot: DeviceSnapshot) {
    val semantic = LocalVoltSetColors.current
    val probe: SystemProbeInfo = snapshot.systemProbe
    var expanded by rememberSaveable(snapshot.compatibility.level) {
        mutableStateOf(snapshot.compatibility.level == CompatibilityLevel.UNSUPPORTED)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, semantic.subtleBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "全机只读自动探测",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${probe.pdpCdtPartitions.size} 个 PDP/CDT 分区 · " +
                            "${probe.vadjModuleFiles.size} 个磁盘模块 · " +
                            "${probe.cpufreqPolicies.size} 个 CPU 簇",
                        style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = semantic.subtleBorder.copy(alpha = 0.7f))
                    ProbeInfoRow(
                        label = "qcom_voltage_adj 目录",
                        value = probe.adjDirPath ?: "未发现",
                    )
                    ProbeInfoRow(
                        label = "平台设备 / 驱动",
                        value = probe.vadjPlatformDevice?.let {
                            "$it (driver=${probe.vadjDriver ?: "unknown"}, spmi=${probe.vadjSpmiParent ?: "unknown"})"
                        } ?: "未发现",
                    )
                    ProbeInfoRow(
                        label = "设备树路径",
                        value = probe.vadjDtPath ?: "未发现",
                    )
                    ProbeInfoRow(
                        label = "内核模块",
                        value = "${if (probe.vadjModuleLoaded) "已加载" else "未加载"}" +
                            (probe.vadjModuleFiles.takeIf { it.isNotEmpty() }?.let { "\n${it.joinToString("\n")}" } ?: "\n磁盘未发现 *voltage_adj*.ko"),
                    )
                    ProbeInfoRow(
                        label = "PMIC regmap",
                        value = probe.regmapNode ?: "未命中",
                    )
                    ProbeInfoRow(
                        label = "debugfs 挂载与节点",
                        value = buildString {
                            append(probe.debugfsMounts.joinToString(", ").ifEmpty { "未挂载" })
                            if (probe.debugfsVoltNodes.isNotEmpty()) {
                                append('\n')
                                append(probe.debugfsVoltNodes.joinToString("\n"))
                            }
                        },
                    )
                    ProbeInfoRow(
                        label = "GPT 发现分区 (pdp/cdt)",
                        value = probe.pdpCdtPartitions.joinToString("\n").ifEmpty { "未发现" },
                    )
                    ProbeInfoRow(
                        label = "by-name 目录",
                        value = probe.byNameDirs.joinToString("\n").ifEmpty { "未发现" },
                    )
                    ProbeInfoRow(
                        label = "SCMI 接口",
                        value = "${probe.scmiDeviceCount} 个设备" +
                            (probe.scmiDrivers.takeIf { it.isNotEmpty() }?.let { "\n${it.joinToString(", ")}" } ?: ""),
                    )
                    ProbeInfoRow(
                        label = "CPU 调频簇",
                        value = probe.cpufreqPolicies.joinToString("\n").ifEmpty { "未读取" },
                    )
                    Text(
                        text = "提示：点击顶部「复制检测报告」可一并导出以上全部只读探测路径，便于排查跨机型适配问题。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProbeInfoRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private data class CompatColors<A, B, C, D>(
    val bg: A,
    val fg: B,
    val border: C,
    val iconRes: D,
)
