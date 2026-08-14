package com.projectnuke.keplerstudio.editor

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun backgroundThenMainProgressesThroughSharedWaiter() {
        val executor = Executors.newSingleThreadExecutor()
        val completed = AtomicBoolean(false)
        try {
            executor.execute {
                Handler(Looper.getMainLooper()).post { completed.set(true) }
            }
            awaitEditorConditionForTest(
                description = "background result must reach Main",
                timeoutMillis = 2_000L,
                pumpMain = { shadowOf(Looper.getMainLooper()).idleFor(0, TimeUnit.MILLISECONDS) },
            ) { completed.get() }
            assertTrue(completed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun mainThenBackgroundProgressesThroughSharedWaiter() {
        val executor = Executors.newSingleThreadExecutor()
        val completed = AtomicBoolean(false)
        try {
            Handler(Looper.getMainLooper()).post {
                executor.execute { completed.set(true) }
            }
            awaitEditorConditionForTest(
                description = "Main callback must start background work",
                timeoutMillis = 2_000L,
                pumpMain = { shadowOf(Looper.getMainLooper()).idleFor(0, TimeUnit.MILLISECONDS) },
            ) { completed.get() }
            assertTrue(completed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun timeoutIsBoundedAndDiagnostic() {
        val failure =
            runCatching {
                awaitEditorConditionForTest(
                    description = "condition must settle",
                    timeoutMillis = 100L,
                    predicate = { false },
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("condition must settle"))
        assertTrue(failure?.message.orEmpty().contains("100ms"))
    }
}
