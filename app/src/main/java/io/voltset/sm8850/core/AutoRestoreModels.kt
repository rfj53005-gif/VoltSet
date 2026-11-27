package io.voltset.sm8850.core

/** Persisted configuration for optional boot-completed SDAM auto-write in the KSU module. */
data class AutoRestoreConfig(
    val enabled: Boolean = false,
    val targetSet: Int? = null,
    val pdpCdbSha256: String? = null,
)

/** Last execution record written by `boot-completed.sh` (`voltset.sh auto_restore`). */
data class AutoRestoreStatus(
    val bootId: String? = null,
    val time: String? = null,
    val action: String? = null,
    val targetSet: Int? = null,
    val observedSet: Int? = null,
    val detail: String? = null,
)

/** Combined KSU auto-restore state read from `/data/adb/voltset/persistent`. */
data class AutoRestoreSnapshot(
    val moduleInstalled: Boolean = false,
    val config: AutoRestoreConfig = AutoRestoreConfig(),
    val status: AutoRestoreStatus? = null,
)

object AutoRestoreParser {
    private val sha256Regex = Regex("^[0-9a-f]{64}$")

    fun parsePdpCdbFingerprint(raw: String?): String? {
        val clean = raw?.trim()?.takeIf { sha256Regex.matches(it) }
        return clean
    }

    fun parseConfig(raw: String?): AutoRestoreConfig {
        if (raw.isNullOrBlank()) return AutoRestoreConfig()
        var enabledRaw = false
        var targetSet: Int? = null
        var hash: String? = null
        for (line in raw.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq).trim()
            val v = line.substring(eq + 1).trim()
            when (k) {
                "enabled" -> enabledRaw = v == "1"
                "target_set" -> targetSet = v.toIntOrNull()?.takeIf { it in 2..7 }
                "pdp_cdb_sha256" -> hash = parsePdpCdbFingerprint(v)
            }
        }
        return AutoRestoreConfig(
            enabled = enabledRaw && targetSet != null,
            targetSet = targetSet,
            pdpCdbSha256 = hash,
        )
    }

    fun parseStatus(raw: String?): AutoRestoreStatus? {
        if (raw.isNullOrBlank()) return null
        val kv = linkedMapOf<String, String>()
        for (line in raw.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) {
                kv[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
        }
        if (kv["action"].isNullOrBlank() && kv["detail"].isNullOrBlank()) return null
        return AutoRestoreStatus(
            bootId = kv["boot_id"]?.takeIf { it.isNotEmpty() },
            time = kv["time"]?.takeIf { it.isNotEmpty() },
            action = kv["action"]?.takeIf { it.isNotEmpty() },
            targetSet = kv["target_set"]?.toIntOrNull()?.takeIf { it in 1..7 },
            observedSet = kv["observed_set"]?.toIntOrNull()?.takeIf { it in 0..7 },
            detail = kv["detail"]?.takeIf { it.isNotEmpty() },
        )
    }
}
