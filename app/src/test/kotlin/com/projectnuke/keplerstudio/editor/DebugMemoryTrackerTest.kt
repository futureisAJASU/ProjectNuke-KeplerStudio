package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.graphics.Bitmap.Config as BitmapConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DebugMemoryTrackerTest {

    private lateinit var tracker: TrackerSession

    @Before
    fun setUp() {
        tracker = TrackerSession("test")
    }

    @After
    fun tearDown() {
        tracker.clear()
    }

    @Test
    fun testBeginEndOperation() {
        val token = tracker.beginOperation(
            name = "testOp",
            documentGeneration = "gen1",
            baseContentToken = "token1",
            revision = 1,
            transientReserveBytes = 100L,
            snapshotState = "hot"
        )
        assertTrue(token > 0L)
        tracker.endOperation("testOp", token)
        val snap = tracker.snapshot()
        assertTrue(snap.activeOperations.isEmpty())
    }

    @Test
    fun testBeginOperationZeroTokenNoOp() {
        tracker.endOperation("testOp", 0L)
        val snap = tracker.snapshot()
        assertTrue(snap.activeOperations.isEmpty())
    }

    @Test
    fun testDocumentRegistration() {
        tracker.registerDocument("gen1")
        tracker.registerDocument("gen2")
        tracker.unregisterDocument("gen1")
        val snap = tracker.snapshot()
        assertTrue(snap.estimatedPeakBytes == 0L)
    }

    @Test
    fun testNativeSessionTracking() {
        tracker.registerDocument("gen1")
        tracker.registerNativeSession(
            handle = 42L,
            documentGeneration = "gen1",
            sourceIdentity = "src1",
            state = "created"
        )
        var snap = tracker.snapshot()
        assertEquals(1, snap.nativeSessions.size)
        assertEquals("created", snap.nativeSessions.first().state)
        assertEquals(42L, snap.nativeSessions.first().handle)

        tracker.updateNativeSession(42L, "active")
        snap = tracker.snapshot()
        assertEquals("active", snap.nativeSessions.first().state)

        tracker.unregisterNativeSession(42L)
        snap = tracker.snapshot()
        assertTrue(snap.nativeSessions.isEmpty())
    }

    @Test
    fun testNativeSessionZeroHandleNoOp() {
        tracker.registerNativeSession(
            handle = 0L,
            documentGeneration = "gen1",
            sourceIdentity = "src1",
            state = "created"
        )
        val snap = tracker.snapshot()
        assertTrue(snap.nativeSessions.isEmpty())
    }

    @Test
    fun testUpdateNativeSessionNonExistentNoOp() {
        tracker.updateNativeSession(99L, "active")
        val snap = tracker.snapshot()
        assertTrue(snap.nativeSessions.isEmpty())
    }

    @Test
    fun testHistoryHotResidentMetrics() {
        tracker.setHistoryHotResident(1024L, 2)
        val snap = tracker.snapshot()
        assertEquals(1024L, snap.historyHotResidentBytes)
        assertEquals(2, snap.historyHotEntryCount)
    }

    @Test
    fun testHistoryColdCompressedMetrics() {
        tracker.setHistoryColdCompressed(4096L)
        val snap = tracker.snapshot()
        assertEquals(4096L, snap.historyColdCompressedBytes)
    }

    @Test
    fun testColdLoadDecodedTransientMetrics() {
        tracker.setColdLoadDecodedTransient(2048L)
        val snap = tracker.snapshot()
        assertEquals(2048L, snap.coldLoadDecodedTransientBytes)
    }

    @Test
    fun testDeletionDebtMetrics() {
        tracker.setDeletionDebt(512L)
        val snap = tracker.snapshot()
        assertEquals(512L, snap.deletionDebtBytes)
    }

    @Test
    fun testSnapshotReturnsEmptyOnNoData() {
        val snap = tracker.snapshot()
        assertEquals(0L, snap.totalBytes)
        assertEquals(0, snap.bitmapCount)
    }

    @Test
    fun testDebugString() {
        tracker.registerDocument("gen1")
        tracker.beginOperation(
            name = "testOp",
            documentGeneration = "gen1",
            baseContentToken = "token1",
            revision = 1,
            transientReserveBytes = 100L,
            snapshotState = "hot"
        )
        val result = tracker.debugString()
        assertTrue(result.contains("TrackerSession"))
    }

    @Test
    fun testLogSnapshotDoesNotThrow() {
        tracker.registerDocument("gen1")
        tracker.logSnapshot("testTag")
    }

    @Test
    fun testTransientReserveBytesInOperation() {
        tracker.registerDocument("gen1")
        val token = tracker.beginOperation(
            name = "opWithReserve",
            documentGeneration = "gen1",
            baseContentToken = "token1",
            revision = 1,
            transientReserveBytes = 999L,
            snapshotState = "hot"
        )
        val snap = tracker.snapshot()
        assertEquals(999L, snap.activeOperations.first().transientReserveBytes)
        tracker.endOperation("opWithReserve", token)
    }

    @Test
    fun testPeakWithNativeSessions() {
        tracker.registerDocument("gen1")
        tracker.setCurrentDocumentGeneration("gen1")
        tracker.registerNativeSession(
            handle = 1L,
            documentGeneration = "gen1",
            sourceIdentity = "src1",
            state = "created",
            knownEstimateBytes = 1024L
        )
        val snap = tracker.snapshot()
        assertTrue(snap.nativeEstimateBytes > 0L)
        assertTrue(snap.estimatedPeakBytes >= snap.nativeEstimateBytes)
    }

    @Test
    fun testRegisterBitmapRecycledIsNoOp() {
        val bitmap = createMockBitmap(1, 1)
        bitmap.recycle()
        tracker.registerBitmap(
            bitmap = bitmap,
            owner = "test",
            operation = "testOp",
            token = 0L,
            documentGeneration = "gen1"
        )
        val snap = tracker.snapshot()
        assertEquals(0L, snap.totalBytes)
    }

    @Test
    fun testUnregisterBitmapDisabledIsNoOp() {
        val mockBitmap = createMockBitmap(100, 100)
        tracker.unregisterBitmap(mockBitmap, "test")
    }

    @Test
    fun testClearResetsAllState() {
        tracker.registerDocument("gen1")
        tracker.setHistoryHotResident(1024L, 2)
        tracker.setHistoryColdCompressed(4096L)
        tracker.setDeletionDebt(512L)
        tracker.clear()
        val snap = tracker.snapshot()
        assertEquals(0L, snap.historyHotResidentBytes)
        assertEquals(0L, snap.historyColdCompressedBytes)
        assertEquals(0L, snap.deletionDebtBytes)
    }

    @Test
    fun testReleaseEdgeIsIdempotent() {
        val mockBitmap = createMockBitmap(100, 100)
        val handle = tracker.registerBitmap(
            bitmap = mockBitmap,
            owner = "test",
            operation = "testOp",
            token = 0L,
            documentGeneration = "gen1"
        )
        assertTrue(handle > 0L)
        assertTrue(tracker.releaseEdge(handle))
        assertFalse(tracker.releaseEdge(handle))
    }

    @Test
    fun testReleaseEdgeWorksAfterRecycle() {
        val mockBitmap = createMockBitmap(100, 100)
        val handle = tracker.registerBitmap(
            bitmap = mockBitmap,
            owner = "test",
            operation = "testOp",
            token = 0L,
            documentGeneration = "gen1"
        )
        mockBitmap.recycle()
        assertTrue(tracker.releaseEdge(handle))
    }

    @Test
    fun testUnregisterBitmapWorksAfterRecycle() {
        val mockBitmap = createMockBitmap(100, 100)
        tracker.registerBitmap(
            bitmap = mockBitmap,
            owner = "test",
            operation = "testOp",
            token = 0L,
            documentGeneration = "gen1"
        )
        mockBitmap.recycle()
        tracker.unregisterBitmap(mockBitmap, "test")
    }

    @Test
    fun testTwoAliasOwnersOneResidentByteCount() {
        val bitmap = createMockBitmap(100, 100)
        tracker.registerBitmap(bitmap, "ownerA", "op", 0L, "gen1")
        tracker.registerBitmap(bitmap, "ownerB", "op", 0L, "gen1")
        val snap = tracker.snapshot()
        assertEquals(1, snap.bitmapCount)
        assertEquals(bitmap.allocationByteCount.toLong(), snap.totalBytes)
    }

    @Test
    fun testTwoIndependentAcquisitionsSameOwner() {
        val bitmap1 = createMockBitmap(100, 100)
        val bitmap2 = createMockBitmap(100, 100)
        tracker.registerBitmap(bitmap1, "owner", "op", 0L, "gen1")
        tracker.registerBitmap(bitmap2, "owner", "op", 0L, "gen1")
        val snap = tracker.snapshot()
        assertEquals(2, snap.bitmapCount)
        assertEquals(bitmap1.allocationByteCount.toLong() * 2, snap.totalBytes)
    }

    @Test
    fun testReleasingOneEdgePreservesOther() {
        val bitmap = createMockBitmap(100, 100)
        val handleA = tracker.registerBitmap(bitmap, "ownerA", "op", 0L, "gen1")
        val handleB = tracker.registerBitmap(bitmap, "ownerB", "op", 0L, "gen1")
        tracker.releaseEdge(handleA)
        val snap = tracker.snapshot()
        assertEquals(1, snap.bitmapCount)
        assertTrue(snap.byOwner.containsKey("ownerB"))
    }

    @Test
    fun testIdentityHashCodeCollisionWithDistinctLiveObjects() {
        val bitmap1 = createMockBitmap(100, 100, identity = 42)
        val bitmap2 = createMockBitmap(100, 100, identity = 42)
        tracker.registerBitmap(bitmap1, "ownerA", "op", 0L, "gen1")
        tracker.registerBitmap(bitmap2, "ownerB", "op", 0L, "gen1")
        val snap = tracker.snapshot()
        assertEquals(2, snap.bitmapCount)
    }

    @Test
    fun testDocumentGenerationReplacement() {
        tracker.registerDocument("gen1")
        tracker.setCurrentDocumentGeneration("gen1")
        val handle = tracker.registerBitmap(createMockBitmap(100, 100), "test", "op", 0L, "gen1")
        tracker.registerDocument("gen2")
        tracker.setCurrentDocumentGeneration("gen2")
        tracker.unregisterDocument("gen1")
        assertEquals("gen2", tracker.currentDocumentGeneration())
    }

    @Test
    fun testOperationExactOnceCompletion() {
        val token = tracker.beginOperation("op", "gen1", "t", 1, 0L, "hot")
        tracker.endOperation("op", token)
        tracker.endOperation("op", token)
        val snap = tracker.snapshot()
        assertTrue(snap.activeOperations.isEmpty())
    }

    @Test
    fun testStaleTrackerSessionIgnoresLaterEvents() {
        val gen1 = tracker.currentDocumentGeneration()
        tracker.registerBitmap(createMockBitmap(100, 100), "test", "op", 0L, gen1)
        tracker.clear()
        val snap = tracker.snapshot()
        assertTrue(snap.activeOperations.isEmpty())
        assertEquals(0, snap.bitmapCount)
    }

    @Test
    fun testMonotonicConcurrentPeakUpdate() {
        tracker.registerDocument("gen1")
        tracker.registerNativeSession(1L, "gen1", "src", "active", knownEstimateBytes = 1000L)
        val snap1 = tracker.snapshot()
        tracker.registerNativeSession(2L, "gen1", "src", "active", knownEstimateBytes = 5000L)
        val snap2 = tracker.snapshot()
        assertTrue(snap2.estimatedPeakBytes >= snap1.estimatedPeakBytes)
        assertTrue(snap2.nativeEstimateBytes >= snap1.nativeEstimateBytes)
    }

    @Test
    fun testUnknownNativeEstimateDoesNotBecomeZero() {
        tracker.registerDocument("gen1")
        tracker.registerNativeSession(1L, "gen1", "src", "created", knownEstimateBytes = -1L)
        val snap = tracker.snapshot()
        assertEquals(1L, snap.unknownNativeBytes)
        assertTrue(snap.combinedKnownEstimatedBytes > 0L || snap.unknownNativeBytes > 0L)
    }

    @Test
    fun testHistoryMetricsSnapshot() {
        val metrics = TrackerSession.HistoryMetricsSnapshot(
            editorInstanceId = "test",
            coordinatorGeneration = "gen1",
            hotEntryCount = 3,
            hotResidentBytes = 4096L,
            retainedColdCompressedBytes = 8192L,
            pendingDeletionDebtBytes = 512L,
            activeColdLoadDecodedBytes = 2048L,
            operationActive = false,
            loading = false,
            spilling = false,
            adopting = false,
            protectedTargetId = null,
            timestamp = 12345L
        )
        tracker.setHistoryMetricsSnapshot(metrics)
        assertEquals(metrics, tracker.historySnapshot())
    }

    @Test
    fun closeRejectsLateEventsAndRemovesOnlyItsHolderEntry() {
        val id = tracker.editorInstanceId
        tracker.activateDocument("gen-a")
        tracker.close()

        tracker.registerBitmap(createMockBitmap(10, 10), "late", "late", 0L, "gen-a")
        tracker.beginOperation("late", "gen-a", "base", 1, 10L, "hot")
        tracker.registerNativeSession(9L, "gen-a", "source", "late")

        val snap = tracker.snapshot()
        assertEquals(0, snap.bitmapCount)
        assertTrue(snap.activeOperations.isEmpty())
        assertTrue(snap.nativeSessions.isEmpty())
        assertFalse(TrackerSessionHolder.sessions.containsKey(id))
    }

    private fun createMockBitmap(width: Int, height: Int, identity: Int = -1): Bitmap {
        return Bitmap.createBitmap(width, height, BitmapConfig.ARGB_8888)
    }
}
