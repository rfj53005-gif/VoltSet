package io.voltset.sm8850

import android.content.Context
import io.voltset.sm8850.core.GpuDraft
import io.voltset.sm8850.core.GpuVoltageCorners
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores per-device/DTB stock GPU voltage tables and `gfx.lvl` corners locally so users can
 * continue comparing against their own unmodified stock table after flashing a patched `vendor_boot`.
 */
internal class GpuBaselineStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    fun load(device: String, dtbIndex: Int): GpuVoltageReference? = runCatching {
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val entry = root.optJSONObject(key(device, dtbIndex)) ?: return null
        val model = entry.optString("model").ifBlank { "$device (本机备份)" }
        val build = entry.optString("build").ifBlank { "未知版本" }
        val cornersArray = entry.optJSONArray("corners") ?: return null
        val cornerLevels = (0 until cornersArray.length()).map { cornersArray.getInt(it) }
        val corners = GpuVoltageCorners.fromLevels(cornerLevels) ?: return null
        val binsObj = entry.optJSONObject("voltages") ?: return null
        val voltages = linkedMapOf<Long?, Map<Long, Long>>()
        for (binKey in binsObj.keys()) {
            val speedBin = if (binKey == "default") null else binKey.toLongOrNull() ?: continue
            val freqObj = binsObj.getJSONObject(binKey)
            val freqMap = linkedMapOf<Long, Long>()
            for (hzKey in freqObj.keys()) {
                val hz = hzKey.toLongOrNull() ?: continue
                freqMap[hz] = freqObj.getLong(hzKey)
            }
            if (freqMap.isNotEmpty()) {
                voltages[speedBin] = freqMap
            }
        }
        if (voltages.isEmpty()) return null
        if (!voltages.containsKey(null)) {
            voltages[null] = voltages.values.maxBy { it.size }
        }
        GpuVoltageReference(model = model, build = build, voltages = voltages, corners = corners)
    }.getOrNull()

    fun save(snapshot: GpuSnapshot): GpuVoltageReference {
        val corners = checkNotNull(snapshot.voltageCorners) { "未读取到本机 GFX 电压档位表，无法保存原厂基线" }
        val voltages = linkedMapOf<Long?, Map<Long, Long>>()
        for (bin in snapshot.image.bins) {
            val activeMap = snapshot.image.rows(GpuDraft(), bin.index)
                .map { it.value }
                .filter { it.frequencyHz > 0L }
                .associate { it.frequencyHz to it.voltageLevel }
            if (activeMap.isNotEmpty()) {
                voltages[bin.speedBin] = activeMap
            }
        }
        check(voltages.isNotEmpty()) { "GPU 频压表为空，无法保存基线" }
        val defaultBin = snapshot.activeBin?.let { idx ->
            snapshot.image.bins.firstOrNull { it.index == idx }?.speedBin
        }
        if (!voltages.containsKey(null)) {
            voltages[null] = defaultBin?.let { voltages[it] } ?: voltages.values.maxBy { it.size }
        }
        val reference = GpuVoltageReference(
            model = "${snapshot.source.device} (本机原厂)",
            build = snapshot.source.build,
            voltages = voltages,
            corners = corners,
        )

        val root = runCatching {
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        }.getOrElse { JSONObject() }

        val entry = JSONObject()
            .put("model", reference.model)
            .put("build", reference.build)
            .put("corners", JSONArray(corners.availableLevels))
        val binsObj = JSONObject()
        for ((speedBin, freqMap) in voltages) {
            val freqObj = JSONObject()
            for ((hz, level) in freqMap) {
                freqObj.put(hz.toString(), level)
            }
            binsObj.put(speedBin?.toString() ?: "default", freqObj)
        }
        entry.put("voltages", binsObj)
        root.put(key(snapshot.source.device, snapshot.source.dtbIndex), entry)

        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(root.toString(2))
        check(tmp.renameTo(file)) { "无法保存本机 GPU 基线" }
        return reference
    }

    fun clear(device: String, dtbIndex: Int) {
        if (!file.isFile) return
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        if (root.remove(key(device, dtbIndex)) != null) {
            file.writeText(root.toString(2))
        }
    }

    companion object {
        private const val FILE_NAME = "gpu-stock-baselines.json"
        private fun key(device: String, dtbIndex: Int): String = "$device#dtb$dtbIndex"
    }
}
