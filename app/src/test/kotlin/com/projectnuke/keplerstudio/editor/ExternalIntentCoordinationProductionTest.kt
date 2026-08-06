package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
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

/**
 * Phase 8: every external intent that captures editor state (engine change,
 * preset look, image replacement, brush stroke, selection gesture) must
 * settle an open adopted parameter transaction EXACTLY once — one commit,
 * one history entry — before acting, and then operate on the committed
 * state, never on the optimistic pending one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExternalIntentCoordinationProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private val red = 0xffff3333.toInt()

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
        SelectionPreviewPreparationGateway.resetForTest()
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    private class AdoptedSetup(
        val vm: EditorViewModel,
        val commitBegan: AtomicInteger,
        val committed: AtomicInteger,
        val closed: MutableList<Long>,
        val rollbacks: AtomicInteger,
        val renderCalls: AtomicInteger,
        val renderer: AutoCloseable,
        val hooks: AutoCloseable,
    ) {
        fun assertCommittedExactlyOnce() {
            assertEquals("commit began exactly once", 1, commitBegan.get())
            assertEquals("committed exactly once", 1, committed.get())
            assertEquals(1, closed.size)
            assertEquals("no rollback", 0, rollbacks.get())
        }
    }

    private fun openAdoptedTransaction(
        sourcePath: String,
        hooks: ParameterLifecycleHooks = ParameterLifecycleHooks(),
        renderer: suspend (RenderRequest) -> RenderResult = {
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
        },
    ): AdoptedSetup {
        val vm = editor(sourcePath)
        val renderCalls = AtomicInteger(0)
        val commitBegan = AtomicInteger(0)
        val committed = AtomicInteger(0)
        val closed = mutableListOf<Long>()
        val rollbacks = AtomicInteger(0)
        val wrappedHooks =
            ParameterLifecycleHooks(
                onTransactionCreated = hooks.onTransactionCreated,
                onHistoryPublished = hooks.onHistoryPublished,
                onRenderRequestStarted = hooks.onRenderRequestStarted,
                onRenderOutputProduced = hooks.onRenderOutputProduced,
                onRenderOutputAdopted = hooks.onRenderOutputAdopted,
                onInactivityTimerFired = hooks.onInactivityTimerFired,
                onTransactionCommitBegan = {
                    commitBegan.incrementAndGet()
                    hooks.onTransactionCommitBegan?.invoke(it)
                },
                onTransactionCommitted = {
                    committed.incrementAndGet()
                    hooks.onTransactionCommitted?.invoke(it)
                },
                onRollbackAdoptedStartState = {
                    rollbacks.incrementAndGet()
                    hooks.onRollbackAdoptedStartState?.invoke(it)
                },
                onTransactionClosed = {
                    closed += it
                    hooks.onTransactionClosed?.invoke(it)
                },
                onDraftCaptureBegan = hooks.onDraftCaptureBegan,
            )
        val rendererHandle =
            EditorRenderer.installRendererOverrideForTest {
                renderCalls.incrementAndGet()
                renderer(it)
            }
        val hooksHandle = ParameterLifecycleTestHook.install(wrappedHooks)
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) {
                vm.uiState.value.params.exposure == 0.2f &&
                    !vm.uiState.value.isBusy &&
                    vm.adoptedParamsForTest()?.exposure == 0.2f
            }
            assertTrue("open adopted gesture", vm.hasOpenParameterGesture())
            assertEquals(0, commitBegan.get())
            return AdoptedSetup(vm, commitBegan, committed, closed, rollbacks, renderCalls, rendererHandle, hooksHandle)
        } catch (failure: Throwable) {
            hooksHandle.close()
            rendererHandle.close()
            throw failure
        }
    }

    // Test 1: engine change settles the adopted transaction exactly once,
    // then its own render applies on top of the committed 0.2 state.
    @Test
    fun engineChangeSettlesAdoptedTransactionExactlyOnceThenApplies() {
        val sourceFile = draftSourceFile("intent-engine-source.png")
        val setup = openAdoptedTransaction(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.applyCorrectionEngineToCurrentDocument(CorrectionEngine.Engine2)
            assertTrue(
                awaitEvent(vm) {
                    setup.renderCalls.get() >= 2 &&
                        !vm.uiState.value.isBusy &&
                        vm.uiState.value.correctionEngineState.documentEngine == CorrectionEngine.Engine2
                },
            )
            setup.assertCommittedExactlyOnce()
            assertEquals("committed params retained", 0.2f, vm.uiState.value.params.exposure)
            assertEquals(red, uiPixelColor(vm))
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 2: preset look settles the adopted transaction exactly once, then
    // the preset applies the new params on top of the committed state.
    @Test
    fun presetLookSettlesAdoptedTransactionExactlyOnceThenApplies() {
        val sourceFile = draftSourceFile("intent-preset-source.png")
        val setup = openAdoptedTransaction(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            val result =
                vm.applyPresetLook(
                    EditParams(exposure = 0.9f),
                    PresetColorLook(2, 1f, FloatArray(2 * 2 * 2 * 3)),
                    "테스트 프리셋 적용",
                )
            assertEquals(PresetApplyResult.Accepted, result)
            awaitEvent(vm) {
                setup.renderCalls.get() >= 2 &&
                    !vm.uiState.value.isBusy &&
                    vm.uiState.value.params.exposure == 0.9f
            }
            setup.assertCommittedExactlyOnce()
            assertEquals(red, uiPixelColor(vm))
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 3: image replacement (openImage) settles the adopted transaction
    // exactly once before decoding the new image.
    @Test
    fun openImageSettlesAdoptedTransactionExactlyOnceBeforeDecode() {
        val sourceFile = draftSourceFile("intent-open-source.png")
        val setup = openAdoptedTransaction(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.openImage(Uri.fromFile(sourceFile))
            awaitEvent(vm) {
                vm.uiState.value.sourcePath == sourceFile.absolutePath &&
                    !vm.uiState.value.isBusy
            }
            setup.assertCommittedExactlyOnce()
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 4: a brush stroke settles the adopted transaction exactly once
    // before painting on the committed state.
    @Test
    fun brushBeginSettlesAdoptedTransactionExactlyOnceBeforePainting() {
        val sourceFile = draftSourceFile("intent-brush-source.png")
        val setup = openAdoptedTransaction(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.updateUiState {
                it.copy(
                    selectionLayers =
                        listOf(
                            SelectionLayer(
                                id = "phase8-mask",
                                name = "phase8-mask",
                                kind = SelectionLayerKind.Brush,
                                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
                            )
                        ),
                    activeSelectionLayerId = "phase8-mask",
                )
            }
            awaitEvent(vm) { vm.canEnterEditorAction() }
            assertTrue("brush stroke accepted", vm.beginBrushStroke())
            setup.assertCommittedExactlyOnce()
            assertEquals("brush paints committed params", 0.2f, vm.uiState.value.params.exposure)
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 5: a selection gesture settles the adopted transaction exactly
    // once before the preview transaction opens on the committed state.
    @Test
    fun selectionGestureSettlesAdoptedTransactionExactlyOnceBeforePreview() {
        val sourceFile = draftSourceFile("intent-selection-source.png")
        val setup = openAdoptedTransaction(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.updateUiState {
                it.copy(
                    selectionLayers =
                        listOf(
                            SelectionLayer(
                                id = "phase8-sel",
                                name = "phase8-sel",
                                kind = SelectionLayerKind.Brush,
                                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
                            )
                        ),
                    activeSelectionLayerId = "phase8-sel",
                )
            }
            awaitEvent(vm) { vm.canEnterEditorAction() }
            assertTrue("selection gesture accepted", vm.startSelectionParamGesture())
            setup.assertCommittedExactlyOnce()
            assertTrue("selection transaction open on committed state", vm.currentSelectionParamTransaction() != null)
            assertEquals(0.2f, vm.uiState.value.params.exposure)
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            setup.renderer.close()
            setup.hooks.close()
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
                baseContentToken = "intent-base",
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
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(vm.canEnterEditorAction())
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

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            if (predicate()) return true
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        repeat(5000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return true
            yieldToEditorBackgroundForTest()
        }
        return false
    }
}
