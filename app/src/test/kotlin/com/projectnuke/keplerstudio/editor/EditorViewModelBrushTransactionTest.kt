package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.projectnuke.keplerstudio.ui.paintActiveSelectionAt
import com.projectnuke.keplerstudio.ui.deleteActiveSelectionLayer
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorViewModelBrushTransactionTest {
    private lateinit var harness: OwnedEditorViewModelHarness

    @Before
    fun setUpHarness() {
        harness = OwnedEditorViewModelHarness(
            RuntimeEnvironment.getApplication() as Application,
            installBitmapCopySeam = true,
        )
    }

    @After
    fun closeHarness() { harness.close() }

    private fun viewModel(includeSelectionLayer: Boolean = true): EditorViewModel {
        val vm = harness.createEditor()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val layerBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = "brush-test",
                baseContentToken = "brush-base",
                previewBitmap = bitmap,
                originalPreviewBitmap = bitmap,
                selectionLayers =
                    if (includeSelectionLayer) {
                        listOf(SelectionLayer("mask", "mask", SelectionLayerKind.Brush, layerBitmap))
                    } else {
                        emptyList()
                    },
                activeSelectionLayerId = if (includeSelectionLayer) "mask" else null,
            )
        }
        awaitEditorReady(vm)
        return vm
    }

    private fun settle(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(
            predicate(),
            "brush transaction did not settle: state=${vm.uiState.value} pendingRender=${vm.pendingParamRenderRevision()} adopted=${vm.adoptedParamsForTest()} pending=${vm.hasPendingBrushStartForTest()} points=${vm.pendingBrushPointCountForTest()} active=${vm.hasActiveBrushStroke()} preparing=${vm.isBrushPreparing()} historyBusy=${vm.uiState.value.historyBusy}",
        )
    }

    private fun awaitEditorReady(vm: EditorViewModel) {
        settle(vm) { vm.startupInitCompletion.isCompleted && vm.canEnterEditorAction() }
    }

    @Test
    fun `cancel during preparation restores exact pre-stroke mask`() {
        val vm = viewModel()

        awaitEditorReady(vm)
        assertTrue(vm.beginBrushStroke())
        vm.paintActiveSelectionAt(16f, 16f)
        vm.cancelBrushStroke()
        settle(vm) { !vm.hasActiveBrushStroke() }

        assertEquals(0, vm.uiState.value.revision)
        assertEquals(0, vm.uiState.value.selectionLayers.single().bitmap.getPixel(16, 16))
    }

    @Test
    fun `finish after queued first point creates one changed revision`() {
        val vm = viewModel()

        awaitEditorReady(vm)
        assertTrue(vm.beginBrushStroke())
        vm.paintActiveSelectionAt(16f, 16f)
        vm.finishBrushStroke()
        settle(vm) { !vm.hasActiveBrushStroke() }

        assertEquals(1, vm.uiState.value.revision, "message=${vm.uiState.value.message}")
        settle(vm) { vm.uiState.value.canUndo }
        assertTrue(vm.uiState.value.canUndo)
    }

    @Test
    fun `finish before history release replays queued brush point and closes stroke`() {
        val vm = viewModel(includeSelectionLayer = false)
        val renderer = installDeterministicRenderer()
        try {
            awaitEditorReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            settle(vm) {
                vm.hasOpenParameterGesture() &&
                    vm.adoptedParamsForTest()?.exposure == 0.2f &&
                    vm.pendingParamRenderRevision() == null
            }
            vm.updateUiState {
                it.copy(
                    selectionLayers =
                        listOf(
                            SelectionLayer(
                                "mask",
                                "mask",
                                SelectionLayerKind.Brush,
                                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888),
                            )
                        ),
                    activeSelectionLayerId = "mask",
                )
            }
            val gate = HistoryAdmissionTestSeam()
            harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
            assertTrue(vm.beginBrushStroke())
            settle(vm) { gate.reached.isCompleted && vm.hasPendingBrushStartForTest() && vm.isBrushPreparing() }
            vm.paintActiveSelectionAt(16f, 16f)
            vm.finishBrushStroke()

            assertTrue(vm.isBrushPreparing())
            assertTrue(vm.hasPendingBrushStartForTest())
            assertEquals(1, vm.pendingBrushPointCountForTest())
            assertTrue(vm.pendingBrushFinishRequestedForTest())

            gate.releaseSuccess()
            settle(vm) { !vm.hasActiveBrushStroke() && !vm.hasPendingBrushStartForTest() }
            assertEquals(0, vm.pendingBrushPointCountForTest())
            assertTrue(vm.uiState.value.canUndo)
            assertTrue(vm.uiState.value.selectionLayers.single().bitmap.getPixel(16, 16) != 0)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun `cancel before history release closes pending brush immediately without cancelling prerequisite`() {
        val vm = viewModel(includeSelectionLayer = false)
        val renderer = installDeterministicRenderer()
        try {
            awaitEditorReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            settle(vm) {
                vm.hasOpenParameterGesture() &&
                    vm.adoptedParamsForTest()?.exposure == 0.2f &&
                    vm.pendingParamRenderRevision() == null
            }
            vm.updateUiState {
                it.copy(
                    selectionLayers =
                        listOf(
                            SelectionLayer(
                                "mask",
                                "mask",
                                SelectionLayerKind.Brush,
                                Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888),
                            )
                        ),
                    activeSelectionLayerId = "mask",
                )
            }
            val gate = HistoryAdmissionTestSeam()
            harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
            assertTrue(vm.beginBrushStroke())
            settle(vm) { gate.reached.isCompleted && vm.hasPendingBrushStartForTest() && vm.isBrushPreparing() }
            vm.paintActiveSelectionAt(16f, 16f)
            vm.cancelBrushStroke()

            assertFalse(vm.hasPendingBrushStartForTest())
            assertFalse(vm.isBrushPreparing())
            assertFalse(vm.hasActiveBrushStroke())
            assertEquals(0, vm.pendingBrushPointCountForTest())
            assertEquals(0, vm.uiState.value.selectionLayers.single().bitmap.getPixel(16, 16))

            gate.releaseSuccess()
            settle(vm) { !vm.uiState.value.historyBusy && !vm.hasActiveBrushStroke() }
            assertFalse(vm.hasPendingBrushStartForTest())
            assertEquals(0, vm.pendingBrushPointCountForTest())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun `brush selection creation prepares mask and history off main`() {
        val vm = viewModel()
        awaitEditorReady(vm)

        vm.createBrushSelectionInternal(allowRecovery = false)
        settle(vm) { !vm.uiState.value.isBusy && vm.uiState.value.selectionLayers.size == 2 }

        assertEquals(1, vm.uiState.value.revision)
        settle(vm) { vm.uiState.value.canUndo }
    }

    @Test
    fun `cancel settles before immediate delete captures the next edit`() {
        val vm = viewModel()
        awaitEditorReady(vm)

        assertTrue(vm.beginBrushStroke())
        vm.paintActiveSelectionAt(16f, 16f)
        vm.cancelBrushStroke()
        vm.deleteActiveSelectionLayer()
        settle(vm) { !vm.uiState.value.isBusy && !vm.hasActiveBrushStroke() }

        assertTrue(vm.uiState.value.selectionLayers.isEmpty())
        assertEquals(0, vm.uiState.value.selectionLayers.size)
    }

    @Test
    fun `add stroke over a fully selected region is a true no-op`() {
        val vm = viewModel()
        vm.updateUiState { state ->
            state.selectionLayers.single().bitmap.eraseColor(android.graphics.Color.WHITE)
            state.copy(
                selectionPaintSettings =
                    state.selectionPaintSettings.copy(
                        mode = SelectionPaintMode.Add,
                        sizePx = 4f,
                        strength = 1f,
                        feather = 0f,
                    )
            )
        }

        awaitEditorReady(vm)
        assertTrue(vm.beginBrushStroke())
        vm.paintActiveSelectionAt(16f, 16f)
        vm.finishBrushStroke()
        settle(vm) { !vm.hasActiveBrushStroke() }

        assertEquals(0, vm.uiState.value.revision)
        assertTrue(!vm.uiState.value.canUndo)
    }

    @Test
    fun `selection parameter settlement completes before a concurrent edit snapshot`() {
        val vm = viewModel()
        awaitEditorReady(vm)

        assertTrue(vm.beginSelectionParamGesture())
        assertTrue(vm.settleForEditorAction())
        vm.acquireEditorSnapshot("settled")?.close()

        settle(vm) { vm.currentSelectionParamTransaction() == null }
        assertTrue(vm.canEnterEditorAction())
    }

    @Test
    fun `real history adoption accepts an exact empty-mask document`() {
        val vm = viewModel()
        awaitEditorReady(vm)
        vm.updateUiState { it.copy(selectionLayers = emptyList(), activeSelectionLayerId = null) }

        val before = vm.captureCurrentHistorySnapshot()!!
        vm.updateUiState { it.copy(params = it.params.copy(exposure = 0.5f), revision = it.revision + 1) }
        assertTrue(vm.commitUndoSnapshot(before, clearRedo = true))
        settle(vm) { !vm.historyCoordinator.flags().busy && vm.uiState.value.canUndo }

        vm.undoEdit()
        settle(vm) { !vm.historyCoordinator.flags().busy && vm.uiState.value.canRedo }

        assertTrue(vm.uiState.value.selectionLayers.isEmpty())
        assertEquals(0.0f, vm.uiState.value.params.exposure)
    }

    @Test
    fun `selection rollback preserves unrelated export state`() {
        val vm = viewModel()
        awaitEditorReady(vm)
        assertTrue(vm.beginSelectionParamGesture())
        vm.updateUiState { it.copy(exportFormat = ExportFormat.Png) }

        assertTrue(vm.canEnterEditorAction())

        assertEquals(ExportFormat.Png, vm.uiState.value.exportFormat)
        assertEquals(0, vm.uiState.value.revision)
        assertTrue(vm.uiState.value.selectionLayers.isNotEmpty())
    }

    private fun installDeterministicRenderer(): AutoCloseable =
        EditorRenderer.installRendererOverrideForTest { request ->
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888),
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
