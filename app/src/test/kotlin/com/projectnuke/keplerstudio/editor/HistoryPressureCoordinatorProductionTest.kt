package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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

/** Read-only filesystem view of every tracked history payload directory. */
private data class SessionView(
    val entryIds: Set<String>,
    val entryDirectories: List<File>,
    val totalBytes: Long,
)

/**
 * Phase 5 race/invariant coverage: the global storage-pressure layer may only
 * REQUEST history reclamation through the history-owned coordinator boundary.
 * Every scenario here runs the real [EditorHistoryStorage] filesystem under
 * editor_history_v3 with a thin production-path gate/failure wrapper, so
 * physical deletion, deletion debt, protection and supersession semantics are
 * exercised against real files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryPressureCoordinatorProductionTest {
    private lateinit var context: Context
    private lateinit var coordinator: EditorHistoryCoordinator
    private lateinit var testScope: TestScope
    private lateinit var dispatcher: TestDispatcher
    private lateinit var backend: GatedRealHistoryBackend

    /** Result of the most recent completed in-test reclaim request. */
    private var lastReclaimOutcome: Long = Long.MIN_VALUE

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        Dispatchers.setMain(dispatcher)
        backend = GatedRealHistoryBackend(context)
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
        // Fail-safe: a test that failed mid-flight must never leave a parked
        // production job hanging the test scheduler.
        runCatching { backend.releaseDeleteEntries() }
        coordinator.close()
        testScope.advanceUntilIdle()
        Dispatchers.resetMain()
        backend.deleteHistoryRootForTest()
    }

    // ------------------------------------------------------------------
    // Scenario 1 — protected Undo target
    // ------------------------------------------------------------------

    @Test
    fun pressureReclaimKeepsProtectedUndoTargetAndEvictsOtherColdEntries() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val tipId = checkNotNull(coordinator.navigationTargetId(true))
        val before = backend.sessionSnapshot()

        val reclaimed = checkNotNull(coordinator.reclaimHistoryForStoragePressure())

        val after = backend.sessionSnapshot()
        assertEquals("seed produced three cold payloads", 3, before.entryDirectories.size)
        assertTrue("reported bytes equal exactly the physically removed payload bytes", reclaimed > 0L)
        assertEquals(
            "reported bytes match physically removed bytes",
            before.totalBytes - after.totalBytes,
            reclaimed,
        )
        assertEquals("protected undo tip survives", tipId, coordinator.navigationTargetId(true))
        assertTrue(coordinator.flags().canUndo)
        assertEquals("exactly the tip payload remains", setOf(tipId), after.entryIds)
        assertEquals("no failed delete may become debt", 0L, coordinator.pendingDeletionDebtBytesForTest())
    }

    // ------------------------------------------------------------------
    // Scenario 2 — protected Redo target
    // ------------------------------------------------------------------

    @Test
    fun pressureReclaimKeepsProtectedRedoTargetAndEvictsDeepUndoEntry() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        navigateOnceToMakeColdRedoTip()
        val undoTipBefore = checkNotNull(coordinator.navigationTargetId(true))
        val redoTipBefore = checkNotNull(coordinator.navigationTargetId(false))
        val before = backend.sessionSnapshot()

        val reclaimed = checkNotNull(coordinator.reclaimHistoryForStoragePressure())

        val after = backendSessionViewAfter(before, reclaimed)
        assertEquals("both navigation tips survive", setOf(undoTipBefore, redoTipBefore), after.entryIds)
        assertEquals(undoTipBefore, coordinator.navigationTargetId(true))
        assertEquals(redoTipBefore, coordinator.navigationTargetId(false))
        assertTrue(coordinator.flags().canRedo)
    }

    // ------------------------------------------------------------------
    // Scenario 3 — protection releases later
    // ------------------------------------------------------------------

    @Test
    fun protectedEntrySurvivesUntilNewerAdmissionReleasesItThenIsReclaimable() = testScope.runTest {
        seedColdUndoStack(entryCount = 1)
        val formerTipId = checkNotNull(coordinator.navigationTargetId(true))

        val firstPass = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertEquals("protected tip alone has nothing else eligible", 0L, firstPass)
        assertEquals(formerTipId, coordinator.navigationTargetId(true))
        assertEquals(1, backend.sessionSnapshot().entryDirectories.size)

        // A newer edit pushes a new tip; the formerly protected entry loses its
        // protection through the ordinary admission path.
        admitOversizedSnapshot(clearRedo = false)
        val newTipId = checkNotNull(coordinator.navigationTargetId(true))
        assertFalse(newTipId == formerTipId)

        val secondPass = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertTrue("released entry becomes reclaimable", secondPass > 0L)
        assertEquals(newTipId, coordinator.navigationTargetId(true))
        val after = backend.sessionSnapshot()
        assertEquals(1, after.entryDirectories.size)
        assertEquals(setOf(newTipId), after.entryIds)
    }

    // ------------------------------------------------------------------
    // Scenario 4 — document replacement supersedes pressure recovery
    // ------------------------------------------------------------------

    @Test
    fun documentReplacementDuringParkedPressureCannotMutateNewGeneration() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val oldGeneration = coordinator.currentGeneration()
        val candidatesBytes = backend.nonTipPayloadBytesTotal()

        backend.parkNextDeleteEntries()
        val reclaimJob = launchReclaim()
        testScope.advanceUntilIdle()
        backend.awaitDeleteEntriesParkedForTest()

        // Old generation A recovery is parked mid-settlement. Replace the
        // document: generation B initializes while A recovery is suspended.
        coordinator.replaceDocument()
        testScope.advanceUntilIdle()
        val newGeneration = coordinator.currentGeneration()
        assertFalse(newGeneration == oldGeneration)
        val newGenerationSentinel = backend.createForeignGenerationSentinelForTest(newGeneration)

        backend.releaseDeleteEntries()
        testScope.advanceUntilIdle()
        assertTrue("reclaim job must finish", reclaimJob.isCompleted)

        assertEquals(
            "only pre-replacement captured old-generation payloads are reported",
            candidatesBytes,
            lastReclaimOutcome,
        )
        assertEquals("no A debt may be applied to B", 0L, coordinator.pendingDeletionDebtBytesForTest())
        assertEquals(
            "B history state untouched by superseded recovery",
            0,
            coordinator.undoEntryCountForTest(),
        )
        assertTrue("foreign generation B sentinel payload must survive", newGenerationSentinel.isFile)
        assertEquals(newGeneration, coordinator.currentGeneration())
        // The coordinator stays fully usable for the new document.
        admitOversizedSnapshot(clearRedo = true)
        assertTrue(coordinator.flags().canUndo)
    }

    // ------------------------------------------------------------------
    // Scenario 5 — delete failure / deletion debt truthfulness
    // ------------------------------------------------------------------

    @Test
    fun failedPhysicalDeleteKeepsDebtTruthfulAndLaterRetrySettlesExactlyOnce() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val tipId = checkNotNull(coordinator.navigationTargetId(true))
        val nonTip = backend.nonTipDirectoriesExcludingTip()
        val failingDirectory = nonTip[0]
        val succeedingDirectory = nonTip[1]
        val failingBytes = backend.directoryBytes(failingDirectory)
        val succeedingBytes = backend.directoryBytes(succeedingDirectory)

        backend.failDeleteDirectories.add(failingDirectory)
        val firstPass = checkNotNull(coordinator.reclaimHistoryForStoragePressure())

        assertEquals("failed bytes are never reported as reclaimed", succeedingBytes, firstPass)
        assertEquals(
            "debt truthfully represents the failed payload",
            failingBytes,
            coordinator.pendingDeletionDebtBytesForTest(),
        )
        assertTrue("failed payload file remains on disk", failingDirectory.exists())
        assertFalse("succeeding payload really deleted", succeedingDirectory.exists())
        assertEquals(tipId, coordinator.navigationTargetId(true))

        // Next pressure pass retries through the history-owned mechanism.
        backend.failDeleteDirectories.clear()
        val secondPass = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertEquals("retry settles the debt exactly once with true bytes", failingBytes, secondPass)
        assertEquals(0L, coordinator.pendingDeletionDebtBytesForTest())
        assertFalse("failed payload now really gone", failingDirectory.exists())
        assertEquals(1, backend.sessionSnapshot().entryDirectories.size)
    }

    // ------------------------------------------------------------------
    // Scenario 6 — repeated pressure idempotence
    // ------------------------------------------------------------------

    @Test
    fun repeatedPressureRequestsAreIdempotentWithoutDoubleAccounting() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val deletesAfterSeed = backend.deleteEntriesCalls.get()
        val expectedFirstPass = backend.nonTipPayloadBytesTotal()

        val firstPass = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        val deletesAfterFirst = backend.deleteEntriesCalls.get()
        val stackAfterFirst = coordinator.undoEntryCountForTest()
        val tipAfterFirst = coordinator.navigationTargetId(true)
        val directoriesAfterFirst = backend.sessionSnapshot().entryDirectories.size

        val secondPass = checkNotNull(coordinator.reclaimHistoryForStoragePressure())

        assertEquals("first pass frees both non-tip payloads", expectedFirstPass, firstPass)
        assertEquals("second pass finds nothing more to free", 0L, secondPass)
        assertEquals("second pass performs no deletion batch", deletesAfterFirst, backend.deleteEntriesCalls.get())
        assertEquals("stacks unchanged by second pass", stackAfterFirst, coordinator.undoEntryCountForTest())
        assertEquals("tip unchanged", tipAfterFirst, coordinator.navigationTargetId(true))
        assertEquals(directoriesAfterFirst, backend.sessionSnapshot().entryDirectories.size)
        assertEquals(0L, coordinator.pendingDeletionDebtBytesForTest())
        assertTrue(deletesAfterFirst > deletesAfterSeed)
    }

    // ------------------------------------------------------------------
    // Scenario 7 — cancellation
    // ------------------------------------------------------------------

    @Test
    fun callerCancellationAtRealHistoryBoundaryPropagatesAndLeavesHealthyCoordinator() = testScope.runTest {
        seedColdUndoStack(entryCount = 3)
        val tipId = coordinator.navigationTargetId(true)
        val generation = coordinator.currentGeneration()
        // Capture the EXACT non-tip candidate payload directories pressure will
        // choose, before any settlement begins.
        val candidates = backend.nonTipDirectoriesExcludingTip()
        assertTrue("seed must produce non-tip candidates", candidates.size >= 2)
        // Force ONE candidate delete failure so both total-settlement outcomes
        // are proven: confirmed-absent AND debt-represented.
        val debtCandidate = candidates.last()
        val absentCandidates = candidates.dropLast(1)
        val debtCandidateBytes = backend.directoryBytes(debtCandidate)
        backend.failDeleteDirectories.add(debtCandidate)

        backend.parkNextDeleteEntries()
        val reclaimJob = launchReclaim()
        testScope.advanceUntilIdle()
        backend.awaitDeleteEntriesParkedForTest()

        reclaimJob.cancel()
        backend.releaseDeleteEntries()
        testScope.advanceUntilIdle()

        assertTrue("cancellation must terminate the reclaim", reclaimJob.isCancelled)
        assertEquals("cancelled settlement must never publish a result", Long.MIN_VALUE, lastReclaimOutcome)
        assertFalse("coordinator must not stay busy", coordinator.flags().busy)
        assertEquals("protected undo tip survives", tipId, coordinator.navigationTargetId(true))
        assertEquals("cancellation must not switch document generation", generation, coordinator.currentGeneration())

        // TOTAL ownership settlement: every discarded candidate ends in exactly
        // one of two states — physically absent, or represented by a truthful
        // pendingDeletionDebt row. A file that exists WITHOUT a debt row is an
        // untracked persistent orphan and fails this test.
        absentCandidates.forEach { directory ->
            assertFalse(
                "settled candidate must be confirmed absent: ${directory.name}",
                directory.exists(),
            )
        }
        assertEquals(
            "failed physical delete is truthfully recorded as debt",
            debtCandidateBytes,
            coordinator.pendingDeletionDebtBytesForTest(),
        )
        val debtDirectories =
            coordinator.pendingDeletionDebtDirectoriesForTest().mapTo(HashSet()) { it.canonicalFile }
        candidates.forEach { directory ->
            val canonical = directory.canonicalFile
            if (canonical in debtDirectories) {
                assertTrue(
                    "debt row must own a payload that still exists: ${directory.name}",
                    directory.exists(),
                )
            } else {
                assertFalse(
                    "untracked orphan (file exists, no debt owns it): ${directory.name}",
                    directory.exists(),
                )
            }
        }

        // Coordinator remains healthy: a later pressure pass retries remaining
        // debt and settles it exactly once with true bytes.
        backend.failDeleteDirectories.clear()
        val followup = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertEquals(
            "debt retry settles exactly once with the failed payload's true bytes",
            debtCandidateBytes,
            followup,
        )
        assertEquals(0L, coordinator.pendingDeletionDebtBytesForTest())
        assertFalse("debt-owned payload now really gone", debtCandidate.exists())
        assertEquals(
            "only the protected tip payload remains",
            setOf(tipId),
            backend.sessionSnapshot().entryIds,
        )
    }

    // ------------------------------------------------------------------
    // Scenario 8 — current Draft / document storage remains untouched
    // ------------------------------------------------------------------

    @Test
    fun historyPressureNeverTouchesCurrentDraftOrDocumentSources() = testScope.runTest {
        val sentinels = createDocumentSourceSentinels()
        val sentinelDigestBefore = sentinels.map { it.second.length() }.toTypedArray()

        seedColdUndoStack(entryCount = 3)
        val reclaimed = checkNotNull(coordinator.reclaimHistoryForStoragePressure())
        assertTrue(reclaimed > 0L)

        val sentinelDigestAfter = sentinels.map { it.second.length() }.toTypedArray()
        assertTrue(
            "current draft/document sources byte-identical",
            sentinelDigestBefore.contentEquals(sentinelDigestAfter),
        )
        sentinels.forEach { (description, file) ->
            assertTrue("$description must survive history pressure", file.isFile)
        }
        assertEquals(1, backend.sessionSnapshot().entryDirectories.size)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Admits [entryCount] oversized snapshots so every entry lands COLD. */
    private suspend fun seedColdUndoStack(entryCount: Int) {
        repeat(entryCount) { index -> admitOversizedSnapshot(clearRedo = index == 0) }
    }

    private suspend fun admitOversizedSnapshot(clearRedo: Boolean) {
        val bitmap = bitmap()
        val outcome = coordinator.admitAdoptedSnapshot(snapshot(bitmap), clearRedo, 0L)
        bitmap.recycle()
        assertTrue(
            "seed admission must be retained (got $outcome)",
            outcome is HistoryAdmissionOutcome.Retained,
        )
        testScope.advanceUntilIdle()
    }

    /** One undo navigation turns the previous current state into a COLD redo tip. */
    private suspend fun navigateOnceToMakeColdRedoTip() {
        val redoBitmap = bitmap()
        val result = coordinator.navigate(
            undoDirection = true,
            currentCaptureBytes = BitmapMemoryBudget.bytes(redoBitmap),
            captureCurrent = { storageKind, _ -> snapshot(redoBitmap, storageKind) },
            materialize = { value, transfer -> value.also(transfer) },
            adopt = { true },
        )
        redoBitmap.recycle()
        assertTrue("navigation must adopt (got $result)", result is HistoryNavigationResult.Adopted)
        testScope.advanceUntilIdle()
    }

    private fun launchReclaim(): Job =
        testScope.launch {
            lastReclaimOutcome = coordinator.reclaimHistoryForStoragePressure() ?: Long.MIN_VALUE
        }

    /** Shared tail assertions for an eviction-style reclaim. */
    private fun backendSessionViewAfter(
        before: SessionView,
        reclaimed: Long,
    ): SessionView {
        val after = backend.sessionSnapshot()
        assertEquals(before.entryDirectories.size - 1, after.entryDirectories.size)
        assertEquals(before.totalBytes - after.totalBytes, reclaimed)
        assertTrue(reclaimed > 0L)
        return after
    }

    private fun createDocumentSourceSentinels(): List<Pair<String, File>> {
        val legacyDraftDir = File(context.filesDir, "drafts/current").apply { mkdirs() }
        val generationsDir = File(context.filesDir, "drafts/generations").apply { mkdirs() }
        val generationDir = File(generationsDir, "gen_sentinel").apply { mkdirs() }
        val restoredDir = File(context.filesDir, "editor_sources").apply { mkdirs() }
        return listOf(
            "legacy compatibility source" to File(legacyDraftDir, "source.img").apply { writeBytes(byteArrayOf(1, 2, 3)) },
            "legacy thumbnail" to File(legacyDraftDir, "thumbnail.jpg").apply { writeBytes(byteArrayOf(4, 5)) },
            "current draft generation manifest" to File(generationDir, "manifest.json").apply { writeText("{}") },
            "current draft generation complete marker" to File(generationDir, "complete").apply { writeText("ok") },
            "current draft generation source" to File(generationDir, "source.img").apply { writeBytes(byteArrayOf(9, 9, 9)) },
            "restored live source" to restoredDir.resolve("restored_sentinel.img").apply { writeBytes(byteArrayOf(7, 8)) },
            "incoming live source" to File(context.cacheDir, "source_pressure-sentinel.img").apply { writeBytes(byteArrayOf(6, 6)) },
        )
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

    /**
     * Thin gate/failure wrapper over the REAL filesystem backend. All storage
     * behavior is production behavior; the wrapper only adds deterministic
     * parking and per-directory deletion failure injection.
     */
    private inner class GatedRealHistoryBackend(context: Context) : HistoryStorageBackend {
        // syncDirectories=false: POSIX directory fsync is unavailable on the
        // Windows test host; publication durability semantics are unchanged.
        // ioDispatcher=test dispatcher: every production IO hop stays on the
        // deterministic virtual scheduler, so post-release resumption cannot
        // race ahead of advanceUntilIdle on a real worker pool.
        private val delegate =
            EditorHistoryStorage(
                context,
                ioDispatcher = dispatcher,
                syncDirectories = false,
            )
        private val root = File(context.filesDir, "editor_history_v3")

        val deleteEntriesCalls = AtomicInteger(0)
        val failDeleteDirectories = HashSet<File>()

        private var parkNextBatch = false
        private val deleteEntriesParked = CompletableDeferred<Unit>()
        private val deleteEntriesRelease = CompletableDeferred<Unit>()

        fun parkNextDeleteEntries() {
            parkNextBatch = true
        }

        suspend fun awaitDeleteEntriesParkedForTest() {
            deleteEntriesParked.await()
        }

        fun releaseDeleteEntries() {
            deleteEntriesRelease.complete(Unit)
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

        override suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>): DeletionResult {
            deleteEntriesCalls.incrementAndGet()
            if (parkNextBatch) {
                parkNextBatch = false
                deleteEntriesParked.complete(Unit)
                deleteEntriesRelease.await()
            }
            val surviving = ArrayList<EditorHistoryEntry>()
            val forcedFailures = ArrayList<ColdHistoryPayload>()
            entries.forEach { entry ->
                val payload = entry.coldPayload
                if (payload != null && payload.directory in failDeleteDirectories) {
                    forcedFailures.add(payload)
                } else {
                    surviving.add(entry)
                }
            }
            val delegated = delegate.deleteEntries(surviving)
            return DeletionResult(
                delegated.allConfirmedAbsent && forcedFailures.isEmpty(),
                delegated.failedPayloads + forcedFailures,
            )
        }

        override suspend fun delete(entry: EditorHistoryEntry): Boolean = delegate.delete(entry)

        override suspend fun delete(payload: ColdHistoryPayload): Boolean = delegate.delete(payload)

        override suspend fun deletePayloads(payloads: Collection<ColdHistoryPayload>): DeletionResult {
            val surviving = ArrayList<ColdHistoryPayload>()
            val forcedFailures = ArrayList<ColdHistoryPayload>()
            payloads.forEach { payload ->
                if (payload.directory in failDeleteDirectories) forcedFailures.add(payload)
                else surviving.add(payload)
            }
            val delegated = delegate.deletePayloads(surviving)
            return DeletionResult(
                delegated.allConfirmedAbsent && forcedFailures.isEmpty(),
                delegated.failedPayloads + forcedFailures,
            )
        }

        override suspend fun deleteSession(sessionId: String): Boolean = delegate.deleteSession(sessionId)

        // ---- inspection helpers ----

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

        fun entryDirectoryFor(entryId: String, generation: String): File =
            File(File(root, "session-$generation"), "entry-$entryId")

        /** Every tracked payload directory except the current undo tip. */
        fun nonTipDirectoriesExcludingTip(): List<File> {
            val tipId = checkNotNull(coordinator.navigationTargetId(true))
            val generation = coordinator.currentGeneration()
            val tipDirectory = entryDirectoryFor(tipId, generation)
            return sessionSnapshot().entryDirectories.filter { it.canonicalFile != tipDirectory.canonicalFile }
        }

        fun nonTipPayloadBytesTotal(): Long = nonTipDirectoriesExcludingTip().fold(0L) { acc, dir -> acc + directoryBytes(dir) }

        /**
         * Creates a payload directory that belongs to [generation] but is NOT
         * tracked by the coordinator — a foreign B-side artifact that stale
         * recovery must never touch.
         */
        fun createForeignGenerationSentinelForTest(generation: String): File {
            val session = File(root, "session-$generation").apply { mkdirs() }
            val sentinel = File(session, "entry-pressuresentinel").apply { mkdirs() }
            return File(sentinel, "bitmap-0.png").apply { writeBytes(byteArrayOf(1, 4, 9)) }
        }

        fun deleteHistoryRootForTest() {
            root.deleteRecursively()
        }
    }
}
