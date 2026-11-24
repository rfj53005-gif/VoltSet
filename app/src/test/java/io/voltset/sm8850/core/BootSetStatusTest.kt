package io.voltset.sm8850.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootSetStatusTest {
    @Test
    fun stagedSetBecomesFirstReadAfterSuccessfulReboot() {
        val beforeReboot = BootSetStatus(2, null, 6, null)
        assertEquals(2, beforeReboot.runningIndex)
        assertEquals(6, beforeReboot.pendingIndex(6))

        val afterReboot = BootSetStatus(6, 6, null, null)
        assertEquals(6, afterReboot.runningIndex)
        assertFalse(afterReboot.rebootReadbackMismatch)
        assertNull(afterReboot.pendingIndex(6))
    }

    @Test
    fun mismatchedReadbackDoesNotBecomeTheRunningSet() {
        val afterReboot = BootSetStatus(1, 6, null, null)
        assertTrue(afterReboot.rebootReadbackMismatch)
        assertNull(afterReboot.runningIndex)
        assertNull(afterReboot.pendingIndex(1))

        val stagedAgain = afterReboot.copy(stagedThisBootIndex = 3)
        assertNull(stagedAgain.runningIndex)
        assertEquals(3, stagedAgain.pendingIndex(3))
        assertNull(stagedAgain.pendingIndex(4))
    }

    @Test
    fun firmwareLockTakesPrecedenceAndZeroIsNotASet() {
        val unlocked = BootSetStatus(0, null, null, null)
        assertNull(unlocked.runningIndex)
        assertNull(unlocked.pendingIndex(0))

        val locked = BootSetStatus(2, 6, null, 4)
        assertEquals(4, locked.runningIndex)
        assertNull(locked.pendingIndex(2))
    }
}
