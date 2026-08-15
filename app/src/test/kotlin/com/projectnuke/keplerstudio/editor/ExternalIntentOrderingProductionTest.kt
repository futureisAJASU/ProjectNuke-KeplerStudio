package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExternalIntentOrderingProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    // Test 1: adopted 0.2 plus a suspended newer 0.4 render in ONE open
    // transaction, then Undo on first invocation. Settlement must commit the
    // adopted revision exactly once, then Undo applies it on the same call.
    @Test
    fun undoCommitsAdoptedThenUndoesOnFirstInvocation() {
        val sourceFile = draftSourceFile("external-undo-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output = renderOutput(0xff224466.toInt())
        val pendingGate = CompletableDeferred<Unit>()
        val secondRenderEntered = CompletableDeferred<Unit>()
        val firstAdoption = CompletableDeferred<Int>()
        val transactionClosed = CompletableDeferred<Long>()
        val renderCalls = AtomicInteger(0)
        var adopted = mutableListOf<Int>()
        var commitBegan = mutableListOf<Long>()
        var committed = mutableListOf<Int>()
        var closed = mutableListOf<Long>()
        var rollbacks = 0
        var inactivityFired = 0
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            if (call == 2) {
                secondRenderEntered.complete(Unit)
                pendingGate.await()
                currentCoroutineContext().ensureActive()
            }
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
                    onRenderOutputAdopted = {
                        adopted += it
                        firstAdoption.complete(it)
                    },
                    onInactivityTimerFired = { inactivityFired++ },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = {
                        closed += it
                        transactionClosed.complete(it)
                    },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)

            // adopt 0.2 inside an open transaction
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitCompletion("first render must be adopted", firstAdoption, vm)
            assertEquals(0.2f, vm.adoptedParamsForTest()?.exposure)

            // request 0.4; the renderer suspends — no adoption, transaction still open
            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitCompletion("second render must enter its cancellation gate", secondRenderEntered, vm)
            assertTrue("0.4 render must be suspended", renderCalls.get() >= 2)
            assertEquals("adopted params stay 0.2", 0.2f, vm.adoptedParamsForTest()?.exposure)
            assertEquals("latest optimistic params are 0.4", 0.4f, vm.latestParamsForTest()?.exposure)
            assertTrue("transaction remains open", vm.hasOpenParameterGesture())
            assertEquals(0, inactivityFired)

            // Undo on first invocation: settlement commits 0.2, then Undo pops it
            vm.undoEdit()
            awaitCompletion("external undo must close the parameter transaction", transactionClosed, vm)
            awaitState("external undo must restore the start state", vm) {
                it.params.exposure == 0f && !it.isBusy
            }

            assertEquals("0.4 never adopts", 1, adopted.size)
            assertEquals(2, renderCalls.get())
            assertEquals("transaction settled closed", false, vm.hasOpenParameterGesture())
            assertEquals("commit began exactly once", 1, commitBegan.size)
            assertEquals("committed exactly once", 1, committed.size)
            assertEquals("closed exactly once", 1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("busy must be cleared", false, vm.uiState.value.isBusy)
            assertEquals("params are the start state after undo", 0f, vm.uiState.value.params.exposure)
            assertEquals("undo entry consumed", 0, vm.undoEntryCountForTest())
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            if (!output.isRecycled) output.recycle()
            sourceFile.delete()
        }
    }

    // Test 2: adopted 0.3 plus a suspended newer 0.5 render in ONE open
    // transaction, then rotate on first invocation. Settlement must commit the
    // adopted revision and the rotation must actually execute — the 16x8
    // preview is replaced by a new 8x16 bitmap, revision advances, the
    // pre-rotation state is recorded as an Undo entry, and a Draft capture is
    // forced — not merely busy cleared.
    @Test
    fun rotateExecutesOnFirstInvocationAfterSettlingPendingRender() {
        val sourceFile = draftSourceFile("external-rotate-source.png")
        val vm = editor(sourceFile.absolutePath)
        // 16x8 output: rotation is provable by the swapped preview dimensions.
        val output = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        output.eraseColor(0xff224466.toInt())
        val pendingGate = CompletableDeferred<Unit>()
        val secondRenderEntered = CompletableDeferred<Unit>()
        val firstAdoption = CompletableDeferred<Int>()
        val transactionClosed = CompletableDeferred<Long>()
        val draftCaptureBegan = CompletableDeferred<Long>()
        val renderCalls = AtomicInteger(0)
        var adopted = mutableListOf<Int>()
        var commitBegan = mutableListOf<Long>()
        var committed = mutableListOf<Int>()
        var closed = mutableListOf<Long>()
        var rollbacks = 0
        var draftCaptures = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            if (call == 2) {
                secondRenderEntered.complete(Unit)
                pendingGate.await()
                currentCoroutineContext().ensureActive()
            }
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
                    onRenderOutputAdopted = {
                        adopted += it
                        firstAdoption.complete(it)
                    },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = {
                        closed += it
                        transactionClosed.complete(it)
                    },
                    onRollbackAdoptedStartState = { rollbacks++ },
                    onDraftCaptureBegan = {
                        draftCaptures += it
                        draftCaptureBegan.complete(it)
                    },
                )
            )
        try {
            awaitReady(vm)

            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitCompletion("first render must be adopted", firstAdoption, vm)
            assertEquals("adopted pixels are the 16x8 output", 16, uiPixelBitmap(vm).width)
            assertEquals(8, uiPixelBitmap(vm).height)

            vm.updateParams { it.copy(exposure = 0.5f) }
            awaitCompletion("second render must enter its cancellation gate", secondRenderEntered, vm)
            assertTrue("0.5 render must be suspended", renderCalls.get() >= 2)
            assertTrue("busy while render is pending", vm.uiState.value.isBusy)
            val revisionBeforeRotate = vm.uiState.value.revision
            val epochBefore = vm.draftEpochForTest()

            // rotate on first invocation
            vm.rotatePreview90()
            awaitCompletion("external rotate must close the parameter transaction", transactionClosed, vm)
            awaitState("external rotate must publish the rotated preview", vm) {
                val preview = it.previewBitmap
                preview != null && !it.isBusy && preview.width == 8 && preview.height == 16
            }

            assertEquals("0.5 never adopts", 1, adopted.size)
            assertEquals(2, renderCalls.get())
            assertFalse("transaction settled closed", vm.hasOpenParameterGesture())
            assertEquals("adopted 0.3 committed exactly once", 1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("adopted params committed", 0.3f, vm.uiState.value.params.exposure)
            assertEquals("settlement and rotation each advance the revision", revisionBeforeRotate + 2, vm.uiState.value.revision)
            assertEquals("settlement and rotation each record history", 2, vm.undoEntryCountForTest())
            // settle schedules an autosave (+1), the rotation forces a save (+1)
            awaitCompletion("external rotate must begin draft capture", draftCaptureBegan, vm)
            awaitState("external rotate draft generation must validate", vm) {
                validateCurrentDraftGeneration(context) != null
            }
            assertEquals("rotation forces exactly one Draft capture", 1, draftCaptures.size)
            assertEquals("rotation bumps the Draft epoch", epochBefore + 2L, vm.draftEpochForTest())
            val validated = validateCurrentDraftGeneration(context) ?: error("no validated draft")
            assertEquals("draft records committed 0.3", 0.3f, validated.manifest.params.exposure)
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            if (!output.isRecycled) output.recycle()
            sourceFile.delete()
        }
    }

    // Test 3: no adoption, external save — settlement rolls back to the exact
    // start state exactly once and the saved Draft records the start state.
    @Test
    fun externalSaveWithNoAdoptionRollsBackExactlyOnce() {
        val sourceFile = draftSourceFile("external-noadopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val suspended = CompletableDeferred<Unit>()
        val renderEntered = CompletableDeferred<Unit>()
        var adopted = 0
        var commitBegan = 0
        var committed = 0
        var rollbacks = mutableListOf<Int>()
        var closed = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            renderEntered.complete(Unit)
            suspended.await()
            currentCoroutineContext().ensureActive()
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
                )
            )
        try {
            awaitReady(vm)
            val startPixels = uiPixelColor(vm)
            assertEquals(0f, vm.uiState.value.params.exposure)
            assertEquals("no undo before edits", 0, vm.undoEntryCountForTest())
            val epochBefore = vm.draftEpochForTest()

            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitCompletion("pending render must enter its cancellation gate", renderEntered, vm)
            assertEquals("no adoption before the save", 0, adopted)

            val saved = persistDraftForTest(vm)
            assertTrue("save must complete", saved)

            assertEquals("params roll back to start", 0f, vm.uiState.value.params.exposure)
            assertEquals("pixels roll back to start", startPixels, uiPixelColor(vm))
            assertFalse("busy clears synchronously", vm.uiState.value.isBusy)
            assertEquals("no undo entry for abandoned gesture", 0, vm.undoEntryCountForTest())
            assertEquals("no commit for a never-adopted transaction", 0, commitBegan)
            assertEquals(0, committed)
            assertEquals("rollback fired exactly once", 1, rollbacks.size)
            assertEquals(1, closed.size)
            assertEquals("only the save bumps the epoch", epochBefore + 1L, vm.draftEpochForTest())

            val validated = validateCurrentDraftGeneration(context) ?: error("no validated draft")
            assertEquals("draft records start params", 0f, validated.manifest.params.exposure)
            assertPixelClose(startPixels, thumbnailPixelColor(validated.thumbnailFile), "draft records start pixels")
        } finally {
            suspended.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    private fun draftSourceFile(name: String = "external-source.png"): File {
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

    private fun editor(sourcePath: String): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "external-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        // Drain the startup init coroutine before the test body so no export-
        // history IO outlives the test sandbox.
        awaitInit(vm)
        return vm
    }

    private fun uiPixelColor(vm: EditorViewModel): Int {
        val preview = vm.uiState.value.previewBitmap ?: error("no preview")
        return preview.getPixel(8, 8)
    }

    private fun uiPixelBitmap(vm: EditorViewModel): Bitmap =
        vm.uiState.value.previewBitmap ?: error("no preview")

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

    private fun awaitReady(vm: EditorViewModel) {
        awaitState("editor must become ready", vm) { !it.isBusy && !it.historyBusy }
        assertTrue("editor action admission must be ready", vm.canEnterEditorAction())
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitEditorCompletionForTest(
            description = "startup init must complete",
            completion = vm.startupInitCompletion,
            pumpMain = ::drainMain,
            diagnostic = { editorDiagnostic(vm) },
        )
    }

    private fun awaitCompletion(description: String, completion: CompletableDeferred<*>, vm: EditorViewModel) {
        awaitEditorCompletionForTest(
            description = description,
            completion = completion,
            pumpMain = ::drainMain,
            diagnostic = { editorDiagnostic(vm) },
        )
    }

    private fun awaitState(description: String, vm: EditorViewModel, predicate: (EditorUiState) -> Boolean) {
        val completion = CompletableDeferred<Unit>()
        val observerScope = CoroutineScope(Dispatchers.Default)
        val observer = observerScope.launch {
            vm.uiState.first(predicate)
            completion.complete(Unit)
        }
        try {
            awaitCompletion(description, completion, vm)
        } finally {
            observer.cancel()
            observerScope.cancel()
        }
    }

    private fun persistDraftForTest(vm: EditorViewModel): Boolean {
        val callerScope = CoroutineScope(Dispatchers.Default)
        val deferred = callerScope.async { vm.persistDraftSnapshotNow() }
        try {
            awaitEditorCompletionForTest(
                description = "draft save caller must complete",
                completion = deferred,
                timeoutMillis = 30_000L,
                pumpMain = ::drainMain,
                diagnostic = { "leave=${vm.editorLeaveState.value} ${editorDiagnostic(vm)}" },
            )
            return runBlocking { deferred.await() }
        } finally {
            deferred.cancel()
            callerScope.cancel()
        }
    }

    private fun drainMain() {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(20, java.util.concurrent.TimeUnit.MILLISECONDS)
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun editorDiagnostic(vm: EditorViewModel): String =
        "busy=${vm.uiState.value.isBusy} revision=${vm.uiState.value.revision} " +
            "historyBusy=${vm.uiState.value.historyBusy} admission=${vm.editorActionAdmissionForTest()} " +
            "pending=${vm.pendingParamRenderRevision()} phase=${vm.paramRenderPhaseForTest()} " +
            "open=${vm.hasOpenParameterGesture()}"
}
