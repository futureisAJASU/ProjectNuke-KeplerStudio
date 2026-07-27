package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Deterministic transition coverage of the production history coordinator.
 *
 * Persistence is the production-used [HistoryStorageBackend] seam. Bitmap ownership, stack
 * transitions, cancellation, replacement, recovery, and UI adoption all execute the real
 * [EditorHistoryCoordinator].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorHistoryCoordinatorTransitionTest {
    private data class StorageRecord(
        val generation: String,
        val storage: HistorySnapshotStorage,
        val width: Int,
        val height: Int,
        val color: Int,
        val bytes: Long,
    )

    private lateinit var context: Context
    private lateinit var coordinator: EditorHistoryCoordinator
    private lateinit var testScope: TestScope
    private lateinit var dispatcher: TestDispatcher
    private lateinit var storage: DeterministicHistoryStorage
    private var ramBudget = 1_024L

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        Dispatchers.setMain(dispatcher)
        storage = DeterministicHistoryStorage(context)
        coordinator =
            EditorHistoryCoordinator(
                context,
                testScope,
                tracker = null,
                settlementDispatcher = dispatcher,
                storage = storage,
                historyRamBudgetBytes = { ramBudget },
                historyDiskBudgetBytes = { 1_024L * 1_024L },
            )
        testScope.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        coordinator.close()
        testScope.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun exactSnapshotToHotAndCloseDrainsTransferredBitmap() = testScope.runTest {
        val bitmap = bitmap(0xff102030.toInt())

        val admission = coordinator.admitAdoptedSnapshot(snapshot(bitmap), true, 0L)

        assertTrue(admission.retained)
        assertFalse(admission.movedToStorage)
        assertTrue(coordinator.flags().canUndo)
        assertFalse(bitmap.isRecycled)
        coordinator.close()
        advanceUntilIdle()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun exactSnapshotDirectToColdRecyclesRamAndRetainsEntry() = testScope.runTest {
        ramBudget = 8L
        val bitmap = bitmap(0xff304050.toInt())

        val admission = coordinator.admitAdoptedSnapshot(snapshot(bitmap), true, 0L)

        assertTrue(admission.retained)
        assertTrue(admission.movedToStorage)
        assertTrue(bitmap.isRecycled)
        assertEquals(1, storage.records.size)
        assertTrue(coordinator.flags().canUndo)
    }

    @Test
    fun failedAdmissionRecyclesAndRetainsNoEntry() = testScope.runTest {
        val bitmap = bitmap(0xff405060.toInt())
        val stale = snapshot(bitmap).copy(coordinatorGeneration = "stale")

        val result = coordinator.admitAdoptedSnapshot(stale, true, 0L)

        assertFalse(result.retained)
        assertTrue(bitmap.isRecycled)
        assertFalse(coordinator.flags().canUndo)
    }

    @Test
    fun cancellationAfterCoordinatorTransferCannotRecycleRetainedSnapshot() = testScope.runTest {
        val bitmap = bitmap(0xff506070.toInt())
        storage.publishGate = CompletableDeferred()
        val job =
            launch {
                coordinator.admitAdoptedSnapshot(
                    snapshot(bitmap),
                    clearRedo = true,
                    foregroundReserveBytes = ramBudget,
                )
            }
        storage.publishStarted.await()

        job.cancelAndJoin()

        assertTrue(coordinator.flags().canUndo)
        assertFalse(bitmap.isRecycled)
        coordinator.close()
        advanceUntilIdle()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun documentReplacementDuringAdmissionSettlesOldGeneration() = testScope.runTest {
        val bitmap = bitmap(0xff607080.toInt())
        val oldGeneration = coordinator.currentGeneration()
        storage.publishGate = CompletableDeferred()
        val job =
            launch {
                coordinator.admitAdoptedSnapshot(
                    snapshot(bitmap),
                    clearRedo = true,
                    foregroundReserveBytes = ramBudget,
                )
            }
        storage.publishStarted.await()

        coordinator.replaceDocument()
        storage.publishGate?.complete(Unit)
        advanceUntilIdle()
        job.join()

        assertFalse(oldGeneration == coordinator.currentGeneration())
        assertFalse(coordinator.flags().canUndo)
        assertTrue(bitmap.isRecycled)
        assertTrue(storage.deletedPayloads.get() >= 1)
    }

    @Test
    fun hotUndoCapturesCurrentAndTransfersTargetToUi() = testScope.runTest {
        val target = bitmap(0xff708090.toInt())
        coordinator.admitAdoptedSnapshot(snapshot(target), true, 0L)
        val current = bitmap(0xff8090a0.toInt())
        var adopted: Bitmap? = null

        val result =
            coordinator.navigate(
                undoDirection = true,
                expectedTargetId = coordinator.navigationTargetId(true),
                currentCaptureBytes = BitmapMemoryBudget.bytes(current),
                captureCurrent = { storageKind, _ -> snapshot(current, storageKind) },
                materialize = { value, transfer -> value.also(transfer) },
                adopt = {
                    adopted = it.previewBitmap
                    true
                },
            )

        assertTrue(result is HistoryNavigationResult.Adopted)
        assertSame(target, adopted)
        assertFalse(target.isRecycled)
        assertTrue(coordinator.flags().canRedo)
        coordinator.close()
        advanceUntilIdle()
        assertTrue(current.isRecycled)
        target.recycle()
    }

    @Test
    fun coldUndoDecodesThenTransfersOnlyDecodedBitmapToUi() = testScope.runTest {
        ramBudget = 8L
        val original = bitmap(0xff90a0b0.toInt())
        coordinator.admitAdoptedSnapshot(snapshot(original), true, 0L)
        assertTrue(original.isRecycled)
        ramBudget = 1_024L
        val current = bitmap(0xffa0b0c0.toInt())
        var adopted: Bitmap? = null

        val result =
            coordinator.navigate(
                undoDirection = true,
                currentCaptureBytes = BitmapMemoryBudget.bytes(current),
                captureCurrent = { storageKind, _ -> snapshot(current, storageKind) },
                materialize = { value, transfer -> value.also(transfer) },
                adopt = {
                    adopted = it.previewBitmap
                    true
                },
            )

        assertTrue(result is HistoryNavigationResult.Adopted)
        assertEquals(1, storage.loads.get())
        assertTrue(adopted != null && adopted !== original && !adopted!!.isRecycled)
        coordinator.close()
        advanceUntilIdle()
        assertTrue(current.isRecycled)
        adopted?.recycle()
    }

    @Test
    fun metadataOnlyUndoMaterializesAndTransfersRenderedBitmap() = testScope.runTest {
        coordinator.admitAdoptedSnapshot(snapshot(null, HistorySnapshotStorage.MetadataOnly), true, 0L)
        val rendered = bitmap(0xffb0c0d0.toInt())
        var adopted: Bitmap? = null

        val result =
            coordinator.navigate(
                undoDirection = true,
                currentCaptureBytes = 0L,
                captureCurrent = { storageKind, _ -> snapshot(null, storageKind) },
                materialize = { value, transfer ->
                    value.copy(previewBitmap = rendered, storage = HistorySnapshotStorage.Exact)
                        .also(transfer)
                },
                adopt = {
                    adopted = it.previewBitmap
                    true
                },
            )

        assertTrue(result is HistoryNavigationResult.Adopted)
        assertSame(rendered, adopted)
        assertFalse(rendered.isRecycled)
        rendered.recycle()
    }

    @Test
    fun failedUiAdoptionKeepsTargetAndSettlesCurrentCapture() = testScope.runTest {
        val target = bitmap(0xffc0d0e0.toInt())
        coordinator.admitAdoptedSnapshot(snapshot(target), true, 0L)
        val current = bitmap(0xffd0e0f0.toInt())

        val result =
            coordinator.navigate(
                undoDirection = true,
                currentCaptureBytes = BitmapMemoryBudget.bytes(current),
                captureCurrent = { storageKind, _ -> snapshot(current, storageKind) },
                materialize = { value, transfer -> value.also(transfer) },
                adopt = { false },
            )

        assertTrue(result is HistoryNavigationResult.Failed)
        assertTrue(coordinator.flags().canUndo)
        assertTrue(current.isRecycled)
        assertFalse(target.isRecycled)
    }

    @Test
    fun undoAndRedoEachCaptureCurrentState() = testScope.runTest {
        val undoTarget = bitmap(0xff112233.toInt())
        coordinator.admitAdoptedSnapshot(snapshot(undoTarget), true, 0L)
        val beforeUndo = bitmap(0xff223344.toInt())
        val beforeRedo = bitmap(0xff334455.toInt())
        val captures = AtomicInteger()
        var undoAdopted: Bitmap? = null
        var redoAdopted: Bitmap? = null

        assertTrue(
            coordinator.navigate(
                undoDirection = true,
                currentCaptureBytes = BitmapMemoryBudget.bytes(beforeUndo),
                captureCurrent = { storageKind, _ ->
                    captures.incrementAndGet()
                    snapshot(beforeUndo, storageKind)
                },
                materialize = { value, transfer -> value.also(transfer) },
                adopt = {
                    undoAdopted = it.previewBitmap
                    true
                },
            ) is HistoryNavigationResult.Adopted
        )
        assertTrue(
            coordinator.navigate(
                undoDirection = false,
                currentCaptureBytes = BitmapMemoryBudget.bytes(beforeRedo),
                captureCurrent = { storageKind, _ ->
                    captures.incrementAndGet()
                    snapshot(beforeRedo, storageKind)
                },
                materialize = { value, transfer -> value.also(transfer) },
                adopt = {
                    redoAdopted = it.previewBitmap
                    true
                },
            ) is HistoryNavigationResult.Adopted
        )

        assertEquals(2, captures.get())
        assertSame(undoTarget, undoAdopted)
        assertSame(beforeUndo, redoAdopted)
        coordinator.close()
        advanceUntilIdle()
        assertTrue(beforeRedo.isRecycled)
        undoTarget.recycle()
        beforeUndo.recycle()
    }

    @Test
    fun automaticRecoverySpillsUnprotectedEntriesAndKeepsUndoTarget() = testScope.runTest {
        val first = bitmap(0xff010101.toInt())
        val second = bitmap(0xff020202.toInt())
        val protected = bitmap(0xff030303.toInt())
        coordinator.admitAdoptedSnapshot(snapshot(first), false, 0L)
        coordinator.admitAdoptedSnapshot(snapshot(second), false, 0L)
        coordinator.admitAdoptedSnapshot(snapshot(protected), false, 0L)
        val protectedId = coordinator.navigationTargetId(true)

        val result = coordinator.recover(strong = false, protectedEntryId = protectedId)

        assertFalse(result.superseded)
        assertEquals(32L, result.reclaimedRamBytes)
        assertTrue(first.isRecycled)
        assertTrue(second.isRecycled)
        assertFalse(protected.isRecycled)
        assertEquals(protectedId, coordinator.navigationTargetId(true))
    }

    @Test
    fun strongRecoveryPreservesProtectedUndoAndRedoTargets() = testScope.runTest {
        val olderUndo = bitmap(0xff111111.toInt())
        val undoTarget = bitmap(0xff222222.toInt())
        coordinator.admitAdoptedSnapshot(snapshot(olderUndo), false, 0L)
        coordinator.admitAdoptedSnapshot(snapshot(undoTarget), false, 0L)
        val redoTarget = bitmap(0xff333333.toInt())
        var uiOwned: Bitmap? = null
        coordinator.navigate(
            undoDirection = true,
            currentCaptureBytes = BitmapMemoryBudget.bytes(redoTarget),
            captureCurrent = { storageKind, _ -> snapshot(redoTarget, storageKind) },
            materialize = { value, transfer -> value.also(transfer) },
            adopt = {
                uiOwned = it.previewBitmap
                true
            },
        )
        val protectedUndoId = coordinator.navigationTargetId(true)
        val protectedRedoId = coordinator.navigationTargetId(false)

        val result = coordinator.recover(strong = true)

        assertFalse(result.superseded)
        assertEquals(protectedUndoId, coordinator.navigationTargetId(true))
        assertEquals(protectedRedoId, coordinator.navigationTargetId(false))
        assertFalse(olderUndo.isRecycled)
        assertFalse(redoTarget.isRecycled)
        uiOwned?.recycle()
    }

    @Test
    fun automaticFailurePreservesHotEntryWhileStrongFailureEvictsUnprotectedEntry() =
        testScope.runTest {
            val first = bitmap(0xff444444.toInt())
            val protected = bitmap(0xff555555.toInt())
            coordinator.admitAdoptedSnapshot(snapshot(first), false, 0L)
            coordinator.admitAdoptedSnapshot(snapshot(protected), false, 0L)
            storage.failPublish = true

            val automatic = coordinator.recover(strong = false)
            assertFalse(automatic.superseded)
            assertFalse(first.isRecycled)
            assertFalse(protected.isRecycled)

            val strong = coordinator.recover(strong = true)
            assertFalse(strong.superseded)
            assertTrue(first.isRecycled)
            assertFalse(protected.isRecycled)
        }

    @Test
    fun cleanupFailureDoesNotSkipIndependentCloseSettlement() = testScope.runTest {
        ramBudget = 8L
        coordinator.admitAdoptedSnapshot(snapshot(bitmap(0xff666666.toInt())), true, 0L)
        storage.throwOnDeleteEntries = true

        coordinator.close()
        advanceUntilIdle()

        assertEquals(1, storage.unregisterCalls.get())
        assertEquals(1, storage.deleteSessionCalls.get())
        assertTrue(coordinator.closeSettlement?.isCompleted == true)
    }

    @Test
    fun diagnosticOwnershipMovesFromLocalEdgeToCoordinatorThenUiWithoutDoubleCount() =
        testScope.runTest {
            val tracker = TrackerSession("history-matrix")
            val localCoordinator =
                EditorHistoryCoordinator(
                    context,
                    testScope,
                    tracker = tracker,
                    settlementDispatcher = dispatcher,
                    storage = storage,
                    historyRamBudgetBytes = { 1_024L },
                )
            tracker.activateDocument(localCoordinator.currentGeneration())
            advanceUntilIdle()
            val target = bitmap(0xff777777.toInt())
            val targetSnapshot =
                snapshot(target, claim = true).also {
                    it.coordinatorGeneration = localCoordinator.currentGeneration()
                    it.attachLocalDiagnostics(tracker, localCoordinator.currentGeneration())
                }
            localCoordinator.admitAdoptedSnapshot(targetSnapshot, true, 0L)
            assertEquals(0, tracker.snapshot().bitmapCount)
            assertEquals(16L, tracker.snapshot().historyHotResidentBytes)
            val current = bitmap(0xff888888.toInt())
            var uiEdge = 0L

            val result =
                localCoordinator.navigate(
                    undoDirection = true,
                    currentCaptureBytes = 16L,
                    captureCurrent = { storageKind, _ ->
                        snapshot(current, storageKind).also {
                            it.coordinatorGeneration = localCoordinator.currentGeneration()
                            it.attachLocalDiagnostics(tracker, localCoordinator.currentGeneration())
                        }
                    },
                    materialize = { value, transfer -> value.also(transfer) },
                    adopt = {
                        uiEdge =
                            tracker.registerBitmap(
                                checkNotNull(it.previewBitmap),
                                "ui",
                                "history-adopt",
                                0L,
                                localCoordinator.currentGeneration(),
                            )
                        true
                    },
                )

            assertTrue(result is HistoryNavigationResult.Adopted)
            val resident = tracker.snapshot()
            assertEquals(1, resident.bitmapCount)
            assertEquals(1, resident.acquisitionCount)
            assertEquals(16L, resident.historyHotResidentBytes)
            tracker.releaseEdge(uiEdge)
            target.recycle()
            localCoordinator.close()
            advanceUntilIdle()
            assertTrue(current.isRecycled)
            tracker.close()
        }

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun snapshot(
        bitmap: Bitmap?,
        storage: HistorySnapshotStorage =
            if (bitmap == null) HistorySnapshotStorage.MetadataOnly else HistorySnapshotStorage.Exact,
        claim: Boolean = true,
    ): EditorHistorySnapshot =
        rawSnapshot(bitmap, storage, coordinator.currentGeneration()).also {
            if (claim) it.claimCoordinatorOwnership()
        }

    private fun rawSnapshot(
        bitmap: Bitmap?,
        storage: HistorySnapshotStorage,
        generation: String,
    ): EditorHistorySnapshot =
        EditorHistorySnapshot(
            params = EditParams(),
            noiseEngine = NoiseEngine.FastEdgeAware,
            detailEngine = DetailEngine.MaskedUnsharp,
            toneEngine = ToneEngine.HistogramAuto,
            hazeEngine = DehazeEngine.FastContrast,
            baseBitmapDirty = false,
            baseContentToken = "base",
            previewBitmap = bitmap,
            originalPreviewBitmap = null,
            presetLook = null,
            cropState = CropState(),
            selectionLayers = emptyList(),
            activeSelectionLayerId = null,
            selectionPaintSettings = SelectionPaintSettings(),
            showSelectionOverlay = false,
            activeQuickEffects = emptyList(),
            flareGuardRuntimeStatus = null,
            storage = storage,
            coordinatorGeneration = generation,
        )

    private inner class DeterministicHistoryStorage(context: Context) : HistoryStorageBackend {
        private val root = File(context.cacheDir, "history-matrix-${System.nanoTime()}")
        val records = LinkedHashMap<String, StorageRecord>()
        val publishStarted = CompletableDeferred<Unit>()
        var publishGate: CompletableDeferred<Unit>? = null
        var failPublish = false
        var throwOnDeleteEntries = false
        val loads = AtomicInteger()
        val deletedPayloads = AtomicInteger()
        val unregisterCalls = AtomicInteger()
        val deleteSessionCalls = AtomicInteger()

        override fun registerSession(sessionId: String) = Unit

        override fun unregisterSession(sessionId: String) {
            unregisterCalls.incrementAndGet()
        }

        override suspend fun initializeSession(sessionId: String) {
            root.mkdirs()
        }

        override suspend fun publish(
            entry: EditorHistoryEntry,
            snapshot: EditorHistorySnapshot,
        ): ColdHistoryPayload? {
            publishStarted.complete(Unit)
            publishGate?.await()
            if (failPublish) return null
            val bitmap = snapshot.previewBitmap
            val bytes = snapshot.bitmapBytes()
            records[entry.id] =
                StorageRecord(
                    generation = entry.documentGeneration,
                    storage = snapshot.storage,
                    width = bitmap?.width ?: 0,
                    height = bitmap?.height ?: 0,
                    color = bitmap?.getPixel(0, 0) ?: 0,
                    bytes = bytes,
                )
            val directory = File(root, entry.id).also(File::mkdirs)
            return ColdHistoryPayload(directory, bytes.coerceAtLeast(1L), bytes, entry.documentGeneration)
        }

        override suspend fun load(
            entry: EditorHistoryEntry,
            expectedGeneration: String,
            register: (EditorHistorySnapshot) -> Unit,
        ): EditorHistorySnapshot? {
            val record = records[entry.id] ?: return null
            if (record.generation != expectedGeneration) return null
            loads.incrementAndGet()
            val decoded =
                if (record.storage == HistorySnapshotStorage.Exact) {
                    Bitmap.createBitmap(record.width, record.height, Bitmap.Config.ARGB_8888)
                        .also { it.eraseColor(record.color) }
                } else {
                    null
                }
            return rawSnapshot(decoded, record.storage, expectedGeneration).also(register)
        }

        override suspend fun requiredBitmapBytes(
            entry: EditorHistoryEntry,
            expectedGeneration: String,
        ): Long? = records[entry.id]?.takeIf { it.generation == expectedGeneration }?.bytes

        override suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>): DeletionResult {
            if (throwOnDeleteEntries) error("delete entries")
            val payloads = entries.mapNotNull(EditorHistoryEntry::coldPayload)
            payloads.forEach { delete(it) }
            return DeletionResult(true, emptyList())
        }

        override suspend fun delete(entry: EditorHistoryEntry): Boolean =
            entry.coldPayload?.let { delete(it) } ?: true

        override suspend fun delete(payload: ColdHistoryPayload): Boolean {
            records.entries.removeAll { it.value.generation == payload.generation && File(root, it.key) == payload.directory }
            payload.directory.deleteRecursively()
            deletedPayloads.incrementAndGet()
            return true
        }

        override suspend fun deletePayloads(payloads: Collection<ColdHistoryPayload>): DeletionResult {
            payloads.forEach { delete(it) }
            return DeletionResult(true, emptyList())
        }

        override suspend fun deleteSession(sessionId: String): Boolean {
            deleteSessionCalls.incrementAndGet()
            records.entries.removeAll { it.value.generation == sessionId }
            return true
        }
    }
}
