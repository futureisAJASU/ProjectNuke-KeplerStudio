package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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

        assertTrue(tracked.transferToOrKeep("child"))
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

        assertEquals(bitmap, tracked.requireAdopt())
        assertNull(tracked.adoptOrNull())
        assertFalse(bitmap.isRecycled)
        assertEquals(0, tracker.snapshot().bitmapCount)
        assertFalse(tracked.recycleAndRelease())
        assertFalse(bitmap.isRecycled)
        scope.end()
        tracker.close()
        bitmap.recycle()
    }

    @Test
    fun failedDestinationRegistrationKeepsSourceOwnership() {
        val trackCalls = AtomicInteger()
        val releases = AtomicInteger()
        val tracker =
            object : TrackedBitmap.EdgeTracker {
                override fun track(bitmap: Bitmap, owner: String): Long =
                    if (trackCalls.incrementAndGet() == 1) 41L else 0L

                override fun release(edge: Long) {
                    releases.incrementAndGet()
                }
            }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val tracked = TrackedBitmap.acquireForTest(bitmap, tracker, "source")

        assertFalse(tracked.transferToOrKeep("destination"))
        assertEquals(0, releases.get())
        assertTrue(tracked.recycleAndRelease())
        assertEquals(1, releases.get())
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun exactlyOneConcurrentAdopterReceivesBitmap() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val tracked = TrackedBitmap.acquireForTest(bitmap, null, "source")
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        val threads =
            List(2) {
                Thread {
                    start.await()
                    if (tracked.adoptOrNull() === bitmap) wins.incrementAndGet()
                }
            }
        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, wins.get())
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun cleanupExceptionsAreContainedAndSettlementRemainsExactOnce() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val tracked =
            TrackedBitmap.acquireForTest(
                bitmap,
                object : TrackedBitmap.EdgeTracker {
                    override fun track(bitmap: Bitmap, owner: String): Long = 9L
                    override fun release(edge: Long) = error("cleanup")
                },
                "source",
            )

        assertTrue(tracked.recycleAndRelease())
        assertFalse(tracked.recycleAndRelease())
        assertTrue(bitmap.isRecycled)
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
