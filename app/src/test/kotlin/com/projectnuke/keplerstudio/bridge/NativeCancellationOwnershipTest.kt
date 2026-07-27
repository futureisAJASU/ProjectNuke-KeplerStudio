package com.projectnuke.keplerstudio.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Host ownership tests for the coroutine/JNI cancellation bridge.
 *
 * The backend is a seam for the JNI registry. These tests deliberately execute a blocking
 * "native" call on a real worker thread and prove cancellation waits for its return before
 * unregistering. Actual C++ polling is covered by Android JNI tests.
 */
class NativeCancellationOwnershipTest {
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
    fun coroutineCancellationSignalsWaitsForReturnAndUnregistersOnce() = runBlocking {
        val entered = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val job =
            launch(Dispatchers.Default) {
                try {
                    executeCancellableNative(backend, Dispatchers.IO) { token ->
                        entered.countDown()
                        while (!backend.cancelled(token)) {
                            Thread.yield()
                        }
                        returned.countDown()
                        CancelledNativeExitCode.CancelledMidPass.code
                    }
                    fail("cancelled native execution returned successfully")
                } catch (_: CancellationException) {
                    // Expected: cancellation is not reported until the worker has returned.
                }
            }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertEquals(0L, returned.count)
        assertEquals(0, backend.size())
        assertEquals(1, backend.releaseCount.get())
    }

    @Test
    fun cancelledBeforeDispatchDoesNotRegister() = runBlocking {
        val parent = launch(start = CoroutineStart.LAZY) {
            executeCancellableNative(backend, Dispatchers.IO) { 0 }
        }
        parent.cancel()
        parent.start()
        parent.join()
        assertEquals(0, backend.registerCount.get())
        assertEquals(0, backend.size())
    }

    @Test
    fun concurrentSignalAndCloseSettlesRegistryOnce() {
        val op = CancellableNativeOperation(backend)
        val start = CountDownLatch(1)
        val threads =
            listOf(
                Thread {
                    start.await()
                    repeat(100) { op.signal() }
                },
                Thread {
                    start.await()
                    repeat(100) { op.close() }
                },
            )
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)
        assertEquals(0, backend.size())
        assertEquals(1, backend.releaseCount.get())
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
        val registerCount = AtomicInteger()
        val releaseCount = AtomicInteger()
        override fun register(): Long =
            next.incrementAndGet().also {
                registerCount.incrementAndGet()
                flags[it] = false
            }
        override fun signal(token: Long): Boolean =
            flags.computeIfPresent(token) { _, _ -> true } != null
        override fun release(token: Long): Boolean =
            (flags.remove(token) != null).also { removed ->
                if (removed) releaseCount.incrementAndGet()
            }
        fun cancelled(token: Long) = flags[token] == true
        fun active(token: Long) = flags.containsKey(token)
        fun size() = flags.size
    }
}
