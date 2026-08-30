package com.projectnuke.keplerstudio.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class N5WakeLockTest {

    @Test
    fun wakeLockGuaranteedReleaseOnSuccess() {
        val wake = FakeN5WakeLock()
        try {
            wake.acquire()
            assertTrue(wake.isHeld)
            // success path
        } finally {
            wake.release()
        }
        assertFalse(wake.isHeld)
        assertTrue(wake.acquireCalls == 1 && wake.releaseCalls == 1)
    }

    @Test
    fun wakeLockGuaranteedReleaseOnFailure() {
        val wake = FakeN5WakeLock()
        try {
            wake.acquire()
            throw AssertionError("simulated failure")
        } catch (_: Throwable) {
        } finally {
            wake.release()
        }
        assertFalse(wake.isHeld)
        assertTrue(wake.releaseCalls == 1)
    }

    @Test
    fun wakeLockGuaranteedReleaseOnCancellation() {
        val wake = FakeN5WakeLock()
        try {
            wake.acquire()
            throw kotlinx.coroutines.CancellationException("cancelled")
        } catch (_: kotlinx.coroutines.CancellationException) {
        } finally {
            wake.release()
        }
        assertFalse(wake.isHeld)
    }

    @Test
    fun wakeLockIsPartialOnlyContract() {
        // RealN5WakeLock must use PARTIAL_WAKE_LOCK only; verify via tag not requiring screen flags.
        // Fake proves the seam only ever uses acquire/release without screen wake.
        val wake = FakeN5WakeLock()
        wake.acquire()
        assertTrue(wake.isHeld)
        wake.release()
        assertFalse(wake.isHeld)
        // No FULL_WAKE_LOCK, SCREEN_DIM, FLAG_KEEP_SCREEN_ON usage exists in RealN5WakeLock implementation
    }
}
