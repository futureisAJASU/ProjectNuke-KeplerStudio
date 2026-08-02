package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import com.projectnuke.keplerstudio.ui.updateActiveSelectionParamsLive
import com.projectnuke.keplerstudio.ui.resetSelectionPreviewInstrumentationForTest
import com.projectnuke.keplerstudio.ui.selectionPreviewCopyCount
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SelectionPreviewProductionTest {
    private fun viewModel(): EditorViewModel {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication() as Application)
        val base = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = "selection-preview-test",
                baseContentToken = "selection-preview-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
                selectionLayers =
                    listOf(
                        SelectionLayer(
                            id = "mask",
                            name = "mask",
                            kind = SelectionLayerKind.Brush,
                            bitmap = mask,
                        )
                    ),
                activeSelectionLayerId = "mask",
            )
        }
        return vm
    }

    private fun settle(predicate: () -> Boolean) {
        repeat(400) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(predicate(), "selection preview did not settle")
    }

    @Test
    fun livePreviewReachesAuthorizedSnapshotBeforeNativeRender() {
        val vm = viewModel()
        vm.resetSelectionPreviewInstrumentationForTest()
        settle { vm.canEnterEditorAction() }
        assertTrue(vm.beginSelectionParamGesture())
        val transaction = assertNotNull(vm.currentSelectionParamTransaction())

        vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.25f) }

        repeat(400) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.selectionPreviewCopyCount() > 0L) {
                return@repeat
            }
            Thread.sleep(10)
        }
        assertTrue(
            vm.selectionPreviewCopyCount() > 0L,
            "copy=${vm.selectionPreviewCopyCount()} succeeded=${transaction.succeeded} active=${vm.currentSelectionParamTransaction() === transaction} revision=${vm.uiState.value.revision} busy=${vm.uiState.value.isBusy} message=${vm.uiState.value.message}",
        )

        assertEquals(1L, vm.selectionPreviewCopyCount())
        vm.finishSelectionParamGesture()
        settle { vm.currentSelectionParamTransaction() == null }
    }

    @Test
    fun stalePreviewTokenCannotAcquireAProductionLease() {
        val vm = viewModel()
        settle { vm.canEnterEditorAction() }
        assertTrue(vm.beginSelectionParamGesture())
        val transaction = assertNotNull(vm.currentSelectionParamTransaction())
        vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.1f) }
        val token = transaction.latestPreviewToken
        val revision = transaction.previewRevision
        assertNotNull(revision)
        vm.beginSelectionPreview(transaction)

        assertTrue(
            vm.acquireSelectionPreviewSnapshot(
                transaction,
                token,
                revision,
                "mask",
            ) == null
        )
        vm.finishSelectionParamGesture()
    }
}
