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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DebugMemoryTrackerTest {

    private lateinit var tracker: TrackerSession

    @Before
    fun setUp() {
        tracker = TrackerSession("test")
        tracker.activateDocument("gen1")
    }

    @After
    fun tearDown() {
        tracker.close()
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
        publishHistory(hotBytes = 1024L, hotCount = 2)
        val snap = tracker.snapshot()
        assertEquals(1024L, snap.historyHotResidentBytes)
        assertEquals(2, snap.historyHotEntryCount)
    }

    @Test
    fun testHistoryColdCompressedMetrics() {
        publishHistory(coldBytes = 4096L)
        val snap = tracker.snapshot()
        assertEquals(4096L, snap.historyColdCompressedBytes)
    }

    @Test
    fun testColdLoadDecodedTransientMetrics() {
        publishHistory(coldLoadBytes = 2048L)
        val snap = tracker.snapshot()
        assertEquals(2048L, snap.coldLoadDecodedTransientBytes)
    }

    @Test
    fun testDeletionDebtMetrics() {
        publishHistory(deletionDebtBytes = 512L)
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
    fun clearForTestResetsAllStateWithoutClosing() {
        publishHistory(hotBytes = 1024L, hotCount = 2, coldBytes = 4096L, deletionDebtBytes = 512L)
        tracker.clearForTest()
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
        tracker.registerBitmap(bitmap1, "owner", "op", 0L, "gen1")
        tracker.registerBitmap(bitmap1, "owner", "op", 0L, "gen1")
        val snap = tracker.snapshot()
        assertEquals(1, snap.bitmapCount)
        assertEquals(2, snap.acquisitionCount)
        assertEquals(2, snap.byOwnerAcquisitionCount["owner"])
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
        val collisionTracker = TrackerSession("collision") { 42 }
        try {
            collisionTracker.activateDocument("gen1")
            val bitmap1 = createMockBitmap(100, 100)
            val bitmap2 = createMockBitmap(100, 100)
            val handle1 = collisionTracker.registerBitmap(bitmap1, "ownerA", "op", 0L, "gen1")
            val handle2 = collisionTracker.registerBitmap(bitmap2, "ownerB", "op", 0L, "gen1")
            assertEquals(2, collisionTracker.snapshot().bitmapCount)
            assertTrue(collisionTracker.releaseEdge(handle1))
            assertEquals(1, collisionTracker.snapshot().bitmapCount)
            assertTrue(collisionTracker.releaseEdge(handle2))
        } finally {
            collisionTracker.close()
        }
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
        tracker.close()
        val snap = tracker.snapshot()
        assertTrue(snap.activeOperations.isEmpty())
        assertEquals(0, snap.bitmapCount)
    }

    @Test
    fun concurrentPeakUpdateIsMonotonic() {
        val barrier = CyclicBarrier(3)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val smaller = executor.submit {
                barrier.await()
                tracker.registerNativeSession(1L, "gen1", "small", "active", knownEstimateBytes = 1_000L)
                barrier.await()
            }
            val larger = executor.submit {
                barrier.await()
                tracker.registerNativeSession(2L, "gen1", "large", "active", knownEstimateBytes = 5_000L)
                barrier.await()
            }
            barrier.await()
            barrier.await()
            smaller.get(5, TimeUnit.SECONDS)
            larger.get(5, TimeUnit.SECONDS)
            assertTrue(tracker.snapshot().estimatedPeakBytes >= 6_000L)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun testUnknownNativeEstimateDoesNotBecomeZero() {
        tracker.registerDocument("gen1")
        tracker.registerNativeSession(1L, "gen1", "src", "created", knownEstimateBytes = -1L)
        val snap = tracker.snapshot()
        assertEquals(1L, snap.unknownNativeContributorCount)
        assertTrue(snap.combinedHasUnknownContributors)
        assertEquals(null, snap.combinedCompleteEstimatedBytes)
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
        tracker.publishHistoryMetrics(metrics)
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

    @Test
    fun lastReleaseRacingNewAcquisitionPreservesLedgerInvariants() {
        val bitmap = createMockBitmap(100, 100)
        val first = tracker.registerBitmap(bitmap, "owner", "op", 0L, "gen1")
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        var second = 0L
        try {
            executor.execute {
                start.await()
                tracker.releaseEdge(first)
                done.countDown()
            }
            executor.execute {
                start.await()
                second = tracker.registerBitmap(bitmap, "owner", "op", 0L, "gen1")
                done.countDown()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(second > 0L)
            assertTrue(tracker.ledgerInvariantViolations().isEmpty())
            assertEquals(1, tracker.snapshot().bitmapCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun closeRacingRegistrationCannotRepopulateSession() {
        val bitmap = createMockBitmap(100, 100)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            executor.execute {
                start.await()
                tracker.close()
                done.countDown()
            }
            executor.execute {
                start.await()
                repeat(100) { tracker.registerBitmap(bitmap, "race", "race", 0L, "gen1") }
                done.countDown()
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(0, tracker.snapshot().bitmapCount)
            assertTrue(tracker.ledgerInvariantViolations().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun holderRemovalIsConditionalOnExactInstance() {
        val older = TrackerSession("same-id")
        val newer = TrackerSession("same-id")
        older.close()
        assertTrue(TrackerSessionHolder.sessions["same-id"] === newer)
        newer.close()
        assertFalse(TrackerSessionHolder.sessions.containsKey("same-id"))
    }

    @Test
    fun diskMetricsDoNotContributeToCombinedRam() {
        val before = tracker.snapshot().combinedKnownEstimatedBytes
        publishHistory(coldBytes = 50_000L, deletionDebtBytes = 25_000L)
        assertEquals(before, tracker.snapshot().combinedKnownEstimatedBytes)
    }

    @Test
    fun staleHistoryPublicationIsIgnoredAfterReplacement() {
        tracker.activateDocument("gen2", "gen1")
        publishHistory(generation = "gen2", hotBytes = 200L, hotCount = 1)
        publishHistory(generation = "gen1", hotBytes = 999L, hotCount = 9)
        assertEquals(200L, tracker.historySnapshot()!!.hotResidentBytes)
    }

    @Test
    fun nullableReleasePathCreatesNoSessionOrOperationToken() {
        val holderBefore = TrackerSessionHolder.sessions.toMap()
        val diagnostics = DebugMemoryTracker.diagnostics(null)
        assertEquals(0L, diagnostics.beginOperation("noop", "gen", "base", 1, 1_000L, "allocating"))
        assertEquals(0L, diagnostics.registerBitmap(createMockBitmap(1, 1), "noop", "noop", 0L, "gen"))
        assertEquals(holderBefore, TrackerSessionHolder.sessions.toMap())
    }

    @Test
    fun globalModelUnknownContributorHasExplicitStateAndCount() {
        try {
            GlobalModelDiagnostics.publish("test-model", "inferring")
            val snapshot = tracker.snapshot()
            assertTrue(snapshot.globalModelContributors.any { it.name == "test-model" && it.state == "inferring" })
            assertEquals(1L, snapshot.unknownNativeContributorCount)
            assertEquals(null, snapshot.combinedCompleteEstimatedBytes)
        } finally {
            GlobalModelDiagnostics.publish("test-model", "unloaded")
        }
    }

    private fun publishHistory(
        generation: String = "gen1",
        hotBytes: Long = 0L,
        hotCount: Int = 0,
        coldBytes: Long = 0L,
        deletionDebtBytes: Long = 0L,
        coldLoadBytes: Long = 0L
    ) {
        tracker.publishHistoryMetrics(
            TrackerSession.HistoryMetricsSnapshot(
                editorInstanceId = tracker.editorInstanceId,
                coordinatorGeneration = generation,
                hotEntryCount = hotCount,
                hotResidentBytes = hotBytes,
                retainedColdCompressedBytes = coldBytes,
                pendingDeletionDebtBytes = deletionDebtBytes,
                activeColdLoadDecodedBytes = coldLoadBytes,
                operationActive = coldLoadBytes > 0L,
                loading = coldLoadBytes > 0L,
                spilling = false,
                adopting = false,
                protectedTargetId = null,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun createMockBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, BitmapConfig.ARGB_8888)
    }
}
