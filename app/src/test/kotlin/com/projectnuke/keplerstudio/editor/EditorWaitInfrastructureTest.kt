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
     * Focused regression: the startup coordinator launches work on Default/IO
     * that must begin before Main is pumped. Without the pre-pump yield,
     * the waiter never sees the startup-completion signal.
     */
    @Test
    fun startupCoordinatorRequiresDefaultYieldBeforeMainPump() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ready = CompletableDeferred<Unit>()
        try {
            scope.launch(Dispatchers.Default) {
                // Simulate startup coordinator: begin on Default, then signal.
                yield()
                Handler(Looper.getMainLooper()).post { ready.complete(Unit) }
            }
            awaitEditorCompletionForTest(
                description = "startup coordinator default work must reach main",
                completion = ready,
                timeoutMillis = 2_000L,
                pumpMain = ::drainReadyMain,
            )
            assertTrue(ready.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    private fun drainReadyMain() {
        shadowOf(Looper.getMainLooper()).idleFor(0, TimeUnit.MILLISECONDS)
    }
}
