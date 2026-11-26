package io.voltset.sm8850.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal data class GpuLevelKey(val bin: Int, val level: Int)
internal data class GpuBus(val minimum: Long, val target: Long, val maximum: Long)
internal data class GpuAcdCalibration(val code: Long, val voltageLevel: Long)
internal data class GpuLevelValue(
    val frequencyHz: Long,
    val voltageLevel: Long,
    val bus: GpuBus? = null,
    val acdLevel: Long? = null,
)
internal data class GpuRow(val template: GpuLevelKey, val value: GpuLevelValue)

internal data class GpuLevel(
    val key: GpuLevelKey,
    val original: GpuLevelValue,
    val frequencyOffset: Int,
    val voltageOffset: Int,
    val regOffset: Int,
    val busOffsets: List<Int>,
    val acdOffset: Int?,
    val node: DtbNode,
)

internal data class GpuBin(val index: Int, val speedBin: Long?, val levels: List<GpuLevel>, val node: DtbNode)
internal enum class GpuTableId(val label: String) {
    DDR("GPU DDR 带宽"), CNOC("GPU CNOC 带宽"), GMU_CLOCKS("GMU 频率与档位"), GMU_DDR("GMU DDR 带宽"),
}
internal data class GpuAuxTable(val id: GpuTableId, val original: List<Long>, val offset: Int)

internal data class GpuDraft(
    val bins: Map<Int, List<GpuRow>> = emptyMap(),
    val tables: Map<GpuTableId, List<Long>> = emptyMap(),
) {
    val isEmpty: Boolean get() = bins.isEmpty() && tables.isEmpty()
}

internal data class VendorBootLayout(
    val imageSize: Long,
    val dtbOffset: Long,
    val dtbSize: Int,
    val pageSize: Long,
    val followingSize: Long,
) {
    fun aligned(bytes: Long): Long = (bytes + pageSize - 1) / pageSize * pageSize
    val followingOffset: Long get() = dtbOffset + aligned(dtbSize.toLong())
}

internal class GpuVendorBoot private constructor(
    val layout: VendorBootLayout,
    val dtbIndex: Int,
    val bins: List<GpuBin>,
    val tables: List<GpuAuxTable>,
    private val headerHash: ByteArray,
    private val dtbHash: ByteArray,
    private val treeStart: Int,
    private val treeSize: Int,
    private val structure: IntRange,
    private val acdReference: Map<Long, Map<Long, GpuAcdCalibration>>,
) {
    private val levelsByKey = bins.flatMap { it.levels }.associateBy { it.key }
    private val baseRows = bins.associate { bin -> bin.index to bin.levels.map { GpuRow(it.key, it.original) } }
    private val baseTables = tables.associateBy { it.id }
    val frequencies: List<Long> = levelsByKey.values.map { it.original.frequencyHz }.filter { it > 0 }.distinct().sortedDescending()
    val voltageLevels: List<Long> = levelsByKey.values.map { it.original.voltageLevel }.filter { it > 0 }.distinct().sortedDescending()
    val busIndices: List<Long> = baseTables[GpuTableId.DDR]?.let { it.original.indices.map(Int::toLong) }.orEmpty()
    private val largestBin = bins.maxOf { it.levels.count { level -> level.original.frequencyHz > 0 } }

    fun sameSource(other: GpuVendorBoot): Boolean =
        layout == other.layout && dtbIndex == other.dtbIndex &&
            headerHash.contentEquals(other.headerHash) && dtbHash.contentEquals(other.dtbHash)

    fun matchRuntimeBin(frequencies: Set<Long>): Int? = bins.singleOrNull { bin ->
        frequencies.isNotEmpty() && frequencies == bin.levels
            .map { it.original.frequencyHz }.filter { it > 0 }.toSet()
    }?.index

    fun rows(draft: GpuDraft, bin: Int): List<GpuRow> = draft.bins[bin] ?: baseRows.getValue(bin)
    fun values(draft: GpuDraft, id: GpuTableId): List<Long> = draft.tables[id] ?: baseTables.getValue(id).original

    fun changeRows(draft: GpuDraft, bin: Int, rows: List<GpuRow>): GpuDraft {
        val next = if (rows == baseRows.getValue(bin)) draft.bins - bin else draft.bins + (bin to rows)
        return draft.copy(bins = next).also(::validate)
    }

    fun changeTable(draft: GpuDraft, id: GpuTableId, values: List<Long>): GpuDraft {
        val next = if (values == baseTables.getValue(id).original) draft.tables - id
        else draft.tables + (id to values)
        return draft.copy(tables = next).also(::validate)
    }

    fun frequencyChoices(rows: List<GpuRow>, index: Int): List<Long> {
        val before = rows.take(index).lastOrNull { it.value.frequencyHz > 0 }?.value?.frequencyHz ?: Long.MAX_VALUE
        val after = rows.drop(index + 1).firstOrNull { it.value.frequencyHz > 0 }?.value?.frequencyHz ?: 0
        return frequencies.filter { it < before && it > after }
    }

    fun voltageChoices(rows: List<GpuRow>, index: Int): List<Long> {
        val before = rows.take(index).lastOrNull { it.value.frequencyHz > 0 }?.value?.voltageLevel ?: Long.MAX_VALUE
        val after = rows.drop(index + 1).firstOrNull { it.value.frequencyHz > 0 }?.value?.voltageLevel ?: 0
        return voltageLevels.filter { it in after..before }
    }

    fun spareFrequencies(rows: List<GpuRow>): List<Long> =
        frequencies.filter { frequency -> rows.none { it.value.frequencyHz == frequency } }

    fun canInsert(bin: GpuBin, rows: List<GpuRow>): Boolean =
        canReindex(bin) && rows.count { it.value.frequencyHz > 0 } < largestBin && spareFrequencies(rows).isNotEmpty()

    fun canReindex(bin: GpuBin): Boolean {
        val indices = bin.levels.map { it.key.level }
        val ordered = bin.levels.map { it.node }.sortedBy { it.start }
        return indices == indices.indices.toList() && ordered.zipWithNext().all { (a, b) -> a.end == b.start } &&
            ordered.all(::canClone) && bin.node.properties.keys.none {
                it.endsWith("pwrlevel") && it !in POWER_LEVEL_REFERENCES
            }
    }

    fun template(bin: Int, originalFrequency: Long): GpuLevelKey? = baseRows[bin]
        ?.singleOrNull { it.value.frequencyHz == originalFrequency }?.template

    fun templateFrequency(key: GpuLevelKey): Long = levelsByKey.getValue(key).original.frequencyHz
    fun baseValue(key: GpuLevelKey): GpuLevelValue = levelsByKey.getValue(key).original

    fun factoryAcd(bin: Int, frequencyHz: Long): GpuAcdCalibration? =
        bins.firstOrNull { it.index == bin }?.speedBin?.let { acdReference[it]?.get(frequencyHz) }

    fun acdChoices(bin: Int, template: GpuLevelKey, frequencyHz: Long): List<Long> {
        if (levelsByKey.getValue(template).acdOffset == null) return emptyList()
        return listOfNotNull(0L, sourceAcd(bin, frequencyHz), factoryAcd(bin, frequencyHz)?.code).distinct()
    }

    fun carryAcd(bin: Int, row: GpuRow): GpuRow {
        val value = row.value
        val options = acdChoices(bin, row.template, value.frequencyHz)
        val carried = value.acdLevel?.takeIf { it in options }
            ?: sourceAcd(bin, value.frequencyHz)?.takeIf { it in options } ?: options.firstOrNull()
        return row.copy(value = value.copy(acdLevel = carried))
    }

    fun restoreFactoryAcd(draft: GpuDraft, bin: Int): GpuDraft {
        val rows = rows(draft, bin).map { row ->
            val factory = row.value.acdLevel?.let { factoryAcd(bin, row.value.frequencyHz) }
            if (factory == null) row else row.copy(value = row.value.copy(acdLevel = factory.code))
        }
        return changeRows(draft, bin, rows)
    }

    private fun sourceAcd(bin: Int, frequencyHz: Long): Long? =
        baseRows.getValue(bin).singleOrNull { it.value.frequencyHz == frequencyHz }?.value?.acdLevel

    fun tableChoices(id: GpuTableId, current: List<Long>, index: Int): List<Long> {
        val base = baseTables.getValue(id).original
        val choices = if (id == GpuTableId.GMU_CLOCKS) {
            base.filterIndexed { i, _ -> i % 2 == index % 2 }.distinct()
        } else base.distinct()
        return choices.filter { candidate ->
            val updated = current.toMutableList().apply { this[index] = candidate }
            runCatching { checkTable(id, updated) }.isSuccess
        }.sorted()
    }

    fun validate(draft: GpuDraft) {
        for ((index, rows) in draft.bins) {
            val bin = bins.singleOrNull { it.index == index } ?: error("配置引用了不存在的 GPU 分档")
            checkRows(bin, rows)
        }
        draft.tables.forEach { (id, values) -> checkTable(id, values) }
    }

    private fun checkRows(bin: GpuBin, rows: List<GpuRow>) {
        val original = baseRows.getValue(bin.index)
        val reindex = rows.map { it.template } != original.map { it.template }
        require(!reindex || canReindex(bin)) { "此分档的节点布局不支持增删或复制" }
        val active = rows.filter { it.value.frequencyHz > 0 }
        require(active.isNotEmpty() && active.size <= largestBin) { "频点数量须在 1 到 $largestBin 之间" }
        require(active.zipWithNext().all { (a, b) -> a.value.frequencyHz > b.value.frequencyHz }) {
            "频点须按从高到低排列且不能重复"
        }
        require(active.zipWithNext().all { (a, b) ->
            a.value.voltageLevel >= b.value.voltageLevel ||
                original.zipWithNext().any { (before, after) ->
                    a.matchesSourceApartFromAcd(before) && b.matchesSourceApartFromAcd(after)
                }
        }) { "更高频点的电压档位不能低于下一频点" }
        val originalIdle = original.filter { it.value.frequencyHz == 0L }
        require(rows.filter { it.value.frequencyHz == 0L } == originalIdle &&
            (originalIdle.isEmpty() || rows.takeLast(originalIdle.size) == originalIdle)) {
            "休眠频点须保留在表尾"
        }
        for (row in rows) {
            val template = levelsByKey[row.template] ?: error("配置引用了不存在的频点模板")
            val value = row.value
            if (reindex) require(canClone(template.node)) { "此频点包含无法复制的设备树引用" }
            require(value.frequencyHz == template.original.frequencyHz || value.frequencyHz in frequencies) {
                "频率必须选自本机镜像已有的 GPU 频点"
            }
            require(value.voltageLevel == template.original.voltageLevel || value.voltageLevel in voltageLevels) {
                "电压必须选自本机镜像已有的 GPU 电压档位"
            }
            val acd = acdChoices(bin.index, row.template, value.frequencyHz)
            require(if (acd.isEmpty()) value.acdLevel == null else value.acdLevel in acd) {
                "ACD 必须匹配当前芯片分档和频率"
            }
            val bus = value.bus
            require((bus != null) == (template.original.bus != null)) { "配置的总线字段与源镜像不匹配" }
            if (bus != null && bus != template.original.bus) {
                require(listOf(bus.minimum, bus.target, bus.maximum).all { it in busIndices } &&
                    bus.minimum <= bus.target && bus.target <= bus.maximum) {
                    "带宽索引须在 DDR 表内，且最低值 ≤ 目标值 ≤ 最高值"
                }
            }
        }
    }

    private fun GpuRow.matchesSourceApartFromAcd(source: GpuRow): Boolean =
        copy(value = value.copy(acdLevel = source.value.acdLevel)) == source

    private fun checkTable(id: GpuTableId, values: List<Long>) {
        val base = baseTables[id]?.original ?: error("本机镜像没有此调参表")
        require(values.size == base.size) { "调参表长度必须与本机一致" }
        if (id == GpuTableId.GMU_CLOCKS) {
            val originalPairs = base.chunked(2)
            val pairs = values.chunked(2)
            require(pairs.all { p -> originalPairs.any { it[0] == p[0] } && originalPairs.any { it[1] == p[1] } } &&
                pairs.zipWithNext().all { (a, b) ->
                    a[0] < b[0] && (a[1] <= b[1] ||
                        originalPairs.zipWithNext().any { (before, after) -> a == before && b == after })
                }) {
                "GMU 须使用本机档位，且频率递增时档位不能下降"
            }
        } else {
            require(values.indices.all { (base[it] == 0L) == (values[it] == 0L) && values[it] in base }) {
                "带宽须取自本机同一张表，并保留零值位置"
            }
            if (id != GpuTableId.GMU_DDR) {
                require(values.zipWithNext().all { (a, b) -> a <= b }) { "带宽表须按非递减顺序排列" }
            }
        }
    }

    // All writes target an app-private copy. Match both immutable regions before applying the draft.
    fun patchCopy(copy: RandomAccessFile, draft: GpuDraft) {
        validate(draft)
        require(!draft.isEmpty) { "没有可导出的改动" }
        require(copy.length() == layout.imageSize) { "镜像大小已变化，请重新读取" }
        copy.seek(0)
        val header = ByteArray(HEADER_BYTES).also(copy::readFully)
        require(headerHash.contentEquals(sha256(header))) { "镜像头已变化，请重新读取" }
        copy.seek(layout.dtbOffset)
        val region = ByteArray(layout.dtbSize).also(copy::readFully)
        require(dtbHash.contentEquals(sha256(region))) { "设备树已变化，请重新读取" }
        val patches = replacements(region, draft).sortedBy { it.start }
        require(patches.all { it.start in structure && it.end <= structure.last + 1 } &&
            patches.zipWithNext().all { (a, b) -> a.end <= b.start }) { "GPU 编辑区域重叠或越界" }

        if (patches.all { it.end - it.start == it.bytes.size }) {
            for (patch in patches) {
                copy.seek(layout.dtbOffset + patch.start)
                copy.write(patch.bytes)
            }
        } else {
            rewriteTree(copy, region, patches)
        }
        verifyCopy(copy, draft)
    }

    private fun verifyCopy(copy: RandomAccessFile, draft: GpuDraft) {
        copy.seek(0)
        val header = ByteArray(HEADER_BYTES).also(copy::readFully)
        val updated = layout(header, copy.length())
        copy.seek(updated.dtbOffset)
        val region = ByteArray(updated.dtbSize).also(copy::readFully)
        val result = read(header, copy.length(), region, dtbIndex, acdReference)
        require(result.bins.map { it.index to it.speedBin } == bins.map { it.index to it.speedBin } &&
            result.tables.map { it.id } == tables.map { it.id } &&
            result.bins.all { bin ->
                bin.levels.map { it.original } == rows(draft, bin.index).map { it.value }
            } &&
            result.tables.all { it.original == values(draft, it.id) }) { "导出镜像回读与草稿不一致" }
    }

    private fun replacements(region: ByteArray, draft: GpuDraft): List<DtbReplacement> = buildList {
        for ((index, rows) in draft.bins) {
            val bin = bins.first { it.index == index }
            if (rows.map { it.template } == baseRows.getValue(index).map { it.template }) {
                for (row in rows) addAll(editCells(levelsByKey.getValue(row.template), row.value))
                continue
            }
            val section = ByteArrayOutputStream()
            for ((newIndex, row) in rows.withIndex()) {
                val level = levelsByKey.getValue(row.template)
                val body = region.copyOfRange(level.node.bodyStart, level.node.end)
                val buf = ByteBuffer.wrap(body)
                buf.putInt(level.regOffset - level.node.bodyStart, newIndex)
                for (patch in editCells(level, row.value)) {
                    patch.bytes.copyInto(body, patch.start - level.node.bodyStart)
                }
                val name = ("qcom,gpu-pwrlevel@" + newIndex.toString(16)).toByteArray(Charsets.US_ASCII)
                val stream = DataOutputStream(section)
                stream.writeInt(1)
                stream.write(name)
                stream.write(ByteArray(4 - name.size % 4))
                section.write(body)
            }
            add(DtbReplacement(
                bin.levels.minOf { it.node.start }, bin.levels.maxOf { it.node.end }, section.toByteArray(),
            ))
            val originalData = ByteBuffer.wrap(region)
            for (reference in POWER_LEVEL_REFERENCES) {
                val prop = bin.node.properties[reference] ?: continue
                require(prop.length == 4) { "GPU 分档引用格式无效" }
                val oldIndex = originalData.getInt(prop.offset)
                val oldFrequency = bin.levels.firstOrNull { it.key.level == oldIndex }?.original?.frequencyHz
                    ?: error("源 GPU 分档引用无效")
                val next = rows.indexOfFirst { it.value.frequencyHz <= oldFrequency && it.value.frequencyHz > 0 }
                    .takeIf { it >= 0 } ?: rows.indexOfLast { it.value.frequencyHz > 0 }
                if (oldIndex != next) add(cellPatch(prop.offset, next.toLong()))
            }
        }
        for ((id, values) in draft.tables) {
            val table = baseTables.getValue(id)
            values.forEachIndexed { i, value ->
                if (value != table.original[i]) add(cellPatch(table.offset + i * 4, value))
            }
        }
    }

    private fun editCells(level: GpuLevel, value: GpuLevelValue): List<DtbReplacement> = buildList {
        if (level.original.frequencyHz != value.frequencyHz) add(cellPatch(level.frequencyOffset, value.frequencyHz))
        if (level.original.voltageLevel != value.voltageLevel) add(cellPatch(level.voltageOffset, value.voltageLevel))
        if (level.original.acdLevel != value.acdLevel) {
            add(cellPatch(checkNotNull(level.acdOffset), checkNotNull(value.acdLevel)))
        }
        if (value.bus != null && value.bus != level.original.bus) {
            val before = checkNotNull(level.original.bus)
            listOf(before.minimum to value.bus.minimum, before.target to value.bus.target, before.maximum to value.bus.maximum)
                .forEachIndexed { i, (old, new) ->
                    if (new != old) add(cellPatch(level.busOffsets[i], new))
                }
        }
    }

    private fun rewriteTree(copy: RandomAccessFile, region: ByteArray, patches: List<DtbReplacement>) {
        val output = ByteArrayOutputStream(treeSize)
        var cursor = treeStart
        for (patch in patches) {
            output.write(region, cursor, patch.start - cursor)
            output.write(patch.bytes)
            cursor = patch.end
        }
        output.write(region, cursor, treeStart + treeSize - cursor)
        var tree = output.toByteArray()
        val delta = tree.size - treeSize
        var total = region.size + delta
        require(total.toLong() in FDT_HEADER_SIZE.toLong()..MAX_DTB_BYTES) { "新设备树尺寸无效" }
        val fdtHeader = ByteBuffer.wrap(tree)
        fdtHeader.putInt(4, tree.size)
        fdtHeader.putInt(36, structure.count() + delta)
        for (offset in listOf(12, 16)) {
            val section = fdtHeader.getInt(offset)
            if (treeStart + section > structure.last) fdtHeader.putInt(offset, section + delta)
        }

        val originalEnd = layout.followingOffset + layout.followingSize
        fun canFit(end: Long): Boolean {
            if (end > layout.imageSize) return false
            if (end <= originalEnd) return true
            copy.seek(originalEnd)
            val extra = ByteArray((end - originalEnd).toInt()).also(copy::readFully)
            return extra.all { it == 0.toByte() }
        }
        fun tailOffset(size: Int) = layout.dtbOffset + layout.aligned(size.toLong())
        if (!canFit(tailOffset(total) + layout.followingSize)) {
            tree = FlatDeviceTree(tree, 0, tree.size).compactPropertyNames()
            total = region.size + tree.size - treeSize
        }
        val nextTail = tailOffset(total)
        val nextEnd = nextTail + layout.followingSize
        require(canFit(nextEnd)) { "设备树预留空间不足，新增频点会覆盖镜像尾部数据" }
        // The vendor ramdisk table and bootconfig follow the padded DTB section.
        // Move them as a byte-for-byte block if a page boundary changes.
        val tail = if (nextTail != layout.followingOffset) {
            require(layout.followingSize <= MAX_DTB_BYTES) { "镜像尾部分区过大，无法安全移动" }
            copy.seek(layout.followingOffset)
            ByteArray(layout.followingSize.toInt()).also(copy::readFully)
        } else null

        copy.seek(layout.dtbOffset)
        copy.write(region, 0, treeStart)
        copy.write(tree)
        copy.write(region, treeStart + treeSize, region.size - treeStart - treeSize)
        copy.write(ByteArray((nextTail - copy.filePointer).toInt()))
        if (tail != null) copy.write(tail)
        if (nextEnd < originalEnd) {
            // Only zero space previously occupied by these sections. The rest of the partition stays intact.
            copy.seek(nextEnd)
            copy.write(ByteArray((originalEnd - nextEnd).toInt()))
        }
        copy.seek(2100)
        copy.writeInt(Integer.reverseBytes(total))
    }

    private fun canClone(node: DtbNode): Boolean =
        node.children.isEmpty() && "phandle" !in node.properties && "linux,phandle" !in node.properties

    private fun cellPatch(offset: Int, value: Long): DtbReplacement =
        DtbReplacement(offset, offset + 4, ByteBuffer.allocate(4).putInt(value.toInt()).array())

    companion object {
        const val HEADER_BYTES = 2128
        private const val MAX_DTB_BYTES = 32 * 1024 * 1024L
        private val POWER_LEVEL_REFERENCES = setOf("qcom,initial-pwrlevel", "qcom,min-pwrlevel", "qcom,max-pwrlevel")
        private val BUS_FIELDS = listOf("qcom,bus-min", "qcom,bus-freq", "qcom,bus-max")

        fun layout(header: ByteArray, imageSize: Long): VendorBootLayout {
            require(header.size == HEADER_BYTES && header.decodeToString(0, 8) == "VNDRBOOT") {
                "无法识别 vendor_boot 镜像头"
            }
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.getInt(8)
            val pageSize = buffer.u32(12)
            require(version in 3..4 && pageSize in 4096..65536 && pageSize and (pageSize - 1) == 0L) {
                "不支持此 vendor_boot 格式"
            }
            fun aligned(bytes: Long) = (bytes + pageSize - 1) / pageSize * pageSize
            val headerSize = buffer.u32(2096)
            val dtbSize = buffer.u32(2100)
            require(headerSize >= (if (version == 4) HEADER_BYTES else 2112) &&
                dtbSize in FDT_HEADER_SIZE.toLong()..MAX_DTB_BYTES) { "设备树区域大小无效" }
            val dtbOffset = aligned(headerSize) + aligned(buffer.u32(24))
            val tail = if (version == 4) aligned(buffer.u32(2112)) + aligned(buffer.u32(2124)) else 0L
            require(dtbOffset + aligned(dtbSize) + tail <= imageSize) { "vendor_boot 镜像不完整" }
            return VendorBootLayout(imageSize, dtbOffset, dtbSize.toInt(), pageSize, tail)
        }

        fun read(
            header: ByteArray,
            imageSize: Long,
            region: ByteArray,
            dtbIndex: Int,
            acdReference: Map<Long, Map<Long, GpuAcdCalibration>> = emptyMap(),
        ): GpuVendorBoot {
            val layout = layout(header, imageSize)
            require(region.size == layout.dtbSize && dtbIndex >= 0) { "设备树区域或索引无效" }
            val buffer = ByteBuffer.wrap(region)
            var cursor = 0
            var index = 0
            while (cursor < region.size) {
                if (region[cursor] == 0.toByte()) {
                    cursor++
                    continue
                }
                require(region.size - cursor >= FDT_HEADER_SIZE && buffer.getInt(cursor) == FDT_MAGIC) {
                    "不支持此设备树封装"
                }
                val size = buffer.u32(cursor + 4)
                require(size in FDT_HEADER_SIZE.toLong()..(region.size - cursor).toLong()) { "设备树长度无效" }
                if (index == dtbIndex) {
                    val tree = FlatDeviceTree(region, cursor, size.toInt())
                    val nodes = tree.enabledNodes()
                    val gpu = nodes.singleOrNull { it.name.substringBefore('@') == "qcom,kgsl-3d0" }
                        ?: error("当前设备树缺少唯一的 Adreno GPU 节点")
                    val gmu = nodes.singleOrNull { it.name.substringBefore('@') == "qcom,gmu" }
                    return GpuVendorBoot(layout, index, readBins(tree, gpu), readTables(tree, gpu, gmu),
                        sha256(header), sha256(region), cursor, size.toInt(), tree.structure, acdReference)
                }
                cursor += size.toInt()
                index++
            }
            error("当前 DTB 索引不在 vendor_boot 镜像内")
        }

        private fun readBins(tree: FlatDeviceTree, gpu: DtbNode): List<GpuBin> {
            val containers = gpu.children.filter { it.name == "qcom,gpu-pwrlevel-bins" && tree.enabled(it) }
            require(containers.size <= 1) { "GPU 档位表结构存在歧义" }
            val bins = (containers.singleOrNull() ?: gpu).children.mapNotNull { node ->
                if (!tree.enabled(node)) return@mapNotNull null
                val bin = when {
                    node.name == "qcom,gpu-pwrlevels" -> 0
                    node.name.startsWith("qcom,gpu-pwrlevels-") -> node.name.removePrefix("qcom,gpu-pwrlevels-").toIntOrNull()
                    else -> null
                } ?: return@mapNotNull null
                require(bin >= 0) { "GPU 分档索引无效" }
                val levels = node.children.filter { it.name.startsWith("qcom,gpu-pwrlevel@") && tree.enabled(it) }
                    .map { point ->
                        fun field(name: String) = tree.cell(point, name)
                            ?: error("${point.name} 缺少有效的 $name")
                        val reg = field("reg")
                        val index = tree.cells(reg).single()
                        require(index <= Int.MAX_VALUE) { "GPU 频点索引无效" }
                        val frequency = field("qcom,gpu-freq")
                        val voltage = field("qcom,level")
                        val acd = tree.cell(point, "qcom,acd-level")
                        val buses = BUS_FIELDS.mapNotNull { tree.cell(point, it) }.takeIf { it.size == 3 }.orEmpty()
                        val bus = if (buses.isEmpty()) null else {
                            val (min, target, max) = buses.map { tree.cells(it).single() }
                            GpuBus(min, target, max)
                        }
                        GpuLevel(GpuLevelKey(bin, index.toInt()),
                            GpuLevelValue(tree.cells(frequency).single(), tree.cells(voltage).single(), bus,
                                acd?.let { tree.cells(it).single() }),
                            frequency.offset, voltage.offset, reg.offset, buses.map { it.offset }, acd?.offset, point)
                    }.sortedBy { it.key.level }
                require(levels.isNotEmpty() && levels.map { it.key.level }.distinct().size == levels.size) {
                    "GPU 频点表为空或索引重复"
                }
                GpuBin(bin, tree.cell(node, "qcom,speed-bin")?.let { tree.cells(it).single() }, levels, node)
            }.sortedBy { it.index }
            require(bins.isNotEmpty() && bins.map { it.index }.distinct().size == bins.size) {
                "当前设备树缺少可编辑的 GPU 档位"
            }
            return bins
        }

        private fun readTables(tree: FlatDeviceTree, gpu: DtbNode, gmu: DtbNode?): List<GpuAuxTable> = buildList {
            fun addTable(node: DtbNode?, name: String, id: GpuTableId, alignment: Int = 4) {
                val property = node?.properties?.get(name) ?: return
                if (property.length > 0 && property.length % alignment == 0) {
                    add(GpuAuxTable(id, tree.cells(property), property.offset))
                }
            }
            addTable(gpu, "qcom,bus-table-ddr", GpuTableId.DDR)
            addTable(gpu, "qcom,bus-table-cnoc", GpuTableId.CNOC)
            addTable(gmu, "qcom,gmu-freq-table", GpuTableId.GMU_CLOCKS, alignment = 8)
            addTable(gmu, "qcom,gmu-perf-ddr-bw", GpuTableId.GMU_DDR)
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

