package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
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
import com.projectnuke.keplerstudio.editor.EditorViewModel.SettlementReason
import com.projectnuke.keplerstudio.editor.EditorViewModel.SettlementResult
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 6: transaction terminal state is separated from per-render state.
 * Multiple sequential adoptions in ONE open gesture (0.2 → 0.4 → 0.6) must
 * commit only the latest actually adopted revision, produce exactly ONE undo
 * entry, and undo/redo must restore exact pixels. A failing or superseded
 * newer render must never discard an already-adopted output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ParameterMultiAdoptionProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private val red = 0xffff3333.toInt()
    private val green = 0xff33ff33.toInt()
    private val blue = 0xff3333ff.toInt()

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

    // Test 1: 0.2 → 0.4 → 0.6 adopted sequentially in ONE gesture; the
    // terminal commit uses only the latest adopted revision (0.6), records
    // exactly one history entry, and Undo/Redo restore exact pixels
    // (gesture start vs. latest adopted).
    @Test
    fun sequentialAdoptionsCommitLatestWithSingleUndoAndExactRedoPixels() {
        val sourceFile = draftSourceFile("multi-adopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val colors = intArrayOf(red, green, blue)
        val renderCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        var commitBegan = 0
        var committed = 0
        val closed = mutableListOf<Long>()
        var rollbacks = 0
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(colors[call - 1]),
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
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            val startPixel = uiPixelColor(vm)

            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted.size == 1 && uiPixelColor(vm) == red }
            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitEvent(vm) { adopted.size == 2 && uiPixelColor(vm) == green }
            vm.updateParams { it.copy(exposure = 0.6f) }
            awaitEvent(vm) { adopted.size == 3 && uiPixelColor(vm) == blue }

            assertTrue("one open gesture across all adoptions", vm.hasOpenParameterGesture())
            assertEquals("latest requested = 0.6", 0.6f, vm.latestParamsForTest()?.exposure)
            assertEquals("latest adopted = 0.6", 0.6f, vm.adoptedParamsForTest()?.exposure)
            assertEquals("no history entry before commit", 0, vm.undoEntryCountForTest())

            val settled = vm.settleParameterTransaction(SettlementReason.ExternalEdit)
            assertTrue("settle commits", settled is SettlementResult.Committed)
            assertEquals("commit began exactly once", 1, commitBegan)
            assertEquals("committed exactly once", 1, committed)
            assertEquals(1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("0.6 retained after commit", 0.6f, vm.uiState.value.params.exposure)
            assertEquals(blue, uiPixelColor(vm))
            assertEquals("exactly one undo entry", 1, vm.undoEntryCountForTest())

            vm.undoEdit()
            awaitEvent(vm) {
                !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0f && vm.uiState.value.canRedo
            }
            assertEquals("undo restores gesture-start params", 0f, vm.uiState.value.params.exposure)
            assertEquals("undo restores exact start pixels", startPixel, uiPixelColor(vm))
            assertEquals(0, vm.undoEntryCountForTest())
            assertTrue(vm.uiState.value.canRedo)

            vm.redoEdit()
            awaitEvent(vm) {
                !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.6f && vm.uiState.value.canUndo
            }
            assertEquals("redo restores latest adopted params", 0.6f, vm.uiState.value.params.exposure)
            assertEquals("redo restores exact latest-adopted pixels", blue, uiPixelColor(vm))
            assertEquals(1, vm.undoEntryCountForTest())
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 2: a FAILING newer render must not discard the previously adopted
    // output. 0.2 is adopted; the 0.4 render fails; params and pixels stay at
    // 0.2, the gesture stays open, and settlement commits 0.2 with exact
    // undo/redo pixels.
    @Test
    fun failedNewerRenderRetainsPreviouslyAdoptedOutput() {
        val sourceFile = draftSourceFile("multi-adopt-fail-source.png")
        val vm = editor(sourceFile.absolutePath)
        val renderCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        var commitBegan = 0
        var committed = 0
        var rollbacks = 0
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            if (call == 2) {
                RenderResult.Failure(
                    operation = RenderOperation.NativePreview,
                    requestedRoute = NativeRenderRoute.V1,
                    attemptedRoute = NativeRenderRoute.V1,
                    kind = RenderFailureKind.Unexpected,
                    message = "phase6 forced failure",
                )
            } else {
                RenderResult.Success(
                    operation = RenderOperation.NativePreview,
                    requestedRoute = NativeRenderRoute.V1,
                    output = renderOutput(red),
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
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            val startPixel = uiPixelColor(vm)

            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted.size == 1 && uiPixelColor(vm) == red }

            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitEvent(vm) {
                renderCalls.get() >= 2 &&
                    vm.pendingParamRenderRevision() == null &&
                    !vm.uiState.value.isBusy
            }
            assertEquals("failed render must not adopt", 1, adopted.size)
            assertEquals("0.2 retained in params", 0.2f, vm.uiState.value.params.exposure)
            assertEquals("0.2 retained in pixels", red, uiPixelColor(vm))
            assertTrue("gesture stays open", vm.hasOpenParameterGesture())
            assertEquals("no rollback despite failure", 0, rollbacks)

            val settled = vm.settleParameterTransaction(SettlementReason.ExternalEdit)
            assertTrue("settle commits the retained adoption", settled is SettlementResult.Committed)
            assertEquals("one commit", 1, commitBegan)
            assertEquals(1, committed)
            assertEquals(0.2f, vm.uiState.value.params.exposure)
            assertEquals("exactly one undo entry", 1, vm.undoEntryCountForTest())

            awaitEvent(vm) { !vm.uiState.value.historyBusy }
            vm.undoEdit()
            awaitEvent(vm) {
                !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0f && vm.uiState.value.canRedo
            }
            assertEquals(startPixel, uiPixelColor(vm))

            awaitEvent(vm) { !vm.uiState.value.historyBusy }
            vm.redoEdit()
            awaitEvent(vm) {
                !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.2f && vm.uiState.value.canUndo
            }
            assertEquals("redo restores the retained adoption pixels", red, uiPixelColor(vm))
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 3: while a newer render is pending, the latest REQUESTED revision
    // is distinct from the latest ADOPTED one; the superseded render never
    // adopts; the newest render wins and commits with a single undo entry.
    @Test
    fun supersededRenderNeverAdoptsWhileNewerRequestWins() {
        val sourceFile = draftSourceFile("multi-adopt-supersede-source.png")
        val vm = editor(sourceFile.absolutePath)
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        var commitBegan = 0
        var committed = 0
        var rollbacks = 0
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            if (call == 2) pendingGate.await()
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(if (call == 1) red else blue),
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
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)

            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted.size == 1 && uiPixelColor(vm) == red }

            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }
            assertEquals("latest requested is 0.4", 0.4f, vm.latestParamsForTest()?.exposure)
            assertEquals("latest adopted is still 0.2", 0.2f, vm.adoptedParamsForTest()?.exposure)

            vm.updateParams { it.copy(exposure = 0.6f) }
            awaitEvent(vm) { adopted.size == 2 && uiPixelColor(vm) == blue }

            // release the superseded render; its output must never adopt
            pendingGate.complete(Unit)
            awaitEvent(vm) {
                renderCalls.get() == 3 && adopted.size == 2 && !vm.uiState.value.isBusy
            }
            assertEquals("superseded render never adopts", 2, adopted.size)
            assertEquals("0.6 won", 0.6f, vm.uiState.value.params.exposure)
            assertEquals(blue, uiPixelColor(vm))
            assertEquals("latest adopted is 0.6", 0.6f, vm.adoptedParamsForTest()?.exposure)

            val settled = vm.settleParameterTransaction(SettlementReason.ExternalEdit)
            assertTrue("settle commits the newest adoption", settled is SettlementResult.Committed)
            assertEquals(1, commitBegan)
            assertEquals(1, committed)
            assertEquals(0, rollbacks)
            assertEquals("exactly one undo entry", 1, vm.undoEntryCountForTest())
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    private fun draftSourceFile(name: String): File {
        val source = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff00ff00.toInt())
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
                baseContentToken = "multi-adopt-base",
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

    private fun awaitReady(vm: EditorViewModel) {
        repeat(1000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorActionPure()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(vm.canEnterEditorActionPure())
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(1200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            if (predicate()) return
        }
        repeat(5000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }
}
