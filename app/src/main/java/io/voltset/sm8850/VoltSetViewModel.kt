package io.voltset.sm8850

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.ClusterSetSummary
import io.voltset.sm8850.core.CompatibilityChecker
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.ScmiPlanParser
import io.voltset.sm8850.core.VoltageSetEstimator
import io.voltset.sm8850.core.VoltageSetProfile
import kotlinx.coroutines.CancellationException
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
    val loading: Boolean = false,
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
    val controlsEnabled: Boolean get() = snapshot != null && !busy && error == null
}

internal class VoltSetViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = RootBackend(application.applicationContext)
    private val _state = MutableStateFlow(VoltSetUiState())
    val state: StateFlow<VoltSetUiState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value.busy) return
        _state.update {
            it.copy(loading = true, error = null, pendingConfirmSet = null, showRebootConfirm = false)
        }
        viewModelScope.launch {
            attempt {
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
        return attempt { saveBaseline(snap) }.getOrDefault(snap)
    }

    private suspend fun saveBaseline(snap: DeviceSnapshot): DeviceSnapshot {
        val csv = backend.saveBaseline(snap.bootId)
        val points = ScmiPlanParser.parseCsv(csv, snap.isSm8850, snap.qualityReport?.coefficients.orEmpty())
        check(points.any { it.domain == 0 } && points.any { it.domain == 1 }) { "原厂基线缺少完整的 CPU 集群数据" }
        val estimates = VoltageSetEstimator.estimateAll(snap.pdpCdb?.sets.orEmpty(), points, snap.pdpHardwareLimits)
        val updated = snap.copy(
            baselineSource = BaselineSource.VOLTSET,
            baselinePoints = points,
            voltageDiffRows = ScmiPlanParser.diff(snap.scmiPoints, points, snap.activeBootSet),
            setEstimates = estimates,
        )
        return updated.copy(compatibility = CompatibilityChecker.evaluateSnapshot(updated))
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
        val current = _state.value
        if (!current.controlsEnabled || current.snapshot?.canSelectSet(profile.setId) != true) return
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
        val current = _state.value
        val snap = current.snapshot ?: return
        val target = current.pendingConfirmSet ?: return
        if (!current.controlsEnabled || !snap.canSelectSet(target.setId)) return
        val cpussIdx = snap.sdamCpussIndex ?: return
        val globalIdx = snap.sdamGlobalIndex ?: return
        _state.update {
            it.copy(pendingConfirmSet = null, busyAction = "正在写入 Set ${target.setId}…")
        }
        viewModelScope.launch {
            attempt {
                backend.applySet(snap.bootId, target.setId, cpussIdx, globalIdx)
            }.onSuccess { result ->
                val updated = snap.copy(
                    bootSet = snap.bootSet.copy(stagedThisBootIndex = result.sdamCpussIndex),
                    sdamCpussIndex = result.sdamCpussIndex,
                    sdamGlobalIndex = result.sdamGlobalIndex,
                    reg7151Hex = result.reg7151Hex,
                )
                val message = if (updated.pendingReboot) {
                    "已写入 Set ${result.sdamCpussIndex}，下次开机核对"
                } else {
                    "SDAM 已读回 Set ${result.sdamCpussIndex}"
                }
                _state.update {
                    it.copy(
                        busyAction = null,
                        snapshot = updated.copy(compatibility = CompatibilityChecker.evaluateSnapshot(updated)),
                        snackbarMessage = message,
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busyAction = null,
                        error = err.message ?: "写入结果未确认，请刷新核对",
                    )
                }
            }
        }
    }

    fun captureStockBaseline() {
        val current = _state.value
        val snap = current.snapshot ?: return
        if (!current.controlsEnabled || !snap.canSaveStockBaseline) return
        _state.update { it.copy(busyAction = "正在采集 Set 1 原厂基线…") }
        viewModelScope.launch {
            attempt { saveBaseline(snap) }.onSuccess { updated ->
                _state.update {
                    it.copy(
                        busyAction = null,
                        snapshot = updated,
                        snackbarMessage = "已保存原厂基线（${updated.baselinePoints.orEmpty().size} 个频点）",
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
        val current = _state.value
        if (!current.controlsEnabled || current.snapshot?.pendingReboot != true) return
        _state.update { it.copy(showRebootConfirm = true) }
    }

    fun dismissRebootConfirm() {
        _state.update { it.copy(showRebootConfirm = false) }
    }

    fun confirmReboot() {
        val current = _state.value
        val snap = current.snapshot ?: return
        if (!current.controlsEnabled || !current.showRebootConfirm) return
        val target = snap.pendingSetIndex ?: return
        val globalIdx = snap.sdamGlobalIndex ?: return
        _state.update { it.copy(showRebootConfirm = false, busyAction = "正在请求重启…") }
        viewModelScope.launch {
            attempt {
                backend.reboot(snap.bootId, target, globalIdx)
            }.onSuccess {
                _state.update { it.copy(busyAction = null, snackbarMessage = "已请求重启") }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busyAction = null,
                        error = err.message ?: "重启请求失败，请刷新后重试",
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

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
