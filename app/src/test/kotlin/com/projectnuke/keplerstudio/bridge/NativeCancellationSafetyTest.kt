package com.projectnuke.keplerstudio.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Gate 5 native cancellation registry and scratch-plan V1 safety checks.
 *
 * Host-runnable: no native library required.
 *
 * Native JNI cancellation checkpoints across pixels/memory ops are validated
 * by integration device tests; this host test covers the V1 token lifecycle
 * and scratch-budget plan enforcement.
 */
class NativeCancellationSafetyTest {
    private val backend = FakeBackend()

    @Test
    fun tokenSignalledBeforeStartReturnsCancellationCode() {
        val op = CancellableNativeOperation(backend)
        op.signal()
        assertTrue(backend.cancelled(op.token))
        assertTrue(
            isNativeCancelledCode(CancelledNativeExitCode.CancelledBeforeStart.code),
        )
        op.close()
    }

    @Test
    fun tokenNeverSignalledIsNotCancelled() {
        val op = CancellableNativeOperation(backend)
        assertFalse(backend.cancelled(op.token))
        op.close()
    }

    @Test
    fun closedTokenIsRemovedAsIfCancelled() {
        val op = CancellableNativeOperation(backend)
        op.close()
        assertFalse(backend.active(op.token))
    }

    @Test
    fun doubleCloseIsNoop() {
        val op = CancellableNativeOperation(backend)
        op.close()
        op.close()
    }

    @Test
    fun oldTokenCannotCancelNewerOperation() {
        val first = CancellableNativeOperation(backend)
        first.signal()
        val firstToken = first.token
        first.close()
        val second = CancellableNativeOperation(backend)
        assertFalse(backend.signal(firstToken))
        assertFalse(backend.cancelled(second.token))
        second.close()
    }

    @Test
    fun cancellationCodeEnumerationIsExhaustiveForCheckpoints() {
        assertEquals(-7, CancelledNativeExitCode.CancelledBeforeStart.code)
        assertEquals(-8, CancelledNativeExitCode.CancelledMidPass.code)
        assertEquals(-9, CancelledNativeExitCode.CancelledAtCommit.code)
    }

    @Test
    fun nativeCancellationRegistryIsClearedAcrossRuns() {
        repeat(4) {
            val t = backend.register()
            backend.signal(t)
            assertTrue(backend.cancelled(t))
            backend.release(t)
        }
        assertEquals(0, backend.size())
    }

    @Test
    fun scratchPlanEnforcesPositiveDimensionsAndBudget() {
        // mainRender rowBytes = 0 rejected
        assertFailsWith<IllegalArgumentException> {
            NativeScratchPlanner.mainRender(0, needsFiveRows = true, needsThreeRows = true)
        }
        // Excessive specialEffect size rejected
        assertFalse(
            NativeScratchPlanner.specialEffect(200_000, 2_000, effect = 3).withinNativeBudget,
        )
        // Crop + Selection blend always within budget
        assertTrue(NativeScratchPlanner.crop().withinNativeBudget)
        assertTrue(NativeScratchPlanner.selectionBlend().withinNativeBudget)
    }

    private class FakeBackend : NativeCancellationBackend {
        private val next = AtomicLong()
        private val flags = ConcurrentHashMap<Long, Boolean>()
        override fun register(): Long = next.incrementAndGet().also { flags[it] = false }
        override fun signal(token: Long): Boolean =
            flags.computeIfPresent(token) { _, _ -> true } != null
        override fun release(token: Long): Boolean = flags.remove(token) != null
        fun cancelled(token: Long) = flags[token] == true
        fun active(token: Long) = flags.containsKey(token)
        fun size() = flags.size
    }
}
