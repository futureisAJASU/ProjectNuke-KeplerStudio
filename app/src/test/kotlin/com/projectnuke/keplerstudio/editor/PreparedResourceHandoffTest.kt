package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    fun cleanupActionsContinueAfterFailure() {
        val attempted = AtomicInteger()
        val handoff =
            PreparedResourceHandoff.create(
                { attempted.incrementAndGet() },
                {
                    attempted.incrementAndGet()
                    error("expected")
                },
                { attempted.incrementAndGet() },
            )

        assertTrue(handoff.settleCallerOwned())
        assertTrue(attempted.get() == 3)
    }

    @Test
    fun concurrentClaimAndCallerSettlement_chooseExactlyOneOwner() {
        repeat(100) {
            val cleanup = AtomicInteger()
            val handoff = PreparedResourceHandoff.create { cleanup.incrementAndGet() }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            val claim = pool.submit<Boolean> {
                start.await()
                handoff.claimForChild()
            }
            val caller = pool.submit<Boolean> {
                start.await()
                handoff.settleCallerOwned()
            }
            start.countDown()
            val claimed = claim.get()
            val callerSettled = caller.get()
            if (claimed) assertTrue(handoff.settleChildOwned())
            pool.shutdown()

            assertTrue(claimed.xor(callerSettled))
            assertTrue(cleanup.get() == 1)
        }
    }

    @Test
    fun concurrentSettlement_isExactOnce() {
        val cleanup = AtomicInteger()
        val handoff = PreparedResourceHandoff.create { cleanup.incrementAndGet() }
        assertTrue(handoff.claimForChild())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val results =
            (0 until 8).map {
                pool.submit<Boolean> {
                    start.await()
                    handoff.settleChildOwned()
                }
            }
        start.countDown()
        assertTrue(results.count { it.get() } == 1)
        pool.shutdown()
        assertTrue(cleanup.get() == 1)
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
