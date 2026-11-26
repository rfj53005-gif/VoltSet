package io.voltset.sm8850.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.voltset.sm8850.GpuEditorState
import io.voltset.sm8850.GpuEditorViewModel
import io.voltset.sm8850.R
import io.voltset.sm8850.VoltSetTab
import io.voltset.sm8850.VoltSetUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoltSetApp(
    state: VoltSetUiState,
    gpuState: GpuEditorState,
    gpuModel: GpuEditorViewModel,
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
    onToggleQptMonitor: () -> Unit,
    onResetQptSession: () -> Unit,
    onSavePowerRecord: () -> Unit,
    onDeletePowerRecord: (Int) -> Unit,
    onClearPowerRecords: () -> Unit,
    onShowMessage: (String) -> Unit,
    onConsumeSnackbar: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = rememberSaveableStateHolder()
    val showingGpu = state.selectedTab == VoltSetTab.GPU
    val gpuConfigInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(gpuModel::importConfig)
    }
    val gpuConfigOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(gpuModel::exportConfig)
    }
    val gpuImageOutput = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let(gpuModel::exportImage)
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onConsumeSnackbar()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                            val subtitle = if (showingGpu) "Adreno GPU 调参" else state.busyAction ?: when {
                                state.loading -> "正在检测设备状态…"
                                snap != null -> "${snap.socModel} · ${snap.deviceModel}"
                                else -> "SM8850 OEM 调压"
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
                            onClick = if (showingGpu) gpuModel::refresh else onRefresh,
                            enabled = if (showingGpu) !gpuState.busy && !gpuState.stressRunning else !state.busy,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "刷新状态",
                            )
                        }
                    },
                )
                if (if (showingGpu) gpuState.busy else state.busy) {
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
                    selected = showingGpu,
                    onClick = { onSelectTab(VoltSetTab.GPU) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_gpu),
                            contentDescription = null,
                        )
                    },
                    label = { Text("GPU") },
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
            showingGpu -> tabs.SaveableStateProvider(VoltSetTab.GPU.name) {
                GpuEditorScreen(
                    state = gpuState,
                    model = gpuModel,
                    onPickConfig = { gpuConfigInput.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "application/gzip")) },
                    onSaveConfig = { gpuConfigOutput.launch(it) },
                    onSaveImage = { gpuImageOutput.launch(it) },
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }

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
                            text = "正在读取档位和电压表…",
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
                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    state.error?.let { SnapshotErrorBanner(it, onRefresh) }
                    tabs.SaveableStateProvider(state.selectedTab.name) {
                        when (state.selectedTab) {
                            VoltSetTab.SETS -> SetsScreen(
                                state = state,
                                snapshot = snap,
                                onApplySet = onRequestApplySet,
                                onResetDefault = onRequestResetDefault,
                                onReboot = onRequestReboot,
                                onToggleExpanded = onToggleExpanded,
                                onOpenCompatTab = { onSelectTab(VoltSetTab.COMPAT) },
                            )
                            VoltSetTab.VOLTAGE -> VoltageScreen(
                                state = state,
                                snapshot = snap,
                                onSelectClusterFilter = onSelectClusterFilter,
                                onCaptureBaseline = onCaptureBaseline,
                                onToggleQptMonitor = onToggleQptMonitor,
                                onResetQptSession = onResetQptSession,
                                onSavePowerRecord = onSavePowerRecord,
                                onDeletePowerRecord = onDeletePowerRecord,
                                onClearPowerRecords = onClearPowerRecords,
                                onShowMessage = onShowMessage,
                            )
                            VoltSetTab.COMPAT -> CompatibilityScreen(
                                state = state,
                                snapshot = snap,
                                onRefresh = onRefresh,
                                onShowMessage = onShowMessage,
                            )
                            VoltSetTab.GPU -> Unit
                        }
                    }
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

@Composable
private fun SnapshotErrorBanner(error: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("显示上次读数 · 刷新后可操作", style = MaterialTheme.typography.titleSmall)
                Text(error, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
