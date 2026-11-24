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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoltSetTheme {
                val state by model.state.collectAsStateWithLifecycle()
                VoltSetApp(
                    state = state,
                    onRefresh = model::refresh,
                    onSelectTab = model::selectTab,
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
}
