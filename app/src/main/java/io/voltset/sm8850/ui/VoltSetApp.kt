package io.voltset.sm8850.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.voltset.sm8850.R
import io.voltset.sm8850.VoltSetTab
import io.voltset.sm8850.VoltSetUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoltSetApp(
    state: VoltSetUiState,
    onRefresh: () -> Unit,
    onSelectTab: (VoltSetTab) -> Unit,
    onSelectClusterFilter: (Int?) -> Unit,
    onToggleExpanded: (Int) -> Unit,
    onRequestApplySet: (io.voltset.sm8850.core.VoltageSetProfile) -> Unit,
    onRequestResetDefault: () -> Unit,
    onConfirmApplySet: () -> Unit,
    onDismissApplyConfirm: () -> Unit,
    onRequestReboot: () -> Unit,
    onConfirmReboot: () -> Unit,
    onDismissRebootConfirm: () -> Unit,
    onCaptureBaseline: () -> Unit,
    onShowMessage: (String) -> Unit,
    onConsumeSnackbar: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onConsumeSnackbar()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "VoltSet",
                                fontWeight = FontWeight.Bold,
                            )
                            val snap = state.snapshot
                            val subtitle = if (snap != null) {
                                "${snap.socModel} · ${snap.deviceModel} · ${snap.deviceName}"
                            } else {
                                "SM8850 OEM 调压控制台"
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !state.busy,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "刷新状态",
                            )
                        }
                    },
                )
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.selectedTab == VoltSetTab.SETS,
                    onClick = { onSelectTab(VoltSetTab.SETS) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_tune),
                            contentDescription = null,
                        )
                    },
                    label = { Text("调压档位") },
                )
                NavigationBarItem(
                    selected = state.selectedTab == VoltSetTab.VOLTAGE,
                    onClick = { onSelectTab(VoltSetTab.VOLTAGE) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_bolt),
                            contentDescription = null,
                        )
                    },
                    label = { Text("SCMI 电压") },
                )
                NavigationBarItem(
                    selected = state.selectedTab == VoltSetTab.COMPAT,
                    onClick = { onSelectTab(VoltSetTab.COMPAT) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield_check),
                            contentDescription = null,
                        )
                    },
                    label = { Text("设备检测") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val snap = state.snapshot
        when {
            snap == null && state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在读取 PMK8850 SDAM、pdp_cdb 与 SCMI 状态...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            snap == null && state.error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "读取失败",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(4.dp))
                        Button(onClick = onRefresh) {
                            Text("重试")
                        }
                    }
                }
            }

            snap != null -> {
                when (state.selectedTab) {
                    VoltSetTab.SETS -> SetsScreen(
                        state = state,
                        snapshot = snap,
                        onApplySet = onRequestApplySet,
                        onResetDefault = onRequestResetDefault,
                        onReboot = onRequestReboot,
                        onToggleExpanded = onToggleExpanded,
                        onOpenCompatTab = { onSelectTab(VoltSetTab.COMPAT) },
                        modifier = Modifier.padding(innerPadding),
                    )
                    VoltSetTab.VOLTAGE -> VoltageScreen(
                        state = state,
                        snapshot = snap,
                        onSelectClusterFilter = onSelectClusterFilter,
                        onCaptureBaseline = onCaptureBaseline,
                        modifier = Modifier.padding(innerPadding),
                    )
                    VoltSetTab.COMPAT -> CompatibilityScreen(
                        state = state,
                        snapshot = snap,
                        onRefresh = onRefresh,
                        onShowMessage = onShowMessage,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    val snap = state.snapshot
    val pendingSet = state.pendingConfirmSet
    if (snap != null && pendingSet != null) {
        ApplySetConfirmDialog(
            snapshot = snap,
            target = pendingSet,
            onConfirm = onConfirmApplySet,
            onDismiss = onDismissApplyConfirm,
        )
    }

    if (snap != null && state.showRebootConfirm) {
        RebootConfirmDialog(
            snapshot = snap,
            onConfirm = onConfirmReboot,
            onDismiss = onDismissRebootConfirm,
        )
    }
}
