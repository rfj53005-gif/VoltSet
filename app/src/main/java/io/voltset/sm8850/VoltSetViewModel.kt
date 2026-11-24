package io.voltset.sm8850

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.ClusterSetSummary
import io.voltset.sm8850.core.CompatibilityChecker
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.ScmiPlanParser
import io.voltset.sm8850.core.VoltageSetProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoltSetTab {
    SETS,
    VOLTAGE,
    COMPAT,
}

internal data class VoltSetUiState(
    val loading: Boolean = true,
    val busyAction: String? = null,
    val error: String? = null,
    val snapshot: DeviceSnapshot? = null,
    val snackbarMessage: String? = null,
    val selectedTab: VoltSetTab = VoltSetTab.SETS,
    val selectedClusterFilter: Int? = null,
    val expandedSetIds: Set<Int> = emptySet(),
    val pendingConfirmSet: VoltageSetProfile? = null,
    val showRebootConfirm: Boolean = false,
) {
    val busy: Boolean get() = loading || busyAction != null
}

internal class VoltSetViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = RootBackend(application.applicationContext)
    private val _state = MutableStateFlow(VoltSetUiState())
    val state: StateFlow<VoltSetUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.busyAction != null) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val initial = backend.readStatus()
                maybeAutoSaveBaseline(initial)
            }.onSuccess { snap ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        snapshot = snap,
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = err.message ?: "读取设备状态失败",
                    )
                }
            }
        }
    }

    private suspend fun maybeAutoSaveBaseline(snap: DeviceSnapshot): DeviceSnapshot {
        if (!snap.canSaveStockBaseline || snap.baselineSource == BaselineSource.VOLTSET) {
            return snap
        }
        return runCatching {
            val savedCsv = backend.saveBaseline(snap.bootId)
            val parsedBaseline = ScmiPlanParser.parseCsv(savedCsv, snap.isSm8850)
            if (parsedBaseline.isEmpty()) return snap
            snap.copy(
                baselineSource = BaselineSource.VOLTSET,
                baselinePoints = parsedBaseline,
                voltageDiffRows = ScmiPlanParser.diff(
                    snap.scmiPoints,
                    parsedBaseline,
                    snap.activeBootSet,
                ),
            )
        }.getOrDefault(snap)
    }

    fun selectTab(tab: VoltSetTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun selectClusterFilter(cluster: Int?) {
        _state.update { it.copy(selectedClusterFilter = cluster) }
    }

    fun toggleSetExpanded(setId: Int) {
        _state.update { current ->
            val next = if (setId in current.expandedSetIds) {
                current.expandedSetIds - setId
            } else {
                current.expandedSetIds + setId
            }
            current.copy(expandedSetIds = next)
        }
    }

    fun requestApplySet(profile: VoltageSetProfile) {
        if (_state.value.busy) return
        _state.update { it.copy(pendingConfirmSet = profile) }
    }

    fun requestResetToDefault() {
        val snap = _state.value.snapshot ?: return
        val set1 = snap.pdpCdb?.sets?.firstOrNull { it.setId == 1 } ?: VoltageSetProfile(
            setId = 1,
            clus0 = ClusterSetSummary(0, 0, 0, emptyList(), 0, 0, 0, 0),
            clus1 = ClusterSetSummary(1, 0, 0, emptyList(), 0, 0, 0, 0),
        )
        requestApplySet(set1)
    }

    fun dismissApplyConfirm() {
        _state.update { it.copy(pendingConfirmSet = null) }
    }

    fun confirmApplySet() {
        val snap = _state.value.snapshot ?: return
        val target = _state.value.pendingConfirmSet ?: return
        if (_state.value.busy) return

        val globalIdx = snap.sdamGlobalIndex ?: 1
        viewModelScope.launch {
            _state.update {
                it.copy(
                    pendingConfirmSet = null,
                    busyAction = "正在写入 Set ${target.setId}...",
                    error = null,
                )
            }
            runCatching {
                backend.applySet(snap.bootId, target.setId, globalIdx)
            }.onSuccess { result ->
                val hexDisplay = result.reg7151Hex?.uppercase()?.let { "0x$it" } ?: "未知"
                val message = if (result.bootCpussIndex != result.sdamCpussIndex) {
                    "已写入 Set ${result.sdamCpussIndex}（0x7151=$hexDisplay），重启手机后生效"
                } else {
                    "已写入 Set ${result.sdamCpussIndex}（0x7151=$hexDisplay），与当前运行档位一致"
                }
                _state.update { current ->
                    val updatedSnap = current.snapshot?.let { cur ->
                        val nextSnap = cur.copy(
                            bootCpussIndex = result.bootCpussIndex,
                            sdamCpussIndex = result.sdamCpussIndex,
                            sdamGlobalIndex = result.sdamGlobalIndex,
                            reg7151Hex = result.reg7151Hex,
                        )
                        nextSnap.copy(compatibility = CompatibilityChecker.evaluateSnapshot(nextSnap))
                    }
                    current.copy(
                        busyAction = null,
                        snapshot = updatedSnap,
                        snackbarMessage = message,
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busyAction = null,
                        snackbarMessage = err.message ?: "写入 Set 档位失败",
                    )
                }
            }
        }
    }

    fun captureStockBaseline() {
        val snap = _state.value.snapshot ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busyAction = "正在采集原厂 Set 1 SCMI 基线...",
                    error = null,
                )
            }
            runCatching {
                val csv = backend.saveBaseline(snap.bootId)
                ScmiPlanParser.parseCsv(csv, snap.isSm8850)
            }.onSuccess { points ->
                _state.update { current ->
                    val curSnap = current.snapshot
                    val updatedSnap = curSnap?.copy(
                        baselineSource = BaselineSource.VOLTSET,
                        baselinePoints = points,
                        voltageDiffRows = ScmiPlanParser.diff(
                            curSnap.scmiPoints,
                            points,
                            curSnap.activeBootSet,
                        ),
                    )
                    current.copy(
                        busyAction = null,
                        snapshot = updatedSnap,
                        snackbarMessage = "已保存原厂 Set 1 基线 (${points.size} 个频点)",
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busyAction = null,
                        snackbarMessage = err.message ?: "采集基线失败",
                    )
                }
            }
        }
    }

    fun requestReboot() {
        if (_state.value.busy) return
        _state.update { it.copy(showRebootConfirm = true) }
    }

    fun dismissRebootConfirm() {
        _state.update { it.copy(showRebootConfirm = false) }
    }

    fun confirmReboot() {
        val snap = _state.value.snapshot ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    showRebootConfirm = false,
                    busyAction = "正在重启设备...",
                )
            }
            runCatching {
                backend.reboot(snap.bootId)
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busyAction = null,
                        snackbarMessage = err.message ?: "触发重启失败",
                    )
                }
            }
        }
    }

    fun showMessage(message: String) {
        _state.update { it.copy(snackbarMessage = message) }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbarMessage = null) }
    }
}
