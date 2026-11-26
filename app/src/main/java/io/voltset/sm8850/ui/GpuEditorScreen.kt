package io.voltset.sm8850.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.voltset.sm8850.GpuEditorState
import io.voltset.sm8850.GpuEditorViewModel
import io.voltset.sm8850.core.GpuAuxTable
import io.voltset.sm8850.core.GpuDraft
import io.voltset.sm8850.core.GpuLevelValue
import io.voltset.sm8850.core.GpuRow
import io.voltset.sm8850.core.GpuTableId
import io.voltset.sm8850.core.GpuVendorBoot
import io.voltset.sm8850.core.GpuVoltageScore
import java.math.BigDecimal
import java.util.Locale

private enum class Panel(val title: String) {
    LEVELS("频压与档位"), BANDWIDTH("带宽"), GMU("GMU"), STRESS("稳定性"),
}

@Composable
internal fun GpuEditorScreen(
    state: GpuEditorState,
    model: GpuEditorViewModel,
    onPickConfig: () -> Unit,
    onSaveConfig: (String) -> Unit,
    onSaveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    if (snapshot == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) {
                    CircularProgressIndicator()
                    Text("正在读取 GPU 表…")
                } else {
                    Text(state.loadError ?: "从当前槽读取 GPU 调参表")
                    Button(onClick = model::refresh) { Text("读取当前镜像") }
                }
            }
        }
        return
    }
    val image = snapshot.image
    val bin = image.bins.firstOrNull { it.index == state.selectedBin } ?: image.bins.first()
    val stockLevels = snapshot.stockVoltages[bin.speedBin]
    val voltageCorners = snapshot.voltageCorners
    val sourceRows = image.rows(GpuDraft(), bin.index)
    val rows = image.rows(state.draft, bin.index)
    val sourceScore = remember(sourceRows, stockLevels, voltageCorners) {
        if (stockLevels != null && voltageCorners != null) GpuVoltageScore.evaluate(sourceRows, stockLevels, voltageCorners)
        else null
    }
    val draftScore = remember(rows, sourceRows, stockLevels, voltageCorners) {
        val changed = rows.map { it.value.frequencyHz to it.value.voltageLevel } !=
            sourceRows.map { it.value.frequencyHz to it.value.voltageLevel }
        if (changed && stockLevels != null && voltageCorners != null) GpuVoltageScore.evaluate(rows, stockLevels, voltageCorners)
        else null
    }
    val factoryAcdChanges = remember(image, rows, bin.index) {
        rows.count { row ->
            val current = row.value.acdLevel
            val factory = image.factoryAcd(bin.index, row.value.frequencyHz)
            current != null && factory != null && current != factory.code
        }
    }
    val source = snapshot.source
    val slot = source.slot.removePrefix("_").uppercase(Locale.ROOT)
    val filename = "VoltSet-GPU-" + source.device.replace(Regex("[^A-Za-z0-9._-]"), "_")
    var panelName by rememberSaveable { mutableStateOf(Panel.LEVELS.name) }
    val panel = Panel.valueOf(panelName)
    var editing by remember(image, bin.index) { mutableStateOf<Int?>(null) }
    var adding by remember(image, bin.index) { mutableStateOf(false) }
    var copyLevel by remember(image, bin.index) { mutableStateOf<Int?>(null) }
    var copyingBin by remember(image, bin.index) { mutableStateOf(false) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var localError by remember(image) { mutableStateOf<String?>(null) }

    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("${source.device} · 槽 $slot · DTB ${source.dtbIndex}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in Panel.entries) {
                    FilterChip(selected = option == panel, onClick = { panelName = option.name }, label = { Text(option.title) })
                }
            }
        }
        val status = state.loadError ?: state.operation ?: localError ?: state.message
        status?.let {
            Text(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                color = if (state.loadError != null || localError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        }

        Box(Modifier.weight(1f)) {
            when (panel) {
                Panel.LEVELS -> LazyColumn(
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (option in image.bins) {
                                FilterChip(selected = option.index == bin.index, onClick = {
                                    localError = null
                                    model.selectBin(option.index)
                                }, label = {
                                    val suffix = if (option.index == snapshot.activeBin) " · 当前" else ""
                                    Text("表 ${option.index}$suffix")
                                })
                            }
                        }
                        Text("${rows.count { it.value.frequencyHz > 0 }} 个频点 · 芯片分档 ${bin.speedBin ?: "默认"}",
                            style = MaterialTheme.typography.bodySmall)
                        val acd = when (snapshot.runtimeAcd) {
                            true -> "开启"
                            false -> "关闭"
                            null -> "驱动未报告"
                        }
                        Text("运行 ACD · $acd", style = MaterialTheme.typography.bodySmall)
                        if (snapshot.activeBin == null) {
                            Text("运行频点未唯一匹配，请确认选中的表。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        VoltageScoreCard(source.build, sourceScore, draftScore, stockLevels != null)
                    }
                    item {
                        Curve(sourceRows, rows)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { adding = true }, enabled = state.editable && image.canInsert(bin, rows)) {
                                Text("新增频点")
                            }
                            TextButton(onClick = { copyingBin = true }, enabled = state.editable && image.bins.any {
                                it.index != bin.index && image.canReindex(it)
                            }) { Text("复制整表") }
                            if (factoryAcdChanges > 0) {
                                TextButton(onClick = { localError = model.restoreFactoryAcd(bin.index) }, enabled = state.editable) {
                                    Text("套用原厂 ACD（$factoryAcdChanges）")
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            Text("频率（MHz）", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Text("电压请求档", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    itemsIndexed(rows, key = { _, row -> row.value.frequencyHz }) { index, row ->
                        val original = image.baseValue(row.template)
                        val changed = row.value != original || row.template.bin != bin.index
                        val hz = row.value.frequencyHz
                        val point = (draftScore ?: sourceScore)?.points?.get(hz)
                        val effective = point?.effectiveLevel ?: voltageCorners?.resolve(row.value.voltageLevel)?.level
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(
                                enabled = state.editable && row.value.frequencyHz > 0,
                            ) { editing = index },
                            shape = MaterialTheme.shapes.medium,
                            color = if (changed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (hz == 0L) "休眠" else mhz(hz), fontWeight = FontWeight.SemiBold)
                                    val details = listOfNotNull(
                                        "P$index",
                                        row.value.bus?.let { "DDR ${it.minimum}/${it.target}/${it.maximum}" },
                                        row.value.acdLevel?.let { "ACD ${acdLabel(it)}" },
                                    )
                                    Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                                    if (point != null) {
                                        val difference = when {
                                            point.reduction > 0 -> "低 ${point.reduction} 档"
                                            point.reduction < 0 -> "高 ${-point.reduction} 档"
                                            else -> "同档"
                                        }
                                        Text("原厂 GFX ${point.factoryLevel} · $difference", style = MaterialTheme.typography.labelSmall)
                                    } else if (hz > 0 && stockLevels != null && hz !in stockLevels) {
                                        Text("原厂表无同频点", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(row.value.voltageLevel.toString(), style = MaterialTheme.typography.titleMedium)
                                    if (hz > 0 && effective != null) {
                                        Text("GFX $effective", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                Panel.BANDWIDTH, Panel.GMU -> {
                    val tables = image.tables.filter {
                        if (panel == Panel.GMU) it.id in setOf(GpuTableId.GMU_CLOCKS, GpuTableId.GMU_DDR)
                        else it.id in setOf(GpuTableId.DDR, GpuTableId.CNOC)
                    }
                    AuxiliaryTables(tables, image, state.draft, state.editable) { id, index, value ->
                        localError = model.editTable(id, index, value)
                    }
                }

                Panel.STRESS -> StressPanel(state, model)
            }
        }

        if (panel != Panel.STRESS) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = model::undoDraft, enabled = state.editable && state.canUndo) { Text("撤销") }
                TextButton(onClick = model::redoDraft, enabled = state.editable && state.canRedo) { Text("重做") }
                TextButton(onClick = onPickConfig, enabled = state.editable) { Text("导入配置") }
                TextButton(onClick = { onSaveConfig("$filename.json") }, enabled = state.editable) { Text("导出配置") }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.draft.isEmpty) "源镜像" else "草稿 · ${state.draft.bins.size + state.draft.tables.size} 张表",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { localError = null; model.clearDraft() }, enabled = state.editable && !state.draft.isEmpty) { Text("清空") }
                Button(onClick = { reviewing = true }, enabled = state.editable && !state.draft.isEmpty) { Text("预览并导出") }
            }
        }
    }

    editing?.let { index ->
        rows.getOrNull(index)?.let { row ->
            LevelDialog(
                image, bin.index, index, row, image.frequencyChoices(rows, index), image.voltageChoices(rows, index),
                removable = image.canReindex(bin) && rows.count { it.value.frequencyHz > 0 } > 1,
                duplicable = image.canInsert(bin, rows),
                onDismiss = { editing = null },
                onEdit = { value -> model.editRow(bin.index, index, value).also { if (it == null) editing = null } },
                onRemove = { localError = model.removeRow(bin.index, index); editing = null },
                onDuplicate = { copyLevel = index; editing = null; adding = true },
            )
        }
    }
    if (adding && state.editable) {
        val choices = image.spareFrequencies(rows)
        NumberDialog(
            if (copyLevel == null) "新增频点" else "复制频点",
            choices, { "${mhz(it)} MHz" },
            onDismiss = { adding = false; copyLevel = null },
            onPick = {
                localError = model.addRow(bin.index, it, copyLevel)
                adding = false
                copyLevel = null
            },
        )
    }
    if (copyingBin && state.editable) {
        NumberDialog(
            "把表 ${bin.index} 复制到",
            image.bins.filter { it.index != bin.index && image.canReindex(it) }.map { it.index.toLong() },
            { "表 $it" }, onDismiss = { copyingBin = false },
            onPick = { localError = model.copyBin(bin.index, it.toInt()); copyingBin = false },
        )
    }
    val incoming = state.incoming
    if (state.editable && (incoming != null || reviewing && !state.draft.isEmpty)) {
        val candidate = incoming ?: state.draft
        val summary = remember(image, candidate) { describe(image, candidate) }
        fun close() {
            reviewing = false
            model.discardImport()
        }
        AlertDialog(
            onDismissRequest = ::close,
            title = { Text(if (incoming == null) "导出前核对" else "配置迁移预览") },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("槽 $slot · DTB ${source.dtbIndex}")
                    for (line in summary) Text(line, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (incoming != null) model.acceptImport()
                    else {
                        reviewing = false
                        onSaveImage("$filename-DTB${source.dtbIndex}-$slot.img")
                    }
                }) { Text(if (incoming == null) "导出镜像" else "载入草稿") }
            },
            dismissButton = { TextButton(onClick = ::close) { Text("返回") } },
        )
    }
}

@Composable
private fun VoltageScoreCard(
    build: String,
    source: GpuVoltageScore?,
    draft: GpuVoltageScore?,
    hasStockLevels: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("GPU 电压评分", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (source == null) {
                Text(if (hasStockLevels) "未读到 GFX 电压档位表" else "此机型、系统版本和分档暂无已核对的原厂表",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    VoltageScoreValue("当前镜像", source, Modifier.weight(1f))
                    draft?.let { VoltageScoreValue("草稿", it, Modifier.weight(1f)) }
                }
                Text("原厂 $build · 基准 100 分", style = MaterialTheme.typography.bodySmall)
                Text("同频 GFX 档差按全部原厂频点平均", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VoltageScoreValue(title: String, report: GpuVoltageScore, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Text(
            report.score?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text("同频 ${report.points.size}/${report.stockFrequencyCount}", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Curve(source: List<GpuRow>, draft: List<GpuRow>) {
    val original = source.map { it.value }.filter { it.frequencyHz > 0 }
    val edited = draft.map { it.value }.filter { it.frequencyHz > 0 }
    val values = original + edited
    if (values.size < 4) return
    val loHz = values.minOf { it.frequencyHz }
    val hiHz = values.maxOf { it.frequencyHz }
    val loLevel = values.minOf { it.voltageLevel }
    val hiLevel = values.maxOf { it.voltageLevel }
    val baseColor = MaterialTheme.colorScheme.outline
    val color = MaterialTheme.colorScheme.primary
    Text("频率（横轴） · 电压请求档（纵轴）", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(88.dp).padding(8.dp)) {
        fun at(point: GpuLevelValue): Offset = Offset(
            (point.frequencyHz - loHz).toFloat() / (hiHz - loHz).coerceAtLeast(1) * size.width,
            size.height - (point.voltageLevel - loLevel).toFloat() / (hiLevel - loLevel).coerceAtLeast(1) * size.height,
        )
        for ((a, b) in original.zipWithNext()) drawLine(baseColor, at(a), at(b), strokeWidth = 2.dp.toPx())
        for ((a, b) in edited.zipWithNext()) drawLine(color, at(a), at(b), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun LevelDialog(
    image: GpuVendorBoot, bin: Int, index: Int, row: GpuRow, frequencies: List<Long>, levels: List<Long>,
    removable: Boolean, duplicable: Boolean, onDismiss: () -> Unit,
    onEdit: (GpuLevelValue) -> String?, onRemove: () -> Unit, onDuplicate: () -> Unit,
) {
    var value by remember(row) { mutableStateOf(row.value) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("表 $bin · P$index") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectValue("频率", value.frequencyHz, frequencies, { "${mhz(it)} MHz" }) {
                    value = image.carryAcd(bin, row.copy(value = value.copy(frequencyHz = it))).value
                    error = null
                }
                SelectValue("电压档位", value.voltageLevel, levels, Long::toString) {
                    value = value.copy(voltageLevel = it)
                    error = null
                }
                val acd = value.acdLevel
                if (acd == null) {
                    Text("ACD 未配置", style = MaterialTheme.typography.bodySmall)
                } else {
                    val choices = image.acdChoices(bin, row.template, value.frequencyHz)
                    val factory = image.factoryAcd(bin, value.frequencyHz)
                    SelectValue("ACD 标定", acd, choices, { if (it == factory?.code) "原厂 ${acdLabel(it)}" else acdLabel(it) }) {
                        value = value.copy(acdLevel = it)
                        error = null
                    }
                    if (factory != null && value.voltageLevel != factory.voltageLevel) {
                        Text("草稿电压档 ${value.voltageLevel}，原厂标定对应 ${factory.voltageLevel}",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (choices == listOf(0L)) {
                        Text("本分档无此频率的 ACD 标定", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val bus = value.bus
                val busIndices = image.busIndices
                if (bus != null && busIndices.isNotEmpty()) {
                    SelectValue("DDR 最低索引", bus.minimum, busIndices.filter { it <= bus.target }, Long::toString) {
                        value = value.copy(bus = bus.copy(minimum = it))
                        error = null
                    }
                    SelectValue("DDR 目标索引", bus.target, busIndices.filter { it in bus.minimum..bus.maximum }, Long::toString) {
                        value = value.copy(bus = bus.copy(target = it))
                        error = null
                    }
                    SelectValue("DDR 最高索引", bus.maximum, busIndices.filter { it >= bus.target }, Long::toString) {
                        value = value.copy(bus = bus.copy(maximum = it))
                        error = null
                    }
                }
                Row {
                    if (duplicable) TextButton(onClick = onDuplicate) { Text("复制频点") }
                    if (removable) TextButton(onClick = onRemove) { Text("删除频点") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onEdit(value) }) { Text("暂存改动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SelectValue(label: String, value: Long, choices: List<Long>, format: (Long) -> String, onChange: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = choices.any { it != value }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · ${format(value)}", Modifier.fillMaxWidth())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
            for (choice in choices) {
                DropdownMenuItem(text = { Text(format(choice) + if (choice == value) " · 已选" else "") }, onClick = {
                    expanded = false
                    onChange(choice)
                })
            }
        }
    }
}

@Composable
private fun NumberDialog(title: String, choices: List<Long>, format: (Long) -> String, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 310.dp).verticalScroll(rememberScrollState())) {
            for (value in choices) TextButton(onClick = { onPick(value) }, modifier = Modifier.fillMaxWidth()) {
                Text(format(value), Modifier.fillMaxWidth())
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun AuxiliaryTables(
    tables: List<GpuAuxTable>, image: GpuVendorBoot, draft: GpuDraft, editable: Boolean,
    onChange: (GpuTableId, Int, Long) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (tables.isEmpty()) item { Text("当前设备树没有这类调参表") }
        for (table in tables) {
            val entries = image.values(draft, table.id)
            item { Text(table.id.label, style = MaterialTheme.typography.titleLarge) }
            itemsIndexed(entries, key = { i, _ -> "${table.id}:$i" }) { index, value ->
                val clock = table.id == GpuTableId.GMU_CLOCKS
                val frequency = clock && index % 2 == 0
                val label = if (clock) {
                    "P${index / 2} " + if (frequency) "频率" else "电压档位"
                } else "索引 $index"
                val choices = if (editable) image.tableChoices(table.id, entries, index) else emptyList()
                SelectValue(label, value, choices, { if (frequency) "${mhz(it)} MHz" else it.toString() }) {
                    onChange(table.id, index, it)
                }
            }
        }
    }
}

@Composable
private fun StressPanel(state: GpuEditorState, model: GpuEditorViewModel) {
    var seconds by rememberSaveable { mutableIntStateOf(30) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("GPU 稳定性测试", style = MaterialTheme.typography.titleLarge)
        Text("用当前运行配置离屏渲染，并定期核对回读图样。", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (duration in listOf(10, 30, 60, 180)) {
                FilterChip(selected = seconds == duration, onClick = { seconds = duration },
                    enabled = !state.stressRunning, label = { Text("$duration 秒") })
            }
        }
        if (state.stressRunning) Button(onClick = model::stopStress) { Text("停止测试") }
        else Button(onClick = { model.startStress(seconds) }, enabled = !state.busy) { Text("开始测试") }

        state.stressStats?.let { result ->
            Text(result.renderer, style = MaterialTheme.typography.titleSmall)
            Text("${result.elapsedSeconds} 秒 · ${result.frames} 帧 · ${decimal(result.fps)} FPS")
            Text("最慢一帧 ${decimal(result.worstFrameMs)} ms · 已核对 ${result.verifiedSamples} 个样本")
        }
        state.stressMessage?.let { Text(it) }
    }
}

private fun describe(image: GpuVendorBoot, draft: GpuDraft): List<String> = buildList {
    for ((bin, rows) in draft.bins.toSortedMap()) {
        val original = image.rows(GpuDraft(), bin)
        val old = original.associateBy { it.value.frequencyHz }
        val new = rows.associateBy { it.value.frequencyHz }
        add("表 $bin · ${original.size} → ${rows.size} 个档位")
        for (before in original) if (before.value.frequencyHz !in new) add("移除 ${mhz(before.value.frequencyHz)} MHz")
        for (after in rows) {
            val hz = after.value.frequencyHz
            val before = old[hz]
            if (before == after) continue
            val beforeLevel = before?.value?.voltageLevel
            val voltage = if (beforeLevel == null) "新增档位 ${after.value.voltageLevel}"
                else if (beforeLevel != after.value.voltageLevel) "电压档位 $beforeLevel → ${after.value.voltageLevel}" else ""
            val beforeBus = before?.value?.bus
            val afterBus = after.value.bus
            val bus = if (afterBus != null && beforeBus != afterBus) {
                val start = beforeBus?.let { "${it.minimum}/${it.target}/${it.maximum} → " }.orEmpty()
                "DDR $start${afterBus.minimum}/${afterBus.target}/${afterBus.maximum}"
            } else ""
            val beforeAcd = before?.value?.acdLevel
            val afterAcd = after.value.acdLevel
            val acd = if (beforeAcd != afterAcd) {
                val factory = image.factoryAcd(bin, hz)
                val context = if (factory != null && afterAcd == factory.code &&
                    after.value.voltageLevel != factory.voltageLevel) {
                    "（草稿电压档 ${after.value.voltageLevel}，原厂 ${factory.voltageLevel}）"
                } else ""
                "ACD ${acdLabel(beforeAcd)} → ${acdLabel(afterAcd)}$context"
            } else ""
            val info = listOf(voltage, bus, acd).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "频点来源已更改" }
            add("${mhz(hz)} MHz · $info")
        }
    }
    for ((id, target) in draft.tables) {
        val before = image.values(GpuDraft(), id)
        add(id.label)
        for ((i, value) in target.withIndex()) if (value != before[i]) {
            val clock = id == GpuTableId.GMU_CLOCKS
            val frequency = clock && i % 2 == 0
            val name = if (clock) "P${i / 2} " + if (frequency) "频率" else "档位" else "索引 $i"
            add(if (frequency) "$name ${mhz(before[i])} → ${mhz(value)} MHz" else "$name ${before[i]} → $value")
        }
    }
    if (isEmpty()) add("配置与当前源镜像一致")
}

private fun mhz(hz: Long): String = BigDecimal.valueOf(hz, 6).stripTrailingZeros().toPlainString()
private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
private fun acdLabel(value: Long?): String = when (value) {
    null -> "未配置"
    0L -> "关闭"
    else -> "0x" + value.toString(16).padStart(8, '0')
}

