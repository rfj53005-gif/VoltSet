package io.voltset.sm8850.core

/** SDAM observations are tied to a boot session. Only the firmware can select the running rails. */
data class BootSetStatus(
    val firstSdamIndex: Int?,
    val previousTargetIndex: Int?,
    val stagedThisBootIndex: Int?,
    val firmwareIndex: Int?,
) {
    val rebootReadbackMismatch: Boolean
        get() = previousTargetIndex != null && firstSdamIndex != null && previousTargetIndex != firstSdamIndex

    val runningIndex: Int?
        get() = firmwareIndex ?: firstSdamIndex?.takeIf { it in 1..7 && !rebootReadbackMismatch }

    fun pendingIndex(liveSdamIndex: Int?): Int? {
        if (firmwareIndex != null || liveSdamIndex == null || liveSdamIndex !in 1..7) return null
        val current = runningIndex
        return liveSdamIndex.takeIf { if (current != null) it != current else it == stagedThisBootIndex }
    }
}
