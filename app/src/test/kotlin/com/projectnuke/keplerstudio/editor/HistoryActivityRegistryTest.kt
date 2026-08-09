package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryActivityRegistryTest {
    @Test
    fun coordinatorActivityIsVisibleWithoutRegisteredJob() {
        var coordinatorBusy = false
        val registry = HistoryActivityRegistry(coordinatorBusy = { coordinatorBusy })

        assertFalse(registry.isBusy())
        coordinatorBusy = true
        assertTrue(registry.isBusy())
    }

    @Test
    fun registrationMakesLaunchWindowBusyBeforeCoroutineBodyRuns(): Unit = runBlocking {
        val registry = HistoryActivityRegistry(coordinatorBusy = { false })
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launch(start = CoroutineStart.LAZY) {}

        assertTrue(registry.register(job))
        assertTrue(registry.isBusy())
        job.start()
        job.join()
        assertFalse(registry.isBusy())
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun oldCompletionCannotClearNewRegistration() {
        val registry = HistoryActivityRegistry(coordinatorBusy = { false })
        val old = Job()
        val newer = Job()

        assertTrue(registry.register(old))
        old.complete()
        assertTrue(registry.register(newer))
        assertTrue(registry.isBusy())
        old.complete()
        assertTrue(registry.isBusy())
        newer.complete()
        assertFalse(registry.isBusy())
    }

    @Test
    fun cancellationBeforeBodySettlesRegistration() {
        val registry = HistoryActivityRegistry(coordinatorBusy = { false })
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launch(start = CoroutineStart.LAZY) {}

        assertTrue(registry.register(job))
        job.cancel()
        assertFalse(registry.isBusy())
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun shutdownCancellationClearsRegisteredActivity() {
        val registry = HistoryActivityRegistry(coordinatorBusy = { false })
        val job = Job()

        assertTrue(registry.register(job))
        registry.cancel()
        assertTrue(job.isCancelled)
        assertFalse(registry.isBusy())
    }
}
