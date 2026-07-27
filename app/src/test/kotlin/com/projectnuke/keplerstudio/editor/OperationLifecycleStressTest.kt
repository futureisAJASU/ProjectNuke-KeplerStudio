package com.projectnuke.keplerstudio.editor

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationLifecycleStressTest {
    @Test
    fun rapidManagedEditSupersessionSettlesEveryPreparedOwnerAndBusySlot() = runTest {
        val controller = ManagedEditLaunchController(this)
        val settled = AtomicInteger()
        val independentCleanup = AtomicInteger()
        var previousToken = 0L

        repeat(500) { index ->
            val handoff =
                PreparedResourceHandoff.create(
                    {
                        settled.incrementAndGet()
                        if (index % 17 == 0) error("injected cleanup failure")
                    },
                    { independentCleanup.incrementAndGet() },
                )
            controller.launch(handoff) {
                if (index != 499) awaitCancellation()
            }
            assertTrue(controller.token > previousToken)
            previousToken = controller.token
        }
        advanceUntilIdle()

        assertEquals(500, settled.get())
        assertEquals(500, independentCleanup.get())
        assertNull(controller.job)
    }

    @Test
    fun repeatedInvalidationNeverLetsOldCompletionClearNewIdentity() = runTest {
        val controller = ManagedEditLaunchController(this)
        val settled = AtomicInteger()

        repeat(250) {
            val job =
                controller.launch(
                    PreparedResourceHandoff.create { settled.incrementAndGet() }
                ) {
                    awaitCancellation()
                }
            val launchedToken = controller.token
            controller.invalidate()
            assertTrue(job.isCancelled)
            assertTrue(controller.token > launchedToken)
            assertNull(controller.job)
        }
        advanceUntilIdle()

        assertEquals(250, settled.get())
        assertNull(controller.job)
    }
}
