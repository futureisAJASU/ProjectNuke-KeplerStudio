package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ShutdownDraftIntegrityProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        context
            .getSharedPreferences("kepler_studio_editor", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("saved_exports_initialized", true)
            .putString("saved_exports", "")
            .commit()
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

    // Test 1: adopted transaction open when teardown occurs — settlement commits
    // the adopted revision into a coherent final state before invalidation.
    @Test
    fun closeAfterAdoptionBeforeInactivityCommitsCoherently() = runBlocking {
        val sourceFile = draftSourceFile("shutdown-adopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output = renderOutput(0xff224466.toInt())
        var adopted = 0
        var inactivityFired = 0
        var commitBegan = mutableListOf<Long>()
        var committed = mutableListOf<Int>()
        var closed = mutableListOf<Long>()
        var rollbacks = 0
        var draftCaptures = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = output,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onInactivityTimerFired = { inactivityFired++ },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                    onDraftCaptureBegan = { draftCaptures += it },
                )
            )
        try {
            awaitReady(vm)
            val epochBefore = vm.draftEpochForTest()
            assertEquals(0f, vm.uiState.value.params.exposure)

            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted == 1 && vm.hasOpenParameterGesture() }
            assertEquals(0, inactivityFired)
            val revisionBeforeShutdown = vm.uiState.value.revision

            shutDown(vm)

            // Settlement committed the adopted revision coherently: no silent
            // rollback to an older Draft state.
            assertEquals("adopted params are committed", 0.3f, vm.uiState.value.params.exposure)
            assertEquals("revision advances monotonically", revisionBeforeShutdown + 1, vm.uiState.value.revision)
            assertFalse("busy clears synchronously", vm.uiState.value.isBusy)
            assertFalse("transaction is closed", vm.hasOpenParameterGesture())
            assertEquals(null, vm.pendingParamRenderRevision())
            assertEquals(1, adopted)
            assertEquals(1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals("no rollback for an adopted transaction", 0, rollbacks)
            assertEquals(0, inactivityFired)
            assertEquals("no Draft capture is made during teardown", 0, draftCaptures.size)
            assertEquals("only the teardown invalidation bumps the epoch", epochBefore + 1L, vm.draftEpochForTest())
            assertFalse("no Draft job survives teardown", vm.hasActiveDraftSaveJobForTest())
            assertTrue(vm.isShuttingDown())
        } finally {
            hooks.close()
            renderer.close()
            if (!output.isRecycled) output.recycle()
            sourceFile.delete()
        }
    }

    // Test 2: adopted revision plus a suspended newer render at teardown — the
    // adopted revision is committed and the pending render is canceled without
    // adoption.
    @Test
    fun closeDuringPendingNewerRenderCommitsAdoptedRevision() = runBlocking {
        val sourceFile = draftSourceFile("shutdown-pending-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output1 = renderOutput(0xff224466.toInt())
        val output2 = renderOutput(0xff4466AA.toInt())
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val requests = mutableListOf<Int>()
        var adopted = mutableListOf<Int>()
        var commitBegan = mutableListOf<Long>()
        var committed = mutableListOf<Int>()
        var closed = mutableListOf<Long>()
        var rollbacks = 0
        var draftCaptures = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            if (call == 2) {
                pendingGate.await()
            }
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = if (call == 1) output1 else output2,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderRequestStarted = { requests += it },
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                    onDraftCaptureBegan = { draftCaptures += it },
                )
            )
        try {
            awaitReady(vm)

            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted.isNotEmpty() && vm.hasOpenParameterGesture() }
            assertEquals(0.2f, vm.adoptedParamsForTest()?.exposure)

            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitEvent(vm) { requests.size >= 2 && vm.pendingParamRenderRevision() != null }
            assertEquals("0.4 render must be suspended", requests.size >= 2, true)
            val revisionBeforeShutdown = vm.uiState.value.revision
            val epochBefore = vm.draftEpochForTest()

            shutDown(vm)

            assertEquals("0.4 never adopts", 1, adopted.size)
            assertEquals("settlement commits the adopted 0.2", 0.2f, vm.uiState.value.params.exposure)
            assertEquals("revision advances monotonically", revisionBeforeShutdown + 1, vm.uiState.value.revision)
            assertFalse("busy clears synchronously", vm.uiState.value.isBusy)
            assertFalse(vm.hasOpenParameterGesture())
            assertEquals(null, vm.pendingParamRenderRevision())
            assertEquals(1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("no Draft capture is made during teardown", 0, draftCaptures.size)
            assertEquals("only the teardown invalidation bumps the epoch", epochBefore + 1L, vm.draftEpochForTest())
            assertFalse(vm.hasActiveDraftSaveJobForTest())
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            if (!output1.isRecycled) output1.recycle()
            if (!output2.isRecycled) output2.recycle()
            sourceFile.delete()
        }
    }

    // Test 3: teardown before any adoption — settlement rolls back to the exact
    // transaction-start state.
    @Test
    fun closeBeforeAnyAdoptionRollsBackExactStartState() = runBlocking {
        val sourceFile = draftSourceFile("shutdown-noadopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val suspended = CompletableDeferred<Unit>()
        var adopted = 0
        var commitBegan = 0
        var committed = 0
        var rollbacks = mutableListOf<Int>()
        var closed = mutableListOf<Long>()
        var draftCaptures = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            suspended.await()
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(0xffff0000.toInt()),
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onRollbackAdoptedStartState = { rollbacks += it },
                    onTransactionClosed = { closed += it },
                    onDraftCaptureBegan = { draftCaptures += it },
                )
            )
        try {
            awaitReady(vm)
            val epochBefore = vm.draftEpochForTest()

            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { vm.pendingParamRenderRevision() != null }
            val revisionBeforeShutdown = vm.uiState.value.revision
            assertEquals("no adoption before shutdown", 0, adopted)
            val startPixels = uiPixelColor(vm)
            assertEquals("visible pixels still show the start state", 0xff00ff00.toInt(), startPixels)

            shutDown(vm)

            assertEquals("params roll back to start", 0f, vm.uiState.value.params.exposure)
            assertEquals("revision advances monotonically", revisionBeforeShutdown + 1, vm.uiState.value.revision)
            assertFalse("busy clears synchronously", vm.uiState.value.isBusy)
            assertFalse("transaction is closed", vm.hasOpenParameterGesture())
            assertEquals(null, vm.pendingParamRenderRevision())
            assertEquals(0, adopted)
            assertEquals("rollback fired for the never-adopted transaction", 1, rollbacks.size)
            assertEquals(0, commitBegan)
            assertEquals(0, committed)
            assertEquals(1, closed.size)
            assertEquals("no Draft capture is made during teardown", 0, draftCaptures.size)
            assertEquals("only the teardown invalidation bumps the epoch", epochBefore + 1L, vm.draftEpochForTest())
            assertFalse(vm.hasActiveDraftSaveJobForTest())
            assertEquals("rollback records the transaction-start revision", 0, rollbacks[0])
        } finally {
            suspended.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 4: app-level save-and-leave already awaited a final Draft persistence
    // before teardown; shutdown settlement must leave that Draft untouched.
    @Test
    fun savedDraftCoherenceAcrossShutdown() = runBlocking {
        val sourceFile = draftSourceFile("shutdown-saved-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output = renderOutput(0xff224466.toInt())
        var adopted = 0
        var draftCaptures = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = output,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onDraftCaptureBegan = { draftCaptures += it },
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted == 1 && vm.hasOpenParameterGesture() }

            val saved = vm.persistDraftSnapshotNow()
            assertTrue("save-and-leave must succeed", saved)
            val epochAfterSave = vm.draftEpochForTest()
            val validatedBefore = validateCurrentDraftGeneration(context) ?: error("no validated draft")
            assertEquals("draft records adopted exposure", 0.2f, validatedBefore.manifest.params.exposure)
            assertPixelClose(outputPixelColor(output), thumbnailPixelColor(validatedBefore.thumbnailFile), "draft thumbnail records adopted output")

            shutDown(vm)

            val validatedAfter = validateCurrentDraftGeneration(context) ?: error("draft must survive teardown")
            assertEquals("draft params unchanged by teardown", 0.2f, validatedAfter.manifest.params.exposure)
            assertPixelClose(outputPixelColor(output), thumbnailPixelColor(validatedAfter.thumbnailFile), "draft pixels unchanged by teardown")
            assertEquals("only the teardown invalidation bumps the epoch", epochAfterSave + 1L, vm.draftEpochForTest())
            assertEquals("no additional Draft capture during teardown", 1, draftCaptures.size)
            assertFalse("transaction settled closed", vm.hasOpenParameterGesture())
            assertFalse(vm.uiState.value.isBusy)
        } finally {
            hooks.close()
            renderer.close()
            if (!output.isRecycled) output.recycle()
            sourceFile.delete()
        }
    }

    // Test 5: a pending scheduled autosave must never capture after shutdown.
    @Test
    fun scheduledAutosaveNeverCapturesAfterShutdown() = runBlocking {
        val sourceFile = draftSourceFile("shutdown-autosave-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output = renderOutput(0xff224466.toInt())
        var adopted = 0
        var draftCaptures = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = output,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onDraftCaptureBegan = { draftCaptures += it },
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted == 1 && vm.hasOpenParameterGesture() }

            vm.scheduleDraftAutosave()
            val epochAfterSchedule = vm.draftEpochForTest()
            assertTrue("autosave is scheduled before shutdown", vm.hasActiveDraftSaveJobForTest())

            shutDown(vm)

            assertFalse("scheduled autosave is canceled by teardown", vm.hasActiveDraftSaveJobForTest())
            assertEquals("autosave never captures", 0, draftCaptures.size)
            assertEquals("only the teardown invalidation bumps the epoch", epochAfterSchedule + 1L, vm.draftEpochForTest())
            assertEquals("no Draft ever written", null, validateCurrentDraftGeneration(context))
            assertFalse("transaction settled closed", vm.hasOpenParameterGesture())
        } finally {
            hooks.close()
            renderer.close()
            if (!output.isRecycled) output.recycle()
            sourceFile.delete()
        }
    }

    // Test 6: teardown with no parameter transaction is a no-op settlement.
    @Test
    fun closeWithNoPendingTransactionIsNoOp() = runBlocking {
        val sourceFile = draftSourceFile("shutdown-idle-source.png")
        val vm = editor(sourceFile.absolutePath)
        var created = 0
        var commitBegan = 0
        var committed = 0
        var rollbacks = 0
        var closed = 0
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onTransactionCreated = { created++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onRollbackAdoptedStartState = { rollbacks++ },
                    onTransactionClosed = { closed++ },
                )
            )
        try {
            awaitReady(vm)
            val epochBefore = vm.draftEpochForTest()
            val revisionBeforeShutdown = vm.uiState.value.revision
            assertEquals(0f, vm.uiState.value.params.exposure)

            shutDown(vm)

            assertEquals("no transaction was created", 0, created)
            assertEquals(0, commitBegan)
            assertEquals(0, committed)
            assertEquals(0, rollbacks)
            assertEquals(0, closed)
            assertEquals("params untouched by idle teardown", 0f, vm.uiState.value.params.exposure)
            assertEquals("revision untouched by idle teardown", revisionBeforeShutdown, vm.uiState.value.revision)
            assertFalse(vm.hasOpenParameterGesture())
            assertEquals("only the teardown invalidation bumps the epoch", epochBefore + 1L, vm.draftEpochForTest())
            assertTrue(vm.isShuttingDown())
        } finally {
            hooks.close()
            sourceFile.delete()
        }
    }

    @Test
    fun saveAndLeaveWithAdoptedAAndPendingBRetainsA() = runBlocking {
        val sourceFile = draftSourceFile("save-leave-pending-b.png")
        val vm = editor(sourceFile.absolutePath)
        val secondGate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val adopted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            if (calls.incrementAndGet() == 2) {
                secondStarted.complete(Unit)
                secondGate.await()
            }
            renderSuccess(if (calls.get() == 1) 0xff224466.toInt() else 0xff6688aa.toInt())
        }
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(onRenderOutputAdopted = { adopted.complete(Unit) })
        )
        try {
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted.isCompleted }
            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitEvent(vm) { secondStarted.isCompleted }

            assertTrue(vm.persistDraftSnapshotNow())
            val saved = validateCurrentDraftGeneration(context) ?: error("Draft missing")
            assertEquals(0.2f, saved.manifest.params.exposure)
            assertEquals(0.2f, vm.uiState.value.params.exposure)
            assertFalse(vm.hasOpenParameterGesture())
        } finally {
            secondGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun saveAndLeaveBeforeAnyAdoptionRollsBackExactStart() = runBlocking {
        val sourceFile = draftSourceFile("save-leave-no-adoption.png")
        val vm = editor(sourceFile.absolutePath)
        val renderGate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            started.complete(Unit)
            renderGate.await()
            renderSuccess(0xff6688aa.toInt())
        }
        try {
            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { started.isCompleted }
            assertTrue(vm.persistDraftSnapshotNow())
            val saved = validateCurrentDraftGeneration(context) ?: error("Draft missing")
            assertEquals(0f, saved.manifest.params.exposure)
            assertEquals(0f, vm.uiState.value.params.exposure)
            assertFalse(vm.hasOpenParameterGesture())
        } finally {
            renderGate.complete(Unit)
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun clearDuringHistoryPublicationHasNoLateHistoryOrLifecycleCallback() = runBlocking {
        val sourceFile = draftSourceFile("clear-history-publication.png")
        val vm = editor(sourceFile.absolutePath)
        val publish = HistoryPublishTestSeam()
        val publishHandle = HistoryPublishTestSeam.install(publish)
        val historyCallbacks = AtomicInteger()
        val lifecycleCallbacks = AtomicInteger()
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(
                onHistoryPublished = { historyCallbacks.incrementAndGet() },
                onRenderOutputAdopted = { lifecycleCallbacks.incrementAndGet() },
                onTransactionClosed = { lifecycleCallbacks.incrementAndGet() },
            )
        )
        val renderer = EditorRenderer.installRendererOverrideForTest { renderSuccess(0xff224466.toInt()) }
        try {
            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { publish.reached.isCompleted }
            harness.clearViewModels()
            val lifecycleAtClear = lifecycleCallbacks.get()
            publish.releaseGate.complete(Unit)
            awaitSettled { !vm.hasActiveDraftSaveJobForTest() }
            assertEquals(0, historyCallbacks.get())
            assertEquals(lifecycleAtClear, lifecycleCallbacks.get())
            assertEquals(0L, vm.selectionMaskOwnership.reservedBytes())
            assertFalse(vm.hasOpenParameterGesture())
            assertTrue(vm.trackerSession?.snapshot()?.activeOperations?.isEmpty() != false)
        } finally {
            publish.releaseGate.complete(Unit)
            publishHandle.close()
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    @Test
    fun clearDuringDraftSaveCancelsOwnerWithoutEpochSelfInvalidation() = runBlocking {
        val sourceFile = draftSourceFile("clear-draft-save.png")
        val vm = editor(sourceFile.absolutePath)
        val seam = DraftSaveTestSeam()
        val seamHandle = DraftSaveTestSeam.install(seam)
        try {
            val save = async { vm.persistDraftSnapshotNow() }
            awaitEvent(vm) { seam.reached.isCompleted }
            val epochAtCapture = vm.draftEpochForTest()
            harness.clearViewModels()
            seam.releaseGate.complete(Unit)
            val saveResult = runCatching { save.await() }.getOrNull()
            assertFalse("active Draft save is canceled during teardown", saveResult == true)
            assertEquals(epochAtCapture + 1L, vm.draftEpochForTest())
            assertFalse(vm.hasActiveDraftSaveJobForTest())
            assertEquals(0L, vm.selectionMaskOwnership.reservedBytes())
            assertTrue(vm.trackerSession?.snapshot()?.activeOperations?.isEmpty() != false)
        } finally {
            seam.releaseGate.complete(Unit)
            seamHandle.close()
            sourceFile.delete()
        }
    }

    @Test
    fun repeatedViewModelStoreClearIsIdempotentAfterShutdown() = runBlocking {
        val sourceFile = draftSourceFile("repeated-clear.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            awaitReady(vm)
            harness.clearViewModels()
            harness.clearViewModels()
            assertTrue(vm.isShuttingDown())
            assertFalse(vm.hasActiveDraftSaveJobForTest())
            assertFalse(vm.hasOpenParameterGesture())
        } finally {
            sourceFile.delete()
        }
    }

    private fun shutDown(vm: EditorViewModel) {
        harness.clearViewModels()
        // ViewModelStore.clear is the ownership boundary. Drain posted
        // cancellation/finalizer callbacks before the test deletes its Draft
        // and history directories.
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun deleteOwnedTestPath(path: File) {
        // A canceled background finalizer can race the test's own directory
        // cleanup. Both owners only delete the same private test path; make
        // cleanup idempotent rather than allowing FileTreeWalk to observe a
        // directory disappearing between its existence check and walk setup.
        runCatching { if (path.exists()) path.deleteRecursively() }
    }

    private fun draftSourceFile(name: String): File {
        val source = context.cacheDir.resolve(name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun renderOutput(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private suspend fun editor(sourcePath: String): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "shutdown-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        // Drain the startup init coroutine before the test body so no export-
        // history IO outlives the test sandbox or the teardown clear().
        awaitInit(vm)
        return vm
    }

    private fun uiPixelColor(vm: EditorViewModel): Int {
        val preview = vm.uiState.value.previewBitmap ?: error("no preview")
        return preview.getPixel(8, 8)
    }

    private fun outputPixelColor(output: Bitmap): Int = output.getPixel(8, 8)

    private fun assertPixelClose(expected: Int, actual: Int, message: String) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val e = (expected shr shift) and 0xff
            val a = (actual shr shift) and 0xff
            assertTrue("$message (channel shift $shift): expected $e got $a", kotlin.math.abs(e - a) <= 3)
        }
    }

    private fun thumbnailPixelColor(file: File): Int {
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: error("thumbnail decode failed")
        try {
            return decoded.getPixel(decoded.width / 2, decoded.height / 2)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private suspend fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            delay(1)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private suspend fun awaitInit(vm: EditorViewModel) {
        repeat(1200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            delay(1)
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private suspend fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            delay(1)
        }
        assertTrue(predicate())
    }

    private suspend fun awaitSettled(predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            delay(1)
        }
        assertTrue(predicate())
    }

    private fun renderSuccess(color: Int): RenderResult.Success {
        val output = renderOutput(color)
        return RenderResult.Success(
            operation = RenderOperation.NativePreview,
            requestedRoute = NativeRenderRoute.V1,
            output = output,
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )
    }
}
