package io.voltset.sm8850

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.voltset.sm8850.core.GpuDraft
import io.voltset.sm8850.core.GpuLevelValue
import io.voltset.sm8850.core.GpuRow
import io.voltset.sm8850.core.GpuTableId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class GpuEditorState(
    val snapshot: GpuSnapshot? = null,
    val selectedBin: Int? = null,
    val draft: GpuDraft = GpuDraft(),
    val incoming: GpuDraft? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val loading: Boolean = false,
    val operation: String? = null,
    val loadError: String? = null,
    val message: String? = null,
    val stressRunning: Boolean = false,
    val stressStats: GpuStressStats? = null,
    val stressMessage: String? = null,
) {
    val busy: Boolean get() = loading || operation != null
    val editable: Boolean get() = snapshot != null && !busy && loadError == null && !stressRunning
}

internal class GpuEditorViewModel(application: Application) : AndroidViewModel(application) {
    private val backend = GpuBackend(application.applicationContext)
    private val stress = GpuStress(application.applicationContext)
    private val _state = MutableStateFlow(GpuEditorState())
    val state = _state.asStateFlow()
    private val undo = ArrayDeque<GpuDraft>()
    private val redo = ArrayDeque<GpuDraft>()
    private var stressJob: Job? = null

    fun loadIfNeeded() {
        val current = _state.value
        if (current.snapshot == null && current.loadError == null) refresh()
    }

    fun refresh() {
        val previous = _state.value
        if (previous.busy || previous.stressRunning) return
        _state.update { it.copy(loading = true, loadError = null, message = null, incoming = null) }
        viewModelScope.launch {
            try {
                val snapshot = backend.readCurrent()
                val sameSource = previous.snapshot?.sameSource(snapshot) == true
                if (!sameSource) {
                    undo.clear()
                    redo.clear()
                }
                _state.value = previous.copy(
                    snapshot = snapshot,
                    selectedBin = if (sameSource) previous.selectedBin else
                        snapshot.activeBin ?: snapshot.image.bins.first().index,
                    draft = if (sameSource) previous.draft else GpuDraft(),
                    incoming = null, loading = false, loadError = null,
                    canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
                    message = if (!sameSource && !previous.draft.isEmpty) "源镜像已变化，已载入新频点表" else null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.message ?: "读取 GPU 镜像失败") }
            }
        }
    }

    fun selectBin(index: Int) {
        _state.update { current ->
            if (current.snapshot?.image?.bins?.any { it.index == index } == true) {
                current.copy(selectedBin = index)
            } else current
        }
    }

    fun editRow(bin: Int, index: Int, value: GpuLevelValue): String? = transform { snap, draft ->
        val rows = snap.image.rows(draft, bin).toMutableList()
        rows[index] = rows[index].copy(value = value)
        snap.image.changeRows(draft, bin, rows)
    }

    fun removeRow(bin: Int, index: Int): String? = transform { snap, draft ->
        val rows = snap.image.rows(draft, bin).toMutableList()
        require(rows[index].value.frequencyHz != 0L) { "休眠档须保留" }
        rows.removeAt(index)
        snap.image.changeRows(draft, bin, rows)
    }

    fun addRow(bin: Int, hz: Long, copyIndex: Int? = null): String? = transform { snap, draft ->
        val image = snap.image
        val rows = image.rows(draft, bin)
        val template = if (copyIndex == null) {
            val key = image.template(bin, hz) ?: image.bins.firstNotNullOfOrNull { image.template(it.index, hz) }
                ?: error("本机镜像没有此频点")
            GpuRow(key, image.baseValue(key))
        } else rows[copyIndex]
        val added = image.carryAcd(bin, template.copy(value = template.value.copy(frequencyHz = hz, acdLevel = null)))
        image.changeRows(draft, bin, (rows + added).sortedByDescending { it.value.frequencyHz })
    }

    fun copyBin(from: Int, to: Int): String? = transform { snap, draft ->
        val image = snap.image
        val targetRows = image.rows(draft, to)
        val targetAcd = targetRows.associate { it.value.frequencyHz to it.value.acdLevel }
        val copied = image.rows(draft, from).filter { it.value.frequencyHz > 0 }.map { row ->
            image.carryAcd(to, row.copy(value = row.value.copy(acdLevel = targetAcd[row.value.frequencyHz])))
        }
        image.changeRows(draft, to, copied + targetRows.filter { it.value.frequencyHz == 0L })
    }

    fun restoreFactoryAcd(bin: Int): String? = transform { snap, draft ->
        snap.image.restoreFactoryAcd(draft, bin)
    }

    fun editTable(id: GpuTableId, index: Int, value: Long): String? = transform { snap, draft ->
        val entries = snap.image.values(draft, id).toMutableList()
        entries[index] = value
        snap.image.changeTable(draft, id, entries)
    }

    fun clearDraft() {
        if (_state.value.editable) commit(GpuDraft())
    }

    fun undoDraft() {
        if (!_state.value.editable || undo.isEmpty()) return
        redo.addLast(_state.value.draft)
        setDraft(undo.removeLast())
    }

    fun redoDraft() {
        if (!_state.value.editable || redo.isEmpty()) return
        undo.addLast(_state.value.draft)
        setDraft(redo.removeLast())
    }

    fun importConfig(uri: Uri) = operation("正在读取配置…") { snap, _ ->
        val draft = backend.readConfig(snap, uri)
        _state.update { it.copy(incoming = draft) }
        null
    }

    fun discardImport() = _state.update { it.copy(incoming = null) }

    fun acceptImport() {
        val incoming = _state.value.incoming ?: return
        if (!_state.value.editable) return
        commit(incoming)
        _state.update { it.copy(incoming = null, message = "配置已载入草稿") }
    }

    fun exportImage(uri: Uri) = operation("正在导出镜像…", abandonedDocument = uri) { snap, draft ->
        backend.export(snap, draft, uri)
        "GPU 镜像已导出"
    }

    fun exportConfig(uri: Uri) = operation("正在导出配置…", abandonedDocument = uri) { snap, draft ->
        backend.exportConfig(snap, draft, uri)
        "GPU 配置已导出"
    }

    fun startStress(seconds: Int) {
        if (_state.value.busy || stressJob?.isActive == true) return
        _state.update { it.copy(stressRunning = true, stressStats = null, stressMessage = null) }
        stressJob = viewModelScope.launch {
            try {
                val stats = stress.run(seconds) { update -> _state.update { it.copy(stressStats = update) } }
                _state.update {
                    it.copy(stressStats = stats, stressMessage = if (stats.stoppedForThermal) "设备热状态过高，测试已停止"
                    else "测试完成，回读样本一致")
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(stressMessage = "测试已停止") }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(stressMessage = e.message ?: "GPU 测试失败") }
            } finally {
                _state.update { it.copy(stressRunning = false) }
            }
        }
    }

    fun stopStress() { stressJob?.cancel() }

    private fun transform(change: (GpuSnapshot, GpuDraft) -> GpuDraft): String? {
        val state = _state.value
        val snapshot = state.snapshot
        if (!state.editable || snapshot == null) return "请先读取当前 GPU 表"
        return try {
            commit(change(snapshot, state.draft))
            null
        } catch (e: Exception) {
            e.message ?: "无法修改 GPU 草稿"
        }
    }

    private fun commit(draft: GpuDraft) {
        val previous = _state.value.draft
        if (draft == previous) return
        undo.addLast(previous)
        if (undo.size > 64) undo.removeFirst()
        redo.clear()
        setDraft(draft)
    }

    private fun setDraft(draft: GpuDraft) {
        _state.update { it.copy(draft = draft, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(), message = null) }
    }

    private fun operation(
        label: String,
        abandonedDocument: Uri? = null,
        perform: suspend (GpuSnapshot, GpuDraft) -> String?,
    ) {
        val state = _state.value
        val snapshot = state.snapshot
        if (!state.editable || snapshot == null) {
            abandonedDocument?.let { uri -> viewModelScope.launch { backend.discardDocument(uri) } }
            return
        }
        _state.update { it.copy(operation = label, message = null, incoming = null) }
        viewModelScope.launch {
            try {
                val message = perform(snapshot, state.draft)
                _state.update { it.copy(operation = null, message = message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(operation = null, message = e.message ?: "GPU 操作失败") }
            }
        }
    }
}

