package io.voltset.sm8850

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.voltset.sm8850.core.GpuDraft
import io.voltset.sm8850.core.GpuVendorBoot
import io.voltset.sm8850.core.GpuVoltageCorners
import java.io.File
import java.io.RandomAccessFile
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
) {
    val partition: String get() = "/dev/block/by-name/vendor_boot$slot"
}

internal data class GpuSnapshot(
    val source: GpuImageSource,
    val image: GpuVendorBoot,
    val activeBin: Int?,
    val runtimeAcd: Boolean?,
    val stockVoltages: Map<Long?, Map<Long, Long>>,
    val voltageCorners: GpuVoltageCorners?,
) {
    fun sameSource(other: GpuSnapshot): Boolean =
        source == other.source && image.sameSource(other.image)
}

internal class GpuBackend(private val context: Context) {
    private data class DeviceInfo(
        val source: GpuImageSource,
        val availableFrequencies: Set<Long>,
        val runtimeAcd: Boolean?,
        val voltageCorners: GpuVoltageCorners?,
    )

    suspend fun readCurrent(): GpuSnapshot {
        val source = readDeviceInfo().source
        val header = readRange(source, 0, GpuVendorBoot.HEADER_BYTES.toLong()) { it.readBytes() }
        val layout = GpuVendorBoot.layout(header, source.imageSize)
        val region = readRange(source, layout.dtbOffset, layout.dtbSize.toLong()) { it.readBytes() }
        val factory = GpuFactoryReference.forSource(source)
        val image = GpuVendorBoot.read(header, source.imageSize, region, source.dtbIndex, factory?.acd.orEmpty())
        val current = readDeviceInfo(includeVoltageCorners = true)
        check(current.source == source) { "读取期间启动状态发生变化，请重新读取" }
        return GpuSnapshot(
            source, image, image.matchRuntimeBin(current.availableFrequencies), current.runtimeAcd,
            factory?.voltages.orEmpty(), current.voltageCorners,
        )
    }

    /** The destination is a new document returned by Android's CreateDocument picker. */
    suspend fun export(
        snapshot: GpuSnapshot,
        draft: GpuDraft,
        uri: Uri,
    ) = newDocument(uri) {
            val source = snapshot.source
            check(readDeviceInfo().source == source) { "启动槽位或版本已变化，请重新读取" }
            readRange(source, 0, source.imageSize, timeoutSeconds = 90) { staged ->
                check(readDeviceInfo().source == source) { "导出期间启动状态发生变化，请重新读取" }
                RandomAccessFile(staged, "rw").use { snapshot.image.patchCopy(it, draft) }
                runInterruptible {
                    val resolver = context.contentResolver
                    val output = resolver.openOutputStream(uri, "w") ?: error("无法打开导出文件")
                    output.use { sink ->
                        val written = staged.inputStream().use { it.copyTo(sink, bufferSize = 256 * 1024) }
                        check(written == source.imageSize) { "导出的镜像不完整" }
                    }
                }
            }
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
        val voltageProbe = if (includeVoltageCorners) """
            for debug_dir in /sys/kernel/debug ${'$'}(awk '$3 == "debugfs" { print $2 }' /proc/mounts); do
                db="${'$'}debug_dir/cmd-db"
                [ -r "${'$'}db" ] || continue
                gfx="${'$'}(awk '$2 == "gfx.lvl" { print "gfx=" $0 }' "${'$'}db" 2>/dev/null)" || continue
                [ -n "${'$'}gfx" ] || continue
                printf '%s\n' "${'$'}gfx"
                break
            done
        """.trimIndent() else ""
        val script = """
            set -eu
            export PATH=/system/bin
            slot="${'$'}(getprop ro.boot.slot_suffix)"
            case "${'$'}slot" in
                _a|_b) ;;
                *) echo "无法识别当前 A/B 启动槽位" >&2; exit 1 ;;
            esac
            source="/dev/block/by-name/vendor_boot${'$'}slot"
            test -b "${'$'}source" || { echo "找不到当前槽位的 vendor_boot" >&2; exit 1; }
            printf 'slot=%s\n' "${'$'}slot"
            printf 'device=%s\n' "${'$'}(getprop ro.product.device)"
            printf 'build=%s\n' "${'$'}(getprop ro.build.version.incremental)"
            printf 'index=%s\n' "${'$'}(getprop ro.boot.dtb_idx)"
            printf 'boot=%s\n' "${'$'}(cat /proc/sys/kernel/random/boot_id)"
            bytes="${'$'}(toybox blockdev --getsize64 "${'$'}source")"
            printf 'bytes=%s\n' "${'$'}bytes"
            for file in /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies; do
                if [ -r "${'$'}file" ]; then
                    printf 'frequencies=%s\n' "${'$'}(cat "${'$'}file")"
                    break
                fi
            done
            if acd="${'$'}(cat /sys/class/kgsl/kgsl-3d0/acd 2>/dev/null)"; then
                printf 'acd=%s\n' "${'$'}acd"
            fi
            $voltageProbe
        """.trimIndent()
        return readRoot(listOf("/system/bin/sh", "-c", script)) { output ->
            check(output.length() <= 16 * 1024) { "GPU 设备信息超出预期" }
            val lines = output.readLines()
            val fields = lines.filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
            val device = fields["device"]?.takeIf { it.isNotEmpty() } ?: error("无法识别当前机型")
            val build = fields["build"]?.takeIf { it.isNotEmpty() } ?: error("无法识别当前系统版本")
            val slot = fields["slot"]?.takeIf { it == "_a" || it == "_b" } ?: error("启动槽位无效")
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
            DeviceInfo(GpuImageSource(device, build, slot, dtbIndex, imageSize, bootId), frequencies, runtimeAcd, voltageCorners)
        }
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
