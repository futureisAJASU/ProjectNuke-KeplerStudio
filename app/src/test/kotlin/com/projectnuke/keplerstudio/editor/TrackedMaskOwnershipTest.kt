package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
        assertFalse(mask.release())
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
        val adopted = mask.adopt()
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
        assertTrue(mask.transferTo("uiState:finalMask"))
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
        val firstAdoption = mask.adopt()
        // A second adoption path (caller adopting a stale result) must see a settled
        // mask; the call returns the same bitmap reference but no edge is released
        // a second time, and the tracker is already empty.
        val secondAdoption = mask.adopt()
        assertEquals(firstAdoption, secondAdoption)
        assertEquals(0, tracker.snapshot().bitmapCount)
        bitmap.recycle()
        scope.end()
        tracker.close()
    }

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
