package io.voltset.sm8850

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal fun startRootProcess(
    arguments: List<String>,
    configure: ProcessBuilder.() -> Unit,
): Process {
    val quoted = arguments.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    return try {
        ProcessBuilder("su", "-c", quoted).apply(configure).start()
    } catch (e: IOException) {
        throw IllegalStateException("无法启动 Root 请求，请检查 KernelSU 或 Magisk 授权", e)
    }
}

internal suspend fun <T> runRootCommand(
    cacheDir: File,
    arguments: List<String>,
    timeoutSeconds: Long = 20,
    mergeStderr: Boolean = true,
    consume: suspend (output: File, exitCode: Int, stderr: String) -> T,
): T = withContext(Dispatchers.IO) {
    val command = listOf("/system/bin/timeout", "-k", "2s", "${timeoutSeconds}s") + arguments
    val output = File.createTempFile("voltset-root-", ".out", cacheDir)
    var diagnostics: File? = null
    try {
        if (!mergeStderr) diagnostics = File.createTempFile("voltset-root-", ".err", cacheDir)
        val process = startRootProcess(command) {
            redirectErrorStream(mergeStderr)
            redirectOutput(output)
            diagnostics?.let { redirectError(it) }
        }
        val exitCode = try {
            process.outputStream.close()
            check(runInterruptible { process.waitFor(timeoutSeconds + 10, TimeUnit.SECONDS) }) {
                "Root 请求超时，请检查授权后刷新"
            }
            process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        check(exitCode != 124 && exitCode != 137) { "读取或执行超时，请刷新后重试" }
        val stderr = diagnostics?.inputStream()?.use {
            val bytes = ByteArray(512)
            val length = it.read(bytes).coerceAtLeast(0)
            String(bytes, 0, length, Charsets.UTF_8).trim()
        }.orEmpty()
        consume(output, exitCode, stderr)
    } finally {
        output.delete()
        diagnostics?.delete()
    }
}
