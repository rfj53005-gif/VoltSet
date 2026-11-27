package io.voltset.sm8850

import android.content.Context
import android.net.Uri
import io.voltset.sm8850.core.AutoRestoreSnapshot
import io.voltset.sm8850.core.CompatibilityChecker
import io.voltset.sm8850.core.DeviceSnapshot
import io.voltset.sm8850.core.ScmiPoint
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object DiagnosticExporter {
    suspend fun exportZip(
        context: Context,
        snapshot: DeviceSnapshot,
        autoRestore: AutoRestoreSnapshot?,
        appVersion: String,
        uri: Uri,
    ) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "w") ?: error("无法创建诊断压缩包")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                writeTextEntry(zip, "report.txt", CompatibilityChecker.formatTextReport(snapshot, appVersion))
                if (snapshot.scmiPoints.isNotEmpty()) {
                    writeTextEntry(zip, "scmi-live.csv", formatScmiCsv(snapshot.scmiPoints))
                }
                snapshot.baselinePoints?.takeIf { it.isNotEmpty() }?.let { baseline ->
                    writeTextEntry(zip, "scmi-baseline.csv", formatScmiCsv(baseline))
                }
                writeTextEntry(zip, "pdp-cdb-summary.json", formatPdpCdbJson(snapshot))
                writeTextEntry(zip, "pdp-limits-summary.json", formatPdpLimitsJson(snapshot))
                writeTextEntry(zip, "system-probe.txt", formatProbeText(snapshot, autoRestore))
            }
        }
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun formatScmiCsv(points: List<ScmiPoint>): String = buildString {
        appendLine("domain,level_index,freq_khz,power_uw,voltage_mv,exact_formula_match")
        for (p in points) {
            appendLine("${p.domain},${p.levelIndex},${p.freqKhz},${p.powerUw},${p.voltageMv},${p.exactFormulaMatch}")
        }
    }

    private fun formatPdpCdbJson(snapshot: DeviceSnapshot): String {
        val root = JSONObject()
            .put("path", snapshot.pdpCdbPath ?: JSONObject.NULL)
            .put("sha256", snapshot.pdpCdbSha256 ?: JSONObject.NULL)
            .put("error", snapshot.pdpCdbError ?: JSONObject.NULL)
            .put("overrideSetId", snapshot.pdpCdb?.overrideSetId ?: JSONObject.NULL)
        val setsArr = JSONArray()
        for (set in snapshot.pdpCdb?.sets.orEmpty()) {
            setsArr.put(
                JSONObject()
                    .put("setId", set.setId)
                    .put("tag", set.tag.name)
                    .put("clus0AllCxMv", set.clus0.allCxMv)
                    .put("clus0AllMxMv", set.clus0.allMxMv)
                    .put("clus0RangeCxMv", "${set.clus0.minCxMv}..${set.clus0.maxCxMv}")
                    .put("clus1AllCxMv", set.clus1.allCxMv)
                    .put("clus1AllMxMv", set.clus1.allMxMv)
                    .put("clus1RangeCxMv", "${set.clus1.minCxMv}..${set.clus1.maxCxMv}"),
            )
        }
        root.put("sets", setsArr)
        return root.toString(2)
    }

    private fun formatPdpLimitsJson(snapshot: DeviceSnapshot): String {
        val limits = snapshot.pdpHardwareLimits
        val root = JSONObject()
            .put("path", snapshot.pdpFwPath ?: JSONObject.NULL)
            .put("error", snapshot.pdpFwError ?: JSONObject.NULL)
            .put("totalDcvsTablesFound", limits?.totalDcvsTablesFound ?: 0)
        fun clusterArray(domain: Int): JSONArray {
            val arr = JSONArray()
            val cluster = limits?.forDomain(domain) ?: return arr
            for (lim in cluster.limitsByFreqKhz.values.sortedBy { it.freqKhz }) {
                arr.put(
                    JSONObject()
                        .put("freqKhz", lim.freqKhz)
                        .put("cxFloorMv", lim.cxFloorMv)
                        .put("cxCeilMv", lim.cxCeilMv)
                        .put("olvAcdMarginMv", lim.olvAcdMarginMv)
                        .put("mxFloorMv", lim.mxFloorMv)
                        .put("mxCeilMv", lim.mxCeilMv),
                )
            }
            return arr
        }
        root.put("clus0", clusterArray(0))
        root.put("clus1", clusterArray(1))
        return root.toString(2)
    }

    private fun formatProbeText(snapshot: DeviceSnapshot, autoRestore: AutoRestoreSnapshot?): String = buildString {
        val probe = snapshot.systemProbe
        appendLine("device=${snapshot.deviceModel} (${snapshot.deviceName})")
        appendLine("soc=${snapshot.socModel}")
        appendLine("kernel=${snapshot.kernelRelease}")
        appendLine("pageSizeBytes=${snapshot.pageSizeBytes ?: -1}")
        appendLine("slot=${snapshot.slotSuffix}")
        appendLine("bootReason=${probe.bootReason ?: "none"}")
        appendLine("isAbnormalBoot=${probe.isAbnormalBoot}")
        appendLine("pstoreCrash=${probe.pstoreCrash ?: "none"}")
        appendLine("adjBackend=${snapshot.adjBackend}")
        appendLine("adjDirPath=${probe.adjDirPath ?: "none"}")
        appendLine("regmapNode=${probe.regmapNode ?: "none"}")
        appendLine("sdam2NvmemPath=${probe.sdam2NvmemPath ?: "none"}")
        appendLine("sdam2Pmic=${probe.sdam2Pmic ?: "none"}")
        appendLine("socInfo=${probe.socInfo ?: "none"}")
        appendLine("qfpromAging=${probe.qfpromAging?.formatRawHex() ?: "none"}")
        appendLine("qptRails=${probe.qptRails.joinToString(",")}")
        appendLine("cpufreqPolicies=${probe.cpufreqPolicies.joinToString(",")}")
        appendLine("energyModelDomains=${probe.energyModelDomains.joinToString(",")}")
        if (autoRestore != null) {
            appendLine("ksuModuleInstalled=${autoRestore.moduleInstalled}")
            appendLine("autoRestoreEnabled=${autoRestore.config.enabled}")
            appendLine("autoRestoreTarget=${autoRestore.config.targetSet ?: "none"}")
            appendLine("autoRestoreStatus=${autoRestore.status?.action ?: "none"} (${autoRestore.status?.detail ?: ""})")
        }
    }
}
