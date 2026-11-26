package io.voltset.sm8850

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.voltset.sm8850.ui.VoltSetApp
import io.voltset.sm8850.ui.VoltSetTheme

class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[VoltSetViewModel::class.java] }
    private val gpuModel by lazy { ViewModelProvider(this)[GpuEditorViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoltSetTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val gpuState by gpuModel.state.collectAsStateWithLifecycle()
                VoltSetApp(
                    state = state,
                    gpuState = gpuState,
                    gpuModel = gpuModel,
                    onRefresh = model::refresh,
                    onSelectTab = { tab ->
                        model.selectTab(tab)
                        if (tab == VoltSetTab.GPU) gpuModel.loadIfNeeded()
                        else gpuModel.stopStress()
                    },
                    onSelectClusterFilter = model::selectClusterFilter,
                    onToggleExpanded = model::toggleSetExpanded,
                    onRequestApplySet = model::requestApplySet,
                    onRequestResetDefault = model::requestResetToDefault,
                    onConfirmApplySet = model::confirmApplySet,
                    onDismissApplyConfirm = model::dismissApplyConfirm,
                    onRequestReboot = model::requestReboot,
                    onConfirmReboot = model::confirmReboot,
                    onDismissRebootConfirm = model::dismissRebootConfirm,
                    onCaptureBaseline = model::captureStockBaseline,
                    onToggleQptMonitor = model::toggleQptMonitor,
                    onResetQptSession = model::resetQptSession,
                    onSavePowerRecord = model::saveCurrentSetPowerRecord,
                    onDeletePowerRecord = model::deletePowerRecord,
                    onClearPowerRecords = model::clearPowerRecords,
                    onShowMessage = model::showMessage,
                    onConsumeSnackbar = model::consumeSnackbar,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onStop() {
        model.stopQptMonitor()
        gpuModel.stopStress()
        super.onStop()
    }
}
