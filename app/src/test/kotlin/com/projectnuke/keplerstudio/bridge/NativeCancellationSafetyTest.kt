package com.projectnuke.keplerstudio.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    @Test
    fun tokenSignalledBeforeStartReturnsCancellationCode() {
        val op = CancellableNativeOperation()
        op.signal()
        assertTrue(op.checkCancelled())
        assertTrue(
            isNativeCancelledCode(CancelledNativeExitCode.CancelledBeforeStart.code),
        )
        op.close()
    }

    @Test
    fun tokenNeverSignalledIsNotCancelled() {
        val op = CancellableNativeOperation()
        assertFalse(op.checkCancelled())
        op.close()
    }

    @Test
    fun closedTokenIsRemovedAsIfCancelled() {
        val op = CancellableNativeOperation()
        op.close()
        assertTrue(NativeCancellation.isCancelled(op.token))
    }

    @Test
    fun doubleCloseIsNoop() {
        val op = CancellableNativeOperation()
        op.close()
        op.close()
    }

    @Test
    fun oldTokenCannotCancelNewerOperation() {
        val first = CancellableNativeOperation()
        first.signal()
        val firstToken = first.token
        first.close()
        val second = CancellableNativeOperation()
        NativeCancellation.signal(firstToken)
        assertFalse(second.checkCancelled())
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
            val t = NativeCancellation.createToken()
            NativeCancellation.signal(t)
            assertTrue(NativeCancellation.isCancelled(t))
            NativeCancellation.release(t)
        }
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
}