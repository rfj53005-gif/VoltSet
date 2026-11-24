package io.voltset.sm8850

import android.content.Context
import android.util.Base64
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.CdtInspector
import io.voltset.sm8850.core.CompatibilityChecker
import io.voltset.sm8850.core.CompatibilityInput
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.OverrideWriteResult
import io.voltset.sm8850.core.PdpCdbData
import io.voltset.sm8850.core.PdpCdbParser
import io.voltset.sm8850.core.ScmiPlanParser
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class RootBackend(private val context: Context) {
    private val bundle: File by lazy {
        File(context.filesDir, "backend").also { dir ->
            installAssets("backend", dir)
        }
    }

    private fun installAssets(assetPath: String, directory: File) {
        if (directory.isFile) {
            check(directory.delete()) { "无法清理同名旧文件: $assetPath" }
        }
        check(directory.isDirectory || directory.mkdirs()) {
            "无法创建 Root 后端目录: $assetPath"
        }

        val assets = context.assets
        val names = checkNotNull(assets.list(assetPath)).toSet()
        directory.listFiles()?.forEach { existing ->
            if (existing.name !in names) {
                existing.deleteRecursively()
            }
        }

        for (name in names) {
            val childAsset = "$assetPath/$name"
            val destination = File(directory, name)
            val subEntries = assets.list(childAsset)
            if (!subEntries.isNullOrEmpty()) {
                installAssets(childAsset, destination)
                continue
            }
            val tmp = File.createTempFile("voltset-", ".tmp", directory)
            try {
                assets.open(childAsset).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                check(tmp.renameTo(destination)) { "无法部署 Root 后端文件: $childAsset" }
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun readStatus(): DeviceSnapshot {
        val output = execute(listOf("status"))
        return parseStatusOutput(output)
    }

    suspend fun applySet(
        bootId: String,
        cpussIndex: Int,
        globalIndex: Int,
    ): OverrideWriteResult {
        val output = execute(
            listOf(
                "set_override",
                bootId,
                cpussIndex.toString(),
                globalIndex.toString(),
            ),
        )
        val kv = parseKeyValues(output)
        return OverrideWriteResult(
            bootId = kv["BOOT_ID"] ?: bootId,
            bootCpussIndex = kv["BOOT_CPUSS_IDX"]?.toIntOrNull()
                ?: error("未返回当前启动 Set 索引"),
            sdamCpussIndex = kv["SDAM_CPUSS_IDX"]?.toIntOrNull()
                ?: error("未返回写后 SDAM Set 索引"),
            sdamGlobalIndex = kv["SDAM_GLOBAL_IDX"]?.toIntOrNull()
                ?: error("未返回写后 Global Set 索引"),
            reg7151Hex = kv["REG_7151"]?.takeIf { it != "unavailable" },
            writtenDecimal = kv["WRITTEN_DEC"]?.toIntOrNull() ?: ((cpussIndex shl 4) or globalIndex),
        )
    }

    suspend fun saveBaseline(bootId: String): String {
        val output = execute(listOf("save_baseline", bootId))
        return extractBlock(output, "BASELINE_BEGIN", "BASELINE_END")
            ?: error("未能取回已保存的原厂基线内容")
    }

    suspend fun reboot(bootId: String) {
        execute(listOf("reboot", bootId))
    }

    private suspend fun execute(args: List<String>): String = withContext(Dispatchers.IO) {
        val command = listOf("/system/bin/sh", File(bundle, "voltset.sh").path, bundle.path) + args
        val quoted = command.joinToString(" ") { shellQuote(it) }
        val process = try {
            ProcessBuilder("su", "-c", quoted)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw IllegalStateException("无法启动 Root 权限请求，请确认设备已 Root 并授权", e)
        }

        try {
            process.outputStream.close()
            val output = runInterruptible {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val exitCode = runInterruptible { process.waitFor() }
            if (exitCode != 0) {
                val errors = output.lineSequence()
                    .filter { it.startsWith("ERROR ") }
                    .map { it.removePrefix("ERROR ").trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                val msg = errors.joinToString("\n").ifBlank {
                    output.trim().takeLast(600).ifBlank {
                        "Root 后端执行失败 (exit=$exitCode)，请检查 KernelSU/Magisk 授权"
                    }
                }
                error(msg)
            }
            output
        } finally {
            runCatching { process.outputStream.close() }
        }
    }

    private fun parseStatusOutput(output: String): DeviceSnapshot {
        val kv = parseKeyValues(output)
        val bootId = kv["BOOT_ID"] ?: error("后端未返回启动 ID")
        val socModel = kv["SOC_MODEL"] ?: "unknown"
        val deviceModel = kv["DEVICE_MODEL"] ?: "unknown"
        val deviceName = kv["DEVICE_NAME"] ?: "unknown"
        val slotSuffix = kv["SLOT"] ?: ""
        val kernelRelease = kv["KERNEL"] ?: "unknown"
        val pageSizeBytes = kv["PAGE_SIZE"]?.toIntOrNull()
        val scmiModuleAsset = kv["SCMI_MODULE_ASSET"]?.takeIf { it != "none" && it.isNotBlank() }
        val isSm8850 = socModel.uppercase().startsWith("SM8850")

        val adjAvailable = kv["ADJ_AVAILABLE"] == "1"
        val adjSetNodePresent = kv["ADJ_SET_NODE"] == "1"
        val debugfsPath = kv["DEBUGFS"]?.takeIf { it != "none" }
        val adjError = kv["ADJ_ERROR"]
        val bootCpussIndex = kv["BOOT_CPUSS_IDX"]?.toIntOrNull()
        val sdamCpussIndex = kv["SDAM_CPUSS_IDX"]?.toIntOrNull()
        val sdamGlobalIndex = kv["SDAM_GLOBAL_IDX"]?.toIntOrNull()
        val reg7151Hex = kv["REG_7151"]?.takeIf { it.isNotBlank() && it != "unavailable" }
        val pdpCdbPath = kv["PDP_CDB_PATH"]?.takeIf { it != "none" }

        var pdpCdb: PdpCdbData? = null
        var pdpCdbError = kv["PDP_CDB_ERROR"]
        val pdpB64 = extractBlock(output, "PDP_CDB_B64_BEGIN", "PDP_CDB_B64_END")
        if (pdpB64 != null) {
            runCatching {
                val bytes = Base64.decode(pdpB64, Base64.DEFAULT)
                PdpCdbParser.parse(bytes)
            }.onSuccess {
                pdpCdb = it
            }.onFailure { err ->
                pdpCdbError = err.message ?: "pdp_cdb 解析失败"
            }
        } else if (kv["PDP_CDB_PATH"] == "none" && pdpCdbError == null) {
            pdpCdbError = "未找到 pdp_cdb 分区"
        }

        val cdtBytes = extractBlock(output, "CDT_B64_BEGIN", "CDT_B64_END")?.let { b64 ->
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        }
        val cdtStatus = CdtInspector.inspect(cdtBytes)

        val scmiError = if (kv["SCMI_STATUS"] == "ok") null else kv["SCMI_ERROR"] ?: "读取 SCMI 失败"
        val scmiCsv = extractBlock(output, "SCMI_BEGIN", "SCMI_END")
        val scmiPoints = if (scmiCsv != null) {
            ScmiPlanParser.parseCsv(scmiCsv, isSm8850)
        } else {
            emptyList()
        }

        val baselineSource = when (kv["BASELINE_SOURCE"]) {
            "voltset" -> BaselineSource.VOLTSET
            "suntuner" -> BaselineSource.SUNTUNER
            else -> BaselineSource.NONE
        }
        val baselineCsv = extractBlock(output, "BASELINE_BEGIN", "BASELINE_END")
        val baselinePoints = baselineCsv?.let { ScmiPlanParser.parseCsv(it, isSm8850) }
            ?.takeIf { it.isNotEmpty() }

        val activeSet = bootCpussIndex?.let { id -> pdpCdb?.sets?.firstOrNull { it.setId == id } }
        val diffRows = ScmiPlanParser.diff(scmiPoints, baselinePoints, activeSet)

        val compatibility = CompatibilityChecker.evaluate(
            CompatibilityInput(
                socModel = socModel,
                isSm8850 = isSm8850,
                pageSizeBytes = pageSizeBytes,
                debugfsPath = debugfsPath,
                adjAvailable = adjAvailable,
                adjSetNodePresent = adjSetNodePresent,
                adjError = adjError,
                sdamCpussIndex = sdamCpussIndex,
                sdamGlobalIndex = sdamGlobalIndex,
                reg7151Hex = reg7151Hex,
                pdpCdbPath = pdpCdbPath,
                pdpCdb = pdpCdb,
                pdpCdbError = pdpCdbError,
                cdtStatus = cdtStatus,
                scmiModuleAsset = scmiModuleAsset,
                scmiError = scmiError,
                scmiPoints = scmiPoints,
            ),
        )

        return DeviceSnapshot(
            bootId = bootId,
            socModel = socModel,
            deviceModel = deviceModel,
            deviceName = deviceName,
            slotSuffix = slotSuffix,
            kernelRelease = kernelRelease,
            pageSizeBytes = pageSizeBytes,
            isSm8850 = isSm8850,
            debugfsPath = debugfsPath,
            adjAvailable = adjAvailable,
            adjSetNodePresent = adjSetNodePresent,
            adjError = adjError,
            bootCpussIndex = bootCpussIndex,
            sdamCpussIndex = sdamCpussIndex,
            sdamGlobalIndex = sdamGlobalIndex,
            reg7151Hex = reg7151Hex,
            pdpCdbPath = pdpCdbPath,
            pdpCdb = pdpCdb,
            pdpCdbError = pdpCdbError,
            cdtPath = kv["CDT_PATH"]?.takeIf { it != "none" },
            cdtStatus = cdtStatus,
            scmiModuleAsset = scmiModuleAsset,
            scmiError = scmiError,
            scmiPoints = scmiPoints,
            baselineSource = baselineSource,
            baselinePoints = baselinePoints,
            voltageDiffRows = diffRows,
            compatibility = compatibility,
        )
    }

    private fun parseKeyValues(output: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var insideBlock = false
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.endsWith("_BEGIN")) {
                insideBlock = true
                continue
            }
            if (line.endsWith("_END")) {
                insideBlock = false
                continue
            }
            if (insideBlock) continue
            val eq = line.indexOf('=')
            if (eq > 0) {
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                result[key] = value
            }
        }
        return result
    }

    private fun extractBlock(output: String, beginMarker: String, endMarker: String): String? {
        val lines = output.lineSequence().map { it.trimEnd('\r') }.toList()
        val startIdx = lines.indexOf(beginMarker)
        if (startIdx < 0) return null
        val endIdx = lines.drop(startIdx + 1).indexOf(endMarker)
        if (endIdx < 0) return null
        return lines.subList(startIdx + 1, startIdx + 1 + endIdx).joinToString("\n")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
