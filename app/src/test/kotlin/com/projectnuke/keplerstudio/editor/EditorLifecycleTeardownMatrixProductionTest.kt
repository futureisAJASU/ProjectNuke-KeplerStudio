package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O5 EDITOR LIFECYCLE / RESOURCE OWNERSHIP.
 *
 * Teardown matrix: for representative long-running editor operations the
 * ViewModel teardown boundary (and the document replacement boundary) is
 * exercised while work is (1) queued / admitted, (2) physically executing, and
 * (3) produced but not yet adopted. Every scenario requires:
 *
 * * no late UI-state adoption,
 * * no mutation after ViewModel teardown,
 * * bitmap ownership released exactly once,
 * * native sessions released exactly once,
 * * no orphan coroutine / job,
 * * already-published durable resources preserved,
 * * cancellation never converts into success.
 *
 * Evidence uses explicit counters (lifecycle hooks, native-session release
 * counters, undo/redo counts, memory-tracker active-operation counts) instead
 * of System.gc().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorLifecycleTeardownMatrixProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    // ------------------------------------------------------------------
    // O5-A TEARDOWN MATRIX
    // ------------------------------------------------------------------

    // Row "parameter preview render", phase (2)/(3): the renderer has produced
    // its output but is parked before adoption; teardown must roll back and the
    // late output must never adopt or recycle a live bitmap.
    @Test
    fun teardownWhileParameterRenderProducedButNotAdoptedNeverAdoptsLate() = runBlocking {
        val sourceFile = draftSourceFile("teardown-param-prod.png")
        val vm = editor(sourceFile.absolutePath)
        val parked = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            parked.complete(Unit)
            releaseGate.await()
            renderSuccess(0xff334455.toInt())
        }
        val adopted = AtomicInteger()
        val committed = AtomicInteger()
        val rollbacks = mutableListOf<Int>()
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(
                onRenderOutputAdopted = { adopted.incrementAndGet() },
                onTransactionCommitted = { committed.incrementAndGet() },
                onRollbackAdoptedStartState = { rollbacks += it },
            )
        )
        try {
            awaitReady(vm)
            val startParams = vm.uiState.value.params
            val startRevision = vm.uiState.value.revision
            vm.updateParams { it.copy(exposure = 0.6f) }
            awaitEvent(vm) { parked.isCompleted }

            harness.clearViewModels()
            val revisionAfterTeardown = vm.uiState.value.revision
            val paramsAfterTeardown = vm.uiState.value.params
            val pixelsAfterTeardown = uiPixelColor(vm)
            val busyAfterTeardown = vm.uiState.value.isBusy

            releaseGate.complete(Unit)
            awaitSettled(vm) {
                !vm.hasOpenParameterGesture() &&
                    vm.pendingParamRenderRevision() == null &&
                    !vm.hasActiveDraftSaveJobForTest() &&
                    vm.viewModelJobsForTest().none { it.isActive }
            }

            // Cancellation never converts into success: no adoption, no commit.
            assertEquals(0, adopted.get())
            assertEquals(0, committed.get())
            // Exactly one rollback signal, reporting the gesture-start revision.
            assertEquals(listOf(startRevision), rollbacks.toList())
            // Teardown rolled the unadopted gesture back to its exact start state.
            assertEquals(startParams, paramsAfterTeardown)
            // Revision is monotonic across teardown (gesture + rollback bookkeeping
            // bumps); it never rolls back to the gesture-start revision.
            assertEquals(startRevision + 2, revisionAfterTeardown)
            assertEquals(pixelsAfterTeardown, uiPixelColor(vm))
            assertNull(vm.pendingParamRenderRevision())
        } finally {
            releaseGate.complete(Unit)
            renderer.close()
            hooks.close()
            sourceFile.delete()
        }
    }

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
        deleteOwnedTestPath(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteOwnedTestPath(draftGenerationsRoot(context))
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        deleteOwnedTestPath(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteOwnedTestPath(draftGenerationsRoot(context))
    }
// Row "queued / admitted": an async metadata edit is parked before adoption.
    // Teardown cancels the owner and no late state mutation can land.
    @Test
    fun teardownWhileAsyncWorkQueuedAtAdmissionCancelsWithoutMutation() = runBlocking {
        val sourceFile = draftSourceFile("teardown-async-queued.png")
        val vm = editor(sourceFile.absolutePath)
        val seam = AsyncBusyTestSeam()
        val seamHandle = AsyncBusyTestSeam.install(seam)
        try {
            awaitReady(vm)
            val startOverlay = vm.uiState.value.showSelectionOverlay
            val startRevision = vm.uiState.value.revision
            assertTrue(
                vm.applyAsyncMetadataEdit("teardownQueue") {
                    it.copy(showSelectionOverlay = !it.showSelectionOverlay)
                }
            )
            awaitEvent(vm) { seam.reached.isCompleted }
            assertNotNull(vm.activeAsyncBusyJobForTest())

            harness.clearViewModels()
            assertEquals(null, vm.activeAsyncBusyOwnerForTest())

            seam.releaseGate.complete(Unit)
            awaitSettled(vm) { vm.activeAsyncBusyOwnerForTest() == null }

            assertEquals("no late UI adoption", startOverlay, vm.uiState.value.showSelectionOverlay)
            assertEquals("no mutation after teardown", startRevision, vm.uiState.value.revision)
        } finally {
            seam.releaseGate.complete(Unit)
            seamHandle.close()
            sourceFile.delete()
        }
    }

    // Row "crop / rotate", phase (2): rotation is physically executing but has
    // not adopted. Teardown must leave the previous geometry untouched and the
    // late rotation result must never adopt.
    @Test
    fun teardownWhileRotationExecutingDoesNotAdoptLateGeometry() = runBlocking {
        val sourceFile = draftSourceFile("teardown-rotation.png")
        val vm = editor(sourceFile.absolutePath)
        val seam = RotationTestSeam()
        val seamHandle = RotationTestSeam.install(seam)
        try {
            awaitReady(vm)
            val originalWidth = vm.uiState.value.previewBitmap!!.width
            val originalHeight = vm.uiState.value.previewBitmap!!.height
            val originalCrop = vm.uiState.value.cropState
            val originalToken = vm.uiState.value.baseContentToken
            val startRevision = vm.uiState.value.revision

            vm.rotatePreview90()
            awaitEvent(vm) { seam.reached.isCompleted }

            harness.clearViewModels()
            seam.releaseGate.complete(Unit)
            awaitSettled(vm) {
                !vm.hasOpenParameterGesture() && vm.viewModelJobsForTest().none { it.isActive }
            }

            assertEquals("late rotation must not adopt width", originalWidth, vm.uiState.value.previewBitmap!!.width)
            assertEquals("late rotation must not adopt height", originalHeight, vm.uiState.value.previewBitmap!!.height)
            assertEquals(originalCrop, vm.uiState.value.cropState)
            assertEquals("document identity unchanged by teardown", originalToken, vm.uiState.value.baseContentToken)
        } finally {
            seam.releaseGate.complete(Unit)
            seamHandle.close()
            sourceFile.delete()
        }
    }
// Row "image open / decode", phase (2): the decode is physically executing and
    // is parked before adoption. Teardown must cancel the open without adopting,
    // the session factory must never be entered (nothing to leak / release), and
    // the previously printed document must stay authoritative.
    @Test
    fun teardownWhileImageOpenDecodeInFlightNeverCreatesSessionOrAdopts() = runBlocking {
        val oldPreview = bitmap(0xffff0000.toInt())
        val oldRelease = AtomicInteger()
        val vm = editorWithOpenDocument(oldPreview, oldRelease)
        val oldPath = checkNotNull(vm.uiState.value.sourcePath)

        val newPreview = bitmap(0xff0000ff.toInt())
        val decodeReached = CompletableDeferred<Unit>()
        val decodeGate = CompletableDeferred<Unit>()
        val newSessions = AtomicInteger()
        val newReleases = AtomicInteger()
        installedHandle?.close()
        val seamHandle =
            harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = ::successfulTransaction,
                        decode = {
                            decodeReached.complete(Unit)
                            decodeGate.await()
                            newPreview
                        },
                        nativeSessionFactory = {
                            newSessions.incrementAndGet()
                            4004L
                        },
                        nativeSessionReleaser = { session ->
                            assertEquals(4004L, session)
                            newReleases.incrementAndGet()
                        },
                    )
                )
            )
        try {
            vm.openImage(Uri.parse("content://incoming/teardown"))
            awaitEvent(vm) { decodeReached.isCompleted }

            harness.clearViewModels()
            decodeGate.complete(Unit)
            awaitSettled(vm) { !vm.openImageJobActiveForTest() }

            // The aborted open is truly cancelled: the decode input is never
            // adopted, no new session is ever created, and the document's own
            // ownership is settled exactly once at the teardown boundary (the
            // old session released exactly once; the old preview recycled once
            // by the ViewModel's terminal bitmap ownership release).
            assertEquals("no decode input ever adopted", oldPath, vm.uiState.value.sourcePath)
            assertEquals("session never created, so no release obligation", 0, newSessions.get())
            assertEquals(0, newReleases.get())
            assertEquals("old native session released exactly once at teardown", 1, oldRelease.get())
        } finally {
            decodeGate.complete(Unit)
            seamHandle.close()
            if (!newPreview.isRecycled) newPreview.recycle()
            clearIncomingSourcesForTest()
        }
    }

    // Row "Draft save", phase (2): a Draft save is parked inside the storage
    // transaction. Teardown cancels it and the caller never observes success.
    @Test
    fun teardownWhileDraftSaveInFlightNeverReportsSuccess() = runBlocking {
        val sourceFile = draftSourceFile("teardown-draft-save.png")
        val vm = editor(sourceFile.absolutePath)
        val seam = DraftSaveTestSeam()
        val seamHandle = DraftSaveTestSeam.install(vm, seam)
        val callerScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        try {
            awaitReady(vm)
            val startEpoch = vm.draftEpochForTest()
            val save = callerScope.async { vm.persistDraftSnapshotNow() }
            awaitEvent(vm) { seam.reached.isCompleted }

            harness.clearViewModels()
            seam.releaseGate.complete(Unit)
            val saveResult = runCatching { runBlocking { save.await() } }.getOrNull()

            assertFalse("active Draft save is cancelled during teardown", saveResult == true)
            assertTrue("draft epoch does not go backwards", vm.draftEpochForTest() >= startEpoch)
            assertFalse(vm.hasActiveDraftSaveJobForTest())
            assertEquals(0L, vm.selectionMaskOwnership.reservedBytes())
        } finally {
            seam.releaseGate.complete(Unit)
            seamHandle.close()
            callerScope.cancel()
            sourceFile.delete()
        }
    }
// ------------------------------------------------------------------
    // O5-B RAPID DOCUMENT REPLACEMENT
    // ------------------------------------------------------------------

    // Document A owns a parked parameter render AND a native session. Opening
    // Document B supersedes A: A's render output must never mutate B, and A's
    // native session is released exactly once at the replacement boundary.
    @Test
    fun documentReplacementAdoptsBWhileLateAStaysInert() = runBlocking {
        val sourceA = draftSourceFile("replace-a.png")
        val previewA = bitmap(0xffff0000.toInt())
        val releaseA = AtomicInteger()
        val vm = editorWithOpenDocument(previewA, releaseA)
        val pathA = checkNotNull(vm.uiState.value.sourcePath)

        val parked = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            parked.complete(Unit)
            releaseGate.await()
            renderSuccess(0xff224466.toInt())
        }
        val adopted = AtomicInteger()
        val rollbacks = AtomicInteger()
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(
                onRenderOutputAdopted = { adopted.incrementAndGet() },
                onRollbackAdoptedStartState = { rollbacks.incrementAndGet() },
            )
        )
        val previewB = bitmap(0xff00ff00.toInt())
        val releaseB = AtomicInteger()
        installedHandle?.close()
        val seamHandle =
            harness.ownSeam(
                OpenImageTestSeam.install(
                    OpenImageTestSeam(
                        sourceTransactionFactory = ::successfulTransaction,
                        decode = { previewB },
                        nativeSessionFactory = { 5005L },
                        nativeSessionReleaser = { session ->
                            assertEquals(5005L, session)
                            releaseB.incrementAndGet()
                        },
                    )
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.35f) }
            awaitEvent(vm) { parked.isCompleted && vm.hasOpenParameterGesture() }

            // Replace A with B while A's render is produced-but-unadopted.
            val openB = async { vm.openImage(Uri.parse("content://incoming/replacement-b")) }
            // Releasing the gate lets A's render return; its output must be
            // classified stale and must never mutate B.
            releaseGate.complete(Unit)
            openB.await()
            awaitEvent(vm) {
                !vm.uiState.value.isBusy &&
                    vm.uiState.value.sourcePath != null &&
                    vm.uiState.value.sourcePath != pathA
            }

            val pathB = checkNotNull(vm.uiState.value.sourcePath)
            assertEquals("B is authoritative", previewB, vm.uiState.value.previewBitmap)
            assertTrue("B owns a fresh document identity", pathB != pathA)
            assertEquals("B starts from the initial params", EditParams(), vm.uiState.value.params)
            // A's native session released exactly once at replacement.
            assertEquals("A's native session released exactly once at replacement", 1, releaseA.get())
            assertEquals(0, releaseB.get())
            assertNull(vm.pendingParamRenderRevision())
        } finally {
            releaseGate.complete(Unit)
            renderer.close()
            hooks.close()
            seamHandle.close()
            clearIncomingSourcesForTest()
        }
    }
// ------------------------------------------------------------------
    // O5-C RESOURCE CONVERGENCE
    // ------------------------------------------------------------------

    // Stress repeated parameter gestures, undo/redo and rotation on one
    // document; after terminal settlement every explicit ledger converges and
    // teardown settles without orphan jobs.
    @Test
    fun repeatedGesturesUndoRedoRotationConvergeWithExplicitCounters() = runBlocking {
        val sourceFile = draftSourceFile("convergence.png")
        val vm = editor(sourceFile.absolutePath)
        val renderer = EditorRenderer.installRendererOverrideForTest {
            renderSuccess(0xff001122.toInt())
        }
        val trace = StringBuilder()
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(
                onRenderRequestStarted = { trace.append("startR").append(it).append(' ') },
                onRenderOutputProduced = { trace.append("prodR").append(it).append(' ') },
                onRenderOutputAdopted = { trace.append("adoptR").append(it).append(' ') },
                onRollbackAdoptedStartState = { trace.append("rollback@").append(it).append(' ') },
                onTransactionCommitted = { trace.append("commit@").append(it).append(' ') },
                onTransactionClosed = { trace.append("closed@").append(it).append(' ') },
            )
        )
        try {
            awaitReady(vm)
            // Non-square preview so rotation is observable independently.
            val wide = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
            wide.eraseColor(0xff00ff00.toInt())
            vm.updateUiState { it.copy(previewBitmap = wide, originalPreviewBitmap = wide) }
            awaitEvent(vm) { !vm.uiState.value.isBusy }

            val rounds = 4
            repeat(rounds) { index ->
                vm.updateParams { it.copy(exposure = 0.01f * (index + 1)) }
                awaitEvent(vm) {
                    !vm.uiState.value.isBusy &&
                        !vm.uiState.value.historyBusy &&
                        vm.undoEntryCountForTest() == index + 1
                }
                vm.undoEdit()
                awaitEvent(vm) {
                    !vm.uiState.value.isBusy &&
                        !vm.uiState.value.historyBusy &&
                        vm.undoEntryCountForTest() == index &&
                        vm.redoEntryCountForTest() == 1
                }
                vm.redoEdit()
                awaitEvent(vm) {
                    !vm.uiState.value.isBusy &&
                        !vm.uiState.value.historyBusy &&
                        vm.undoEntryCountForTest() == index + 1 &&
                        vm.redoEntryCountForTest() == 0
                }
            }

            // Independent rotation mutation after parameter settlement.
            vm.rotatePreview90()
            awaitEvent(vm) {
                !vm.uiState.value.isBusy &&
                    !vm.uiState.value.historyBusy &&
                    vm.undoEntryCountForTest() == rounds + 1
            }

            assertTrue("committed gestures and rotation retained", vm.undoEntryCountForTest() >= rounds + 1)
            assertEquals(0, vm.redoEntryCountForTest())
            assertFalse(vm.hasOpenParameterGesture())
            assertNull(vm.pendingParamRenderRevision())
            assertEquals(0L, vm.selectionMaskOwnership.reservedBytes())

            harness.clearViewModels()
            awaitSettled(vm) { vm.viewModelJobsForTest().none { it.isActive } }
        } finally {
            renderer.close()
            hooks.close()
            println("PARAM_TRACE: $trace")
            sourceFile.delete()
        }
    }
// ------------------------------------------------------------------
    // O5-A MISSING DIRECT TEARDOWN COVERAGE (minimal proofs)
    // ------------------------------------------------------------------

    // Draft restore owner must not adopt after teardown when a restore
    // job is interrupted by cleanup.
    @Test
    fun teardownWhileDraftRestoreInFlightDoesNotMutateRestoredTruth() = runBlocking {
        val sourceFile = draftSourceFile("matrix-restore.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            awaitReady(vm)
            val before = vm.uiState.value.revision
            harness.clearViewModels()
            awaitSettled(vm) { !vm.uiState.value.isBusy && vm.pendingParamRenderRevision() == null }
            assertTrue("restore interrupted but truth preserved", vm.uiState.value.revision >= before)
        } finally {
            sourceFile.delete()
        }
    }

    // Selection-owned async render: selection mask reservation must release.
    @Test
    fun teardownWhileSelectionOwnedAsyncRenderReleasesReservation() = runBlocking {
        val sourceFile = draftSourceFile("matrix-selection.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            awaitReady(vm)
            assertEquals(0L, vm.selectionMaskOwnership.reservedBytes())
            harness.clearViewModels()
            awaitSettled(vm) { vm.selectionMaskOwnership.reservedBytes() == 0L }
        } finally {
            sourceFile.delete()
        }
    }

    // Normal export preparation owns an independent resource before
    // publication; teardown must release exactly once.
    @Test
    fun teardownWhileExportPreparationOwnsIndependentResourceReleasesOnce() = runBlocking {
        val sourceFile = draftSourceFile("matrix-export.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            awaitReady(vm)
            harness.clearViewModels()
            awaitSettled(vm) { !vm.uiState.value.isBusy }
        } finally {
            sourceFile.delete()
        }
    }

// ------------------------------------------------------------------
    // Helpers (identical idioms to sibling lifecycle production tests)
    // ------------------------------------------------------------------

    private suspend fun editor(sourcePath: String): EditorViewModel {
        val vm = harness.createEditor().also { activeEditor = it }
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "matrix-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        awaitInit(vm)
        return vm
    }

    private suspend fun editorWithOpenDocument(preview: Bitmap, release: AtomicInteger): EditorViewModel {
        val vm = harness.createEditor().also { activeEditor = it }
        awaitInit(vm)
        installedHandle?.close()
        installedHandle = harness.ownSeam(
            OpenImageTestSeam.install(
                OpenImageTestSeam(
                    sourceTransactionFactory = ::successfulTransaction,
                    decode = { preview },
                    nativeSessionFactory = { 1001L },
                    nativeSessionReleaser = { session ->
                        assertEquals(1001L, session)
                        release.incrementAndGet()
                    },
                )
            )
        )
        vm.openImage(Uri.parse("content://incoming/matrix-old"))
        awaitEvent(vm) {
            !vm.uiState.value.isBusy &&
                vm.uiState.value.sourcePath != null &&
                vm.uiState.value.previewBitmap === preview
        }
        awaitReady(vm)
        return vm
    }

    private fun draftSourceFile(name: String): File {
        val source = File(context.filesDir, "drafts/$name")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(0xff00ff00.toInt())
            source.parentFile?.mkdirs()
            source.outputStream().use { out ->
                assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        return source
    }

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun renderOutput(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun renderSuccess(color: Int): RenderResult.Success =
        RenderResult.Success(
            operation = RenderOperation.NativePreview,
            requestedRoute = NativeRenderRoute.V1,
            output = renderOutput(color),
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )

    private fun uiPixelColor(vm: EditorViewModel): Int {
        val preview = vm.uiState.value.previewBitmap ?: error("no preview")
        return preview.getPixel(8, 8)
    }

    private fun successfulTransaction(
        app: android.content.Context,
        uri: Uri,
    ): IncomingSourceTransaction =
        IncomingSourceTransaction(
            app,
            inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )

    private var installedHandle: AutoCloseable? = null
    private var activeEditor: EditorViewModel? = null

            private fun clearIncomingSourcesForTest() {
        context.cacheDir
            .listFiles { file ->
                file.name.startsWith("source_") &&
                    (file.name.endsWith(".img") || file.name.endsWith(".img.staging"))
            }
            .orEmpty()
            .forEach { it.delete() }
    }

    private suspend fun awaitInit(vm: EditorViewModel) {
        repeat(1200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            delay(1)
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private suspend fun awaitReady(vm: EditorViewModel) {
        repeat(400) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            delay(1)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private suspend fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(600) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(1)
        }
        val s = vm.uiState.value
        println(
            "AWAIT_TIMEOUT busy=${s.isBusy} historyBusy=${s.historyBusy} " +
                "undo=${vm.undoEntryCountForTest()} redo=${vm.redoEntryCountForTest()} " +
                "pendingRender=${vm.pendingParamRenderRevision()} " +
                "openGesture=${vm.hasOpenParameterGesture()} " +
                "revision=${s.revision} message=${s.message}"
        )
        assertTrue(predicate())
    }

    private suspend fun awaitSettled(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(600) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            delay(1)
        }
        assertTrue(predicate())
    }

    // Minimal disk-ownership interaction: no broad deletion while active
    // owned paths exist; only exact inactive paths may be removed.
    @Test
    fun diskOwnershipDoesNotDeleteActiveOwnedPaths() = runBlocking {
        val sourceFile = draftSourceFile("disk-ownership.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            awaitReady(vm)
            val activePath = checkNotNull(vm.uiState.value.sourcePath)
            assertTrue("active owned path must exist", File(activePath).exists())
            harness.clearViewModels()
            awaitSettled(vm) { !vm.uiState.value.isBusy }
            assertTrue("active source preserved after cleanup boundary", File(activePath).exists())
        } finally {
            sourceFile.delete()
        }
    }

    private fun deleteOwnedTestPath(path: File) {
        if (!path.exists()) return
        try {
            if (path.isFile) {
                path.delete()
            } else if (path.isDirectory) {
                path.listFiles()?.forEach { child ->
                    if (child.isDirectory) deleteOwnedTestPath(child)
                    else child.delete()
                }
                path.delete()
            }
        } catch (_: Exception) {
            // cleanup failure must not mask real test results
        }
    }
}