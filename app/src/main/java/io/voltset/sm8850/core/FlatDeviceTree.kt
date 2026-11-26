package io.voltset.sm8850.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val FDT_MAGIC = 0xd00d_feed.toInt()
internal const val FDT_HEADER_SIZE = 40

internal fun ByteBuffer.u32(offset: Int): Long = getInt(offset).toLong() and 0xffff_ffffL

internal data class DtbProperty(val offset: Int, val length: Int)

internal class DtbNode(val name: String, val start: Int, val bodyStart: Int) {
    val properties = mutableMapOf<String, DtbProperty>()
    val children = mutableListOf<DtbNode>()
    var end: Int = bodyStart
}

internal data class DtbReplacement(val start: Int, val end: Int, val bytes: ByteArray)

internal class FlatDeviceTree(private val bytes: ByteArray, val start: Int, val size: Int) {
    private val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val structure = section(data.u32(start + 8), data.u32(start + 36))
    private val strings = section(data.u32(start + 12), data.u32(start + 32))

    init {
        require(data.u32(start + 20) >= 17 && data.u32(start + 24) <= 17) {
            "不支持此设备树版本"
        }
        require(structure.first % 4 == start % 4 &&
            (structure.last < strings.first || strings.last < structure.first)) {
            "设备树区段布局无效"
        }
    }

    fun enabledNodes(): List<DtbNode> = nodes(enabledOnly = true)

    private fun nodes(enabledOnly: Boolean): List<DtbNode> {
        val nodes = mutableListOf<DtbNode>()
        val pending = ArrayDeque<DtbNode>()
        pending.add(root())
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (enabledOnly && !enabled(node)) continue
            nodes.add(node)
            pending.addAll(node.children)
        }
        return nodes
    }

    // Property names can share a null-terminated suffix. Repack these strings only
    // when structural GPU edits would otherwise extend into the image trailer.
    fun compactPropertyNames(): ByteArray {
        require(start == 0 && size == bytes.size) { "设备树尺寸无效" }
        val names = nodes(enabledOnly = false).flatMap { it.properties.entries }
        val offsets = mutableMapOf<String, Int>()
        val packed = ByteArrayOutputStream()
        for (name in names.map { it.key }.distinct().sortedWith(compareByDescending<String> { it.length }.thenBy { it })) {
            if (name in offsets) continue
            val position = packed.size()
            packed.write(name.toByteArray(Charsets.US_ASCII))
            packed.write(0)
            for (i in name.indices) offsets.putIfAbsent(name.substring(i), position + i)
        }
        val originalSize = strings.count()
        val saved = (originalSize - packed.size()) and -4
        if (saved <= 0) return bytes

        val updated = bytes.copyOf()
        val original = ByteBuffer.wrap(updated)
        for ((name, property) in names) original.putInt(property.offset - 4, offsets.getValue(name))
        val stringEnd = strings.last + 1
        val result = ByteArrayOutputStream(bytes.size - saved)
        result.write(updated, 0, strings.first)
        result.write(packed.toByteArray())
        result.write(ByteArray(originalSize - packed.size() - saved))
        result.write(updated, stringEnd, updated.size - stringEnd)
        return result.toByteArray().also {
            val header = ByteBuffer.wrap(it)
            header.putInt(4, it.size)
            header.putInt(32, packed.size())
            for (offset in listOf(8, 16)) {
                val section = header.getInt(offset)
                if (section >= stringEnd) header.putInt(offset, section - saved)
            }
        }
    }

    fun enabled(node: DtbNode): Boolean {
        val status = node.properties["status"] ?: return true
        return terminatedString(status.offset, status.offset + status.length) in setOf("okay", "ok")
    }

    fun cells(property: DtbProperty): List<Long> =
        (0 until property.length step 4).map { data.u32(property.offset + it) }

    fun cell(node: DtbNode, name: String): DtbProperty? = node.properties[name]?.takeIf { it.length == 4 }

    private fun root(): DtbNode {
        val structureEnd = structure.last + 1
        val stringsEnd = strings.last + 1
        val stack = ArrayDeque<DtbNode>()
        val names = mutableMapOf<Long, String>()
        var root: DtbNode? = null
        var pos = structure.first
        fun padded(length: Int): Int = (length + 3) and -4

        while (structureEnd - pos >= 4) {
            val tokenStart = pos
            val token = data.getInt(pos)
            pos += 4
            when (token) {
                1 -> {
                    val name = terminatedString(pos, structureEnd)
                    pos += padded(name.length + 1)
                    require(pos <= structureEnd) { "设备树节点名称越界" }
                    val node = DtbNode(name, tokenStart, pos)
                    val parent = stack.lastOrNull()
                    if (parent == null) {
                        require(root == null && name.isEmpty()) { "设备树根节点无效" }
                        root = node
                    } else parent.children.add(node)
                    stack.addLast(node)
                }
                2 -> {
                    require(stack.isNotEmpty()) { "设备树节点结构无效" }
                    stack.removeLast().end = pos
                }
                3 -> {
                    require(structureEnd - pos >= 8 && stack.isNotEmpty()) { "设备树属性头越界" }
                    val length = data.u32(pos)
                    val nameOffset = data.u32(pos + 4)
                    pos += 8
                    require(length <= structureEnd - pos && nameOffset < stringsEnd - strings.first) {
                        "设备树属性范围无效"
                    }
                    val name = names.getOrPut(nameOffset) {
                        terminatedString(strings.first + nameOffset.toInt(), stringsEnd)
                    }
                    val previous = stack.last().properties.put(name, DtbProperty(pos, length.toInt()))
                    require(previous == null) { "设备树属性重复" }
                    pos += padded(length.toInt())
                    require(pos <= structureEnd) { "设备树属性边界无效" }
                }
                4 -> Unit
                9 -> {
                    require(stack.isEmpty() && pos == structureEnd) { "设备树结构未正确结束" }
                    return root ?: error("设备树为空")
                }
                else -> error("设备树标记无效")
            }
        }
        error("设备树结构不完整")
    }

    private fun section(offset: Long, length: Long): IntRange {
        require(offset >= FDT_HEADER_SIZE && length > 0 && offset + length <= size) { "设备树区段越界" }
        return (start + offset.toInt()) until (start + offset.toInt() + length.toInt())
    }

    private fun terminatedString(offset: Int, limit: Int): String {
        var end = offset
        while (end < limit && bytes[end] != 0.toByte()) end++
        require(end < limit) { "设备树字符串缺少结束符" }
        return String(bytes, offset, end - offset, Charsets.US_ASCII)
    }
}
