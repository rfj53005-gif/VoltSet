package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * Verifies exact parity between the Kotlin `io.voltset.sm8850.core` parsers/analyzers
 * and `ksu-module/webroot/core.js` against the shared `shared-fixtures/status-golden.txt`
 * and `shared-fixtures/expected-contract.json`.
 */
class SharedContractParityTest {

    @Test
    fun kotlinCoreMatchesSharedGoldenContract() {
        val fixturesDir = resolveFixturesDir()
        val rawStatus = File(fixturesDir, "status-golden.txt").readText()
        @Suppress("UNCHECKED_CAST")
        val contract = SimpleJson.parse(File(fixturesDir, "expected-contract.json").readText()) as Map<String, Any?>

        val snapshot = parseGoldenSnapshotPureJvm(rawStatus)

        assertEquals(contract["pdpCdbSha256"], snapshot.pdpCdbSha256)
        assertEquals(contract["bootId"], snapshot.bootId)
        assertEquals(contract["socModel"], snapshot.socModel)
        assertEquals(contract["deviceModel"], snapshot.deviceModel)
        assertEquals((contract["overrideSetId"] as Number?)?.toInt(), snapshot.pdpCdb?.overrideSetId)
        assertEquals((contract["activeSetId"] as Number?)?.toInt(), snapshot.bootCpussIndex)
        assertEquals(contract["cdtState"], snapshot.cdtStatus.state.name)
        assertEquals((contract["cdtVersion"] as Number?)?.toInt(), snapshot.cdtStatus.version)
        assertEquals(contract["cdtKey7Enabled"], snapshot.cdtStatus.key7Enabled)
        assertEquals(contract["compatibilityLevel"], snapshot.compatibility.level.name)
        assertEquals((contract["passedCount"] as Number).toInt(), snapshot.compatibility.passedCount)
        assertEquals((contract["totalCount"] as Number).toInt(), snapshot.compatibility.totalCount)
        assertEquals((contract["totalDcvsTablesFound"] as Number).toInt(), snapshot.pdpHardwareLimits?.totalDcvsTablesFound)
        assertEquals((contract["clus0FreqCount"] as Number).toInt(), snapshot.pdpHardwareLimits?.clus0?.limitsByFreqKhz?.size)
        assertEquals((contract["clus1FreqCount"] as Number).toInt(), snapshot.pdpHardwareLimits?.clus1?.limitsByFreqKhz?.size)
        assertEquals((contract["scmiPointsCount"] as Number).toInt(), snapshot.scmiPoints.size)
        assertEquals((contract["baselinePointsCount"] as Number).toInt(), snapshot.baselinePoints?.size)
        assertEquals((contract["qualityScore"] as Number?)?.toInt(), snapshot.qualityReport?.score)
        assertEquals(contract["bootReason"], snapshot.systemProbe.bootReason)
        assertEquals(contract["pstoreCrash"], snapshot.systemProbe.pstoreCrash)
        assertEquals(contract["isAbnormalBoot"], snapshot.systemProbe.isAbnormalBoot)
        assertEquals(contract["hasPstoreCrash"], snapshot.systemProbe.hasPstoreCrash)
        assertEquals((contract["qptRailsCount"] as Number).toInt(), snapshot.systemProbe.qptRails.size)

        @Suppress("UNCHECKED_CAST")
        val expectedSets = contract["sets"] as List<Map<String, Any?>>
        val actualSets = snapshot.pdpCdb!!.sets
        assertEquals(expectedSets.size, actualSets.size)
        for (expected in expectedSets) {
            val setId = (expected["setId"] as Number).toInt()
            val profile = actualSets.first { it.setId == setId }
            val estimate = snapshot.setEstimates[setId]
            assertNotNull("Missing estimate for Set $setId", estimate)
            assertEquals(expected["tag"], profile.tag.name)
            assertEquals(expected["isActive"], setId == snapshot.bootCpussIndex)
            assertEquals((expected["clus0MinDeltaMv"] as Number?)?.toInt(), estimate!!.clus0?.minDeltaMv)
            assertEquals((expected["clus0MaxDeltaMv"] as Number?)?.toInt(), estimate.clus0?.maxDeltaMv)
            assertEquals((expected["clus1MinDeltaMv"] as Number?)?.toInt(), estimate.clus1?.minDeltaMv)
            assertEquals((expected["clus1MaxDeltaMv"] as Number?)?.toInt(), estimate.clus1?.maxDeltaMv)
        }

        val report = CompatibilityChecker.formatTextReport(snapshot, "0.6.0")
        assertTrue(report.contains("[pdp 硬件限位与本机预计范围]"))
        assertTrue(report.contains("启动原因 (boot_reason): reboot,userrequested"))
        assertTrue(report.contains("内核崩溃记录 (pstore): none"))
    }

    private fun resolveFixturesDir(): File {
        val candidates = listOf(
            File("shared-fixtures"),
            File("../shared-fixtures"),
            File("../../shared-fixtures"),
        )
        return candidates.firstOrNull { File(it, "status-golden.txt").isFile }
            ?: error("Unable to locate shared-fixtures/status-golden.txt from ${File(".").absolutePath}")
    }

    private fun parseGoldenSnapshotPureJvm(output: String): DeviceSnapshot {
        val values = mutableMapOf<String, String>()
        val blocks = mutableMapOf<String, MutableList<String>>()
        var currentBlock: String? = null
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (currentBlock != null) {
                if (line == "${currentBlock}_END") {
                    currentBlock = null
                } else {
                    blocks.getValue(currentBlock) += line
                }
                continue
            }
            if (line.endsWith("_BEGIN")) {
                val blockName = line.removeSuffix("_BEGIN")
                currentBlock = blockName
                blocks[blockName] = mutableListOf()
                continue
            }
            val eq = line.indexOf('=')
            if (eq > 0) {
                values[line.substring(0, eq)] = line.substring(eq + 1)
            }
        }

        fun decodeBlock(gzKey: String, b64Key: String): ByteArray? {
            val gzText = blocks[gzKey]?.joinToString("")?.trim()?.takeIf { it.isNotEmpty() }
            if (gzText != null) {
                val compressed = Base64.getDecoder().decode(gzText)
                return GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            }
            val b64Text = blocks[b64Key]?.joinToString("")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Base64.getDecoder().decode(b64Text)
        }

        val socModel = values["SOC_MODEL"].orEmpty()
        val isSm8850 = socModel.equals("SM8850", ignoreCase = true)
        val pdpCdbBytes = decodeBlock("PDP_CDB_GZB64", "PDP_CDB_B64")!!
        val computedSha256 = MessageDigest.getInstance("SHA-256")
            .digest(pdpCdbBytes)
            .joinToString("") { "%02x".format(it) }
        val reportedSha = AutoRestoreParser.parsePdpCdbFingerprint(values["PDP_CDB_SHA256"]) ?: computedSha256
        val pdpCdb = PdpCdbParser.parse(pdpCdbBytes)
        val cdtBytes = decodeBlock("CDT_GZB64", "CDT_B64")
        val cdtStatus = CdtInspector.inspect(cdtBytes)

        val firstSdam = values["FIRST_SDAM_IDX"]?.toIntOrNull()?.takeIf { it in 0..7 }
        val prevTarget = values["PREVIOUS_TARGET_IDX"]?.toIntOrNull()?.takeIf { it in 1..7 }
        val stagedThis = values["STAGED_THIS_BOOT_IDX"]?.toIntOrNull()?.takeIf { it in 1..7 }
        val firmwareOverride = pdpCdb.overrideSetId?.takeIf { it in 1..7 }
        val bootSet = BootSetStatus(
            firstSdamIndex = firstSdam,
            previousTargetIndex = prevTarget,
            stagedThisBootIndex = stagedThis,
            firmwareIndex = firmwareOverride,
        )

        val initialScmi = ScmiPlanParser.parseCsv(blocks["SCMI"]?.joinToString("\n").orEmpty(), isSm8850)
        val recheckScmi = blocks["SCMI_RECHECK"]?.joinToString("\n")?.let { ScmiPlanParser.parseCsv(it, isSm8850) }
        val qualityReport = recheckScmi?.let { ScmiQualityReport.evaluate(initialScmi, it) }
        val scmiPoints = qualityReport?.points ?: initialScmi

        val baselineCsv = blocks["BASELINE"]?.joinToString("\n").orEmpty()
        val baselinePoints = ScmiPlanParser.parseCsv(
            csv = baselineCsv,
            isSm8850 = isSm8850,
            coefficients = qualityReport?.coefficients.orEmpty(),
        ).takeIf { it.isNotEmpty() }

        val pdpFwBytes = decodeBlock("PDP_FW_GZB64", "PDP_FW_B64")!!
        val limits = PdpFirmwareParser.matchDeviceLimits(
            tables = PdpFirmwareParser.parse(pdpFwBytes),
            baselinePoints = baselinePoints,
            livePoints = scmiPoints,
        )

        val adjDir = values["ADJ_DIR"]
        val regmapNode = values["REGMAP_NODE"]
        val systemProbe = SystemProbeParser.parse(
            probeBlock = blocks["PROBE"]?.joinToString("\n").orEmpty(),
            adjDirPath = adjDir,
            regmapNode = regmapNode,
        )
        val adjBackend = when (values["ADJ_BACKEND"]) {
            "debugfs" -> AdjBackend.DEBUGFS
            "nvmem" -> AdjBackend.NVMEM
            else -> AdjBackend.NONE
        }
        val scmiSource = when (values["SCMI_SOURCE"]) {
            "energy_model" -> ScmiDataSource.ENERGY_MODEL
            "module" -> ScmiDataSource.KERNEL_MODULE
            else -> ScmiDataSource.NONE
        }
        val compatibility = CompatibilityChecker.evaluate(
            CompatibilityInput(
                socModel = socModel,
                isSm8850 = isSm8850,
                pageSizeBytes = values["PAGE_SIZE"]?.toIntOrNull(),
                debugfsPath = values["DEBUGFS"],
                debugfsPrivateNs = values["DEBUGFS_PRIVATE_NS"] == "1",
                adjBackend = adjBackend,
                adjAvailable = values["ADJ_AVAILABLE"] == "1",
                adjSetNodePresent = values["ADJ_SET_NODE"] == "1",
                adjError = values["ADJ_ERROR"],
                sdamCpussIndex = values["SDAM_CPUSS_IDX"]?.toIntOrNull(),
                sdamGlobalIndex = values["SDAM_GLOBAL_IDX"]?.toIntOrNull(),
                reg7151Hex = values["REG_7151"],
                pdpCdbPath = values["PDP_CDB_PATH"],
                pdpCdb = pdpCdb,
                pdpCdbError = null,
                pdpFwPath = values["PDP_FW_PATH"],
                pdpHardwareLimits = limits,
                pdpFwError = null,
                cdtStatus = cdtStatus,
                scmiModuleAsset = values["SCMI_MODULE_ASSET"],
                scmiSource = scmiSource,
                scmiError = null,
                scmiPoints = scmiPoints,
                adjDirPath = adjDir,
                regmapNode = regmapNode,
            ),
        )
        val setEstimates = VoltageSetEstimator.estimateAll(pdpCdb.sets, baselinePoints, limits)
        val activeSet = bootSet.runningIndex?.let { id -> pdpCdb.sets.firstOrNull { it.setId == id } }
        val diffRows = ScmiPlanParser.diff(scmiPoints, baselinePoints, activeSet)

        assertNull(values["ADJ_ERROR"])
        assertFalse(systemProbe.isAbnormalBoot)

        return DeviceSnapshot(
            bootId = values.getValue("BOOT_ID"),
            socModel = socModel,
            deviceModel = values.getValue("DEVICE_MODEL"),
            deviceName = values.getValue("DEVICE_NAME"),
            slotSuffix = values.getValue("SLOT"),
            kernelRelease = values.getValue("KERNEL"),
            pageSizeBytes = values["PAGE_SIZE"]?.toIntOrNull(),
            isSm8850 = isSm8850,
            debugfsPath = values["DEBUGFS"],
            debugfsPrivateNs = values["DEBUGFS_PRIVATE_NS"] == "1",
            adjBackend = adjBackend,
            adjAvailable = values["ADJ_AVAILABLE"] == "1",
            adjSetNodePresent = values["ADJ_SET_NODE"] == "1",
            adjError = values["ADJ_ERROR"],
            bootSet = bootSet,
            sdamCpussIndex = values["SDAM_CPUSS_IDX"]?.toIntOrNull(),
            sdamGlobalIndex = values["SDAM_GLOBAL_IDX"]?.toIntOrNull(),
            reg7151Hex = values["REG_7151"],
            pdpCdbPath = values["PDP_CDB_PATH"],
            pdpCdbSha256 = reportedSha,
            pdpCdb = pdpCdb,
            pdpCdbError = null,
            pdpFwPath = values["PDP_FW_PATH"],
            pdpHardwareLimits = limits,
            pdpFwError = null,
            cdtPath = values["CDT_PATH"],
            cdtStatus = cdtStatus,
            scmiModuleAsset = values["SCMI_MODULE_ASSET"],
            scmiSource = scmiSource,
            scmiError = null,
            scmiPoints = scmiPoints,
            qualityReport = qualityReport,
            baselineSource = BaselineSource.VOLTSET,
            baselinePoints = baselinePoints,
            voltageDiffRows = diffRows,
            setEstimates = setEstimates,
            systemProbe = systemProbe,
            compatibility = compatibility,
        )
    }

    /** Minimal pure-JVM JSON parser to avoid Android framework `org.json` stubs. */
    private class SimpleJson private constructor(private val src: String) {
        private var pos = 0

        companion object {
            fun parse(src: String): Any? = SimpleJson(src).readValue()
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun readValue(): Any? {
            skipWs()
            return when (src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> {
                    pos += 4
                    null
                }
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                pos++ // ':'
                val value = readValue()
                map[key] = value
                skipWs()
                if (src[pos] == ',') {
                    pos++
                } else {
                    pos++ // '}'
                    break
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWs()
            if (src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list += readValue()
                skipWs()
                if (src[pos] == ',') {
                    pos++
                } else {
                    pos++ // ']'
                    break
                }
            }
            return list
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') break
                if (c == '\\') {
                    val esc = src[pos++]
                    sb.append(
                        when (esc) {
                            '"', '\\', '/' -> esc
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> esc
                        },
                    )
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun readBoolean(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4
                true
            } else {
                pos += 5
                false
            }
        }

        private fun readNumber(): Long {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            return src.substring(start, pos).toLong()
        }
    }
}
