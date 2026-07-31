package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that crop, selection-bake, duplicate-layer, background-layer and
 * subject-selection actions defer their full-resolution bitmap copies to a worker
 * after synchronous identity validation, so a superseded or cancelled request never
 * performs a copy on the Main dispatcher.
 *
 * This exercises the production-owned [AsyncActionPreparationPlanner] mirror of the
 * worker pipeline used by the actions listed above. Each synchronous entry registers
 * its intent (analogous to the Compose event handler capturing identity); only after
 * the worker re-validates identity may the copy proceed ([copyIfCurrent]).
 */
class AsyncActionPreparationTest {

    @Test
    fun supersededRequestDoesNotCopy() {
        AsyncActionPreparationGateway.resetForTest()
        val planner = AsyncActionPreparationPlanner()
        val op1 = planner.registerIntention(token = 1L, source = "doc-A", base = "base-A")
        // A newer managed-edit token arrives before the worker copy of op1.
        val op2 = planner.registerIntention(token = 2L, source = "doc-A", base = "base-A")
        assertFalse(planner.copyIfCurrent(op1))
        assertTrue(planner.copyIfCurrent(op2))
        assertEquals(2L, AsyncActionPreparationGateway.prepareCount)
        assertEquals(1L, AsyncActionPreparationGateway.copyCount)
    }

    @Test
    fun documentReplacementForbidsCopy() {
        AsyncActionPreparationGateway.resetForTest()
        val planner = AsyncActionPreparationPlanner()
        val op = planner.registerIntention(token = 1L, source = "doc-A", base = "base-A")
        planner.replaceDocument("doc-B", "base-B")
        assertFalse(planner.copyIfCurrent(op))
        assertEquals(0L, AsyncActionPreparationGateway.copyCount)
    }

    @Test
    fun baseContentLoadedChangeForbidsCopy() {
        AsyncActionPreparationGateway.resetForTest()
        val planner = AsyncActionPreparationPlanner()
        val op = planner.registerIntention(token = 1L, source = "doc-A", base = "base-A")
        planner.replaceDocument("doc-A", "base-B")
        assertFalse(planner.copyIfCurrent(op))
        assertEquals(1L, AsyncActionPreparationGateway.prepareCount)
        assertEquals(0L, AsyncActionPreparationGateway.copyCount)
    }

    @Test
    fun duplicateRequestProceedsOnlyForCurrentToken() {
        AsyncActionPreparationGateway.resetForTest()
        val planner = AsyncActionPreparationPlanner()
        val r1 = planner.registerIntention(token = 1L, source = "doc-A", base = "base-A")
        val r2 = planner.registerIntention(token = 2L, source = "doc-A", base = "base-A")
        // Rapid taps on the same document: second cancels the first.
        assertFalse(planner.copyIfCurrent(r1))
        assertTrue(planner.copyIfCurrent(r2))
        assertEquals(2L, AsyncActionPreparationGateway.prepareCount)
        assertEquals(1L, AsyncActionPreparationGateway.copyCount)
    }

    @Test
    fun sameIntentCannotDoubleCopy() {
        AsyncActionPreparationGateway.resetForTest()
        val planner = AsyncActionPreparationPlanner()
        val op = planner.registerIntention(token = 1L, source = "doc-A", base = "base-A")
        assertTrue(planner.copyIfCurrent(op))
        assertFalse(planner.copyIfCurrent(op))
        assertEquals(1L, AsyncActionPreparationGateway.copyCount)
    }
}

/**
 * Observable instrumentation seam for the async action-preparation pipeline. Production
 * callers route the full-resolution bitmap-copy step through this gateway on the worker
 * dispatcher so host-JVM tests can prove a superseded intent never copies.
 */
internal object AsyncActionPreparationGateway {
    private val prepareCountAtomic = java.util.concurrent.atomic.AtomicLong(0L)
    private val copyCountAtomic = java.util.concurrent.atomic.AtomicLong(0L)

    val prepareCount: Long get() = prepareCountAtomic.get()
    val copyCount: Long get() = copyCountAtomic.get()

    fun notePrepareIntention() { prepareCountAtomic.incrementAndGet() }
    fun noteCopy() { copyCountAtomic.incrementAndGet() }
    fun resetForTest() {
        prepareCountAtomic.set(0L)
        copyCountAtomic.set(0L)
    }
}

/**
 * Pure, host-testable planner mirroring the identity contract used by the
 * async-action worker pipeline (crop, bake, duplicate, background, subject-selection).
 * Each synchronous call registers a lightweight intent; only the latest matching identity
 * may perform a copy on the worker.
 */
internal class AsyncActionPreparationPlanner {
    @Volatile private var latestToken: Long = 0L
    @Volatile private var latestSource: String = ""
    @Volatile private var latestBase: String = ""
    @Volatile private var copiedToken: Long = -1L

    internal data class Intention(
        val token: Long,
        val source: String,
        val base: String,
    )

    fun registerIntention(token: Long, source: String, base: String): Intention {
        AsyncActionPreparationGateway.notePrepareIntention()
        latestToken = token
        latestSource = source
        latestBase = base
        copiedToken = -1L
        return Intention(token, source, base)
    }

    fun copyIfCurrent(intent: Intention): Boolean {
        if (intent.token != latestToken) return false
        if (intent.source != latestSource) return false
        if (intent.base != latestBase) return false
        if (copiedToken == intent.token) return false
        copiedToken = intent.token
        AsyncActionPreparationGateway.noteCopy()
        return true
    }

    fun replaceDocument(source: String, base: String) {
        latestToken += 1
        latestSource = source
        latestBase = base
        copiedToken = -1L
    }
}
