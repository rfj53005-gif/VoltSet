package io.voltset.sm8850

import io.voltset.sm8850.core.GpuBus
import io.voltset.sm8850.core.GpuDraft
import io.voltset.sm8850.core.GpuLevelValue
import io.voltset.sm8850.core.GpuRow
import io.voltset.sm8850.core.GpuTableId
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

internal object GpuConfig {
    private const val FORMAT = "voltset-gpu-1"
    private const val MAX_BYTES = 512 * 1024

    fun encode(snapshot: GpuSnapshot, draft: GpuDraft): ByteArray {
        val image = snapshot.image
        image.validate(draft)
        val root = JSONObject().put("format", FORMAT).put("device", snapshot.source.device)
        val bins = JSONArray()
        for (bin in image.bins) {
            val rows = JSONArray()
            for (row in image.rows(draft, bin.index)) {
                val v = row.value
                val item = JSONObject()
                    .put("templateBin", row.template.bin)
                    .put("templateHz", image.templateFrequency(row.template))
                    .put("hz", v.frequencyHz)
                    .put("level", v.voltageLevel)
                v.bus?.let { item.put("bus", JSONArray(listOf(it.minimum, it.target, it.maximum))) }
                v.acdLevel?.let { item.put("acd", it) }
                rows.put(item)
            }
            bins.put(JSONObject().put("id", bin.index).put("speedBin", bin.speedBin ?: JSONObject.NULL).put("rows", rows))
        }
        root.put("bins", bins)
        val tables = JSONObject()
        for (table in image.tables) tables.put(table.id.name, JSONArray(image.values(draft, table.id)))
        root.put("tables", tables)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun decode(input: InputStream, snapshot: GpuSnapshot): GpuDraft {
        val bytes = bounded(input)
        val root = JSONObject(asJson(bytes))
        return if (root.optString("format") == FORMAT) native(root, snapshot) else legacy(root, snapshot)
    }

    private fun native(root: JSONObject, snapshot: GpuSnapshot): GpuDraft {
        require(root.getString("device") == snapshot.source.device) { "配置机型与本机不匹配" }
        val image = snapshot.image
        val incoming = root.getJSONArray("bins")
        require(incoming.length() in 1..image.bins.size) { "配置的分档数量无效" }
        var draft = GpuDraft()
        val seen = mutableSetOf<Int>()
        for (i in 0 until incoming.length()) {
            val item = incoming.getJSONObject(i)
            val id = item.integer("id").toIntExact()
            require(seen.add(id)) { "配置包含重复分档" }
            val bin = image.bins.firstOrNull { it.index == id } ?: error("配置分档与本机不匹配")
            val speed = if (item.isNull("speedBin")) null else item.integer("speedBin")
            require(speed == bin.speedBin) { "配置的芯片分档与本机不匹配" }
            val raw = item.getJSONArray("rows")
            require(raw.length() in 1..256) { "配置的频点数量无效" }
            val rows = (0 until raw.length()).map { j ->
                val row = raw.getJSONObject(j)
                val template = image.template(row.integer("templateBin").toIntExact(), row.integer("templateHz"))
                    ?: error("配置的频点模板在当前镜像中不存在")
                val bus = row.optJSONArray("bus")?.let {
                    require(it.length() == 3) { "配置的带宽索引格式无效" }
                    GpuBus(it.integer(0), it.integer(1), it.integer(2))
                }
                val acd = if (row.isNull("acd")) null else row.integer("acd")
                val point = GpuRow(template, GpuLevelValue(row.integer("hz"), row.integer("level"), bus, acd))
                if (acd == null) image.carryAcd(id, point) else point
            }
            draft = image.changeRows(draft, id, rows)
        }
        val tables = root.optJSONObject("tables") ?: return draft
        for (id in tables.keys()) {
            val key = GpuTableId.entries.firstOrNull { it.name == id } ?: error("未知的 GPU 调参表")
            val raw = tables.getJSONArray(id)
            require(raw.length() <= 1024) { "调参表过大" }
            draft = image.changeTable(draft, key, (0 until raw.length()).map { raw.integer(it) })
        }
        return draft
    }

    // Read only the power-level cells carried by legacy KonaBess configurations.
    // No DTS text is compiled, executed, or inserted into a boot image.
    private fun legacy(root: JSONObject, snapshot: GpuSnapshot): GpuDraft {
        require(root.has("chip") && root.has("freq") && root.optString("volt").isBlank()) {
            "不支持此配置格式"
        }
        val image = snapshot.image
        val text = root.getString("freq").replace(Regex("""/\*[\s\S]*?\*/|//[^\r\n]*"""), "")
        val binPattern = Regex("""\bqcom,gpu-pwrlevels(?:-(\d+))?\s*\{""")
        val levelPattern = Regex("""\bqcom,gpu-pwrlevel@[0-9A-Fa-f]+\s*\{""")
        var draft = GpuDraft()
        val seen = mutableSetOf<Int>()
        for (m in binPattern.findAll(text)) {
            val id = m.groupValues[1].toIntOrNull() ?: 0
            require(seen.add(id) && image.bins.any { it.index == id }) { "旧配置分档与本机不匹配" }
            val body = block(text, m.range.last)
            val rows = levelPattern.findAll(body).map { row ->
                val data = block(body, row.range.last)
                val hz = dtsCell(data, "qcom,gpu-freq") ?: error("旧配置频点缺少频率")
                val level = dtsCell(data, "qcom,level") ?: error("旧配置频点缺少电压档位")
                val template = image.template(id, hz)
                    ?: image.bins.firstNotNullOfOrNull { image.template(it.index, hz) }
                    ?: error("旧配置含本机未识别的 GPU 频率")
                val before = image.baseValue(template)
                val fields = listOf("qcom,bus-min", "qcom,bus-freq", "qcom,bus-max").map { dtsCell(data, it) }
                val bus = if (fields.all { it == null }) before.bus else {
                    require(fields.none { it == null }) { "旧配置的带宽索引不完整" }
                    GpuBus(fields[0]!!, fields[1]!!, fields[2]!!)
                }
                val acd = dtsCell(data, "qcom,acd-level")
                val point = GpuRow(template, GpuLevelValue(hz, level, bus, acd))
                if (acd == null || acd == 0L && before.acdLevel == null) image.carryAcd(id, point) else point
            }.toList()
            draft = image.changeRows(draft, id, rows)
        }
        require(seen.isNotEmpty()) { "旧配置中没有可识别的 GPU 频点表" }
        return draft
    }

    private fun dtsCell(text: String, name: String): Long? {
        val pattern = Regex("""(?<![A-Za-z0-9,_.-])${Regex.escape(name)}\s*=\s*<\s*(0[xX][0-9A-Fa-f]+|[0-9]+)\s*>\s*;""")
        val matches = pattern.findAll(text).toList()
        require(matches.size <= 1) { "旧配置包含重复的 GPU 属性" }
        val value = matches.singleOrNull()?.groupValues?.get(1) ?: return null
        return if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLong(16) else value.toLong()
    }

    private fun block(text: String, start: Int): String {
        var depth = 1
        for (i in start + 1 until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return text.substring(start + 1, i)
        }
        error("旧配置的设备树节点未闭合")
    }

    private fun asJson(data: ByteArray): String {
        fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 31.toByte() && bytes[1] == 139.toByte()
        val raw = if (isGzip(data)) {
            GZIPInputStream(data.inputStream()).use(::bounded)
        } else {
            val text = data.decodeToString().trim().removePrefix("\uFEFF").removePrefix("konabess://").trim()
            if (text.startsWith('{')) return text
            val normalized = text.filterNot(Char::isWhitespace)
            val packed = listOf(Base64.getDecoder(), Base64.getUrlDecoder()).firstNotNullOfOrNull { decoder ->
                runCatching { decoder.decode(normalized) }.getOrNull()?.takeIf(::isGzip)
            } ?: error("不支持此配置格式")
            GZIPInputStream(packed.inputStream()).use(::bounded)
        }
        return raw.decodeToString().trim()
    }

    private fun bounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            require(output.size() + n <= MAX_BYTES) { "配置文件超出大小限制" }
            output.write(buf, 0, n)
        }
        return output.toByteArray()
    }

    private fun JSONObject.integer(key: String) = get(key).toString().toLongOrNull() ?: error("配置数值无效")
    private fun JSONArray.integer(index: Int) = get(index).toString().toLongOrNull() ?: error("配置数值无效")
    private fun Long.toIntExact(): Int = Math.toIntExact(this)
}

