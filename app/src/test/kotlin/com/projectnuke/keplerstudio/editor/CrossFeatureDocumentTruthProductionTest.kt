package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * O6 CROSS-FEATURE DOCUMENT TRUTH.
 *
 * One authoritative edited-document truth across preview, history, Draft,
 * normal Full export and SR handoff, while viewport and transient render
 * presentation stay separate.
 *
 * * Transient previews never roll back params / revision / history / Draft /
 *   export truth / document identity.
 * * Viewport (zoom/pan) changes are presentation only: zero semantic change,
 *   zero edit-history entries.
 * * Draft truth survives representative tone / color / detail / rotation work.
 * * A stale render completing after an undo/redo arbitration can never
 *   overwrite the post-arbitration truth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CrossFeatureDocumentTruthProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

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
// ------------------------------------------------------------------
    // O6-F UNDO/REDO RACE
    // ------------------------------------------------------------------

    // A parameter render is physically executing when undo arbitration runs.
    // The old render completing late must never overwrite the post-undo truth.
    @Test
    fun staleRenderCompletingAfterUndoCannotOverwritePostUndoTruth() = runBlocking {
        val sourceFile = draftSourceFile("truth-undo-race.png")
        val vm = editor(sourceFile.absolutePath)
        val parked = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        vm.viewModelJobsForTest().forEach { root ->
            root.invokeOnCompletion { cause ->
                if (cause != null) {
                    println("ROOT SCOPE CANCELLED cause=$cause")
                    cause.printStackTrace()
                }
            }
        }
        val calls = AtomicInteger()
        val initialColor = 0xff00ff00.toInt()
        val committedColor = 0xff224466.toInt()
        val staleColor = 0xffaa0000.toInt()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            if (calls.getAndIncrement() == 0) {
                renderSuccess(committedColor)
            } else {
                parked.complete(Unit)
                releaseGate.await()
                renderSuccess(staleColor)
            }
        }
        val adopted = AtomicInteger()
        val committed = AtomicInteger()
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(
                onRenderOutputAdopted = { adopted.incrementAndGet() },
                onTransactionCommitted = { committed.incrementAndGet() },
            )
        )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.undoEntryCountForTest() == 1 }
            assertEquals(1, adopted.get())
            assertEquals(1, committed.get())

            // Second render is produced but not adopted when undo is invoked.
            vm.updateParams { it.copy(saturation = 0.3f) }
            awaitEvent(vm) { parked.isCompleted }

            vm.undoEdit()
            awaitEvent(vm) {
                !vm.uiState.value.historyBusy &&
                    vm.undoEntryCountForTest() == 0 &&
                    vm.redoEntryCountForTest() == 1 &&
                    !vm.uiState.value.isBusy
            }
            val paramsAfterUndo = vm.uiState.value.params
            val pixelAfterUndo = uiPixelColor(vm)

            // Old render completes late; it must be classified stale.
            releaseGate.complete(Unit)
            // Wait for the legitimate post-undo Draft autosave debounce to fire
            // and drain, then require full owner-job convergence.
            awaitSettled(vm) {
                !vm.hasOpenParameterGesture() &&
                    vm.pendingParamRenderRevision() == null &&
                    !vm.hasActiveDraftSaveJobForTest() &&
                    vm.viewModelJobsForTest().none { it.isActive }
            }

            assertEquals("undo restored the exact pre-edit params", EditParams(), vm.uiState.value.params)
            assertEquals("undo restored the exact pre-edit pixels (not the stale output)", initialColor, uiPixelColor(vm))
            assertTrue(vm.uiState.value.canRedo)
            assertFalse(vm.uiState.value.canUndo)
            assertEquals(0, vm.undoEntryCountForTest())
            assertEquals(1, vm.redoEntryCountForTest())
            assertFalse(vm.uiState.value.isBusy)
            // The late stale render changed nothing after undo arbitration.
            assertEquals("stale render cannot overwrite post-undo params", paramsAfterUndo, vm.uiState.value.params)
            assertEquals("stale render cannot overwrite post-undo pixels", pixelAfterUndo, uiPixelColor(vm))
            assertEquals("stale render never adopted", 1, adopted.get())
            assertEquals(1, committed.get())
        } finally {
            releaseGate.complete(Unit)
            renderer.close()
            hooks.close()
            sourceFile.delete()
        }
    }
// ------------------------------------------------------------------
    // O6-A TRANSIENT PREVIEW vs AUTHORITATIVE PARAMS
    // ------------------------------------------------------------------

    // During a sustained input the latest params remain authoritative even
    // while the older completed render is still the transient preview. The
    // transient never rolls back params / revision / history / Draft truth.
    @Test
    fun newestInputParamsStayAuthoritativeWhileOlderRenderCompletesLate() = runBlocking {
        val sourceFile = draftSourceFile("truth-sustained.png")
        val vm = editor(sourceFile.absolutePath)
        val parked = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val oldOutputColor = 0xff112233.toInt()
        val newestOutputColor = 0xff334455.toInt()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            if (calls.getAndIncrement() == 0) {
                renderSuccess(oldOutputColor)
            } else {
                parked.complete(Unit)
                releaseGate.await()
                renderSuccess(newestOutputColor)
            }
        }
        val adopted = AtomicInteger()
        val committed = AtomicInteger()
        val rollbacks = AtomicInteger()
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(
                onRenderOutputAdopted = { adopted.incrementAndGet() },
                onTransactionCommitted = { committed.incrementAndGet() },
                onRollbackAdoptedStartState = { rollbacks.incrementAndGet() },
            )
        )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.undoEntryCountForTest() == 1 }
            val oldPreviewPixel = uiPixelColor(vm)
            assertEquals("the committed render is the current preview", oldOutputColor, oldPreviewPixel)

            // Second render is produced but parked before adoption.
            vm.updateParams { it.copy(saturation = 0.3f) }
            awaitEvent(vm) { parked.isCompleted }

            // NEWEST INPUT PARAMS ARE AUTHORITATIVE while the older completed
            // render is still the only visible preview (transient presentation).
            assertEquals("newest input saturation is authoritative", 0.3f, vm.uiState.value.params.saturation)
            assertEquals("older tone truth never rolls back", 0.7f, vm.uiState.value.params.exposure)
            assertEquals("history still records only the committed edit", 1, vm.undoEntryCountForTest())
            assertEquals("preview legitimately shows the older transient output", oldPreviewPixel, uiPixelColor(vm))

            releaseGate.complete(Unit)
            awaitEvent(vm) {
                !vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() && vm.undoEntryCountForTest() == 2
            }

            // After settlement the newest render is adopted; nothing rolled back.
            assertEquals("newest saturation adopted", 0.3f, vm.uiState.value.params.saturation)
            assertEquals("tone truth preserved", 0.7f, vm.uiState.value.params.exposure)
            assertEquals("newest output is now the preview", newestOutputColor, uiPixelColor(vm))
            assertEquals("adopted exactly the two authoritative renders", 2, adopted.get())
            assertEquals(2, committed.get())
            assertEquals(0, rollbacks.get())
            assertEquals(2, vm.undoEntryCountForTest())
            assertFalse(vm.uiState.value.isBusy)
        } finally {
            releaseGate.complete(Unit)
            renderer.close()
            hooks.close()
            sourceFile.delete()
        }
    }
// ------------------------------------------------------------------
    // O6-B VIEWPORT IS PRESENTATION ONLY
    // ------------------------------------------------------------------

    // Zoom / pan changes must have zero semantic effect on document pixels,
    // history, Draft, export or identity, and must create no edit-history entry.
    @Test
    fun viewportChangeAltersPresentationWithoutRevisionHistoryDraftOrExportTruth() = runBlocking {
        val sourceFile = draftSourceFile("truth-viewport.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            awaitReady(vm)
            val beforeRevision = vm.uiState.value.revision
            val beforeParams = vm.uiState.value.params
            val beforeUndo = vm.undoEntryCountForTest()
            val beforeRedo = vm.redoEntryCountForTest()
            val beforeEpoch = vm.draftEpochForTest()
            val beforeToken = vm.uiState.value.baseContentToken
            val beforeCrop = vm.uiState.value.cropState
            val beforePixel = uiPixelColor(vm)

            vm.updateViewport(
                ViewportState(
                    scale = 2.5f,
                    offset = Offset(120f, -80f),
                    viewportWidth = 800,
                    viewportHeight = 600,
                )
            )
            assertEquals("viewport scale is presentation", 2.5f, vm.uiState.value.viewport.scale)
            assertEquals("viewport pan is presentation", Offset(120f, -80f), vm.uiState.value.viewport.offset)

            // Zero semantic change to the authoritative document truth.
            assertEquals(beforeRevision, vm.uiState.value.revision)
            assertEquals(beforeParams, vm.uiState.value.params)
            assertEquals("no edit-history entry from viewport", beforeUndo, vm.undoEntryCountForTest())
            assertEquals(beforeRedo, vm.redoEntryCountForTest())
            assertEquals("no Draft epoch change from viewport", beforeEpoch, vm.draftEpochForTest())
            assertEquals("document identity unchanged", beforeToken, vm.uiState.value.baseContentToken)
            assertEquals(beforeCrop, vm.uiState.value.cropState)
            assertEquals("document pixels unchanged", beforePixel, uiPixelColor(vm))
            assertFalse(vm.uiState.value.isBusy)
        } finally {
            sourceFile.delete()
        }
    }

    // ------------------------------------------------------------------
    // O6-C DRAFT TRUTH
    // ------------------------------------------------------------------

    // Representative tone / color / detail work plus rotation is saved as
    // Draft truth; the save must never distort the authoritative document.
    @Test
    fun draftSavePreservesAuthoritativeTruthAfterRepresentativeEdits() = runBlocking {
        val sourceFile = draftSourceFile("truth-draft.png")
        val vm = editor(sourceFile.absolutePath)
        val renderer = EditorRenderer.installRendererOverrideForTest {
            renderSuccess(0xff445566.toInt())
        }
        try {
            awaitReady(vm)
            // Non-square preview so rotation is observable.
            val wide = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
            wide.eraseColor(0xff00ff00.toInt())
            vm.updateUiState { it.copy(previewBitmap = wide, originalPreviewBitmap = wide) }
            awaitEvent(vm) { !vm.uiState.value.isBusy }

            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.undoEntryCountForTest() == 1 }
            vm.updateParams { it.copy(temperature = 0.2f) }
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.undoEntryCountForTest() == 2 }
            vm.updateParams { it.copy(sharpness = 0.4f) }
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.undoEntryCountForTest() == 3 }
            vm.rotatePreview90()
            awaitEvent(vm) { !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy && vm.undoEntryCountForTest() == 4 }

            val authoritativeParams = vm.uiState.value.params
            val rotatedWidth = vm.uiState.value.previewBitmap!!.width
            val rotatedHeight = vm.uiState.value.previewBitmap!!.height
            val authoritativeCrop = vm.uiState.value.cropState
            val revisionBeforeLeave = vm.uiState.value.revision

            vm.requestSaveAndLeave()
            awaitEvent(vm) { vm.editorLeaveState.value.phase == EditorLeavePhase.Completed }

            assertNotNull("Draft truth persisted", vm.uiState.value.draftGenerationId)
            assertEquals("tone truth survived Draft", 0.7f, vm.uiState.value.params.exposure)
            assertEquals("color truth survived Draft", 0.2f, vm.uiState.value.params.temperature)
            assertEquals("detail truth survived Draft", 0.4f, vm.uiState.value.params.sharpness)
            assertEquals(authoritativeParams, vm.uiState.value.params)
            assertEquals("rotation truth survived Draft width", rotatedWidth, vm.uiState.value.previewBitmap!!.width)
            assertEquals("rotation truth survived Draft height", rotatedHeight, vm.uiState.value.previewBitmap!!.height)
            assertEquals(authoritativeCrop, vm.uiState.value.cropState)
            assertEquals("stable document truth preserved by Draft completion", revisionBeforeLeave, vm.uiState.value.revision)
            assertEquals(4, vm.undoEntryCountForTest())
        } finally {
            renderer.close()
            sourceFile.delete()
        }
    }
// ------------------------------------------------------------------
    // Helpers (identical idioms to sibling lifecycle production tests)
    // ------------------------------------------------------------------

    private suspend fun editor(sourcePath: String): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "truth-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun draftSourceFile(name: String): File {
        val source = File(context.filesDir, "drafts/$name")
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(0xff00ff00.toInt())
            source.parentFile.mkdirs()
            source.outputStream().use { out ->
                assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
        return source
    }

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
        assertTrue(predicate())
    }

    private suspend fun awaitSettled(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(600) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            delay(1)
        }
        println(
            "SETTLE_TIMEOUT jobs=[${vm.activeViewModelJobDiagnosticsForTest()}] " +
                "busy=${vm.uiState.value.isBusy} gesture=${vm.hasOpenParameterGesture()} " +
                "pending=${vm.pendingParamRenderRevision()} revision=${vm.uiState.value.revision}"
        )
        println(
            "SETTLE_TIMEOUT_CATS draftSave=${vm.hasActiveDraftSaveJobForTest()} " +
                "startup=${vm.startupCoordinatorActiveForTest()} restoreDraft=${vm.restoreDraftChildActiveForTest()}"
        )
        Thread.getAllStackTraces().forEach { (thread, frames) ->
            val kepler = frames.filter { it.className.contains("projectnuke") }
            if (kepler.isNotEmpty() && thread.isAlive) {
                println("THREAD ${thread.name}:")
                kepler.take(6).forEach { println("  at $it") }
            }
        }
        assertTrue(predicate())
    }

    private fun deleteOwnedTestPath(path: File) {
        if (!path.exists()) return
        val deleted = if (path.isDirectory) path.deleteRecursively() else path.delete()
        assertTrue("test cleanup could not delete ${path.absolutePath}", deleted || !path.exists())
    }
}