package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.projectnuke.keplerstudio.ui.paintActiveSelectionAt
import com.projectnuke.keplerstudio.ui.deleteActiveSelectionLayer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorViewModelBrushTransactionTest {
    private fun viewModel(): EditorViewModel {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication() as Application)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val layerBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = "brush-test",
                baseContentToken = "brush-base",
                previewBitmap = bitmap,
                originalPreviewBitmap = bitmap,
                selectionLayers =
                    listOf(SelectionLayer("mask", "mask", SelectionLayerKind.Brush, layerBitmap)),
                activeSelectionLayerId = "mask",
            )
        }
        return vm
    }

    private fun settle(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(5)
        }
        assertTrue(predicate(), "brush transaction did not settle")
    }

    private fun awaitEditorReady(vm: EditorViewModel) {
        settle(vm) { vm.canEnterEditorAction() }
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
        assertTrue(vm.canEnterEditorAction())
        vm.acquireEditorSnapshot("settled")?.close()

        settle(vm) { vm.currentSelectionParamTransaction() == null }
        assertTrue(vm.canEnterEditorAction())
    }
}
