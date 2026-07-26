package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrackedBitmapTest {
    @Test
    fun transferReplacesExactEdgeWithoutDuplicatingResidentBitmap() {
        val tracker = TrackerSession("tracked-transfer")
        tracker.activateDocument("generation")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val tracked = TrackedBitmap.acquire(bitmap, scope, "prepare")

        assertTrue(tracked.transferTo("child"))
        assertEquals(1, tracker.snapshot().bitmapCount)
        tracked.recycleAndRelease()

        assertTrue(bitmap.isRecycled)
        assertEquals(0, tracker.snapshot().bitmapCount)
        scope.end()
        tracker.close()
    }

    @Test
    fun adoptionDisarmsWithoutRecyclingAndSettlementIsIdempotent() {
        val tracker = TrackerSession("tracked-adoption")
        tracker.activateDocument("generation")
        val scope = scope(tracker)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val tracked = TrackedBitmap.acquire(bitmap, scope, "helper")

        assertEquals(bitmap, tracked.disarmAfterAdoption())
        assertFalse(bitmap.isRecycled)
        assertEquals(0, tracker.snapshot().bitmapCount)
        assertFalse(tracked.recycleAndRelease())
        assertFalse(bitmap.isRecycled)
        scope.end()
        tracker.close()
        bitmap.recycle()
    }

    private fun scope(tracker: TrackerSession): MemoryTrackerScope =
        MemoryTrackerScope.create(
            tracker = tracker,
            name = "tracked",
            documentGeneration = "generation",
            baseContentToken = "base",
            revision = 1,
            snapshotState = "test",
            transientReserveBytes = 0L,
        )
}
