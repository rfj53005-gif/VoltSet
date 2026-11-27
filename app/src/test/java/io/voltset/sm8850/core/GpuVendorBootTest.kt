package io.voltset.sm8850.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GpuVendorBootTest {
    @Test
    fun patchesOnlySelectedTreeAndChangedCells() {
        val fixture = vendorBoot(tree(900_000_000), tree(1_100_000_000))
        val table = fixture.read(index = 1)
        assertEquals(0, table.matchRuntimeBin(setOf(1_100_000_000L, 700_000_000L)))
        val point = table.bins.first().levels.first()
        val edit = replace(table, GpuLevelValue(1_075_000_000, 196))

        val file = File.createTempFile("gpu-image", ".img")
        try {
            file.writeBytes(fixture.bytes)
            RandomAccessFile(file, "rw").use { table.patchCopy(it, edit) }
            val expected = fixture.bytes.copyOf()
            val values = ByteBuffer.wrap(expected)
            values.putInt(fixture.dtbOffset + point.frequencyOffset, 1_075_000_000)
            values.putInt(fixture.dtbOffset + point.voltageOffset, 196)
            assertArrayEquals(expected, file.readBytes())
            assertEquals(1_100_000_000L, fixture.read(1).bins.first().levels.first().original.frequencyHz)
            assertEquals(900_000_000L, fixture.read(0).bins.first().levels.first().original.frequencyHz)
        } finally {
            file.delete()
        }
    }

    @Test
    fun staleDtbIsRejectedBeforePatch() {
        val fixture = vendorBoot(tree(900_000_000), tree(1_100_000_000))
        val table = fixture.read(index = 1)
        val point = table.bins.first().levels.first()
        val stale = fixture.bytes.copyOf()
        stale[fixture.dtbOffset + point.voltageOffset + 3] = 1
        val file = File.createTempFile("gpu-stale", ".img")
        try {
            file.writeBytes(stale)
            assertThrows(IllegalArgumentException::class.java) {
                RandomAccessFile(file, "rw").use {
                    table.patchCopy(it, replace(table, point.original.copy(voltageLevel = 196)))
                }
            }
            assertArrayEquals(stale, file.readBytes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsCollisionsAndOutOfRangeDtbIndex() {
        val fixture = vendorBoot(tree(900_000_000))
        val table = fixture.read(index = 0)
        val point = table.bins.first().levels.first()
        assertThrows(IllegalArgumentException::class.java) {
            replace(table, point.original.copy(frequencyHz = 700_000_000))
        }
        assertThrows(IllegalStateException::class.java) { fixture.read(index = 1) }
    }

    @Test
    fun rejectsUnrecognizedRatesAndVoltageLevels() {
        val table = vendorBoot(tree(1_100_000_000)).read(0)
        assertThrows(IllegalArgumentException::class.java) {
            replace(table, GpuLevelValue(1_074_000_001, 196))
        }
        assertThrows(IllegalArgumentException::class.java) {
            replace(table, GpuLevelValue(1_075_000_000, 195))
        }
    }

    @Test
    fun appliesAcdOnlyToVerifiedFrequenciesWithExistingFields() {
        val top = 1_100_000_000
        val factoryCode = 0x88295ffdL
        val otherBinCode = 0xc02c5ffdL
        val fixture = vendorBoot(tree(top, mapOf(
            (0 to top) to 0,
            (0 to 700_000_000) to 0,
            (1 to 700_000_000) to otherBinCode.toInt(),
        )))
        val references = mapOf(
            221L to mapOf(top.toLong() to GpuAcdCalibration(factoryCode, 212L)),
            222L to mapOf(500_000_000L to GpuAcdCalibration(otherBinCode, 172L)),
        )
        val table = fixture.read(0, references)
        val original = table.rows(GpuDraft(), 0)
        val high = original.first()
        val low = original.last()
        val missing = table.rows(GpuDraft(), 1).last()
        assertEquals(listOf(0L, factoryCode), table.acdChoices(0, high.template, top.toLong()))
        assertEquals(listOf(0L), table.acdChoices(0, low.template, low.value.frequencyHz))
        assertEquals(emptyList<Long>(), table.acdChoices(1, missing.template, missing.value.frequencyHz))
        assertThrows(IllegalArgumentException::class.java) {
            table.changeRows(GpuDraft(), 0, listOf(high, low.copy(value = low.value.copy(acdLevel = otherBinCode))))
        }
        assertEquals(0L, table.carryAcd(0, high.copy(
            value = high.value.copy(frequencyHz = 1_075_000_000L, acdLevel = factoryCode),
        )).value.acdLevel)

        val edit = table.restoreFactoryAcd(GpuDraft(), 0)
        assertEquals(listOf(factoryCode, 0L), table.rows(edit, 0).map { it.value.acdLevel })
        assertEquals(GpuDraft(), table.restoreFactoryAcd(GpuDraft(), 1))
        val expected = fixture.bytes.copyOf()
        val acdOffset = checkNotNull(table.bins.first().levels.first().acdOffset)
        ByteBuffer.wrap(expected).putInt(fixture.dtbOffset + acdOffset, factoryCode.toInt())
        val copy = File.createTempFile("gpu-acd", ".img")
        try {
            copy.writeBytes(fixture.bytes)
            RandomAccessFile(copy, "rw").use { table.patchCopy(it, edit) }
            assertArrayEquals(expected, copy.readBytes())
        } finally {
            copy.delete()
        }
    }

    @Test
    fun insertsAndRemovesLevelsWithCleanIndices() {
        val fixture = vendorBoot(tree(900_000_000), tree(1_100_000_000))
        val table = fixture.read(1)
        val original = table.rows(GpuDraft(), 0)
        val extra = table.rows(GpuDraft(), 1).last()
        val draft = table.changeRows(GpuDraft(), 0, original + extra)
        val file = File.createTempFile("gpu-structure", ".img")
        try {
            file.writeBytes(fixture.bytes)
            RandomAccessFile(file, "rw").use { table.patchCopy(it, draft) }
            fun read(index: Int): GpuVendorBoot {
                val bytes = file.readBytes()
                val header = bytes.copyOfRange(0, GpuVendorBoot.HEADER_BYTES)
                val layout = GpuVendorBoot.layout(header, bytes.size.toLong())
                val offset = layout.dtbOffset.toInt()
                return GpuVendorBoot.read(header, bytes.size.toLong(), bytes.copyOfRange(offset, offset + layout.dtbSize), index)
            }
            val expanded = read(1)
            assertEquals(listOf(0, 1, 2), expanded.bins.first().levels.map { it.key.level })
            assertEquals(listOf(1_100_000_000L, 700_000_000L, 500_000_000L), expanded.bins.first().levels.map { it.original.frequencyHz })
            assertEquals(900_000_000L, read(0).bins.first().levels.first().original.frequencyHz)
            val rows = expanded.rows(GpuDraft(), 0)
            val removed = expanded.changeRows(GpuDraft(), 0, listOf(rows[0], rows[2]))
            RandomAccessFile(file, "rw").use { expanded.patchCopy(it, removed) }
            assertEquals(listOf(0, 1), read(1).bins.first().levels.map { it.key.level })
        } finally {
            file.delete()
        }
    }

    @Test
    fun packsOnlySelectedTreeToAvoidTouchingImageTrailer() {
        val regular = tree(1_100_000_000)
        val padded = regular.copyOf(4096 - 12)
        ByteBuffer.wrap(padded).apply {
            putInt(4, padded.size)
            putInt(32, getInt(32) + padded.size - regular.size)
        }
        val fixture = vendorBoot(padded)
        val image = fixture.read(0)
        val draft = image.changeRows(GpuDraft(), 0, image.rows(GpuDraft(), 0) + image.rows(GpuDraft(), 1).last())
        val file = File.createTempFile("gpu-trailer", ".img")
        try {
            file.writeBytes(fixture.bytes)
            RandomAccessFile(file, "rw").use { image.patchCopy(it, draft) }
            val newBytes = file.readBytes()
            val trailer = fixture.dtbOffset + 4096
            assertArrayEquals(fixture.bytes.copyOfRange(trailer, fixture.bytes.size), newBytes.copyOfRange(trailer, newBytes.size))
        } finally {
            file.delete()
        }
    }

    @Test
    fun shiftBinVoltageShiftsAllOrHighFreqsClampsBoundsAndPreservesMonotonicity() {
        val image = vendorBoot(tree(900_000_000)).read(0)
        // Sorted voltageLevels ladder across fixture bins: [172, 184, 188, 196, 200]
        // Bin 1 initial levels: 1075MHz->196, 700MHz->184, 500MHz->172
        val shiftedBin1 = image.shiftBinVoltage(GpuDraft(), bin = 1, stepDelta = -1)
        assertEquals(
            listOf(188L, 172L, 172L),
            image.rows(shiftedBin1, 1).map { it.value.voltageLevel },
        )
        // Non-target bin 0 remains completely untouched.
        assertEquals(
            listOf(200L, 188L),
            image.rows(shiftedBin1, 0).map { it.value.voltageLevel },
        )

        // High-frequency only shift (minFreqHz = 1_000_000_000) lowers 1075MHz without violating monotonicity.
        val highOnly = image.shiftBinVoltage(
            GpuDraft(),
            bin = 1,
            stepDelta = -3,
            minFreqHz = 1_000_000_000L,
        )
        val highRows = image.rows(highOnly, 1).map { it.value.voltageLevel }
        // 196 -> -3 steps -> 172, clamped >= next lower row (184) so top becomes 184; lower freqs stay [184, 172].
        assertEquals(listOf(184L, 184L, 172L), highRows)

        // Extreme negative shift clamps cleanly at lowest ladder level without errors.
        val clampedBottom = image.shiftBinVoltage(GpuDraft(), bin = 1, stepDelta = -50)
        assertEquals(listOf(172L, 172L, 172L), image.rows(clampedBottom, 1).map { it.value.voltageLevel })
    }

    private fun replace(table: GpuVendorBoot, top: GpuLevelValue): GpuDraft {
        val rows = table.rows(GpuDraft(), 0)
        return table.changeRows(GpuDraft(), 0, listOf(rows[0].copy(value = top)) + rows.drop(1))
    }

    private data class Fixture(val bytes: ByteArray, val dtbOffset: Int, val dtbSize: Int) {
        fun read(index: Int, references: Map<Long, Map<Long, GpuAcdCalibration>> = emptyMap()) = GpuVendorBoot.read(
            bytes.copyOfRange(0, GpuVendorBoot.HEADER_BYTES),
            bytes.size.toLong(),
            bytes.copyOfRange(dtbOffset, dtbOffset + dtbSize),
            index,
            references,
        )
    }

    private fun vendorBoot(vararg trees: ByteArray): Fixture {
        val dtbs = ByteArrayOutputStream()
        trees.forEachIndexed { index, tree ->
            if (index > 0) dtbs.write(ByteArray(8))
            dtbs.write(tree)
        }
        val region = dtbs.toByteArray()
        val page = 4096
        val dtbOffset = page * 2
        val image = ByteArray(dtbOffset + page * 2) { 0x55 }
        ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("VNDRBOOT".toByteArray())
            putInt(8, 4)
            putInt(12, page)
            putInt(24, 73)
            putInt(2096, GpuVendorBoot.HEADER_BYTES)
            putInt(2100, region.size)
            putInt(2112, 0)
            putInt(2124, 0)
        }
        region.copyInto(image, dtbOffset)
        return Fixture(image, dtbOffset, region.size)
    }

    private fun tree(topFrequency: Int, acd: Map<Pair<Int, Int>, Int> = emptyMap()): ByteArray {
        val structure = ByteArrayOutputStream()
        val strings = ByteArrayOutputStream()
        val offsets = mutableMapOf<String, Int>()
        val data = DataOutputStream(structure)
        fun node(name: String, children: () -> Unit) {
            data.writeInt(1)
            val bytes = name.toByteArray() + byteArrayOf(0)
            data.write(bytes)
            data.write(ByteArray((4 - bytes.size % 4) % 4))
            children()
            data.writeInt(2)
        }
        fun cell(name: String, value: Int) {
            val offset = offsets.getOrPut(name) {
                strings.size().also { strings.write(name.toByteArray() + byteArrayOf(0)) }
            }
            data.writeInt(3)
            data.writeInt(4)
            data.writeInt(offset)
            data.writeInt(value)
        }
        node("") {
            node("soc") {
                node("qcom,kgsl-3d0@3d00000") {
                    node("qcom,gpu-pwrlevel-bins") {
                        for (bin in 0..1) {
                            node("qcom,gpu-pwrlevels-$bin") {
                                cell("qcom,speed-bin", 221 + bin)
                                cell("qcom,initial-pwrlevel", 1)
                                val rates = if (bin == 0) listOf(topFrequency, 700_000_000)
                                else listOf(1_075_000_000, 700_000_000, 500_000_000)
                                for ((index, frequency) in rates.withIndex()) {
                                    node("qcom,gpu-pwrlevel@$index") {
                                        cell("reg", index)
                                        cell("qcom,gpu-freq", frequency)
                                        cell("qcom,level", (if (bin == 0) 200 else 196) - index * 12)
                                        acd[bin to frequency]?.let { cell("qcom,acd-level", it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        data.writeInt(9)
        val structureOffset = 56
        val stringsOffset = structureOffset + structure.size()
        val image = ByteBuffer.allocate(stringsOffset + strings.size()).apply {
            putInt(0xd00d_feed.toInt())
            putInt(capacity())
            putInt(structureOffset)
            putInt(stringsOffset)
            putInt(40) // Empty memory reservation block.
            putInt(17)
            putInt(16)
            putInt(0)
            putInt(strings.size())
            putInt(structure.size())
            position(structureOffset)
            put(structure.toByteArray())
            put(strings.toByteArray())
        }
        return image.array()
    }
}
