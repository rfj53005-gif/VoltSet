package io.voltset.sm8850.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.voltset.sm8850.R
import io.voltset.sm8850.VoltSetUiState
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.ClusterVoltageStats
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.ScmiPlanParser
import io.voltset.sm8850.core.VoltageDiffRow

@Composable
internal fun VoltageScreen(
    state: VoltSetUiState,
    snapshot: DeviceSnapshot,
    onSelectClusterFilter: (Int?) -> Unit,
    onCaptureBaseline: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "scmi_summary") {
            ScmiSummaryCard(
                snapshot = snapshot,
                pdp0 = pdp0Stats,
                pdp1 = pdp1Stats,
                busy = state.busy,
                onCaptureBaseline = onCaptureBaseline,
            )
        }

        if (snapshot.scmiError != null) {
            item(key = "scmi_error") {
                ScmiErrorCard(
                    error = snapshot.scmiError,
                    kernel = snapshot.kernelRelease,
                )
            }
        } else {
            item(key = "filters") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
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
                item(key = "table_container") {
                    VoltageTableCard(
                        rows = filteredRows,
                        showClusterDivider = state.selectedClusterFilter == null,
                        maxMv = maxMv,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScmiSummaryCard(
    snapshot: DeviceSnapshot,
    pdp0: ClusterVoltageStats,
    pdp1: ClusterVoltageStats,
    busy: Boolean,
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
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "SCMI PERF 实时电压计划",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (snapshot.bootCpussIndex != null) {
                            "当前开机运行：Set ${snapshot.bootCpussIndex}"
                        } else {
                            "qcom-scmi-ro 即读即卸"
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

            if (snapshot.canSaveStockBaseline && snapshot.baselineSource != BaselineSource.VOLTSET) {
                OutlinedButton(
                    onClick = onCaptureBaseline,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("在 Set 1 采集并保存为 VoltSet 原厂基线")
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
private fun VoltageTableCard(
    rows: List<VoltageDiffRow>,
    showClusterDivider: Boolean,
    maxMv: Int,
) {
    val semantic = LocalVoltSetColors.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, semantic.subtleBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "频率",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.25f),
                )
                Text(
                    text = "原厂",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.8f),
                )
                Text(
                    text = "当前",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.85f),
                )
                Text(
                    text = "实测变化",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.0f),
                )
            }

            var lastDomain = -1
            rows.forEachIndexed { idx, row ->
                if (showClusterDivider && row.domain != lastDomain) {
                    lastDomain = row.domain
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (row.domain == 0) "小核集群 · PDP0 (CPU0–5)" else "大核集群 · PDP1 (CPU6–7)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (idx > 0) {
                    HorizontalDivider(
                        color = semantic.subtleBorder.copy(alpha = 0.65f),
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }

                VoltageRowItem(row = row, maxMv = maxMv)
            }
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
