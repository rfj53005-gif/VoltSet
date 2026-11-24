package io.voltset.sm8850.core

/** Parsed CFDB v3 directory entry inside a `pdp` or `pdp_cdb` container. */
internal data class CfdbEntry(
    val hash: UInt,
    val valueOrOffset: UInt,
    val type: Int,
    val size: Int,
)

/** Decoded CFDB v3 container located either at offset 0 or inside an ELF32 LE PT_LOAD segment. */
internal class CfdbDocument(
    private val bytes: ByteArray,
    val cfdbOffset: Int,
    val cfdbLength: Int,
    val entries: List<CfdbEntry>,
) {
    fun sliceBlob(entry: CfdbEntry): ByteArray {
        val rel = entry.valueOrOffset.toLong()
        require(entry.size >= 0 && rel >= 0 && rel + entry.size <= cfdbLength) {
            "CFDB Blob 越界 (rel=$rel, size=${entry.size}, cfdbLength=$cfdbLength)"
        }
        val start = cfdbOffset + rel.toInt()
        return bytes.copyOfRange(start, start + entry.size)
    }
}

internal object CfdbReader {
    const val CFDB_MAGIC: UInt = 0x0003DBCFu
    private const val CFDB_HEADER_SIZE = 16
    private const val CFDB_ENTRY_SIZE = 16

    fun parse(
        bytes: ByteArray,
        label: String,
        maxEntryCount: Int = 16_384,
    ): CfdbDocument {
        require(bytes.size >= CFDB_HEADER_SIZE) { "$label 数据长度不足 (${bytes.size} B)" }

        val (cfdbOffset, cfdbLength) = locateCfdb(bytes, label)
        require(cfdbOffset >= 0 && cfdbLength >= CFDB_HEADER_SIZE && cfdbOffset + cfdbLength <= bytes.size) {
            "CFDB 段边界异常 (offset=$cfdbOffset, len=$cfdbLength, total=${bytes.size})"
        }
        require(u32Le(bytes, cfdbOffset) == CFDB_MAGIC) {
            "缺少 CFDB v3 魔数"
        }

        val entryCount = u32Le(bytes, cfdbOffset + 4).toInt()
        require(entryCount in 0..maxEntryCount) { "CFDB 目录项数量异常 ($entryCount)" }
        val tableBytes = entryCount.toLong() * CFDB_ENTRY_SIZE.toLong()
        require(CFDB_HEADER_SIZE + tableBytes <= cfdbLength) {
            "CFDB 目录表超出段长度"
        }

        val entries = ArrayList<CfdbEntry>(entryCount)
        for (i in 0 until entryCount) {
            val base = cfdbOffset + CFDB_HEADER_SIZE + i * CFDB_ENTRY_SIZE
            val hash = u32Le(bytes, base)
            val valueOrOffset = u32Le(bytes, base + 4)
            val typeAndSize = u32Le(bytes, base + 8)
            entries += CfdbEntry(
                hash = hash,
                valueOrOffset = valueOrOffset,
                type = (typeAndSize and 0xFFu).toInt(),
                size = (typeAndSize shr 8).toInt(),
            )
        }

        return CfdbDocument(
            bytes = bytes,
            cfdbOffset = cfdbOffset,
            cfdbLength = cfdbLength,
            entries = entries,
        )
    }

    private fun locateCfdb(bytes: ByteArray, label: String): Pair<Int, Int> {
        if (u32Le(bytes, 0) == CFDB_MAGIC) {
            return 0 to bytes.size
        }

        require(
            bytes.size >= 52 &&
                bytes[0] == 0x7F.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[4] == 1.toByte() &&
                bytes[5] == 1.toByte(),
        ) {
            "无法识别的 $label 格式（非 ELF32 LE 且非 CFDB）"
        }

        val phoff = u32Le(bytes, 28).toLong()
        val phentsize = u16Le(bytes, 42)
        val phnum = u16Le(bytes, 44)
        require(phentsize >= 32 && phoff >= 0 && phoff + phnum.toLong() * phentsize <= bytes.size) {
            "ELF32 Program Header 越界"
        }

        for (i in 0 until phnum) {
            val ph = (phoff + i.toLong() * phentsize).toInt()
            val pType = u32Le(bytes, ph)
            if (pType != 1u) continue // PT_LOAD
            val offset = u32Le(bytes, ph + 4).toLong()
            val filesz = u32Le(bytes, ph + 16).toLong()
            if (offset < 0 || filesz < CFDB_HEADER_SIZE || offset + filesz > bytes.size) continue
            val offInt = offset.toInt()
            if (u32Le(bytes, offInt) == CFDB_MAGIC) {
                return offInt to filesz.toInt()
            }
        }
        throw IllegalArgumentException("ELF32 中未找到包含 CFDB v3 的 PT_LOAD 段")
    }

    fun u16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun i16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)

    fun u32Le(bytes: ByteArray, offset: Int): UInt =
        (bytes[offset].toUByte().toUInt()) or
            (bytes[offset + 1].toUByte().toUInt() shl 8) or
            (bytes[offset + 2].toUByte().toUInt() shl 16) or
            (bytes[offset + 3].toUByte().toUInt() shl 24)
}
