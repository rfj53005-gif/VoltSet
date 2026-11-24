package io.voltset.sm8850.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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

private data class CompatColors<A, B, C, D>(
    val bg: A,
    val fg: B,
    val border: C,
    val iconRes: D,
)
