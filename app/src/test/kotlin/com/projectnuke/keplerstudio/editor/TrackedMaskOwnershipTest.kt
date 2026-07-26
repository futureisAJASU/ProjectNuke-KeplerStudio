package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gate 3 tracked-mask ownership semantics.
 *
 * TrackedMask is the structured replacement for the raw "Bitmap + separately
 * mutable Long diagnosticEdge" pattern. Tests cover immediate edge
 * registration on allocation, idempotent release/recycle/transfer, helper-failure
 * recycles-and-releases, stale settles without publication, caller adopts exactly
 * once, and cleanup remains exact-once even when an exception is thrown during
 * caller transfer (caller never adopted, so the finally recycles the mask).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrackedMaskOwnershipTest {
    @After
    fun cleanup() {
        scrubTrackerSessions()
    }

    @Test
    fun acquireRegistersEdgeImmediatelyAndBecomesVisibleToSnapshot() {
        val tracker = TrackerSession("tracked-acquire")
        tracker.activateDocument("gen")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val mask =
            TrackedMask.acquire(
                bitmap = bitmap,
                scope = scope,
                owner = "model:finalMask",
                modelId = "flare-masker",
                modelVersion = "1.0.0",
                operationToken = 17L,
                documentGeneration = "gen",
                confidenceMetrics = fullConfidence(),
            )
        // The mask AND its edge are present in the snapshot; the mask itself
        // remains visible while internal buffers retain their own edges.
        assertEquals(1, tracker.snapshot().bitmapCount)
        assertEquals(17L, mask.operationToken)
        assertEquals("gen", mask.documentGeneration)
        mask.recycleAndRelease()
        assertTrue(bitmap.isRecycled) // bitmap recycled by recycleAndRelease
        scope.end()
        tracker.close()
    }

    @Test
    fun recycleAndReleaseIsIdempotentAndKeepsEdgeReleasedExactlyOnce() {
        val tracker = TrackerSession("tracked-idempotent")
        tracker.activateDocument("gen")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val mask =
            TrackedMask.acquire(bitmap, scope, "model:finalMask", "flare-masker", "1.0.0", 0L, "gen", fullConfidence())
        assertTrue(mask.recycleAndRelease())
        assertFalse(mask.recycleAndRelease())
        assertFalse(mask.releaseWithoutRecycle())
        assertEquals(0, tracker.snapshot().bitmapCount)
        scope.end()
        tracker.close()
    }

    @Test
    fun adoptReleasesEdgeWithoutRecyclingAndIsExactOnce() {
        val tracker = TrackerSession("tracked-adopt")
        tracker.activateDocument("gen")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val mask =
            TrackedMask.acquire(bitmap, scope, "model:finalMask", "flare-masker", "1.0.0", 0L, "gen", fullConfidence())
        val adopted = mask.requireAdopt()
        assertEquals(bitmap, adopted)
        assertFalse(bitmap.isRecycled)
        assertEquals(0, tracker.snapshot().bitmapCount)
        // Subsequent operations must be no-ops.
        assertFalse(mask.recycleAndRelease())
        bitmap.recycle()
        scope.end()
        tracker.close()
    }

    @Test
    fun transferRekeysEdgeWithoutDuplicatingAndBecomesActiveOwner() {
        val tracker = TrackerSession("tracked-transfer")
        tracker.activateDocument("gen")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val mask =
            TrackedMask.acquire(bitmap, scope, "model:finalMask", "flare-masker", "1.0.0", 0L, "gen", fullConfidence())
        assertTrue(mask.transferToOrKeep("uiState:finalMask"))
        assertEquals("uiState:finalMask", mask.diagnosticOwner)
        // Bitmap count remains 1; no duplicate tracking edge.
        assertEquals(1, tracker.snapshot().bitmapCount)
        mask.recycleAndRelease()
        assertEquals(0, tracker.snapshot().bitmapCount)
        scope.end()
        tracker.close()
    }

    @Test
    fun helperFailureRecyclesAndReleasesReminderAndSettlesWithoutPublication() {
        val tracker = TrackerSession("tracked-helper-failure")
        tracker.activateDocument("gen")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val tracked =
            TrackedMask.acquire(bitmap, scope, "model:finalMask", "flare-masker", "1.0.0", 0L, "gen", fullConfidence())
        // "Helper failure" path: the caller simulates an exception during the work
        // that happens AFTER allocate but BEFORE publishing to UI/history. The
        // mask is settled (recycled + released) without ever being published.
        var published: Bitmap? = null
        try {
            error("downstream helper failed")
        } catch (failure: Throwable) {
            tracked.recycleAndRelease()
            published = null
        }
        assertTrue(tracked.isSettled)
        assertEquals(null, published)
        assertEquals(0, tracker.snapshot().bitmapCount)
        scope.end()
        tracker.close()
    }

    @Test
    fun staleAdoptionTransfersOwnershipExactlyOnceAndCatchesDoubleAdoption() {
        val tracker = TrackerSession("tracked-stale-adoption")
        tracker.activateDocument("gen")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val mask =
            TrackedMask.acquire(bitmap, scope, "stale:finalMask", "flare-masker", "1.0.0", 0L, "gen", fullConfidence())
        val firstAdoption = mask.requireAdopt()
        // A second adoption explicitly fails and can never receive the bitmap again.
        assertNull(mask.adoptOrNull())
        assertFailsWith<IllegalStateException> { mask.requireAdopt() }
        assertEquals(bitmap, firstAdoption)
        assertEquals(0, tracker.snapshot().bitmapCount)
        bitmap.recycle()
        scope.end()
        tracker.close()
    }

    @Test
    fun twoAdoptersAreLinearized() {
        val mask = maskWithCountingTracker()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) { pool.submit<Bitmap?> { start.await(); mask.adoptOrNull() } }
            start.countDown()
            assertEquals(1, results.count { it.get() != null })
        } finally {
            pool.shutdownNow()
            if (!mask.bitmap.isRecycled) mask.bitmap.recycle()
        }
    }

    @Test
    fun adoptVersusRecycleAndReleaseAreLinearized() {
        repeat(40) { raceSettlement { it.adoptOrNull() } }
        repeat(40) { raceSettlement { it.releaseWithoutRecycle() } }
    }

    @Test
    fun transferVersusRecycleAndReleaseAreLinearized() {
        repeat(40) { raceTransfer { it.recycleAndRelease() } }
        repeat(40) { raceTransfer { it.releaseWithoutRecycle() } }
    }

    @Test
    fun destinationRegistrationFailureKeepsOriginalOwnership() {
        val releases = AtomicInteger()
        val tracker = object : TrackedMask.EdgeTracker {
            var calls = 0
            override fun track(bitmap: Bitmap, owner: String): Long {
                calls++
                if (calls > 1) throw IllegalStateException("registration failed")
                return 11L
            }
            override fun release(edge: Long) { releases.incrementAndGet() }
        }
        val mask = testMask(tracker)
        assertFalse(mask.transferToOrKeep("destination"))
        assertEquals("source", mask.diagnosticOwner)
        assertEquals(11L, mask.exactEdge())
        assertTrue(mask.recycleAndRelease())
        assertEquals(1, releases.get())
    }

    @Test
    fun cleanupExceptionsAreNoThrowAndSettlementIsExactOnce() {
        val tracker = object : TrackedMask.EdgeTracker {
            override fun track(bitmap: Bitmap, owner: String) = 7L
            override fun release(edge: Long) { error("cleanup failure") }
        }
        val mask = testMask(tracker)
        assertTrue(mask.recycleAndRelease())
        assertFalse(mask.recycleAndRelease())
        assertTrue(mask.bitmap.isRecycled)
    }

    private fun raceSettlement(other: (TrackedMask) -> Any?) {
        val mask = maskWithCountingTracker()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val adopt = pool.submit<Bitmap?> { start.await(); mask.adoptOrNull() }
            val settle = pool.submit<Any?> { start.await(); other(mask) }
            start.countDown()
            val adopted = adopt.get()
            settle.get()
            if (adopted != null) assertFalse(adopted.isRecycled)
            assertTrue(mask.isSettled)
        } finally {
            pool.shutdownNow()
            if (!mask.bitmap.isRecycled) mask.bitmap.recycle()
        }
    }

    private fun raceTransfer(other: (TrackedMask) -> Any?) {
        val releases = AtomicInteger()
        val tracker = object : TrackedMask.EdgeTracker {
            private val next = AtomicInteger(0)
            override fun track(bitmap: Bitmap, owner: String) = next.incrementAndGet().toLong()
            override fun release(edge: Long) { releases.incrementAndGet() }
        }
        val mask = testMask(tracker)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val transfer = pool.submit<Boolean> { start.await(); mask.transferToOrKeep("destination") }
            val settle = pool.submit<Any?> { start.await(); other(mask) }
            start.countDown()
            transfer.get()
            settle.get()
            assertTrue(mask.isSettled)
            assertTrue(releases.get() in 1..2)
        } finally {
            pool.shutdownNow()
            if (!mask.bitmap.isRecycled) mask.bitmap.recycle()
        }
    }

    private fun maskWithCountingTracker(): TrackedMask = testMask(
        object : TrackedMask.EdgeTracker {
            override fun track(bitmap: Bitmap, owner: String) = 1L
            override fun release(edge: Long) = Unit
        },
    )

    private fun testMask(tracker: TrackedMask.EdgeTracker): TrackedMask =
        TrackedMask.acquireForTest(
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
            tracker, "source", "model", "1", 1L, "gen", fullConfidence(),
        )

    private fun fullConfidence(): ModelConfidence =
        ModelConfidence(
            wholeImageMean = 0.5f,
            peak = 0.9f,
            activeRegionMean = 0.7f,
            activeRegionPercentile = 0.8f,
            affectedAreaRatio = 0.2f,
            backgroundLeakage = 0.05f,
            finalPolicy = 0.6f,
        )

    private fun scope(tracker: TrackerSession): MemoryTrackerScope =
        MemoryTrackerScope.create(
            tracker = tracker,
            name = "tracked",
            documentGeneration = "gen",
            baseContentToken = "base",
            revision = 1,
            snapshotState = "test",
            transientReserveBytes = 0L,
        )

    private fun scrubTrackerSessions() {
        TrackerSessionHolder.sessions.values.forEach { runCatching { it.close() } }
        TrackerSessionHolder.sessions.clear()
    }
}
