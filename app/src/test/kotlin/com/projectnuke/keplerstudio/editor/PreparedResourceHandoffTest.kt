package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedResourceHandoffTest {
    @Test
    fun callerOwned_childNeverStarts_cleanupRunsExactlyOnce() {
        val cleanups = AtomicInteger()
        val handoff = PreparedResourceHandoff.create { cleanups.incrementAndGet() }

        assertTrue(handoff.settleCallerOwned())
        assertFalse(handoff.settleCallerOwned())
        assertFalse(handoff.claimForChild())
        assertTrue(cleanups.get() == 1)
    }

    @Test
    fun claimSucceedsOnce_andCallerCannotSettleChildOwnership() {
        val cleanups = AtomicInteger()
        val handoff = PreparedResourceHandoff.create { cleanups.incrementAndGet() }

        assertTrue(handoff.claimForChild())
        assertFalse(handoff.claimForChild())
        assertFalse(handoff.settleCallerOwned())
        assertTrue(handoff.settleChildOwned())
        assertFalse(handoff.settleChildOwned())
        assertTrue(cleanups.get() == 1)
    }

    @Test
    fun cleanupFailureDoesNotEscapeLifecycleSettlement() {
        val handoff = PreparedResourceHandoff.create { error("cleanup") }

        assertTrue(handoff.settleCallerOwned())
        assertFalse(handoff.settleCallerOwned())
    }

    @Test
    fun disarmedTransferredResourceIsNotReleasedBySettlement() {
        var owned = true
        var releases = 0
        val handoff =
            PreparedResourceHandoff.create {
                if (owned) {
                    releases++
                    owned = false
                }
            }

        assertTrue(handoff.claimForChild())
        owned = false
        assertTrue(handoff.settleChildOwned())
        assertTrue(releases == 0)
    }
}
