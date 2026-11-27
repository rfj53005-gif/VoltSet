package io.voltset.sm8850

import io.voltset.sm8850.core.ClusterSetSummary
import io.voltset.sm8850.core.VoltageSetProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltSetResumeTest {
    @Test
    fun refreshesOnColdStartAndAfterTheLastSuccessfulReadAges() {
        val state = VoltSetUiState()
        assertTrue(state.canRefreshOnResume(null, 1))
        assertFalse(state.canRefreshOnResume(1000, 5999))
        assertTrue(state.canRefreshOnResume(1000, 6000))
        assertFalse(state.copy(loading = true).canRefreshOnResume(null, 6000))
    }

    @Test
    fun preservesGpuWorkAndWriteOrRebootConfirmationsOnResume() {
        val state = VoltSetUiState()
        val cluster = ClusterSetSummary(0, 0, 0, emptyList(), 0, 0, 0, 0)
        val profile = VoltageSetProfile(2, cluster, cluster.copy(cluster = 1))
        assertFalse(state.copy(selectedTab = VoltSetTab.GPU).canRefreshOnResume(1000, 8000))
        assertFalse(state.copy(pendingConfirmSet = profile).canRefreshOnResume(1000, 8000))
        assertFalse(state.copy(showRebootConfirm = true).canRefreshOnResume(1000, 8000))
    }
}
