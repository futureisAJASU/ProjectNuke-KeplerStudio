package com.projectnuke.keplerstudio.editor

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorWaitInfrastructureTest {
    @Test
    fun defaultWorkThenMainProgressesThroughCompletionWaiter() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val completion = CompletableDeferred<Unit>()
        try {
            scope.launch {
                Handler(Looper.getMainLooper()).post { completion.complete(Unit) }
            }
            awaitEditorCompletionForTest(
                description = "background result must reach Main",
                completion = completion,
                timeoutMillis = 2_000L,
                pumpMain = ::drainReadyMain,
            )
            assertTrue(completion.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mainCallbackThenDefaultWorkProgressesThroughCompletionWaiter() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val completion = CompletableDeferred<Unit>()
        try {
            Handler(Looper.getMainLooper()).post {
                scope.launch { completion.complete(Unit) }
            }
            awaitEditorCompletionForTest(
                description = "Main callback must start background work",
                completion = completion,
                timeoutMillis = 2_000L,
                pumpMain = ::drainReadyMain,
            )
            assertTrue(completion.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun timeoutIsBoundedAndDiagnostic() {
        val failure =
            runCatching {
                awaitEditorCompletionForTest(
                    description = "completion must settle",
                    completion = CompletableDeferred<Unit>(),
                    timeoutMillis = 100L,
                    pumpMain = ::drainReadyMain,
                    diagnostic = { "phase=Rendering" },
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("completion must settle"))
        assertTrue(failure?.message.orEmpty().contains("100ms"))
        assertTrue(failure?.message.orEmpty().contains("phase=Rendering"))
    }

    /**
     * Production-faithful regression: the startup coordinator launches work
     * on Main through viewModelScope; the waiter must observe it without
     * a synthetic Default-first yield.
     */
    @Test
    fun mainFirstStartupCompletesWithoutSyntheticDefaultYield() {
        val completion = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        try {
            scope.launch {
                // Simulate production startup: launched on Main, completes directly.
                Handler(Looper.getMainLooper()).post { completion.complete(Unit) }
            }
            awaitEditorCompletionForTest(
                description = "main-first startup completes without default yield",
                completion = completion,
                timeoutMillis = 2_000L,
                pumpMain = ::drainReadyMain,
            )
            assertTrue(completion.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    private fun drainReadyMain() {
        shadowOf(Looper.getMainLooper()).idleFor(0, TimeUnit.MILLISECONDS)
    }
}
