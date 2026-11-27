package io.voltset.sm8850

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.voltset.sm8850.core.GpuDraft
import io.voltset.sm8850.core.GpuVendorBoot
import io.voltset.sm8850.core.GpuVoltageCorners
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal data class GpuImageSource(
    val device: String,
    val build: String,
    val slot: String,
    val dtbIndex: Int,
    val imageSize: Long,
    val bootId: String,
    val partition: String,
)

internal data class GpuSnapshot(
    val source: GpuImageSource,
    val image: GpuVendorBoot,
    val activeBin: Int?,
    val runtimeAcd: Boolean?,
    val voltageReference: GpuVoltageReference?,
    val voltageCorners: GpuVoltageCorners?,
    val hasAvbFooter: Boolean = false,
    val dtboPresent: Boolean = false,
) {
    fun sameSource(other: GpuSnapshot): Boolean =
        source == other.source && image.sameSource(other.image)
}

internal data class GpuExportResult(
    val sha256Hex: String,
    val sizeBytes: Long,
    val slot: String,
    val partitionName: String,
    val isStockBackup: Boolean = false,
)

internal class GpuBackend(private val context: Context) {
    private val backend = RootBackend(context)

    private data class DeviceInfo(
        val source: GpuImageSource,
        val availableFrequencies: Set<Long>,
        val runtimeAcd: Boolean?,
        val voltageCorners: GpuVoltageCorners?,
        val socModel: String,
        val socId: Int?,
        val dtboPresent: Boolean,
    )

    suspend fun readCurrent(): GpuSnapshot {
        val info = readDeviceInfo()
        val source = info.source
        val header = readRange(source, 0, GpuVendorBoot.HEADER_BYTES.toLong()) { it.readBytes() }
        val layout = GpuVendorBoot.layout(header, source.imageSize)
        val region = readRange(source, layout.dtbOffset, layout.dtbSize.toLong()) { it.readBytes() }
        val hasAvbFooter = if (source.imageSize >= 64L) {
            runCatching {
                readRange(source, source.imageSize - 64L, 64L) { file ->
                    val tail = file.readBytes()
                    tail.size >= 4 && tail[0] == 'A'.code.toByte() && tail[1] == 'V'.code.toByte() &&
                        tail[2] == 'B'.code.toByte() && tail[3] == 'f'.code.toByte()
                }
            }.getOrDefault(false)
        } else false
        val image = GpuVendorBoot.read(header, source.imageSize, region, source.dtbIndex, GpuFactoryReference.acdForSource(source))
        val current = readDeviceInfo(includeVoltageCorners = true)
        check(current.source == source) { "读取期间启动状态发生变化，请重新读取" }
        val reference = GpuFactoryReference.voltageForSoc(current.socModel, current.socId)
        return GpuSnapshot(
            source, image, image.matchRuntimeBin(current.availableFrequencies), current.runtimeAcd,
            reference, current.voltageCorners, hasAvbFooter = hasAvbFooter, dtboPresent = current.dtboPresent,
        )
    }

    /** The destination is a new document returned by Android's CreateDocument picker. */
    suspend fun export(
        snapshot: GpuSnapshot,
        draft: GpuDraft,
        uri: Uri,
    ): GpuExportResult = exportPartitionImage(snapshot, draft, uri, isStockBackup = false)

    suspend fun exportStockBackup(
        snapshot: GpuSnapshot,
        uri: Uri,
    ): GpuExportResult = exportPartitionImage(snapshot, null, uri, isStockBackup = true)

    private suspend fun exportPartitionImage(
        snapshot: GpuSnapshot,
        draft: GpuDraft?,
        uri: Uri,
        isStockBackup: Boolean,
    ): GpuExportResult {
        var result: GpuExportResult? = null
        newDocument(uri) {
            val source = snapshot.source
            val requiredBytes = source.imageSize + 16L * 1024L * 1024L
            val usableBytes = context.cacheDir.usableSpace
            check(usableBytes >= requiredBytes) {
                val needMb = (requiredBytes + 1024 * 1024 - 1) / (1024 * 1024)
                val availMb = usableBytes / (1024 * 1024)
                "应用缓存剩余空间不足（需要至少 ${needMb} MB，当前可用 ${availMb} MB）"
            }
            check(readDeviceInfo().source == source) { "启动槽位或版本已变化，请重新读取" }
            readRange(source, 0, source.imageSize, timeoutSeconds = 90) { staged ->
                check(readDeviceInfo().source == source) { "导出期间启动状态发生变化，请重新读取" }
                if (draft != null) {
                    RandomAccessFile(staged, "rw").use { snapshot.image.patchCopy(it, draft) }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                runInterruptible {
                    val resolver = context.contentResolver
                    val output = resolver.openOutputStream(uri, "w") ?: error("无法打开导出文件")
                    output.use { sink ->
                        val buffer = ByteArray(256 * 1024)
                        var written = 0L
                        staged.inputStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                sink.write(buffer, 0, n)
                                written += n
                            }
                        }
                        check(written == source.imageSize) { "导出的镜像不完整" }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                result = GpuExportResult(
                    sha256Hex = hex,
                    sizeBytes = source.imageSize,
                    slot = source.slot,
                    partitionName = "vendor_boot${source.slot}",
                    isStockBackup = isStockBackup,
                )
            }
        }
        return checkNotNull(result)
    }

    suspend fun exportConfig(snapshot: GpuSnapshot, draft: GpuDraft, uri: Uri) = newDocument(uri) {
        val payload = GpuConfig.encode(snapshot, draft)
        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("无法打开配置文件")
        output.use { it.write(payload) }
    }

    suspend fun readConfig(snapshot: GpuSnapshot, uri: Uri): GpuDraft = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "无法访问选定的配置文件" }
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开配置文件")
        input.use { GpuConfig.decode(it, snapshot) }
    }

    suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        Unit
    }

    private suspend fun newDocument(uri: Uri, write: suspend () -> Unit) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "无法访问选定的导出位置" }
        try {
            write()
        } catch (e: Exception) {
            discardDocument(uri)
            throw e
        }
    }

    private suspend fun readDeviceInfo(includeVoltageCorners: Boolean = false): DeviceInfo {
        val output = backend.readGpuInfo(includeVoltageCorners)
        val lines = output.lines()
        val fields = lines.filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        val device = fields["device"]?.takeIf { it.isNotEmpty() } ?: error("无法识别当前机型")
        val build = fields["build"]?.takeIf { it.isNotEmpty() } ?: error("无法识别当前系统版本")
        val slot = fields["slot"]?.takeIf { it == "_a" || it == "_b" } ?: error("启动槽位无效")
        val partition = fields["source"]?.takeIf { it.startsWith("/dev/block/") }
            ?: error("找不到当前槽位的 vendor_boot")
        val dtbIndex = fields["index"]?.toIntOrNull()?.takeIf { it >= 0 }
            ?: error("系统没有报告可用的 DTB 索引")
        val bootId = fields["boot"]?.takeIf { it.isNotEmpty() } ?: error("无法确认当前开机记录")
        val imageSize = fields["bytes"]?.toLongOrNull()?.takeIf { it >= GpuVendorBoot.HEADER_BYTES }
            ?: error("无法读取 vendor_boot 分区大小")
        val frequencies = fields["frequencies"].orEmpty().split(Regex("\\s+"))
            .mapNotNull { it.toLongOrNull()?.takeIf { value -> value > 0 } }.toSet()
        val runtimeAcd = when (fields["acd"]) {
            "1" -> true
            "0" -> false
            else -> null
        }
        val voltageCorners = lines.singleOrNull { it.startsWith("gfx=") }
            ?.let { GpuVoltageCorners.parse(it.substringAfter('=')) }
        return DeviceInfo(
            GpuImageSource(device, build, slot, dtbIndex, imageSize, bootId, partition), frequencies, runtimeAcd, voltageCorners,
            fields["soc_model"].orEmpty(), fields["soc_id"]?.toIntOrNull(),
            dtboPresent = fields["dtbo_present"] == "1",
        )
    }

    private suspend fun <T> readRange(
        source: GpuImageSource,
        offset: Long,
        size: Long,
        timeoutSeconds: Long = 20,
        consume: suspend (File) -> T,
    ): T {
        require(offset >= 0 && size > 0 && offset <= source.imageSize - size) { "镜像读取范围无效" }
        return readRoot(listOf(
            "/system/bin/toybox", "dd", "if=${source.partition}", "bs=131072",
            "iflag=skip_bytes,count_bytes", "skip=$offset", "count=$size", "status=none",
        ), timeoutSeconds) { output ->
            check(output.length() == size) { "vendor_boot 读取不完整，请重新读取" }
            consume(output)
        }
    }

    private suspend fun <T> readRoot(
        arguments: List<String>,
        timeoutSeconds: Long = 20,
        consume: suspend (File) -> T,
    ): T = runRootCommand(context.cacheDir, arguments, timeoutSeconds, mergeStderr = false) { file, code, stderr ->
        check(code == 0) { stderr.ifEmpty { "GPU 镜像读取失败，请检查 Root 授权（$code）" } }
        consume(file)
    }
}
