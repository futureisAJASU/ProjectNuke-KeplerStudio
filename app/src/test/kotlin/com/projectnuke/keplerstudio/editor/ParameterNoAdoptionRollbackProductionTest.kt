package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 9: when a parameter gesture closes with NO adopted render, every
 * rollback source must restore the exact transaction-start state (params,
 * pixels, engine state), create no history entry, and fire exactly one
 * rollback signal with the start revision. The matrix covers generic
 * editor-action settlement, engine change, preset look, image replacement,
 * brush begin, selection gesture, and undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ParameterNoAdoptionRollbackProductionTest {
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
    fun closeHarness() { harness.close() }

    // Test 1: a plain editor-action readiness check settles the gesture and
    // rolls back to the exact start state.
    @Test
    fun editorActionSettlementRollsBackExactStartState() {
        val sourceFile = draftSourceFile("phase9-settle-source.png")
        val setup = openNoAdoptionGesture(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            assertTrue("editor action must be accepted from settled state", vm.canEnterEditorAction())
            assertRolledBackExactState(setup)
            assertFalse("busy must clear", vm.uiState.value.isBusy)
            assertRollbackSignals(setup)
            assertEquals(0, vm.undoEntryCountForTest())
        } finally {
            setup.releaseGates()
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 2: an engine change rolls the gesture back and renders from the
    // rolled-back start state (its render request carries the start params).
    @Test
    fun engineChangeAfterNoAdoptionRendersFromRolledBackState() {
        val sourceFile = draftSourceFile("phase9-engine-source.png")
        val setup = openNoAdoptionGesture(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.applyCorrectionEngineToCurrentDocument(CorrectionEngine.Engine2)
            assertTrue(
                awaitEvent(vm) {
                    setup.requestOperations.contains(RenderOperation.EngineSwitch)
                },
            )
            // The engine render is blocked at the post-settle gate: the exact
            // rolled-back state is still visible.
            assertRolledBackExactState(setup)
            assertEquals(
                "engine render must be requested from rolled-back start params",
                setup.startParams,
                setup.requestParams.last(),
            )
            assertEquals(CorrectionEngine.Engine2, vm.uiState.value.correctionEngineState.pendingEngine)
            assertRollbackSignals(setup)
            setup.gate2.complete(Unit)
            assertTrue(
                awaitEvent(vm) {
                    !vm.uiState.value.isBusy &&
                        vm.uiState.value.correctionEngineState.documentEngine == CorrectionEngine.Engine2
                },
            )
            assertEquals("params remain start after engine render", setup.startParams, vm.uiState.value.params)
            assertEquals(red, uiPixelColor(vm))
            assertTrue(awaitEvent(vm) { vm.undoEntryCountForTest() == 1 })
        } finally {
            setup.releaseGates()
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 3: a preset look rolls the gesture back and applies its params on
    // top of the rolled-back start state.
    @Test
    fun presetLookAfterNoAdoptionRendersFromRolledBackState() {
        val sourceFile = draftSourceFile("phase9-preset-source.png")
        val setup = openNoAdoptionGesture(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            val result =
                vm.applyPresetLook(
                    EditParams(exposure = 0.9f),
                    PresetColorLook(2, 1f, FloatArray(2 * 2 * 2 * 3)),
                    "phase9 preset",
                )
            assertEquals(PresetApplyResult.Accepted, result)
            assertTrue(
                awaitEvent(vm) {
                    setup.requestOperations.contains(RenderOperation.Preset)
                },
            )
            assertRolledBackExactState(setup)
            assertRollbackSignals(setup)
            setup.gate2.complete(Unit)
            assertTrue(
                awaitEvent(vm) {
                    !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.9f
                },
            )
            assertEquals(red, uiPixelColor(vm))
            assertTrue(awaitEvent(vm) { vm.undoEntryCountForTest() == 1 })
        } finally {
            setup.releaseGates()
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 4: image replacement rolls the gesture back before decoding. In
    // Robolectric the native bridge cannot load, so the replacement itself
    // fails gracefully; the exact rolled-back state must remain visible.
    @Test
    fun openImageAfterNoAdoptionRollsBackBeforeDecode() {
        val sourceFile = draftSourceFile("phase9-open-source.png")
        val replacement = draftSourceFile("phase9-open-replacement.png", 0xff0000ff.toInt())
        val setup = openNoAdoptionGesture(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.openImage(Uri.fromFile(replacement))
            assertTrue(
                awaitEvent(vm) {
                    !vm.uiState.value.isBusy &&
                        (vm.uiState.value.message?.startsWith("이미지를 열지 못했습니다") == true ||
                            vm.uiState.value.sourcePath != sourceFile.absolutePath)
                },
            )
            assertRollbackSignals(setup)
            assertRolledBackExactState(setup)
            assertEquals("no history entry from a rolled-back gesture", 0, vm.undoEntryCountForTest())
        } finally {
            setup.releaseGates()
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
            replacement.delete()
        }
    }

    // Test 5: a brush stroke rolls the gesture back and paints on the
    // rolled-back start state.
    @Test
    fun brushBeginAfterNoAdoptionPaintsFromRolledBackState() {
        val sourceFile = draftSourceFile("phase9-brush-source.png")
        val setup = openNoAdoptionGesture(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.updateUiState {
                it.copy(
                    selectionLayers =
                        listOf(
                            SelectionLayer(
                                id = "phase9-mask",
                                name = "phase9-mask",
                                kind = SelectionLayerKind.Brush,
                                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
                            )
                        ),
                    activeSelectionLayerId = "phase9-mask",
                )
            }
            awaitEvent(vm) { vm.canEnterEditorAction() }
            assertTrue("brush stroke must be accepted", vm.beginBrushStroke())
            assertRollbackSignals(setup)
            assertEquals("brush paints rolled-back start params", setup.startParams, vm.uiState.value.params)
            assertEquals("no history entry from a rolled-back gesture", 0, vm.undoEntryCountForTest())
        } finally {
            setup.releaseGates()
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 6: a selection gesture rolls the gesture back and opens its
    // transaction on the rolled-back start state.
    @Test
    fun selectionGestureAfterNoAdoptionOpensFromRolledBackState() {
        val sourceFile = draftSourceFile("phase9-selection-source.png")
        val setup = openNoAdoptionGesture(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.updateUiState {
                it.copy(
                    selectionLayers =
                        listOf(
                            SelectionLayer(
                                id = "phase9-sel",
                                name = "phase9-sel",
                                kind = SelectionLayerKind.Brush,
                                bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
                            )
                        ),
                    activeSelectionLayerId = "phase9-sel",
                )
            }
            awaitEvent(vm) { vm.canEnterEditorAction() }
            assertTrue("selection gesture must be accepted", vm.startSelectionParamGesture())
            assertRollbackSignals(setup)
            assertEquals("selection opens on rolled-back start params", setup.startParams, vm.uiState.value.params)
            assertNotNull("selection transaction open", vm.currentSelectionParamTransaction())
            assertEquals("no history entry from a rolled-back gesture", 0, vm.undoEntryCountForTest())
        } finally {
            setup.releaseGates()
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    // Test 7: an undo request during the gesture rolls back first; with no
    // history entry the request reports nothing to undo.
    @Test
    fun undoEditDuringNoAdoptionGestureRollsBackExactStartState() {
        val sourceFile = draftSourceFile("phase9-undo-source.png")
        val setup = openNoAdoptionGesture(sourceFile.absolutePath)
        val vm = setup.vm
        try {
            vm.undoEdit()
            assertRolledBackExactState(setup)
            assertFalse("busy must clear", vm.uiState.value.isBusy)
            assertRollbackSignals(setup)
            assertEquals(0, vm.undoEntryCountForTest())
            assertEquals("되돌리기 편집 기록이 없습니다.", vm.uiState.value.message)
        } finally {
            setup.releaseGates()
            setup.renderer.close()
            setup.hooks.close()
            sourceFile.delete()
        }
    }

    private fun assertRolledBackExactState(setup: NoAdoptionSetup) {
        assertEquals("params must equal gesture-start params", setup.startParams, setup.vm.uiState.value.params)
        assertEquals("pixels must equal gesture-start pixels", setup.startPixels, uiPixelColor(setup.vm))
    }

    private fun assertRollbackSignals(setup: NoAdoptionSetup) {
        assertEquals(0, setup.adopted.get())
        assertEquals(0, setup.commitBegan.get())
        assertEquals(0, setup.committed.get())
        assertEquals("exactly one rollback signal", 1, setup.rollbackRevisions.size)
        assertEquals("rollback reports the gesture-start revision", setup.startRevision, setup.rollbackRevisions[0])
        assertEquals("exactly one transaction close", 1, setup.closed.size)
        assertFalse("transaction must be closed", setup.vm.hasOpenParameterGesture())
    }

    private fun openNoAdoptionGesture(sourcePath: String): NoAdoptionSetup {
        val vm = editor(sourcePath)
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val requestParams: MutableList<EditParams> = Collections.synchronizedList(mutableListOf())
        val requestOperations: MutableList<RenderOperation> = Collections.synchronizedList(mutableListOf())
        val adopted = AtomicInteger(0)
        val commitBegan = AtomicInteger(0)
        val committed = AtomicInteger(0)
        val rollbackRevisions = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                requestParams += request.params
                requestOperations += request.operation
                if (vm.hasOpenParameterGesture()) gate1.await()
                else gate2.await()
                RenderResult.Success(
                    operation = request.operation,
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
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted.incrementAndGet() },
                    onTransactionCommitBegan = { commitBegan.incrementAndGet() },
                    onTransactionCommitted = { committed.incrementAndGet() },
                    onRollbackAdoptedStartState = { rollbackRevisions += it },
                    onTransactionClosed = { closed += it },
                )
            )
        awaitReady(vm)
        val startParams = vm.uiState.value.params
        val startPixels = uiPixelColor(vm)
        val startRevision = vm.uiState.value.revision
        vm.updateParams { it.copy(exposure = 0.7f) }
        assertTrue(
            awaitEvent(vm) {
                vm.pendingParamRenderRevision() != null && vm.hasOpenParameterGesture()
            },
        )
        assertEquals(0, adopted.get())
        return NoAdoptionSetup(
            vm = vm,
            renderer = renderer,
            hooks = hooks,
            gate1 = gate1,
            gate2 = gate2,
            requestParams = requestParams,
            requestOperations = requestOperations,
            startParams = startParams,
            startPixels = startPixels,
            startRevision = startRevision,
            adopted = adopted,
            commitBegan = commitBegan,
            committed = committed,
            rollbackRevisions = rollbackRevisions,
            closed = closed,
        )
    }

    private data class NoAdoptionSetup(
        val vm: EditorViewModel,
        val renderer: AutoCloseable,
        val hooks: AutoCloseable,
        val gate1: CompletableDeferred<Unit>,
        val gate2: CompletableDeferred<Unit>,
        val requestParams: MutableList<EditParams>,
        val requestOperations: MutableList<RenderOperation>,
        val startParams: EditParams,
        val startPixels: Int,
        val startRevision: Int,
        val adopted: AtomicInteger,
        val commitBegan: AtomicInteger,
        val committed: AtomicInteger,
        val rollbackRevisions: MutableList<Int>,
        val closed: MutableList<Long>,
    ) {
        fun releaseGates() {
            gate1.complete(Unit)
            gate2.complete(Unit)
        }
    }

    private fun draftSourceFile(name: String, color: Int = 0xff00ff00.toInt()): File {
        val source = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
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
                baseContentToken = "phase9-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
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
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            if (predicate()) return true
        }
        repeat(5000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return true
            Thread.yield()
        }
        return false
    }

    companion object {
        private const val red = 0xffff0000.toInt()
    }
}
