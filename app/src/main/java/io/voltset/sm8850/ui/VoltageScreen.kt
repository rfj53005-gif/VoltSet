package io.voltset.sm8850.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.voltset.sm8850.R
import io.voltset.sm8850.VoltSetUiState
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.ClusterVoltageStats
import io.voltset.sm8850.core.CprhDriftAssessment
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.SavedSetPowerRecord
import io.voltset.sm8850.core.ScmiDataSource
import io.voltset.sm8850.core.ScmiPlanParser
import io.voltset.sm8850.core.VoltageDiffRow
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun VoltageScreen(
    state: VoltSetUiState,
    snapshot: DeviceSnapshot,
    onSelectClusterFilter: (Int?) -> Unit,
    onCaptureBaseline: () -> Unit,
    onToggleQptMonitor: () -> Unit,
    onResetQptSession: () -> Unit,
    onSavePowerRecord: () -> Unit,
    onDeletePowerRecord: (Int) -> Unit,
    onClearPowerRecords: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = snapshot.voltageDiffRows
    val filteredRows = remember(rows, state.selectedClusterFilter) {
        when (state.selectedClusterFilter) {
            null -> rows
            else -> rows.filter { it.domain == state.selectedClusterFilter }
        }
    }
    val pdp0Stats = remember(rows) { ScmiPlanParser.summarizeByCluster(rows, 0) }
    val pdp1Stats = remember(rows) { ScmiPlanParser.summarizeByCluster(rows, 1) }
    val maxMv = remember(rows) {
        maxOf(
            rows.maxOfOrNull { maxOf(it.currentMv, it.baselineMv ?: 0) } ?: 1100,
            1,
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (snapshot.isSm8850) {
            item(key = "quality") {
                QualityCard(snapshot, onShowMessage, Modifier.padding(bottom = 12.dp))
            }
        }
        item(key = "scmi_summary") {
            ScmiSummaryCard(
                snapshot = snapshot,
                pdp0 = pdp0Stats,
                pdp1 = pdp1Stats,
                controlsEnabled = state.controlsEnabled,
                onCaptureBaseline = onCaptureBaseline,
            )
        }

        if (snapshot.systemProbe.qptRails.isNotEmpty() || state.savedPowerRecords.isNotEmpty()) {
            item(key = "qpt_power") {
                Box(Modifier.padding(top = 12.dp)) {
                    QptPowerCard(
                        state = state,
                        bootSetId = snapshot.bootCpussIndex,
                        onToggleMonitor = onToggleQptMonitor,
                        onResetSession = onResetQptSession,
                        onSaveRecord = onSavePowerRecord,
                        onDeleteRecord = onDeletePowerRecord,
                        onClearRecords = onClearPowerRecords,
                    )
                }
            }
        }

        if (snapshot.scmiError != null) {
            item(key = "scmi_error") {
                Box(Modifier.padding(top = 12.dp)) {
                    ScmiErrorCard(error = snapshot.scmiError, kernel = snapshot.kernelRelease)
                }
            }
        } else {
            item(key = "filters") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FilterChip(
                        selected = state.selectedClusterFilter == null,
                        onClick = { onSelectClusterFilter(null) },
                        label = { Text("全部 (${rows.size})") },
                    )
                    FilterChip(
                        selected = state.selectedClusterFilter == 0,
                        onClick = { onSelectClusterFilter(0) },
                        label = { Text("小核 PDP0 (${pdp0Stats.totalBins})") },
                    )
                    FilterChip(
                        selected = state.selectedClusterFilter == 1,
                        onClick = { onSelectClusterFilter(1) },
                        label = { Text("大核 PDP1 (${pdp1Stats.totalBins})") },
                    )
                }
            }

            if (filteredRows.isNotEmpty()) {
                item(key = "table_header") { VoltageTableHeader() }
                itemsIndexed(filteredRows, key = { _, row -> "${row.domain}/${row.freqKhz}" }) { index, row ->
                    val newCluster = state.selectedClusterFilter == null &&
                        (index == 0 || filteredRows[index - 1].domain != row.domain)
                    VoltageTableEntry(row, maxMv, newCluster, index == filteredRows.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun QualityCard(
    snapshot: DeviceSnapshot,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val report = snapshot.qualityReport
    val sourceLabel = when (snapshot.scmiSource) {
        ScmiDataSource.ENERGY_MODEL -> "debugfs/energy_model 零模块"
        ScmiDataSource.KERNEL_MODULE -> "qcom-scmi-ro.ko"
        ScmiDataSource.NONE -> "SCMI"
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "体质电压表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (report != null) {
                    TextButton(onClick = {
                        val text = buildString {
                            appendLine("VoltSet 体质电压表 · ${snapshot.deviceModel}")
                            appendLine("SCMI 计划电压 · 综合参考分 ${report.score}")
                            appendLine("读表来源：$sourceLabel")
                            snapshot.bootCpussIndex?.let { appendLine("开机档位 Set $it（${if (snapshot.bootSet.firmwareIndex == null) "推定" else "固件锁定"}）") }
                            for (cluster in report.clusters) {
                                appendLine()
                                appendLine("PDP${cluster.domain} · ${cluster.score} 分 · C=${cluster.coefficient}")
                                for (point in report.points.filter { it.domain == cluster.domain }) {
                                    appendLine("${formatFreqMhz(point.freqKhz)}  ${point.voltageMv} mV  ${point.powerUw} µW")
                                }
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VoltSet 体质电压表", text))
                        onShowMessage("已复制体质电压表")
                    }) {
                        Text("复制结果")
                    }
                }
            }
            if (report == null) {
                Text(
                    if (snapshot.scmiError != null) "读取 SCMI 计划电压后可查看体质参考分。"
                    else "两次读表和电压换算需通过校验才显示分数，可刷新重试。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column {
                        Text("综合参考分", style = MaterialTheme.typography.labelMedium)
                        Text(
                            report.score.toString(),
                            style = MaterialTheme.typography.headlineMedium.merge(TabularStyle),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    for (cluster in report.clusters) {
                        Column {
                            Text("PDP${cluster.domain} · C=${cluster.coefficient}", style = MaterialTheme.typography.labelMedium)
                            Text(
                                cluster.score.toString(),
                                style = MaterialTheme.typography.headlineMedium.merge(TabularStyle),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Text(
                    "两次读取一致 · ${report.points.size} 个频点 · $sourceLabel。分数随计划电压变化，仅供参考。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ScmiSummaryCard(
    snapshot: DeviceSnapshot,
    pdp0: ClusterVoltageStats,
    pdp1: ClusterVoltageStats,
    controlsEnabled: Boolean,
    onCaptureBaseline: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val (sourceText, bg, fg) = when (snapshot.baselineSource) {
        BaselineSource.VOLTSET -> Triple("VoltSet 原厂基线", semantic.goodContainer, semantic.onGoodContainer)
        BaselineSource.SUNTUNER -> Triple("已复用 SunTuner 原厂基线", semantic.goodContainer, semantic.onGoodContainer)
        BaselineSource.NONE -> Triple("暂无原厂基线", semantic.warnContainer, semantic.onWarnContainer)
    }

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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SCMI 计划电压对比",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val sourceSuffix = when (snapshot.scmiSource) {
                        ScmiDataSource.ENERGY_MODEL -> " · energy_model 零模块"
                        ScmiDataSource.KERNEL_MODULE -> " · 内核模块"
                        ScmiDataSource.NONE -> ""
                    }
                    Text(
                        text = when {
                            snapshot.bootSet.firmwareIndex != null -> "固件锁定 Set ${snapshot.bootSet.firmwareIndex}$sourceSuffix"
                            snapshot.bootCpussIndex != null -> "本次开机推定 Set ${snapshot.bootCpussIndex}$sourceSuffix"
                            else -> "本次开机档待确认$sourceSuffix"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = sourceText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ClusterStatsBox(
                    title = "小核 PDP0 (CPU0–5)",
                    stats = pdp0,
                    modifier = Modifier.weight(1f),
                )
                ClusterStatsBox(
                    title = "大核 PDP1 (CPU6–7)",
                    stats = pdp1,
                    modifier = Modifier.weight(1f),
                )
            }

            val cprhDrift = remember(snapshot.bootCpussIndex, snapshot.voltageDiffRows) {
                CprhDriftAssessment.assess(snapshot.bootCpussIndex, snapshot.voltageDiffRows)
            }
            if (cprhDrift.isLikelyCprhDrift) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Set 1 检测到 ${cprhDrift.affectedBins} 个频点出现 ${formatRange(cprhDrift.minDeltaMv, cprhDrift.maxDeltaMv)} 偏移：" +
                            "这是每次冷启 PDP0 CPRH 老化传感器重测并在 4 mV 步进取整引起的正常校准波动，可点击下方按钮以本次冷启刷新原厂基线。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (snapshot.canSaveStockBaseline) {
                OutlinedButton(
                    onClick = onCaptureBaseline,
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (snapshot.baselineSource == BaselineSource.VOLTSET) {
                            "刷新 Set 1 原厂基线（以本次冷启为准）"
                        } else {
                            "在 Set 1 采集并保存为 VoltSet 原厂基线"
                        },
                    )
                }
            } else if (snapshot.baselineSource == BaselineSource.NONE) {
                Text(
                    text = "提示：切换回 Set 1 并重启后刷新，应用会自动保存原厂 SCMI 基线用于对比实际降压幅度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ClusterStatsBox(
    title: String,
    stats: ClusterVoltageStats,
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
            val rangeStr = if (stats.minDeltaMv != null && stats.maxDeltaMv != null) {
                formatRange(stats.minDeltaMv, stats.maxDeltaMv)
            } else {
                "共 ${stats.totalBins} 档"
            }
            Text(
                text = rangeStr,
                style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                fontWeight = FontWeight.SemiBold,
                color = if ((stats.minDeltaMv ?: 0) < 0) semantic.goodAccent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (stats.loweredBins > 0 || stats.unchangedBins > 0 || stats.raisedBins > 0) {
                    "降压 ${stats.loweredBins}/${stats.totalBins} 档"
                } else {
                    "等待基线对比"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScmiErrorCard(
    error: String,
    kernel: String,
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
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = semantic.onWarnContainer,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "未能读取 SCMI 电压表",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = semantic.onWarnContainer,
                )
                Text(
                    text = "$error\n内核：$kernel",
                    style = MaterialTheme.typography.bodySmall,
                    color = semantic.onWarnContainer,
                )
            }
        }
    }
}

@Composable
private fun VoltageTableHeader() {
    Surface(
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("频率", Modifier.weight(1.25f), style = MaterialTheme.typography.labelMedium)
            Text("原厂", Modifier.weight(0.8f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
            Text("当前", Modifier.weight(0.85f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
            Text("基线差值", Modifier.weight(1f), textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VoltageTableEntry(row: VoltageDiffRow, maxMv: Int, newCluster: Boolean, last: Boolean) {
    Surface(
        shape = RoundedCornerShape(bottomStart = if (last) 14.dp else 0.dp, bottomEnd = if (last) 14.dp else 0.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            if (newCluster) {
                Text(
                    text = if (row.domain == 0) "PDP0 · CPU0–5" else "PDP1 · CPU6–7",
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider(color = LocalVoltSetColors.current.subtleBorder, modifier = Modifier.padding(horizontal = 14.dp))
            }
            VoltageRowItem(row = row, maxMv = maxMv)
        }
    }
}

@Composable
private fun VoltageRowItem(
    row: VoltageDiffRow,
    maxMv: Int,
) {
    val semantic = LocalVoltSetColors.current
    val delta = row.deltaMv
    val (pillBg, pillFg, deltaText) = when {
        delta == null -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            row.expectedCdbCxMv?.let { "目标 ${formatSignedMv(it)}" } ?: "--",
        )
        delta < 0 -> Triple(semantic.goodContainer, semantic.onGoodContainer, "${delta} mV")
        delta > 0 -> Triple(semantic.warnContainer, semantic.onWarnContainer, "+$delta mV")
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "0 mV")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatFreqMhz(row.freqKhz),
                style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1.25f),
            )
            Text(
                text = row.baselineMv?.let { "$it" } ?: "--",
                style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.8f),
            )
            Text(
                text = "${row.currentMv} mV",
                style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.85f),
            )
            Box(
                modifier = Modifier.weight(1.0f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(pillBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = deltaText,
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = pillFg,
                    )
                }
            }
        }

        val currentFraction = (row.currentMv.toFloat() / maxMv.toFloat()).coerceIn(0.05f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(semantic.trackBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(currentFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            delta != null && delta < 0 -> semantic.goodAccent
                            delta != null && delta > 0 -> semantic.warnBorder
                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun QptPowerCard(
    state: VoltSetUiState,
    bootSetId: Int?,
    onToggleMonitor: () -> Unit,
    onResetSession: () -> Unit,
    onSaveRecord: () -> Unit,
    onDeleteRecord: (Int) -> Unit,
    onClearRecords: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val instant = state.qptInstant
    val session = state.qptSession
    val records = remember(state.savedPowerRecords) {
        state.savedPowerRecords.values.sortedBy { it.setId }
    }
    val baseRecord = state.savedPowerRecords[1]

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (state.qptMonitoring) 1.5.dp else 1.dp,
            color = if (state.qptMonitoring) MaterialTheme.colorScheme.primary else semantic.subtleBorder,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "硬件实时功耗 (QPT)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val subtitle = when {
                        session != null -> "已采样 ${session.sampleCount} 次 · ${session.elapsedSec.roundToInt()}s" +
                            (bootSetId?.let { " · Set $it" } ?: "")
                        state.qptMonitoring -> "正在等待首次双点采样…"
                        else -> "每秒读取硬件能量计数器 · 仅监测时运行"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (state.qptMonitoring) {
                    OutlinedButton(onClick = onToggleMonitor) {
                        Text("停止监测")
                    }
                } else {
                    Button(
                        onClick = onToggleMonitor,
                        enabled = state.snapshot?.systemProbe?.qptRails?.isNotEmpty() == true,
                    ) {
                        Text("开始监测")
                    }
                }
            }

            state.qptError?.let { err ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = semantic.warnContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = semantic.onWarnContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            if (instant != null || state.qptMonitoring) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QptRailBox(
                        title = "中核 cpu-m",
                        powerMw = instant?.cpuMidMw,
                        freqText = instant?.cpu0FreqKhz?.takeIf { it > 0 }?.let { "${it / 1000} MHz" },
                        modifier = Modifier.weight(1f),
                    )
                    QptRailBox(
                        title = "大核 cpu-l",
                        powerMw = instant?.cpuLargeMw,
                        freqText = instant?.cpu1FreqKhz?.takeIf { it > 0 }?.let { "${it / 1000} MHz" },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QptRailBox(
                        title = "GPU",
                        powerMw = instant?.gpuMw,
                        freqText = instant?.gpuFreqHz?.takeIf { it > 0L }?.let { "${it / 1_000_000L} MHz" },
                        modifier = Modifier.weight(1f),
                    )
                    QptRailBox(
                        title = "CPU 合计",
                        powerMw = instant?.cpuTotalMw,
                        freqText = instant?.nspMw?.takeIf { it >= 1.0 }?.let { "NSP ${it.roundToInt()} mW" },
                        highlighted = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (session != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "本轮均值：CPU 合计 ${formatMw(session.avgCpuTotalMw)}" +
                                "（中核 ${formatMw(session.avgCpuMidMw)} / 大核 ${formatMw(session.avgCpuLargeMw)}）" +
                                " · GPU ${formatMw(session.avgGpuMw)}",
                            style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                            fontWeight = FontWeight.Medium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onResetSession) {
                                Text("重置统计")
                            }
                            OutlinedButton(
                                onClick = onSaveRecord,
                                enabled = bootSetId != null && session.sampleCount >= 3,
                            ) {
                                Text(bootSetId?.let { "记录为 Set $it 均功耗" } ?: "档位待确认")
                            }
                        }
                    }
                }
            }

            if (records.isNotEmpty()) {
                HorizontalDivider(color = semantic.subtleBorder.copy(alpha = 0.7f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "各档位实测均功耗对比",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TextButton(onClick = onClearRecords) {
                        Text("清空")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (record in records) {
                        SavedPowerRecordRow(
                            record = record,
                            baselineRecord = if (record.setId != 1) baseRecord else null,
                            onDelete = { onDeleteRecord(record.setId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QptRailBox(
    title: String,
    powerMw: Double?,
    freqText: String?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = formatMw(powerMw),
                style = MaterialTheme.typography.titleMedium.merge(TabularStyle),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = freqText ?: "--",
                style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SavedPowerRecordRow(
    record: SavedSetPowerRecord,
    baselineRecord: SavedSetPowerRecord?,
    onDelete: () -> Unit,
) {
    val semantic = LocalVoltSetColors.current
    val deltaMw = baselineRecord?.let { record.avgCpuTotalMw - it.avgCpuTotalMw }
    val deltaPct = if (baselineRecord != null && baselineRecord.avgCpuTotalMw > 0 && deltaMw != null) {
        deltaMw * 100.0 / baselineRecord.avgCpuTotalMw.toDouble()
    } else {
        null
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Set ${record.setId} · CPU ${record.avgCpuTotalMw} mW (${record.elapsedSec}s)",
                    style = MaterialTheme.typography.bodySmall.merge(TabularStyle),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "中核 ${record.avgCpuMidMw} · 大核 ${record.avgCpuLargeMw} · GPU ${record.avgGpuMw} mW",
                    style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (deltaMw != null && deltaPct != null) {
                val (bg, fg) = when {
                    deltaMw < 0 -> semantic.goodContainer to semantic.onGoodContainer
                    deltaMw > 0 -> semantic.warnContainer to semantic.onWarnContainer
                    else -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant
                }
                val sign = if (deltaMw > 0) "+" else ""
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = String.format(Locale.US, "%s%d mW (%s%.1f%%)", sign, deltaMw, sign, deltaPct),
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                    )
                }
            }
            TextButton(onClick = onDelete) {
                Text("删除")
            }
        }
    }
}

private fun formatMw(value: Double?): String =
    value?.let { "${it.roundToInt()} mW" } ?: "-- mW"
