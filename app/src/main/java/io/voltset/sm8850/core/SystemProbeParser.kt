package io.voltset.sm8850.core

/** Read-only device environment discovery details collected by `voltset.sh status`. */
data class SystemProbeInfo(
    val adjDirPath: String? = null,
    val regmapNode: String? = null,
    val vadjPlatformDevice: String? = null,
    val vadjDriver: String? = null,
    val vadjSpmiParent: String? = null,
    val vadjDtPath: String? = null,
    val vadjModuleLoaded: Boolean = false,
    val vadjModuleFiles: List<String> = emptyList(),
    val debugfsMounts: List<String> = emptyList(),
    val debugfsVoltNodes: List<String> = emptyList(),
    val pdpCdtPartitions: List<String> = emptyList(),
    val byNameDirs: List<String> = emptyList(),
    val scmiDrivers: List<String> = emptyList(),
    val scmiDeviceCount: Int = 0,
    val cpufreqPolicies: List<String> = emptyList(),
    val energyModelDomains: List<String> = emptyList(),
    val socInfo: String? = null,
    val sdam2NvmemPath: String? = null,
    val sdam2Pmic: String? = null,
) {
    val hasAnyData: Boolean
        get() = adjDirPath != null || regmapNode != null || vadjPlatformDevice != null ||
            vadjModuleLoaded || vadjModuleFiles.isNotEmpty() || debugfsMounts.isNotEmpty() ||
            debugfsVoltNodes.isNotEmpty() || pdpCdtPartitions.isNotEmpty() ||
            byNameDirs.isNotEmpty() || scmiDrivers.isNotEmpty() || scmiDeviceCount > 0 ||
            cpufreqPolicies.isNotEmpty() || energyModelDomains.isNotEmpty() || socInfo != null ||
            sdam2NvmemPath != null || sdam2Pmic != null
}

/** Parser for the `PROBE_BEGIN` ... `PROBE_END` section emitted by `voltset.sh status`. */
object SystemProbeParser {
    fun parse(
        probeBlock: String?,
        adjDirPath: String? = null,
        regmapNode: String? = null,
    ): SystemProbeInfo {
        val kv = mutableMapOf<String, String>()
        if (!probeBlock.isNullOrBlank()) {
            for (line in probeBlock.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq > 0) {
                    kv[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                }
            }
        }
        return SystemProbeInfo(
            adjDirPath = cleanScalar(adjDirPath),
            regmapNode = cleanScalar(regmapNode),
            vadjPlatformDevice = cleanScalar(kv["vadj_platform_dev"]),
            vadjDriver = cleanScalar(kv["vadj_driver"]),
            vadjSpmiParent = cleanScalar(kv["vadj_spmi_parent"]),
            vadjDtPath = cleanScalar(kv["vadj_dt_path"]),
            vadjModuleLoaded = kv["vadj_module_loaded"] == "1",
            vadjModuleFiles = splitCsv(kv["vadj_module_files"]),
            debugfsMounts = splitCsv(kv["debugfs_mounts"]),
            debugfsVoltNodes = splitCsv(kv["debugfs_volt_nodes"]),
            pdpCdtPartitions = splitCsv(kv["pdp_cdt_partitions"]),
            byNameDirs = splitCsv(kv["by_name_dirs"]),
            scmiDrivers = splitCsv(kv["scmi_drivers"]),
            scmiDeviceCount = kv["scmi_device_count"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            cpufreqPolicies = splitCsv(kv["cpufreq_policies"]),
            energyModelDomains = splitCsv(kv["energy_model_domains"]),
            socInfo = cleanScalar(kv["socinfo"]),
            sdam2NvmemPath = cleanScalar(kv["sdam2_nvmem"]),
            sdam2Pmic = cleanScalar(kv["sdam2_pmic"]),
        )
    }

    private fun cleanScalar(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && it != "none" }

    private fun splitCsv(raw: String?): List<String> {
        val clean = cleanScalar(raw) ?: return emptyList()
        val items = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in clean.indices) {
            when (clean[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) {
                    clean.substring(start, i).trim()
                        .takeIf { it.isNotEmpty() && it != "none" }
                        ?.let(items::add)
                    start = i + 1
                }
            }
        }
        clean.substring(start).trim()
            .takeIf { it.isNotEmpty() && it != "none" }
            ?.let(items::add)
        return items
    }
}
