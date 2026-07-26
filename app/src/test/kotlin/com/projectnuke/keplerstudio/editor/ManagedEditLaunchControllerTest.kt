package com.projectnuke.keplerstudio.editor

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedEditLaunchControllerTest {
    @Test
    fun cancelledScope_childNeverStarts_callerSettlesResources() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        scope.cancel()
        val releases = AtomicInteger()
        val handoff = PreparedResourceHandoff.create { releases.incrementAndGet() }
        var executed = false

        val controller = ManagedEditLaunchController(scope)
        val job = controller.launch(handoff) { executed = true }
        dispatcher.runAll()

        assertTrue(job.isCancelled)
        assertFalse(executed)
        assertTrue(releases.get() == 1)
        assertNull(controller.job)
    }

    @Test
    fun cancellationAfterClaim_settlesThroughChildOwner() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val releases = AtomicInteger()
        val handoff = PreparedResourceHandoff.create { releases.incrementAndGet() }
        var entered = false
        val controller = ManagedEditLaunchController(scope)

        val job =
            controller.launch(handoff) {
                entered = true
                kotlinx.coroutines.awaitCancellation()
            }
        dispatcher.runNext()
        job.cancel()
        dispatcher.runAll()

        assertTrue(entered)
        assertTrue(releases.get() == 1)
        assertNull(controller.job)
        scope.cancel()
    }

    @Test
    fun synchronousCompletionBeforeAssignment_clearsJobField() {
        val scope = CoroutineScope(SupervisorJob() + ImmediateDispatcher)
        val controller = ManagedEditLaunchController(scope)
        val handoff = PreparedResourceHandoff.create {}

        val job = controller.launch(handoff) {}

        assertTrue(job.isCompleted)
        assertNull(controller.job)
        scope.cancel()
    }

    @Test
    fun oldCompletionCannotClearNewerJob() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = ManagedEditLaunchController(scope)
        val first = controller.launch(PreparedResourceHandoff.create {}) {
            kotlinx.coroutines.awaitCancellation()
        }
        dispatcher.runNext()

        val second = controller.launch(PreparedResourceHandoff.create {}) {
            kotlinx.coroutines.awaitCancellation()
        }
        dispatcher.runAll(limit = 2)

        assertTrue(first.isCancelled)
        assertSame(second, controller.job)
        second.cancel()
        dispatcher.runAll()
        assertNull(controller.job)
        scope.cancel()
    }

    @Test
    fun failedClaimNeverExecutesBlock() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val handoff = PreparedResourceHandoff.create {}
        assertTrue(handoff.settleCallerOwned())
        var executed = false

        ManagedEditLaunchController(scope).launch(handoff) { executed = true }
        dispatcher.runAll()

        assertFalse(executed)
        scope.cancel()
    }
}

private object ImmediateDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = false

    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}

private class QueuedDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun runNext() {
        queue.removeFirst().run()
    }

    fun runAll(limit: Int = Int.MAX_VALUE) {
        var remaining = limit
        while (queue.isNotEmpty() && remaining-- > 0) queue.removeFirst().run()
    }
}
