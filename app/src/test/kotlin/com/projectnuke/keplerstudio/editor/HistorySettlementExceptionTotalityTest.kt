package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 7 — HISTORY BACKEND EXCEPTION TOTALITY.
 *
 * After the irreversible logical discard, EVERY discarded cold payload must end
 * physically absent OR represented by pendingDeletionDebt — even when the batch
 * backend itself exits exceptionally (ordinary RuntimeException, a mid-batch
 * failure after partial physical success, or a CancellationException thrown by
 * the backend). These tests drive the REAL filesystem backend through a thin
 * exception-injection wrapper and prove:
 *  - no survivor stays debtless (file exists AND no debt row is forbidden),
 *  - already-deleted payloads are never recorded as debt (no invented reclaim
 *    bytes on retry),
 *  - caller-visible exceptions/cancellation still propagate,
 *  - a later pass settles remaining debt exactly once with true bytes,
 *  - the coordinator never stays Busy and the protected tip survives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistorySettlementExceptionTotalityTest {
    private lateinit var context: Context
    private lateinit var coordinator: EditorHistoryCoordinator
    private lateinit var testScope: TestScope
    private lateinit var dispatcher: TestDispatcher
    private lateinit var backend: ExceptionalHistoryBackend

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        Dispatchers.setMain(dispatcher)
        backend = ExceptionalHistoryBackend(context)
        coordinator =
            EditorHistoryCoordinator(
                context,
                testScope,
                tracker = null,
                settlementDispatcher = dispatcher,
                storage = backend,
                historyRamBudgetBytes = { 8L },
                historyDiskBudgetBytes = { Long.MAX_VALUE },
            )
        testScope.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        backend.resetInjection()
        coordinator.close()
        testScope.advanceUntilIdle()
        Dispatchers.resetMain()
        backend.deleteHistoryRootForTest()
    }

    // ------------------------------------------------------------------
    // Ordinary batch-level RuntimeException after irreversible discard:
    // every survivor becomes debt; retry settles exactly once, truthfully.
    // ------------------------------------------------------------------

    @Test
    fun runtimeExceptionAtBatchBoundaryRecordsEverySurvivorAsDebt() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val tipId = checkNotNull(coordinator.navigationTargetId(true))
        val candidates = backend.nonTipDirectoriesExcludingTip()
        assertTrue(candidates.size >= 2)
        val candidateBytes = candidates.associateWith { backend.directoryBytes(it) }
        val totalCandidateBytes = candidateBytes.values.fold(0L) { acc, value -> acc + value }
        backend.deleteEntriesMode = DeleteEntriesMode.THROW_RUNTIME_BEFORE

        val reclaimed = checkNotNull(coordinator.reclaimHistoryForStoragePressure())

        assertEquals("nothing was physically freed by the failed batch", 0L, reclaimed)
        assertEquals(
            "every still-present survivor is truthfully in debt",
            totalCandidateBytes,
            coordinator.pendingDeletionDebtBytesForTest(),
        )
        assertAbsentOrInDebt(candidates)
        assertFalse("coordinator must not stay busy after backend failure", coordinator.flags().busy)
        assertEquals(tipId, coordinator.navigationTargetId(true))

        // Recovery: a later pass settles the whole debt exactly once, with true bytes.
        backend.deleteEntriesMode = DeleteEntriesMode.PASS
        val followup = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertEquals(totalCandidateBytes, followup)
        assertEquals(0L, coordinator.pendingDeletionDebtBytesForTest())
        candidates.forEach { assertFalse("debt settled physically", it.exists()) }
        assertEquals(setOf(tipId), backend.sessionSnapshot().entryIds)
        Unit
    }

    // ------------------------------------------------------------------
    // Mid-batch ordinary failure AFTER partial physical success: only the
    // payloads STILL PRESENT become debt; deleted ones are never re-tracked
    // (a later retry cannot invent reclaim bytes for work already done).
    // ------------------------------------------------------------------

    @Test
    fun midBatchFailureRecordsOnlyStillPresentPayloadsAsDebt() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val tipId = checkNotNull(coordinator.navigationTargetId(true))
        val candidates = backend.nonTipDirectoriesExcludingTip()
        assertTrue(candidates.size >= 2)
        val candidateBytes = candidates.associateWith { backend.directoryBytes(it) }
        backend.deleteEntriesMode = DeleteEntriesMode.DELETE_FIRST_THEN_THROW_RUNTIME

        val reclaimed = checkNotNull(coordinator.reclaimHistoryForStoragePressure())

        // Exactly one candidate was physically removed before the batch threw.
        val deleted = candidates.filter { !it.exists() }
        val survivors = candidates.filter { it.exists() }
        assertEquals(1, deleted.size)
        assertEquals(1, survivors.size)
        val deletedBytes = checkNotNull(candidateBytes[deleted.single()])
        val survivorBytes = checkNotNull(candidateBytes[survivors.single()])
        assertEquals("only the physically removed payload counts as reclaimed", deletedBytes, reclaimed)
        val debtDirectories = coordinator.pendingDeletionDebtDirectoriesForTest().mapTo(HashSet()) { it.canonicalFile }
        assertTrue(
            "mid-batch survivor must be debt-owned, not stranded",
            debtDirectories.map { it.canonicalFile } == survivors.map { it.canonicalFile },
        )
        assertTrue(
            "already-deleted payload must NOT be recorded as debt",
            debtDirectories.none { it.canonicalFile == deleted.single().canonicalFile },
        )
        assertEquals(survivorBytes, coordinator.pendingDeletionDebtBytesForTest())

        backend.deleteEntriesMode = DeleteEntriesMode.PASS
        val followup = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertEquals("retry settles remaining debt exactly once with true bytes", survivorBytes, followup)
        assertEquals(0L, coordinator.pendingDeletionDebtBytesForTest())
        assertFalse(survivors.single().exists())
        assertEquals(setOf(tipId), backend.sessionSnapshot().entryIds)
        Unit
    }

    // ------------------------------------------------------------------
    // CancellationException thrown by the backend itself mid-batch: physical
    // ownership truth is preserved FIRST (survivors -> debt), THEN caller-
    // visible cancellation propagates. No debtless file may survive.
    // ------------------------------------------------------------------

    @Test
    fun backendCancellationExceptionCannotStrandDebtlessFiles() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val tipId = checkNotNull(coordinator.navigationTargetId(true))
        val generation = coordinator.currentGeneration()
        val candidates = backend.nonTipDirectoriesExcludingTip()
        assertTrue(candidates.size >= 2)
        val candidateBytes = candidates.associateWith { backend.directoryBytes(it) }
        backend.deleteEntriesMode = DeleteEntriesMode.DELETE_FIRST_THEN_THROW_CE

        var thrown: Throwable? = null
        var outcome: Long = Long.MIN_VALUE
        val job: Job =
            testScope.launch {
                try {
                    outcome = coordinator.reclaimHistoryForStoragePressure() ?: Long.MIN_VALUE
                } catch (t: Throwable) {
                    thrown = t
                }
            }
        testScope.advanceUntilIdle()

        assertTrue("backend CancellationException must propagate", thrown is CancellationException)
        assertEquals("cancelled settlement must never publish a result", Long.MIN_VALUE, outcome)

        // Exactly one candidate was physically removed before the backend threw.
        val deleted = candidates.filter { !it.exists() }
        val survivors = candidates.filter { it.exists() }
        assertEquals(1, deleted.size)
        assertEquals(1, survivors.size)
        val survivorBytes = checkNotNull(candidateBytes[survivors.single()])
        val debtDirectories = coordinator.pendingDeletionDebtDirectoriesForTest().mapTo(HashSet()) { it.canonicalFile }
        assertTrue(
            "survivor of an exceptionally-aborted batch must be debt-owned",
            debtDirectories.map { it.canonicalFile } == survivors.map { it.canonicalFile },
        )
        assertEquals(survivorBytes, coordinator.pendingDeletionDebtBytesForTest())
        assertFalse("coordinator must not stay busy", coordinator.flags().busy)
        assertEquals(tipId, coordinator.navigationTargetId(true))
        assertEquals("cancellation must not switch generations", generation, coordinator.currentGeneration())

        // Coordinator remains healthy: remaining debt settles exactly once.
        backend.deleteEntriesMode = DeleteEntriesMode.PASS
        val followup = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertEquals(survivorBytes, followup)
        assertEquals(0L, coordinator.pendingDeletionDebtBytesForTest())
        assertFalse(survivors.single().exists())
        Unit
    }

    // ------------------------------------------------------------------
    // Supersession (replaceDocument) with a batch-level backend exception AND
    // a failing session delete: old-generation survivors become debt; the new
    // generation's history stays untouched.
    // ------------------------------------------------------------------

    @Test
    fun replaceDocumentBatchExceptionLeavesOldGenerationOnlyAsDebtOrAbsent() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val oldGeneration = coordinator.currentGeneration()
        val oldDirectories = backend.sessionSnapshot().entryDirectories
        assertEquals(3, oldDirectories.size)
        backend.deleteEntriesMode = DeleteEntriesMode.DELETE_FIRST_THEN_THROW_RUNTIME
        backend.failDeleteSession = true

        coordinator.replaceDocument()
        testScope.advanceUntilIdle()

        val newGeneration = coordinator.currentGeneration()
        assertFalse("supersession must switch generations", newGeneration == oldGeneration)
        val debtDirectories = coordinator.pendingDeletionDebtDirectoriesForTest().mapTo(HashSet()) { it.canonicalFile }
        var absent = 0
        var indebted = 0
        oldDirectories.forEach { directory ->
            when {
                !directory.exists() -> absent++
                directory.canonicalFile in debtDirectories -> indebted++
                else -> error("old-generation orphan (file exists, no debt owns it): ${directory.name}")
            }
        }
        assertEquals("partial success stays truthful", 1, absent)
        assertEquals("survivors are debt-owned", 2, indebted)

        // The new generation's history space is untouched by old-generation settlement.
        val foreignNewGenerationSentinel =
            File(File(backend.historyRootForTest(), "session-$newGeneration"), "entry-newsentinel")
                .apply { mkdirs() }
                .resolve("bitmap-0.png")
                .apply { writeBytes(byteArrayOf(7, 7, 7)) }
        assertTrue(foreignNewGenerationSentinel.exists())
        assertFalse("coordinator must not stay busy after supersession settlement", coordinator.flags().busy)

        // Debt belongs to the OLD generation only: closing the coordinator
        // settles its own (new) session without mutating anything foreign.
        backend.resetInjection()
        coordinator.close()
        testScope.advanceUntilIdle()

        // Process recreation: a FRESH boot (brand-new storage + empty in-memory
        // session set) reclaims every session that is not its own, converging
        // the interrupted supersession to a clean state.
        val freshStorage =
            EditorHistoryStorage(
                context,
                ioDispatcher = dispatcher,
                syncDirectories = false,
            )
        val bootCoordinator =
            EditorHistoryCoordinator(
                context,
                testScope,
                tracker = null,
                settlementDispatcher = dispatcher,
                storage = freshStorage,
                historyRamBudgetBytes = { 8L },
                historyDiskBudgetBytes = { Long.MAX_VALUE },
            )
        testScope.advanceUntilIdle()
        oldDirectories.forEach { directory ->
            assertFalse("next boot must converge the superseded generation", directory.exists())
        }
        bootCoordinator.close()
        testScope.advanceUntilIdle()
        Unit
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun assertAbsentOrInDebt(directories: List<File>) {
        val debtDirectories = coordinator.pendingDeletionDebtDirectoriesForTest().mapTo(HashSet()) { it.canonicalFile }
        directories.forEach { directory ->
            if (directory.canonicalFile in debtDirectories) {
                assertTrue("debt row must own an existing payload: ${directory.name}", directory.exists())
            } else {
                assertFalse("untracked orphan (file exists, no debt): ${directory.name}", directory.exists())
            }
        }
    }

    private suspend fun seedColdUndoStack(entryCount: Int) {
        repeat(entryCount) { index -> admitOversizedSnapshot(clearRedo = index == 0) }
    }

    private suspend fun admitOversizedSnapshot(clearRedo: Boolean) {
        val bitmap = bitmap()
        val outcome = coordinator.admitAdoptedSnapshot(snapshot(bitmap), clearRedo, 0L)
        bitmap.recycle()
        assertTrue(outcome is HistoryAdmissionOutcome.Retained)
        testScope.advanceUntilIdle()
    }

    private fun bitmap(color: Int = 0xff102030.toInt()): Bitmap =
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun snapshot(
        bitmap: Bitmap?,
        storage: HistorySnapshotStorage =
            if (bitmap == null) HistorySnapshotStorage.MetadataOnly else HistorySnapshotStorage.Exact,
    ): EditorHistorySnapshot =
        EditorHistorySnapshot(
            params = EditParams(),
            correctionEngine = CorrectionEngine.Engine1,
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
            coordinatorGeneration = coordinator.currentGeneration(),
        ).also { it.claimCoordinatorOwnership() }

    private enum class DeleteEntriesMode {
        PASS,
        THROW_RUNTIME_BEFORE,
        DELETE_FIRST_THEN_THROW_RUNTIME,
        DELETE_FIRST_THEN_THROW_CE,
    }

    /**
     * Thin production-path wrapper over the REAL filesystem backend adding
     * deterministic batch-level exception injection. Physical behavior of
     * every delegated call is production behavior.
     */
    private inner class ExceptionalHistoryBackend(context: Context) : HistoryStorageBackend {
        private val delegate =
            EditorHistoryStorage(
                context,
                ioDispatcher = dispatcher,
                syncDirectories = false,
            )
        private val root = File(context.filesDir, "editor_history_v3")

        var deleteEntriesMode = DeleteEntriesMode.PASS
        var failDeleteSession = false

        fun resetInjection() {
            deleteEntriesMode = DeleteEntriesMode.PASS
            failDeleteSession = false
        }

        override fun registerSession(sessionId: String) = delegate.registerSession(sessionId)
        override fun unregisterSession(sessionId: String) = delegate.unregisterSession(sessionId)
        override suspend fun initializeSession(sessionId: String) = delegate.initializeSession(sessionId)

        override suspend fun publish(
            entry: EditorHistoryEntry,
            snapshot: EditorHistorySnapshot,
        ): HistoryPublishResult = delegate.publish(entry, snapshot)

        override suspend fun load(
            entry: EditorHistoryEntry,
            expectedGeneration: String,
            register: (EditorHistorySnapshot) -> Unit,
        ): EditorHistorySnapshot? = delegate.load(entry, expectedGeneration, register)

        override suspend fun requiredBitmapBytes(
            entry: EditorHistoryEntry,
            expectedGeneration: String,
        ): Long? = delegate.requiredBitmapBytes(entry, expectedGeneration)

        override suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>): DeletionResult =
            when (deleteEntriesMode) {
                DeleteEntriesMode.PASS -> delegate.deleteEntries(entries)
                DeleteEntriesMode.THROW_RUNTIME_BEFORE -> throw IllegalStateException("injected batch failure")
                DeleteEntriesMode.DELETE_FIRST_THEN_THROW_RUNTIME -> {
                    delegate.deleteEntries(entries.take(1))
                    throw IllegalStateException("injected mid-batch failure")
                }
                DeleteEntriesMode.DELETE_FIRST_THEN_THROW_CE -> {
                    delegate.deleteEntries(entries.take(1))
                    throw CancellationException("injected backend cancellation")
                }
            }

        override suspend fun delete(entry: EditorHistoryEntry): Boolean = delegate.delete(entry)
        override suspend fun delete(payload: ColdHistoryPayload): Boolean = delegate.delete(payload)

        override suspend fun deletePayloads(payloads: Collection<ColdHistoryPayload>): DeletionResult =
            delegate.deletePayloads(payloads)

        override suspend fun deleteSession(sessionId: String): Boolean =
            if (failDeleteSession) false else delegate.deleteSession(sessionId)

        fun sessionSnapshot(): SessionView {
            val sessionDirs = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("session-") }.orEmpty()
            val entryDirs = sessionDirs.flatMap { session ->
                session.listFiles()?.filter { it.isDirectory && it.name.startsWith("entry-") }.orEmpty()
            }
            return SessionView(
                entryIds = entryDirs.mapTo(HashSet()) { it.name.removePrefix("entry-") },
                entryDirectories = entryDirs,
                totalBytes = entryDirs.fold(0L) { acc, dir -> acc + directoryBytes(dir) },
            )
        }

        fun directoryBytes(directory: File): Long =
            directory.walkTopDown().filter(File::isFile).fold(0L) { acc, file -> acc + file.length() }

        fun nonTipDirectoriesExcludingTip(): List<File> {
            val tipId = checkNotNull(coordinator.navigationTargetId(true))
            val generation = coordinator.currentGeneration()
            val tipDirectory = File(File(root, "session-$generation"), "entry-$tipId")
            return sessionSnapshot().entryDirectories.filter { it.canonicalFile != tipDirectory.canonicalFile }
        }

        fun historyRootForTest(): File = root

        fun deleteHistoryRootForTest() {
            root.deleteRecursively()
        }
    }

    private data class SessionView(
        val entryIds: Set<String>,
        val entryDirectories: List<File>,
        val totalBytes: Long,
    )
}
