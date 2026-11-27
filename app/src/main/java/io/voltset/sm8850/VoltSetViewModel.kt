package io.voltset.sm8850

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.voltset.sm8850.core.AutoRestoreSnapshot
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.ClusterSetSummary
import io.voltset.sm8850.core.CompatibilityChecker
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.QptInstantPower
import io.voltset.sm8850.core.QptPowerParser
import io.voltset.sm8850.core.QptRawSample
import io.voltset.sm8850.core.QptSessionStats
import io.voltset.sm8850.core.SavedSetPowerRecord
import io.voltset.sm8850.core.ScmiPlanParser
import io.voltset.sm8850.core.VoltageSetEstimator
import io.voltset.sm8850.core.VoltageSetProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoltSetTab {
    SETS,
    VOLTAGE,
    GPU,
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
    val qptMonitoring: Boolean = false,
    val qptInstant: QptInstantPower? = null,
    val qptSession: QptSessionStats? = null,
    val qptHistory: List<QptInstantPower> = emptyList(),
    val qptError: String? = null,
    val savedPowerRecords: Map<Int, SavedSetPowerRecord> = emptyMap(),
    val autoRestore: AutoRestoreSnapshot? = null,
    val cpuStressRunning: Boolean = false,
    val cpuStressStats: CpuStressStats? = null,
    val cpuStressMessage: String? = null,
    val cpuStressAvgPowerMw: Double? = null,
    val cpuStressAvgCpu0Mhz: Int? = null,
    val cpuStressAvgCpu1Mhz: Int? = null,
) {
    val busy: Boolean get() = loading || busyAction != null
    val controlsEnabled: Boolean get() = snapshot != null && !busy && error == null

    fun canRefreshOnResume(lastSuccessfulRefreshMs: Long?, nowMs: Long): Boolean =
        !busy && !cpuStressRunning && !qptMonitoring &&
            selectedTab != VoltSetTab.GPU && pendingConfirmSet == null && !showRebootConfirm &&
            (lastSuccessfulRefreshMs == null || nowMs - lastSuccessfulRefreshMs >= 5000L)
}

internal class VoltSetViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = RootBackend(application.applicationContext)
    private val cpuStress = CpuStress(application.applicationContext)
    private val powerPrefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(VoltSetUiState())
    val state: StateFlow<VoltSetUiState> = _state.asStateFlow()

    private var lastSuccessfulRefreshMs: Long? = null
    private var qptJob: Job? = null
    private var cpuStressJob: Job? = null
    private var lastRawSample: QptRawSample? = null
    private val qptIntervals = mutableListOf<QptInstantPower>()

    init {
        val loaded = QptPowerParser.deserializeRecords(powerPrefs.getString(KEY_RECORDS, null))
        if (loaded.isNotEmpty()) {
            _state.update { it.copy(savedPowerRecords = loaded) }
        }
    }

    fun refreshOnResume() {
        val current = _state.value
        if (current.canRefreshOnResume(lastSuccessfulRefreshMs, SystemClock.elapsedRealtime())) {
            refresh(reuse = current.snapshot.takeIf { current.error == null })
        }
    }

    fun refresh() = refresh(reuse = null)

    private fun refresh(reuse: DeviceSnapshot?) {
        if (_state.value.busy) return
        _state.update {
            it.copy(loading = true, error = null, pendingConfirmSet = null, showRebootConfirm = false)
        }
        viewModelScope.launch {
            attempt {
                val snap = if (reuse != null && backend.hasSameReadback(reuse)) {
                    reuse
                } else {
                    maybeAutoSaveBaseline(backend.readStatus())
                }
                val sharedPower = syncPowerRecordsOnRefresh()
                val autoRestore = attempt { backend.readAutoRestore() }.getOrNull()
                Triple(snap, sharedPower, autoRestore)
            }.onSuccess { (snap, sharedPower, autoRestore) ->
                lastSuccessfulRefreshMs = SystemClock.elapsedRealtime()
                _state.update { current ->
                    current.copy(
                        loading = false,
                        error = null,
                        snapshot = snap,
                        savedPowerRecords = sharedPower ?: current.savedPowerRecords,
                        autoRestore = autoRestore ?: current.autoRestore,
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

    private suspend fun syncPowerRecordsOnRefresh(): Map<Int, SavedSetPowerRecord>? {
        val shared = attempt { backend.readPowerRecords() }.getOrNull() ?: return null
        if (shared.isNotEmpty()) {
            persistPowerPrefs(shared)
            return shared
        }
        val local = _state.value.savedPowerRecords
        if (local.isNotEmpty()) {
            attempt { backend.writePowerRecords(local) }
            return local
        }
        return emptyMap()
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
        if (tab != VoltSetTab.VOLTAGE) {
            stopCpuStress()
            stopQptMonitor()
        }
        _state.update { it.copy(selectedTab = tab) }
    }

    fun selectClusterFilter(cluster: Int?) {
        _state.update { it.copy(selectedClusterFilter = cluster) }
    }

    fun toggleQptMonitor() {
        if (_state.value.qptMonitoring) {
            stopQptMonitor()
            return
        }
        startQptMonitorInternal()
    }

    private fun startQptMonitorInternal() {
        if (_state.value.qptMonitoring) return
        val previousJob = qptJob
        lastRawSample = null
        qptIntervals.clear()
        _state.update {
            it.copy(
                qptMonitoring = true,
                qptInstant = null,
                qptSession = null,
                qptHistory = emptyList(),
                qptError = null,
            )
        }
        qptJob = viewModelScope.launch {
            withContext(NonCancellable) { previousJob?.join() }
            ensureActive()
            attempt {
                backend.streamPowerSamples().collect { sample ->
                    val previous = lastRawSample
                    lastRawSample = sample
                    val delta = previous?.let { QptPowerParser.computeDelta(it, sample) } ?: return@collect
                    if (qptIntervals.size >= MAX_QPT_SAMPLES) qptIntervals.removeAt(0)
                    qptIntervals.add(delta)
                    val session = QptPowerParser.summarizeSession(qptIntervals)
                    val frozen = qptIntervals.size >= 3 &&
                        qptIntervals.subList(qptIntervals.size - 3, qptIntervals.size)
                            .all { (it.socTotalMw ?: 0.0) <= 0.001 }
                    val history = qptIntervals.takeLast(MAX_HISTORY_POINTS)
                    _state.update {
                        it.copy(
                            qptInstant = delta,
                            qptSession = session,
                            qptHistory = history,
                            qptError = if (frozen) "QPT 硬件计数器未增长（当前固件可能未启用实时功耗遥测）" else null,
                        )
                    }
                }
            }.onFailure { err ->
                _state.update { it.copy(qptMonitoring = false, qptError = err.message ?: "读取硬件功耗失败") }
            }
        }
    }

    fun stopQptMonitor() {
        qptJob?.cancel()
        if (_state.value.qptMonitoring) {
            _state.update { it.copy(qptMonitoring = false) }
        }
    }

    fun resetQptSession() {
        lastRawSample = null
        qptIntervals.clear()
        _state.update { it.copy(qptInstant = null, qptSession = null, qptHistory = emptyList(), qptError = null) }
    }

    fun startCpuStress(mode: CpuStressMode, seconds: Int) {
        val current = _state.value
        if (current.busy || cpuStressJob?.isActive == true) return
        val hasQpt = current.snapshot?.systemProbe?.qptRails?.isNotEmpty() == true
        val autoStartQpt = hasQpt && !current.qptMonitoring
        if (autoStartQpt) {
            startQptMonitorInternal()
        }
        val startIdx = qptIntervals.size
        _state.update {
            it.copy(
                cpuStressRunning = true,
                cpuStressStats = null,
                cpuStressMessage = null,
                cpuStressAvgPowerMw = null,
                cpuStressAvgCpu0Mhz = null,
                cpuStressAvgCpu1Mhz = null,
            )
        }
        cpuStressJob = viewModelScope.launch {
            try {
                val stats = cpuStress.run(mode, seconds) { progress ->
                    val window = qptWindowSummary(startIdx)
                    _state.update {
                        it.copy(
                            cpuStressStats = progress,
                            cpuStressAvgPowerMw = window?.avgCpuTotalMw ?: it.cpuStressAvgPowerMw,
                            cpuStressAvgCpu0Mhz = window?.avgCpu0Mhz?.takeIf { m -> m > 0 } ?: it.cpuStressAvgCpu0Mhz,
                            cpuStressAvgCpu1Mhz = window?.avgCpu1Mhz?.takeIf { m -> m > 0 } ?: it.cpuStressAvgCpu1Mhz,
                        )
                    }
                }
                val window = qptWindowSummary(startIdx)
                val msg = if (stats.stoppedForThermal) {
                    "设备热状态过高，已自动停止 CPU 压测"
                } else {
                    "CPU 压测完成：${stats.verifiedBlocks} 次校验全部通过"
                }
                _state.update {
                    it.copy(
                        cpuStressStats = stats,
                        cpuStressMessage = msg,
                        cpuStressAvgPowerMw = window?.avgCpuTotalMw ?: it.cpuStressAvgPowerMw,
                        cpuStressAvgCpu0Mhz = window?.avgCpu0Mhz?.takeIf { m -> m > 0 } ?: it.cpuStressAvgCpu0Mhz,
                        cpuStressAvgCpu1Mhz = window?.avgCpu1Mhz?.takeIf { m -> m > 0 } ?: it.cpuStressAvgCpu1Mhz,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(cpuStressMessage = "CPU 压测已停止") }
                throw cancelled
            } catch (err: Exception) {
                _state.update { it.copy(cpuStressMessage = err.message ?: "CPU 压测失败") }
            } finally {
                _state.update { it.copy(cpuStressRunning = false) }
                if (autoStartQpt) {
                    stopQptMonitor()
                }
            }
        }
    }

    fun stopCpuStress() {
        cpuStressJob?.cancel()
    }

    private fun qptWindowSummary(startIdx: Int): QptSessionStats? {
        if (qptIntervals.size <= startIdx) return null
        val slice = qptIntervals.subList(startIdx, qptIntervals.size).toList()
        return QptPowerParser.summarizeSession(slice)
    }

    fun saveCurrentSetPowerRecord() {
        val current = _state.value
        val setId = current.snapshot?.bootCpussIndex
        val stats = current.qptSession
        if (setId == null || stats == null) {
            showMessage("请先开启功耗监测并确认当前开机 Set 档位")
            return
        }
        if (stats.sampleCount < MIN_SAVE_SAMPLES) {
            showMessage("请先至少连续采样 $MIN_SAVE_SAMPLES 次再记录档位均功耗")
            return
        }
        val record = QptPowerParser.buildSavedRecord(setId, stats, System.currentTimeMillis()) ?: return
        val next = current.savedPowerRecords + (setId to record)
        persistPowerRecords(next)
        _state.update {
            it.copy(
                savedPowerRecords = next,
                snackbarMessage = "已记录 Set $setId CPU 均功耗：${record.avgCpuTotalMw} mW",
            )
        }
    }

    fun deletePowerRecord(setId: Int) {
        val current = _state.value.savedPowerRecords
        if (setId !in current) return
        val next = current - setId
        persistPowerRecords(next)
        _state.update { it.copy(savedPowerRecords = next) }
    }

    fun clearPowerRecords() {
        persistPowerRecords(emptyMap())
        _state.update {
            it.copy(
                savedPowerRecords = emptyMap(),
                snackbarMessage = "已清空档位功耗对比记录",
            )
        }
    }

    private fun persistPowerPrefs(records: Map<Int, SavedSetPowerRecord>) {
        val serialized = QptPowerParser.serializeRecords(records)
        powerPrefs.edit().putString(KEY_RECORDS, serialized).apply()
    }

    private fun persistPowerRecords(records: Map<Int, SavedSetPowerRecord>) {
        persistPowerPrefs(records)
        viewModelScope.launch {
            attempt { backend.writePowerRecords(records) }
        }
    }

    fun updateAutoRestore(enabled: Boolean, targetSet: Int?) {
        val current = _state.value
        val snap = current.snapshot ?: return
        if (!current.controlsEnabled) return
        val resolvedTarget = targetSet?.takeIf { it in 2..7 }
            ?: current.autoRestore?.config?.targetSet
            ?: snap.sdamCpussIndex?.takeIf { it in 2..7 }
            ?: snap.bootCpussIndex?.takeIf { it in 2..7 }
            ?: 2
        _state.update { it.copy(busyAction = if (enabled) "正在开启开机自动写入…" else "正在关闭开机自动写入…") }
        viewModelScope.launch {
            attempt {
                backend.setAutoRestore(enabled, resolvedTarget, snap.bootId, snap.pdpCdbSha256)
            }.onSuccess { cfg ->
                _state.update { st ->
                    val updatedSnapshot = st.autoRestore?.copy(config = cfg)
                        ?: AutoRestoreSnapshot(moduleInstalled = true, config = cfg)
                    st.copy(
                        busyAction = null,
                        autoRestore = updatedSnapshot,
                        snackbarMessage = if (cfg.enabled) {
                            "已开启开机自动写入：每次开机写入 Set ${cfg.targetSet}"
                        } else {
                            "已关闭开机自动写入"
                        },
                    )
                }
            }.onFailure { err ->
                _state.update {
                    it.copy(
                        busyAction = null,
                        snackbarMessage = err.message ?: "更新开机自动写入配置失败",
                    )
                }
            }
        }
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
                val result = backend.applySet(snap.bootId, target.setId, cpussIdx, globalIdx)
                var syncNote = ""
                var updatedAutoRestore = current.autoRestore
                val arCfg = current.autoRestore?.config
                if (arCfg?.enabled == true) {
                    if (result.sdamCpussIndex == 1) {
                        attempt {
                            backend.setAutoRestore(false, arCfg.targetSet, snap.bootId, snap.pdpCdbSha256)
                        }.onSuccess { nextCfg ->
                            updatedAutoRestore = updatedAutoRestore.copy(config = nextCfg)
                            syncNote = "（已同时关闭开机自动写入）"
                        }
                    } else if (result.sdamCpussIndex in 2..7 &&
                        arCfg.pdpCdbSha256 != null &&
                        arCfg.pdpCdbSha256 == snap.pdpCdbSha256
                    ) {
                        attempt {
                            backend.setAutoRestore(true, result.sdamCpussIndex, snap.bootId, snap.pdpCdbSha256)
                        }.onSuccess { nextCfg ->
                            updatedAutoRestore = updatedAutoRestore?.copy(config = nextCfg)
                            syncNote = "（开机自动写入已同步为 Set ${result.sdamCpussIndex}）"
                        }
                    }
                }
                Triple(result, updatedAutoRestore, syncNote)
            }.onSuccess { (result, updatedAutoRestore, syncNote) ->
                val updated = snap.copy(
                    bootSet = snap.bootSet.copy(stagedThisBootIndex = result.sdamCpussIndex),
                    sdamCpussIndex = result.sdamCpussIndex,
                    sdamGlobalIndex = result.sdamGlobalIndex,
                    reg7151Hex = result.reg7151Hex,
                )
                val baseMsg = if (updated.pendingReboot) {
                    "已写入 Set ${result.sdamCpussIndex}，下次开机核对"
                } else {
                    "SDAM 已读回 Set ${result.sdamCpussIndex}"
                }
                _state.update {
                    it.copy(
                        busyAction = null,
                        snapshot = updated.copy(compatibility = CompatibilityChecker.evaluateSnapshot(updated)),
                        autoRestore = updatedAutoRestore,
                        snackbarMessage = baseMsg + syncNote,
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
        val isRefresh = snap.baselineSource == BaselineSource.VOLTSET
        _state.update {
            it.copy(busyAction = if (isRefresh) "正在刷新 Set 1 原厂基线…" else "正在采集 Set 1 原厂基线…")
        }
        viewModelScope.launch {
            attempt { saveBaseline(snap) }.onSuccess { updated ->
                val verb = if (isRefresh) "已刷新" else "已保存"
                _state.update {
                    it.copy(
                        busyAction = null,
                        snapshot = updated,
                        snackbarMessage = "${verb}原厂基线（${updated.baselinePoints.orEmpty().size} 个频点）",
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

    fun exportDiagnosticZip(uri: Uri) {
        val current = _state.value
        val snap = current.snapshot ?: return
        if (current.busy) return
        _state.update { it.copy(busyAction = "正在导出诊断压缩包…") }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val appVersion = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "未知"
            }.getOrDefault("未知")
            attempt {
                DiagnosticExporter.exportZip(app, snap, current.autoRestore, appVersion, uri)
            }.onSuccess {
                _state.update { it.copy(busyAction = null, snackbarMessage = "已导出诊断压缩包") }
            }.onFailure { err ->
                _state.update {
                    it.copy(busyAction = null, snackbarMessage = err.message ?: "导出诊断压缩包失败")
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

    override fun onCleared() {
        stopCpuStress()
        stopQptMonitor()
        super.onCleared()
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    companion object {
        private const val PREFS_NAME = "voltset_power_records"
        private const val KEY_RECORDS = "records_v1"
        private const val MAX_QPT_SAMPLES = 600
        private const val MAX_HISTORY_POINTS = 60
        private const val MIN_SAVE_SAMPLES = 3
    }
}
