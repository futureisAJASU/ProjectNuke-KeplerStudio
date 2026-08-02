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
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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

        settle { vm.selectionPreviewCopyCount() > 0L }
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

    @Test
    fun stalePreviewFailureCannotMutateReplacementState() {
        val vm = viewModel()
        settle { vm.canEnterEditorAction() }
        assertTrue(vm.beginSelectionParamGesture())
        val transaction = assertNotNull(vm.currentSelectionParamTransaction())
        vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.1f) }
        val token = transaction.latestPreviewToken
        val revision = checkNotNull(transaction.previewRevision)
        vm.updateUiState {
            it.copy(
                sourcePath = "replacement-document",
                baseContentToken = "replacement-base",
                isBusy = true,
                message = "replacement message",
            )
        }

        var settled = false
        vm.viewModelScope.launch {
            vm.recordSelectionPreviewFailure(
                transaction,
                token,
                revision,
                "selection-preview-base",
                "mask",
                SelectionPreviewFailureKind.RenderFailure,
                "stale failure",
                null,
            )
            settled = true
        }
        settle { settled }

        assertTrue(vm.uiState.value.isBusy)
        assertEquals("replacement message", vm.uiState.value.message)
        vm.finishSelectionParamGesture()
    }

    @Test
    fun livePreviewRendererAdoptsPixelsAndCommitsOneUndo() {
        val vm = viewModel()
        val rendererCalls = AtomicInteger(0)
        val rendered = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        rendered.eraseColor(0xff22aa44.toInt())
        EditorRenderer.installRendererOverrideForTest { request ->
            assertEquals(RenderOperation.SelectionLivePreview, request.operation)
            rendererCalls.incrementAndGet()
            RenderResult.Success(
                operation = request.operation,
                requestedRoute = NativeRenderRoute.V1,
                output = rendered,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        try {
            settle { vm.canEnterEditorAction() }
            assertTrue(vm.beginSelectionParamGesture())
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.25f) }
            val transaction = assertNotNull(vm.currentSelectionParamTransaction())

            settle {
                transaction.succeeded && vm.uiState.value.previewBitmap === rendered
            }
            assertEquals(1, rendererCalls.get())
            assertEquals(0xff22aa44.toInt(), vm.uiState.value.previewBitmap?.getPixel(4, 4))
            assertEquals(0.25f, vm.uiState.value.selectionLayers.single().localParams.exposure)

            vm.finishSelectionParamGesture()
            settle { vm.currentSelectionParamTransaction() == null && vm.uiState.value.canUndo }
            vm.undoEdit()
            settle { vm.uiState.value.canRedo && !vm.uiState.value.isBusy }
            assertEquals(0, vm.uiState.value.previewBitmap?.getPixel(4, 4))
            assertEquals(0f, vm.uiState.value.selectionLayers.single().localParams.exposure)
            vm.redoEdit()
            settle { !vm.uiState.value.canRedo && !vm.uiState.value.isBusy }
            assertEquals(0.25f, vm.uiState.value.selectionLayers.single().localParams.exposure)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
        }
    }

    @Test
    fun currentRenderFailureRollsBackTheOptimisticSelectionEdit() {
        val vm = viewModel()
        EditorRenderer.installRendererOverrideForTest { request ->
            RenderResult.Failure(
                operation = request.operation,
                requestedRoute = NativeRenderRoute.V1,
                attemptedRoute = NativeRenderRoute.V1,
                kind = RenderFailureKind.Unexpected,
                message = "deterministic test failure",
            )
        }
        try {
            settle { vm.canEnterEditorAction() }
            assertTrue(vm.beginSelectionParamGesture())
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.5f) }
            settle { vm.currentSelectionParamTransaction() == null && !vm.uiState.value.isBusy }

            assertEquals(0f, vm.uiState.value.selectionLayers.single().localParams.exposure)
            assertEquals(0, vm.uiState.value.previewBitmap?.getPixel(4, 4))
            assertTrue(!vm.uiState.value.canUndo)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
        }
    }

    @Test
    fun supersededPreparedPreviewClosesOwnerBeforeNextPreviewAdopts() {
        val vm = viewModel()
        val secondMask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        vm.updateUiState { state ->
            state.copy(
                selectionLayers =
                    state.selectionLayers +
                        SelectionLayer("mask-2", "mask-2", SelectionLayerKind.Brush, secondMask)
            )
        }
        val preparedA = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val hookCalls = AtomicInteger()
        val rendererCalls = AtomicInteger()
        val rendered = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        rendered.eraseColor(0xff3366aa.toInt())
        SelectionPreviewPreparationGateway.installPreparedOwnerHookForTest {
            if (hookCalls.incrementAndGet() == 1) {
                preparedA.complete(Unit)
                releaseA.await()
            }
        }
        EditorRenderer.installRendererOverrideForTest {
            rendererCalls.incrementAndGet()
            RenderResult.Success(
                operation = RenderOperation.SelectionLivePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = rendered,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        try {
            settle { vm.canEnterEditorAction() }
            assertTrue(vm.beginSelectionParamGesture())
            val baselineReservations = vm.selectionMaskOwnership.reservedBytes()
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.2f) }
            settle { preparedA.isCompleted }
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.4f) }
            releaseA.complete(Unit)
            val transaction = assertNotNull(vm.currentSelectionParamTransaction())
            var adopted = false
            repeat(400) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                if (
                        transaction.succeeded &&
                        vm.uiState.value.previewBitmap === rendered &&
                        vm.selectionMaskOwnership.reservedBytes() == baselineReservations
                ) {
                    adopted = true
                    return@repeat
                }
                Thread.sleep(10)
            }
            assertTrue(
                adopted,
                "calls=${hookCalls.get()} renders=${rendererCalls.get()} reserved=${vm.selectionMaskOwnership.reservedBytes()} busy=${vm.uiState.value.isBusy} message=${vm.uiState.value.message}",
            )

            assertEquals(1, rendererCalls.get())
            assertEquals(baselineReservations, vm.selectionMaskOwnership.reservedBytes())
            assertEquals(0xff3366aa.toInt(), vm.uiState.value.previewBitmap?.getPixel(3, 3))
            vm.finishSelectionParamGesture()
            settle { vm.currentSelectionParamTransaction() == null && vm.uiState.value.canUndo }
            assertTrue(vm.uiState.value.canUndo)
            assertEquals(baselineReservations, vm.selectionMaskOwnership.reservedBytes())
        } finally {
            releaseA.complete(Unit)
            EditorRenderer.clearRendererOverrideForTest()
            SelectionPreviewPreparationGateway.resetForTest()
        }
    }
}
