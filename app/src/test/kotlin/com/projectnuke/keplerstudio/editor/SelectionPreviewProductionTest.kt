package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import com.projectnuke.keplerstudio.ui.updateActiveSelectionParamsLive
import com.projectnuke.keplerstudio.ui.resetSelectionPreviewInstrumentationForTest
import com.projectnuke.keplerstudio.ui.selectionPreviewCopyCount
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SelectionPreviewProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness

    @Before
    fun setUpHarness() {
        harness = OwnedEditorViewModelHarness(
            RuntimeEnvironment.getApplication() as Application,
            installBitmapCopySeam = true,
        )
    }
    @After
    fun tearDown() {
        harness.close()
        SelectionPreviewPreparationGateway.resetForTest()
    }

    private fun viewModel(includeSelectionLayer: Boolean = true): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val mask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = "selection-preview-test",
                baseContentToken = "selection-preview-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
                selectionLayers =
                    if (includeSelectionLayer) {
                        listOf(
                            SelectionLayer(
                                id = "mask",
                                name = "mask",
                                kind = SelectionLayerKind.Brush,
                                bitmap = mask,
                            )
                        )
                    } else {
                        emptyList()
                    },
                activeSelectionLayerId = if (includeSelectionLayer) "mask" else null,
            )
        }
        settle { vm.startupInitCompletion.isCompleted && vm.canEnterEditorAction() }
        return vm
    }

    private fun settle(predicate: () -> Boolean) {
        // Specialized remaining call: selection-preview settlement observes
        // compound state (preview copy count, transaction state, busy flags)
        // that advances through the Default dispatcher pipeline. The bounded
        // loop (4000 max) exits immediately when the predicate holds; it
        // is not unbounded generic scheduling luck.
        repeat(4000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate(), "selection preview did not settle")
    }

    private fun awaitSignal(signal: CompletableDeferred<Unit>) {
        // Specialized remaining call: selection-preview synchronization
        // requires the Default dispatcher continuation to observe the
        // exact transaction completion event. The event primitive
        // (awaitEditorCompletionForTest) works for direct deferred events,
        // but this test verifies a compound state sequence that only settles
        // after the Default dispatcher processes the preview pipeline.
        // This is a bounded loop (6000 max) that exits immediately on
        // signal completion; it is not unbounded generic polling.
        repeat(6000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (signal.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(signal.isCompleted, "selection preview synchronization signal did not arrive")
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
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            assertEquals(RenderOperation.SelectionLivePreview, request.operation)
            rendererCalls.incrementAndGet()
            RenderResult.Success(
                operation =
                    if (request.operation == RenderOperation.SelectionLivePreview) {
                        RenderOperation.SelectionLivePreview
                    } else {
                        RenderOperation.NativePreview
                    },
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
            settle { vm.startupInitCompletion.isCompleted && vm.canEnterEditorAction() }
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
            settle {
                vm.uiState.value.canRedo && !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy
            }
            assertEquals(0, vm.uiState.value.previewBitmap?.getPixel(4, 4))
            assertEquals(0f, vm.uiState.value.selectionLayers.single().localParams.exposure)
            vm.redoEdit()
            settle { !vm.uiState.value.canRedo && !vm.uiState.value.isBusy }
            assertEquals(0.25f, vm.uiState.value.selectionLayers.single().localParams.exposure)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun currentRenderFailureRollsBackTheOptimisticSelectionEdit() {
        val vm = viewModel()
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            RenderResult.Failure(
                operation = request.operation,
                requestedRoute = NativeRenderRoute.V1,
                attemptedRoute = NativeRenderRoute.V1,
                kind = RenderFailureKind.Unexpected,
                message = "deterministic test failure",
            )
        }
        try {
            settle { vm.startupInitCompletion.isCompleted && vm.canEnterEditorAction() }
            assertTrue(vm.beginSelectionParamGesture())
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.5f) }
            settle { vm.currentSelectionParamTransaction() == null && !vm.uiState.value.isBusy }

            assertEquals(0f, vm.uiState.value.selectionLayers.single().localParams.exposure)
            assertEquals(0, vm.uiState.value.previewBitmap?.getPixel(4, 4))
            assertTrue(!vm.uiState.value.canUndo)
        } finally {
            renderer.close()
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
        val preparedB = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val hookCalls = AtomicInteger()
        val rendererCalls = AtomicInteger()
        val preparedAClosed = CompletableDeferred<Unit>()
        val adopted = CompletableDeferred<Unit>()
        val rendered = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        rendered.eraseColor(0xff3366aa.toInt())
        settle { vm.canEnterEditorAction() }
        lateinit var transaction: SelectionParamTransaction
        var firstPreviewToken = -1L
        val previewHooks =
            SelectionPreviewPreparationGateway.installHooksForTest(
                SelectionPreviewPreparationGateway.Hooks(
                    preparedOwner = {
                        when (hookCalls.incrementAndGet()) {
                            1 -> {
                                preparedA.complete(Unit)
                                releaseA.await()
                            }
                            2 -> preparedB.complete(Unit)
                        }
                    },
                    preparedOwnerClosed = { identity ->
                        if (identity.gestureId == transaction.gestureId && identity.previewToken == firstPreviewToken) {
                            preparedAClosed.complete(Unit)
                        }
                    },
                    previewAdopted = { identity ->
                        if (identity.gestureId == transaction.gestureId && identity.previewToken == transaction.latestPreviewToken) {
                            adopted.complete(Unit)
                        }
                    },
                )
            )
        assertTrue(vm.beginSelectionParamGesture())
        transaction = assertNotNull(vm.currentSelectionParamTransaction())
        val renderer = EditorRenderer.installRendererOverrideForTest {
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
            val baselineReservations = vm.selectionMaskOwnership.reservedBytes()
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.2f) }
            awaitSignal(preparedA)
            firstPreviewToken = transaction.latestPreviewToken ?: error("missing first preview token")
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.4f) }
            releaseA.complete(Unit)
            awaitSignal(preparedAClosed)
            awaitSignal(preparedB)
            awaitSignal(adopted)

            assertEquals(1, rendererCalls.get())
            assertEquals(baselineReservations, vm.selectionMaskOwnership.reservedBytes())
            assertEquals(0xff3366aa.toInt(), vm.uiState.value.previewBitmap?.getPixel(3, 3))
            vm.finishSelectionParamGesture()
            val finished = CompletableDeferred<Unit>()
            vm.viewModelScope.launch {
                vm.awaitSelectionParamGestureFinishedForTest()
                finished.complete(Unit)
            }
            awaitSignal(finished)
            assertTrue(
                vm.currentSelectionParamTransaction() == null && vm.uiState.value.canUndo,
                "transaction=${vm.currentSelectionParamTransaction()} canUndo=${vm.uiState.value.canUndo} busy=${vm.uiState.value.isBusy} message=${vm.uiState.value.message}",
            )
            assertTrue(vm.uiState.value.canUndo)
            assertEquals(baselineReservations, vm.selectionMaskOwnership.reservedBytes())
        } finally {
            releaseA.complete(Unit)
            renderer.close()
            previewHooks.close()
            SelectionPreviewPreparationGateway.resetForTest()
        }
    }

    @Test
    fun pendingSelectionReplaysLatestValueOnlyAfterTransactionStarts() {
        val rendered = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xff224466.toInt()) }
        val selectionRendered = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xff6688aa.toInt()) }
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            RenderResult.Success(
                operation = request.operation,
                requestedRoute = NativeRenderRoute.V1,
                output = if (request.operation == RenderOperation.SelectionLivePreview) selectionRendered else rendered,
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }
        val vm = viewModel(includeSelectionLayer = false)
        try {
            settle { vm.startupInitCompletion.isCompleted && vm.canEnterEditorAction() }
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitParameterRender(vm)
            settle {
                vm.hasOpenParameterGesture() &&
                    vm.adoptedParamsForTest()?.exposure == 0.2f &&
                    vm.pendingParamRenderRevision() == null
            }
            val gate = HistoryAdmissionTestSeam()
            val gateHandle = HistoryAdmissionTestSeam.install(gate)
            harness.ownSeam(gateHandle)
            installSelectionLayer(vm)

            assertTrue(vm.startSelectionParamGesture())
            settle { gate.reached.isCompleted && vm.pendingSelectionParamStart() != null }
            assertNull(vm.currentSelectionParamTransaction())

            val before = vm.uiState.value
            val beforeLocal = before.selectionLayers.single().localParams
            val beforeRevision = vm.uiState.value.revision
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.35f) }
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.55f) }
            vm.finishSelectionParamGesture()

            val pending = assertNotNull(vm.pendingSelectionParamStart())
            assertEquals(beforeLocal, vm.uiState.value.selectionLayers.single().localParams)
            assertEquals(beforeRevision, vm.uiState.value.revision)
            assertFalse(vm.uiState.value.isBusy)
            assertEquals(0.55f, pending.latestIntendedLocalParams.exposure)
            assertTrue(pending.terminalFinish)

            gate.releaseSuccess()
            settle {
                val transaction = vm.currentSelectionParamTransaction()
                transaction != null && transaction.startState.selectionLayers.single().localParams == beforeLocal
            }
            settle { vm.uiState.value.previewBitmap === selectionRendered }
            settle {
                vm.currentSelectionParamTransaction() == null &&
                    !vm.uiState.value.isBusy &&
                    vm.uiState.value.canUndo
            }
            assertEquals(0.55f, vm.uiState.value.selectionLayers.single().localParams.exposure)
            assertEquals(2, vm.undoEntryCountForTest(), "one global and one selection edit")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun pendingSelectionFailureDiscardsIntentWithoutStateMutation() {
        val renderer = installDeterministicRenderer(0xff224466.toInt())
        val vm = viewModel(includeSelectionLayer = false)
        try {
            settle { vm.canEnterEditorAction() }
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitParameterRender(vm)
            settle {
                vm.hasOpenParameterGesture() &&
                    vm.adoptedParamsForTest()?.exposure == 0.2f &&
                    vm.pendingParamRenderRevision() == null
            }
            val gate = HistoryAdmissionTestSeam()
            val gateHandle = HistoryAdmissionTestSeam.install(gate)
            harness.ownSeam(gateHandle)
            installSelectionLayer(vm)
            assertTrue(vm.startSelectionParamGesture())
            settle { gate.reached.isCompleted && vm.pendingSelectionParamStart() != null }
            val before = vm.uiState.value
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.8f) }
            vm.finishSelectionParamGesture()
            gate.releaseFailure(IllegalStateException("expected storage failure"))
            settle { vm.pendingSelectionParamStart() == null && !vm.uiState.value.historyBusy }

            assertNull(vm.currentSelectionParamTransaction())
            assertEquals(before.selectionLayers.single().localParams, vm.uiState.value.selectionLayers.single().localParams)
            assertEquals(before.revision, vm.uiState.value.revision)
            assertFalse(vm.uiState.value.isBusy)
            assertEquals(0, vm.undoEntryCountForTest())
            assertFalse(vm.uiState.value.message.orEmpty().contains("expected storage failure"))
        } finally {
            renderer.close()
        }
    }

    @Test
    fun pendingSelectionCancellationAndStaleIdentityCannotReplay() {
        val renderer = installDeterministicRenderer(0xff224466.toInt())
        val vm = viewModel(includeSelectionLayer = false)
        try {
            settle { vm.canEnterEditorAction() }
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitParameterRender(vm)
            settle {
                vm.hasOpenParameterGesture() &&
                    vm.adoptedParamsForTest()?.exposure == 0.2f &&
                    vm.pendingParamRenderRevision() == null
            }
            val gate = HistoryAdmissionTestSeam()
            val gateHandle = HistoryAdmissionTestSeam.install(gate)
            harness.ownSeam(gateHandle)
            installSelectionLayer(vm)
            assertTrue(vm.startSelectionParamGesture())
            settle { gate.reached.isCompleted && vm.pendingSelectionParamStart() != null }
            val before = vm.uiState.value
            vm.updateActiveSelectionParamsLive { it.copy(exposure = 0.8f) }
            vm.pendingSelectionParamStart()!!.prerequisite.job.cancel()
            settle { vm.pendingSelectionParamStart() == null }
            assertEquals(before.selectionLayers.single().localParams, vm.uiState.value.selectionLayers.single().localParams)
            assertEquals(before.revision, vm.uiState.value.revision)

            gate.releaseSuccess()
            gateHandle.close()
            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitParameterRender(vm)
            settle {
                vm.hasOpenParameterGesture() &&
                    vm.adoptedParamsForTest()?.exposure == 0.3f &&
                    vm.pendingParamRenderRevision() == null
            }
            val replacementGate = HistoryAdmissionTestSeam()
            harness.ownSeam(HistoryAdmissionTestSeam.install(replacementGate))
            assertTrue(vm.startSelectionParamGesture())
            settle { vm.pendingSelectionParamStart() != null }
            val replacementMask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            vm.updateUiState {
                it.copy(
                    baseContentToken = "replacement-base",
                    selectionLayers = listOf(SelectionLayer("replacement", "replacement", SelectionLayerKind.Brush, replacementMask)),
                    activeSelectionLayerId = "replacement",
                )
            }
            replacementGate.releaseSuccess()
            settle { vm.pendingSelectionParamStart() == null }
            assertNull(vm.currentSelectionParamTransaction())
            assertEquals(0f, vm.uiState.value.selectionLayers.single().localParams.exposure)
            assertFalse(vm.uiState.value.isBusy)
        } finally {
            renderer.close()
        }
    }

    private fun awaitParameterRender(vm: EditorViewModel) {
        val completion = checkNotNull(vm.parameterRenderJobForTest()) {
            "parameter render job was not created: ${startupDiagnosticForTest(vm)}"
        }
        awaitEditorCompletionForTest(
            description = "selection parameter render",
            completion = completion,
            pumpMain = {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            },
            diagnostic = {
                "parameterPhase=${vm.paramRenderPhaseForTest()} " +
                    startupDiagnosticForTest(vm)
            },
        )
    }

    private fun installDeterministicRenderer(color: Int): AutoCloseable =
        EditorRenderer.installRendererOverrideForTest { request ->
            RenderResult.Success(
                operation = request.operation,
                requestedRoute = NativeRenderRoute.V1,
                output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) },
                actualRoute = NativeRenderRoute.V1,
                decision = RenderRouteDecision.FollowDocument,
                usedDebugOverride = false,
                algorithmVersion = AlgorithmContracts.NATIVE_V1,
                participation = RenderParticipation(),
                durationMillis = 0L,
                knownTransientBytes = 0L,
            )
        }

    private fun installSelectionLayer(vm: EditorViewModel) {
        vm.updateUiState {
            it.copy(
                selectionLayers =
                    listOf(
                        SelectionLayer(
                            id = "mask",
                            name = "mask",
                            kind = SelectionLayerKind.Brush,
                            bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888),
                        )
                    ),
                activeSelectionLayerId = "mask",
            )
        }
    }
}
