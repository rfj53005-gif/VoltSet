package io.voltset.sm8850

import android.content.Context
import android.util.Base64
import io.voltset.sm8850.core.AdjBackend
import io.voltset.sm8850.core.AutoRestoreConfig
import io.voltset.sm8850.core.AutoRestoreParser
import io.voltset.sm8850.core.AutoRestoreSnapshot
import io.voltset.sm8850.core.BaselineSource
import io.voltset.sm8850.core.BootSetStatus
import io.voltset.sm8850.core.CdtInspector
import io.voltset.sm8850.core.CompatibilityChecker
import io.voltset.sm8850.core.CompatibilityInput
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.OverrideWriteResult
import io.voltset.sm8850.core.PdpCdbData
import io.voltset.sm8850.core.PdpCdbParser
import io.voltset.sm8850.core.PdpFirmwareParser
import io.voltset.sm8850.core.PdpHardwareLimits
import io.voltset.sm8850.core.QptPowerParser
import io.voltset.sm8850.core.QptRawSample
import io.voltset.sm8850.core.SavedSetPowerRecord
import io.voltset.sm8850.core.ScmiDataSource
import io.voltset.sm8850.core.ScmiPlanParser
import io.voltset.sm8850.core.ScmiQualityReport
import io.voltset.sm8850.core.SystemProbeParser
import io.voltset.sm8850.core.VoltageSetEstimator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class RootBackend(private val context: Context) {
    private val bundle: File by lazy {
        File(context.filesDir, ASSET_ROOT).also { dir ->
            synchronized(bundleInstallLock) { installBundle(dir) }
        }
    }

    // Redeploy the bundle only when the APK identity or the deployed tree changed.
    private fun installBundle(directory: File) {
        val identity = installStamp()
        val stored = runCatching { File(directory, INSTALL_STAMP).readText().trimEnd() }.getOrNull()
        if (stored?.startsWith("$identity\n") == true && stored == bundleStamp(directory, identity)) return
        installAssets(ASSET_ROOT, directory)
        writeInstallStamp(directory, bundleStamp(directory, identity))
    }

    // Any reinstall changes lastUpdateTime, so stale binaries get replaced once.
    private fun installStamp(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "format=2\nversionCode=${info.longVersionCode}\nlastUpdateTime=${info.lastUpdateTime}"
    }

    // Validate the deployed tree without reopening or decompressing APK assets on a cache hit.
    private fun bundleStamp(directory: File, identity: String): String =
        directory.walkTopDown()
            .drop(1)
            .filter { it != File(directory, INSTALL_STAMP) }
            .map {
                val path = it.relativeTo(directory).invariantSeparatorsPath
                if (it.isDirectory) "dir $path" else "file $path ${it.length()} ${it.lastModified()}"
            }
            .sorted()
            .joinToString(separator = "\n", prefix = "$identity\n")

    private fun writeInstallStamp(directory: File, stamp: String) {
        val tmp = File.createTempFile("voltset-stamp-", ".tmp", directory)
        try {
            tmp.writeText(stamp + "\n")
            val target = File(directory, INSTALL_STAMP)
            check(tmp.renameTo(target)) { "无法写入 Root 后端安装戳" }
        } finally {
            tmp.delete()
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
        // Stale install stamp is not an asset, so the cleanup below drops it
        // before the copy and the new one is written afterwards.
        directory.listFiles()?.forEach { existing ->
            if (existing.name !in names) {
                check(existing.deleteRecursively()) { "无法清理旧后端文件: ${existing.name}" }
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
            if (destination.isDirectory) {
                check(destination.deleteRecursively()) { "无法清理同名旧目录: $childAsset" }
            }
            val tmp = File.createTempFile("voltset-", ".tmp", directory)
            try {
                assets.open(childAsset).use { input ->
                    val bytes = tmp.outputStream().use { output -> input.copyTo(output) }
                    check(bytes > 0) { "Root 后端文件为空: $childAsset" }
                }
                check(tmp.renameTo(destination)) { "无法部署 Root 后端文件: $childAsset" }
            } finally {
                tmp.delete()
            }
        }
    }

    suspend fun readStatus(): DeviceSnapshot = withContext(Dispatchers.IO) {
        parseStatusOutput(execute(listOf("status")))
    }

    suspend fun hasSameReadback(snapshot: DeviceSnapshot): Boolean {
        val current = BackendOutput(execute(listOf("quick_status"), 16 * 1024)).values
        return current["BOOT_ID"] == snapshot.bootId &&
            current["ADJ_BACKEND"] == snapshot.adjBackend.name.lowercase() &&
            current["ADJ_AVAILABLE"] == (if (snapshot.adjAvailable) "1" else "0") &&
            current["ADJ_SET_NODE"] == (if (snapshot.adjSetNodePresent) "1" else "0") &&
            current["SDAM_CPUSS_IDX"]?.toIntOrNull() == snapshot.sdamCpussIndex &&
            current["SDAM_GLOBAL_IDX"]?.toIntOrNull() == snapshot.sdamGlobalIndex
    }

    suspend fun applySet(
        bootId: String,
        cpussIndex: Int,
        expectedCpussIndex: Int,
        globalIndex: Int,
    ): OverrideWriteResult {
        val output = execute(
            listOf(
                "set_override",
                bootId,
                cpussIndex.toString(),
                expectedCpussIndex.toString(),
                globalIndex.toString(),
            ),
        )
        val kv = BackendOutput(output).values
        return OverrideWriteResult(
            sdamCpussIndex = kv["SDAM_CPUSS_IDX"]?.toIntOrNull()
                ?: error("未返回写后 SDAM Set 索引"),
            sdamGlobalIndex = kv["SDAM_GLOBAL_IDX"]?.toIntOrNull()
                ?: error("未返回写后 Global Set 索引"),
            reg7151Hex = kv["REG_7151"]?.takeIf { it != "unavailable" },
        )
    }

    suspend fun saveBaseline(bootId: String): String {
        val output = execute(listOf("save_baseline", bootId))
        return BackendOutput(output).blocks["BASELINE"]
            ?: error("未能取回已保存的原厂基线内容")
    }

    suspend fun reboot(bootId: String, expectedCpussIndex: Int, globalIndex: Int) {
        execute(listOf("reboot", bootId, expectedCpussIndex.toString(), globalIndex.toString()))
    }

    fun streamPowerSamples(): Flow<QptRawSample> = callbackFlow {
        val process = startRootProcess(backendArguments(listOf("power_stream"))) { redirectErrorStream(true) }
        try {
            launch {
                try {
                    var lastError: String? = null
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            val sample = QptPowerParser.parseStreamFrame(line)
                            if (sample != null) send(sample)
                            else if (line.startsWith("ERROR ")) lastError = line.removePrefix("ERROR ")
                        }
                    }
                    close(IllegalStateException(lastError ?: "QPT 采样已中断，请检查 Root 授权后重试"))
                } catch (error: IOException) {
                    close(error)
                }
            }
            awaitClose()
        } finally {
            withContext(NonCancellable) {
                // Closing stdin tells the sampler to restore the QPT state and exit.
                runCatching { process.outputStream.close() }
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroy()
                    if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun readGpuInfo(includeVoltageCorners: Boolean): String =
        execute(listOf("gpu_info", if (includeVoltageCorners) "1" else "0"), 16 * 1024)

    suspend fun readPowerRecords(): Map<Int, SavedSetPowerRecord> {
        val reply = BackendOutput(execute(listOf("read_power_records"), 64 * 1024))
        return QptPowerParser.deserializeRecords(reply.blocks["POWER_RECORDS"])
    }

    suspend fun writePowerRecords(records: Map<Int, SavedSetPowerRecord>) {
        val csv = QptPowerParser.serializeRecords(records)
        val b64 = if (csv.isEmpty()) "" else Base64.encodeToString(csv.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        execute(listOf("write_power_records", b64), 16 * 1024)
    }

    suspend fun readAutoRestore(): AutoRestoreSnapshot {
        val reply = BackendOutput(execute(listOf("read_auto_restore"), 32 * 1024))
        return AutoRestoreSnapshot(
            moduleInstalled = reply.values["KSU_MODULE_INSTALLED"] == "1",
            config = AutoRestoreParser.parseConfig(reply.blocks["AUTO_RESTORE_CONF"]),
            status = AutoRestoreParser.parseStatus(reply.blocks["AUTO_RESTORE_STATUS"]),
        )
    }

    suspend fun setAutoRestore(
        enabled: Boolean,
        targetSet: Int?,
        bootId: String?,
        pdpCdbSha256: String?,
    ): AutoRestoreConfig {
        val cleanTarget = targetSet?.takeIf { it in 2..7 }
        if (enabled) {
            val validBoot = requireNotNull(bootId) { "缺少启动 ID，请刷新后重试" }
            val validHash = AutoRestoreParser.parsePdpCdbFingerprint(pdpCdbSha256)
                ?: error("缺少有效的 pdp_cdb 指纹，请刷新后重试")
            val chosen = cleanTarget ?: error("目标档位必须为 Set 2..7")
            execute(listOf("set_auto_restore", "1", chosen.toString(), validBoot, validHash), 16 * 1024)
            return AutoRestoreConfig(enabled = true, targetSet = chosen, pdpCdbSha256 = validHash)
        } else {
            execute(listOf("set_auto_restore", "0", cleanTarget?.toString().orEmpty()), 16 * 1024)
            return AutoRestoreConfig(enabled = false, targetSet = cleanTarget, pdpCdbSha256 = null)
        }
    }

    private suspend fun execute(
        args: List<String>,
        maxOutputBytes: Int = MAX_BACKEND_OUTPUT_BYTES,
    ): String = withContext(Dispatchers.IO) {
        runRootCommand(context.cacheDir, backendArguments(args)) { outputFile, exitCode, _ ->
            check(outputFile.length() <= maxOutputBytes) { "Root 后端返回的数据超出预期" }
            val output = outputFile.readText()
            if (exitCode != 0) {
                val errors = output.lineSequence()
                    .filter { it.startsWith("ERROR ") }
                    .map { it.removePrefix("ERROR ").trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                val msg = errors.joinToString("\n").ifBlank {
                    val detail = output.trim().takeLast(400)
                    "Root 后端执行失败，请检查 KernelSU 或 Magisk 授权（$exitCode）" +
                        detail.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
                }
                error(msg)
            }
            output
        }
    }

    private fun backendArguments(args: List<String>): List<String> =
        listOf("/system/bin/sh", File(bundle, "voltset.sh").path, bundle.path) + args

    private fun parseStatusOutput(output: String): DeviceSnapshot {
        val reply = BackendOutput(output)
        val kv = reply.values
        val bootId = kv["BOOT_ID"] ?: error("后端未返回启动 ID")
        val socModel = kv["SOC_MODEL"] ?: "unknown"
        val deviceModel = kv["DEVICE_MODEL"] ?: "unknown"
        val deviceName = kv["DEVICE_NAME"] ?: "unknown"
        val slotSuffix = kv["SLOT"] ?: ""
        val kernelRelease = kv["KERNEL"] ?: "unknown"
        val pageSizeBytes = kv["PAGE_SIZE"]?.toIntOrNull()
        val scmiModuleAsset = kv["SCMI_MODULE_ASSET"]?.takeIf { it != "none" && it.isNotBlank() }
        val isSm8850 = socModel.uppercase().startsWith("SM8850")

        val adjBackend = when (kv["ADJ_BACKEND"]) {
            "debugfs" -> AdjBackend.DEBUGFS
            "nvmem" -> AdjBackend.NVMEM
            else -> AdjBackend.NONE
        }
        val adjAvailable = kv["ADJ_AVAILABLE"] == "1"
        val adjSetNodePresent = kv["ADJ_SET_NODE"] == "1"
        val debugfsPath = kv["DEBUGFS"]?.takeIf { it != "none" }
        val debugfsPrivateNs = kv["DEBUGFS_PRIVATE_NS"] == "1"
        val adjDirPath = kv["ADJ_DIR"]?.takeIf { it != "none" && it.isNotBlank() }
        val regmapNode = kv["REGMAP_NODE"]?.takeIf { it != "none" && it.isNotBlank() }
        val adjError = kv["ADJ_ERROR"]
        val sdamCpussIndex = kv["SDAM_CPUSS_IDX"]?.toIntOrNull()
        val sdamGlobalIndex = kv["SDAM_GLOBAL_IDX"]?.toIntOrNull()
        val reg7151Hex = kv["REG_7151"]?.takeIf { it.isNotBlank() && it != "unavailable" }
        val pdpCdbPath = kv["PDP_CDB_PATH"]?.takeIf { it != "none" }
        val pdpCdbSha256 = AutoRestoreParser.parsePdpCdbFingerprint(kv["PDP_CDB_SHA256"])

        var pdpCdb: PdpCdbData? = null
        var pdpCdbError = kv["PDP_CDB_ERROR"]
        if (reply.blocks["PDP_CDB_GZB64"] != null || reply.blocks["PDP_CDB_B64"] != null) {
            runCatching {
                val bytes = checkNotNull(decodeBlockBytes(reply, "PDP_CDB_GZB64", "PDP_CDB_B64"))
                PdpCdbParser.parse(bytes)
            }.onSuccess {
                pdpCdb = it
            }.onFailure { err ->
                pdpCdbError = err.message ?: "pdp_cdb 解析失败"
            }
        } else if (kv["PDP_CDB_PATH"] == "none" && pdpCdbError == null) {
            pdpCdbError = "未找到 pdp_cdb 分区"
        }

        val cdtBytes = reply.blocks["CDT_B64"]?.let { b64 ->
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
        }
        val cdtStatus = CdtInspector.inspect(cdtBytes)

        val bootSet = BootSetStatus(
            firstSdamIndex = kv["FIRST_SDAM_IDX"]?.toIntOrNull()?.takeIf { it in 0..7 },
            previousTargetIndex = kv["PREVIOUS_TARGET_IDX"]?.toIntOrNull()?.takeIf { it in 1..7 },
            stagedThisBootIndex = kv["STAGED_THIS_BOOT_IDX"]?.toIntOrNull()?.takeIf { it in 1..7 },
            firmwareIndex = pdpCdb?.overrideSetId?.takeIf { it in 1..7 },
        )
        val initialPoints = reply.blocks["SCMI"]?.let { ScmiPlanParser.parseCsv(it, isSm8850) }.orEmpty()
        val qualityReport = reply.blocks["SCMI_RECHECK"]?.let { csv ->
            ScmiQualityReport.evaluate(initialPoints, ScmiPlanParser.parseCsv(csv, isSm8850))
        }
        val scmiPoints = qualityReport?.points ?: initialPoints
        val scmiSource = when (kv["SCMI_SOURCE"]) {
            "energy_model" -> ScmiDataSource.ENERGY_MODEL
            "module" -> ScmiDataSource.KERNEL_MODULE
            else -> ScmiDataSource.NONE
        }
        val scmiError = when {
            kv["SCMI_STATUS"] != "ok" -> kv["SCMI_ERROR"]?.takeIf { it.isNotBlank() } ?: "读取 SCMI 失败"
            scmiPoints.isEmpty() -> "SCMI 快照没有可解析的 CPU 频点"
            else -> null
        }

        val baselinePoints = reply.blocks["BASELINE"]?.let {
            ScmiPlanParser.parseCsv(it, isSm8850, qualityReport?.coefficients.orEmpty())
        }?.takeIf { it.isNotEmpty() }
        val baselineSource = when (kv["BASELINE_SOURCE"].takeIf { baselinePoints != null }) {
            "voltset" -> BaselineSource.VOLTSET
            "suntuner" -> BaselineSource.SUNTUNER
            else -> BaselineSource.NONE
        }
        val activeSet = bootSet.runningIndex?.let { id -> pdpCdb?.sets?.firstOrNull { it.setId == id } }
        val diffRows = ScmiPlanParser.diff(scmiPoints, baselinePoints, activeSet)

        val pdpFwPath = kv["PDP_FW_PATH"]?.takeIf { it != "none" }
        var pdpFwError = kv["PDP_FW_ERROR"]
        var pdpHardwareLimits: PdpHardwareLimits? = null
        if (reply.blocks["PDP_FW_GZB64"] != null || reply.blocks["PDP_FW_B64"] != null) {
            runCatching {
                val bytes = checkNotNull(decodeBlockBytes(reply, "PDP_FW_GZB64", "PDP_FW_B64"))
                PdpFirmwareParser.parse(bytes)
            }.onSuccess { tables ->
                val matched = PdpFirmwareParser.matchDeviceLimits(tables, baselinePoints, scmiPoints)
                pdpHardwareLimits = matched
                if (!matched.isComplete && pdpFwError == null) {
                    pdpFwError = when {
                        baselinePoints.isNullOrEmpty() && scmiPoints.isEmpty() ->
                            "缺少 SCMI 频点列表，无法匹配对应体质档位的 pdp 硬件限位表"
                        matched.clus0 == null && matched.clus1 == null ->
                            "未能在 pdp（${tables.size} 张表）中唯一匹配到双簇频点对应的硬件限位"
                        matched.clus0 == null -> "未能在 pdp 中唯一匹配到小核 PDP0 硬件限位"
                        else -> "未能在 pdp 中唯一匹配到大核 PDP1 硬件限位"
                    }
                }
            }.onFailure { err ->
                pdpFwError = err.message ?: "pdp 固件表解析失败"
            }
        } else if (kv["PDP_FW_PATH"] == "none" && pdpFwError == null) {
            pdpFwError = "未找到 pdp 分区"
        }

        val setEstimates = VoltageSetEstimator.estimateAll(
            sets = pdpCdb?.sets.orEmpty(),
            baselinePoints = baselinePoints,
            limits = pdpHardwareLimits,
        )
        val systemProbe = SystemProbeParser.parse(
            probeBlock = reply.blocks["PROBE"],
            adjDirPath = adjDirPath,
            regmapNode = regmapNode,
        )

        val compatibility = CompatibilityChecker.evaluate(
            CompatibilityInput(
                socModel = socModel,
                isSm8850 = isSm8850,
                pageSizeBytes = pageSizeBytes,
                debugfsPath = debugfsPath,
                debugfsPrivateNs = debugfsPrivateNs,
                adjBackend = adjBackend,
                adjAvailable = adjAvailable,
                adjSetNodePresent = adjSetNodePresent,
                adjError = adjError,
                sdamCpussIndex = sdamCpussIndex,
                sdamGlobalIndex = sdamGlobalIndex,
                reg7151Hex = reg7151Hex,
                pdpCdbPath = pdpCdbPath,
                pdpCdb = pdpCdb,
                pdpCdbError = pdpCdbError,
                pdpFwPath = pdpFwPath,
                pdpHardwareLimits = pdpHardwareLimits,
                pdpFwError = pdpFwError,
                cdtStatus = cdtStatus,
                scmiModuleAsset = scmiModuleAsset,
                scmiSource = scmiSource,
                scmiError = scmiError,
                scmiPoints = scmiPoints,
                adjDirPath = adjDirPath,
                regmapNode = regmapNode,
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
            debugfsPrivateNs = debugfsPrivateNs,
            adjBackend = adjBackend,
            adjAvailable = adjAvailable,
            adjSetNodePresent = adjSetNodePresent,
            adjError = adjError,
            bootSet = bootSet,
            sdamCpussIndex = sdamCpussIndex,
            sdamGlobalIndex = sdamGlobalIndex,
            reg7151Hex = reg7151Hex,
            pdpCdbPath = pdpCdbPath,
            pdpCdbSha256 = pdpCdbSha256,
            pdpCdb = pdpCdb,
            pdpCdbError = pdpCdbError,
            pdpFwPath = pdpFwPath,
            pdpHardwareLimits = pdpHardwareLimits,
            pdpFwError = pdpFwError,
            cdtPath = kv["CDT_PATH"]?.takeIf { it != "none" },
            cdtStatus = cdtStatus,
            scmiModuleAsset = scmiModuleAsset,
            scmiSource = scmiSource,
            scmiError = scmiError,
            scmiPoints = scmiPoints,
            qualityReport = qualityReport,
            baselineSource = baselineSource,
            baselinePoints = baselinePoints,
            voltageDiffRows = diffRows,
            setEstimates = setEstimates,
            systemProbe = systemProbe,
            compatibility = compatibility,
        )
    }

    private class BackendOutput(output: String) {
        val values = mutableMapOf<String, String>()
        val blocks = mutableMapOf<String, String>()

        init {
            var block: String? = null
            val content = StringBuilder()
            for (line in output.lineSequence()) {
                val activeBlock = block
                if (activeBlock != null) {
                    if (line == "${activeBlock}_END") {
                        blocks[activeBlock] = content.toString()
                        content.clear()
                        block = null
                    } else {
                        content.appendLine(line)
                    }
                } else {
                    val equals = line.indexOf('=')
                    if (equals > 0) {
                        values[line.substring(0, equals)] = line.substring(equals + 1)
                    } else if (line.endsWith("_BEGIN")) {
                        block = line.removeSuffix("_BEGIN")
                    }
                }
            }
        }
    }

    private fun decodeBlockBytes(reply: BackendOutput, gzKey: String, rawKey: String): ByteArray? {
        reply.blocks[gzKey]?.let { b64 ->
            val compressed = Base64.decode(b64, Base64.DEFAULT)
            ByteArrayInputStream(compressed).use { rawIn ->
                GZIPInputStream(rawIn).use { gz ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = gz.read(buf)
                        if (n < 0) break
                        total += n
                        check(total <= MAX_PARTITION_BYTES) { "解压分区数据超出上限 ($MAX_PARTITION_BYTES B)" }
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            }
        }
        val rawB64 = reply.blocks[rawKey] ?: return null
        return Base64.decode(rawB64, Base64.DEFAULT).also {
            check(it.size <= MAX_PARTITION_BYTES) { "分区数据超出上限 ($MAX_PARTITION_BYTES B)" }
        }
    }


    companion object {
        private const val ASSET_ROOT = "backend"
        private const val INSTALL_STAMP = ".install-stamp"
        // Two 4 MiB partitions may be returned as raw base64, plus diagnostic metadata.
        private const val MAX_BACKEND_OUTPUT_BYTES = 12 * 1024 * 1024
        private const val MAX_PARTITION_BYTES = 4 * 1024 * 1024
        private val bundleInstallLock = Any()
    }
}
