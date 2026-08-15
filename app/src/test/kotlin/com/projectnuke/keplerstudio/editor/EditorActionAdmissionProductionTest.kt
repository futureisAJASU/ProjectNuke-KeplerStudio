package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.projectnuke.keplerstudio.ui.runAutoRouterV0Analysis
import com.projectnuke.keplerstudio.ui.applyCropTransform
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorActionAdmissionProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @org.junit.Before
    fun setUp() {
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
    }

    @org.junit.After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun nativeEffectAfterVisualAdoptionIsBlockedDuringHistoryLaunchWindow() = runBlocking {
        val editor = editorWithDocument()
        var renderCalls = 0
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderCalls += 1
                renderSuccess(it.operation, 0xff00aa00.toInt())
            }
        )
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))

        editor.applyVignetteCorrection()
        awaitMainUntil { gate.reached.isCompleted && !editor.uiState.value.isBusy && editor.uiState.value.historyBusy }
        val revisionAfterFirst = editor.uiState.value.revision
        editor.applySpotCleanup()

        assertEquals(1, renderCalls)
        assertEquals(revisionAfterFirst, editor.uiState.value.revision)
        assertTrue(editor.uiState.value.message.orEmpty().contains("편집 기록"))
        assertFalse(editor.uiState.value.message.orEmpty().contains("메모리"))

        gate.releaseGate.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }
        editor.applySpotCleanup()
        awaitMainUntil {
            editor.undoEntryCountForTest() == 2 &&
                !editor.uiState.value.isBusy &&
                !editor.uiState.value.historyBusy
        }
        assertEquals(2, renderCalls)
    }

    @Test
    fun parameterUpdateAfterPriorHistoryAdmissionIsRejectedButGestureSupersessionRemainsAvailable() = runBlocking {
        val editor = editorWithDocument()
        var renderCalls = 0
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderCalls += 1
                renderSuccess(it.operation, 0xff0055aa.toInt())
            }
        )
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))

        editor.applyVignetteCorrection()
        awaitMainUntil { gate.reached.isCompleted && !editor.uiState.value.isBusy && editor.uiState.value.historyBusy }
        val before = editor.uiState.value
        editor.updateParams { it.copy(exposure = 0.75f) }

        assertEquals(before.params, editor.uiState.value.params)
        assertEquals(before.revision, editor.uiState.value.revision)
        assertEquals(1, renderCalls)
        assertTrue(editor.uiState.value.message.orEmpty().contains("편집 기록"))

        gate.releaseGate.complete(Unit)
        awaitMainUntil {
            !editor.uiState.value.historyBusy &&
                !editor.uiState.value.isBusy &&
                editor.undoEntryCountForTest() == 1 &&
                editor.canEnterEditorActionPure()
        }
        editor.updateParams { it.copy(exposure = 0.75f) }
        awaitMainUntil { editor.hasOpenParameterGesture() && editor.latestParamsForTest()?.exposure == 0.75f }
        editor.updateParams { it.copy(exposure = 0.9f) }
        awaitMainUntil { editor.latestParamsForTest()?.exposure == 0.9f }
        assertTrue("same gesture keeps supersession available", editor.hasOpenParameterGesture())
    }

    @Test
    fun correctionEngineIsRejectedWhileHistoryNavigationOwnsTheDocument() = runBlocking {
        val editor = editorWithDocument()
        var renderCalls = 0
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderCalls += 1
                renderSuccess(it.operation, 0xff5500aa.toInt())
            }
        )
        val publish = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(publish))
        editor.applyVignetteCorrection()
        awaitMainUntil { publish.reached.isCompleted && !editor.uiState.value.isBusy }
        publish.releaseGate.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }

        val navigation = HistoryNavigationTestSeam()
        harness.ownSeam(HistoryNavigationTestSeam.install(navigation))
        editor.undoEdit()
        awaitMainUntil { navigation.reached.isCompleted && editor.uiState.value.historyBusy }
        val revision = editor.uiState.value.revision
        editor.applyCorrectionEngineToCurrentDocument(CorrectionEngine.Engine2)

        assertEquals(revision, editor.uiState.value.revision)
        assertNull(editor.uiState.value.correctionEngineState.pendingEngine)
        assertEquals(1, renderCalls)
        navigation.releaseGate.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy && !editor.uiState.value.isBusy }
        assertTrue("navigation retains redo", editor.uiState.value.canRedo)
    }

    @Test
    fun correctionEngineIsRejectedWhileRotationOwnsTheDocument() = runBlocking {
        val editor = editorWithDocument()
        val rotation = RotationTestSeam()
        harness.ownSeam(RotationTestSeam.install(rotation))

        editor.rotatePreview90()
        awaitMainUntil { rotation.reached.isCompleted && editor.uiState.value.isBusy }
        val revision = editor.uiState.value.revision
        editor.applyCorrectionEngineToCurrentDocument(CorrectionEngine.Engine2)

        assertEquals(revision, editor.uiState.value.revision)
        assertNull(editor.uiState.value.correctionEngineState.pendingEngine)
        rotation.releaseGate.complete(Unit)
        awaitMainUntil {
            !editor.uiState.value.isBusy &&
                !editor.uiState.value.historyBusy &&
                editor.undoEntryCountForTest() == 1
        }
    }

    @Test
    fun settingsEngineEligibilityUsesDocumentAdmission() = runBlocking {
        val editor = editorWithDocument()
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderSuccess(it.operation, 0xff006600.toInt())
            }
        )
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        editor.applyVignetteCorrection()
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        assertFalse(editor.canApplyCorrectionEngineForUi())
        gate.releaseGate.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy }
        assertTrue(editor.canApplyCorrectionEngineForUi())
    }

    @Test
    fun historyCaptureAvailabilitySeparatesBusyFromMemoryRejection() = runBlocking {
        val editor = editorWithDocument()
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderSuccess(it.operation, 0xff006600.toInt())
            }
        )
        assertEquals(HistoryCaptureAvailability.Ready, editor.historyCaptureAvailabilityForTest(0L))
        assertTrue(editor.historyCaptureAvailabilityForTest(Long.MAX_VALUE) is HistoryCaptureAvailability.MemoryRejected)

        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        editor.applyVignetteCorrection()
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        assertEquals(HistoryCaptureAvailability.HistoryBusy, editor.historyCaptureAvailabilityForTest(0L))
        gate.releaseGate.complete(Unit)
        awaitMainUntil { !editor.uiState.value.historyBusy }
    }

    @Test
    fun acceptedRotationContinuationReceivesExpectedHistoryFailureWithoutCrashing() = runBlocking {
        val editor = editorWithDocument()
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest { request ->
                renderSuccess(request.operation, 0xff006600.toInt())
            }
        )
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil {
            editor.hasOpenParameterGesture() &&
                editor.adoptedParamsForTest()?.exposure == 0.4f
        }
        val adoptedPreview = checkNotNull(editor.uiState.value.previewBitmap)
        val adoptedWidth = adoptedPreview.width
        val adoptedHeight = adoptedPreview.height
        val attempted = AtomicInteger(0)
        val settlement = editor.settleParameterTransactionBeforeExternalEdit()
        assertTrue(settlement is EditorViewModel.SettlementResult.Committed)
        assertTrue((settlement as EditorViewModel.SettlementResult.Committed).historyPrerequisite != null)
        assertTrue(
            editor.continueAfterOwnParameterSettlement(settlement) {
                attempted.incrementAndGet()
            }
        )
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        assertEquals(adoptedWidth, editor.uiState.value.previewBitmap?.width)
        gate.releaseFailure(IllegalStateException("private coordinator detail"))

        awaitMainUntil {
            !editor.uiState.value.historyBusy &&
                !editor.uiState.value.isBusy &&
                !editor.hasOpenParameterGesture()
        }
        assertEquals(adoptedWidth, editor.uiState.value.previewBitmap?.width)
        assertEquals(adoptedHeight, editor.uiState.value.previewBitmap?.height)
        assertEquals(0, attempted.get())
        assertEquals(0, editor.undoEntryCountForTest())
        assertTrue(editor.uiState.value.message.orEmpty().contains("편집 기록을 저장하지 못했습니다"))
        assertFalse(editor.uiState.value.message.orEmpty().contains("private coordinator detail"))
        assertTrue(editor.canEnterEditorActionPure())
    }

    @Test
    fun undoContinuesTheSameInvocationAfterItsOwnParameterHistorySettles() = runBlocking {
        val editor = editorWithDocument()
        val committed = AtomicInteger(0)
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(onTransactionCommitted = { committed.incrementAndGet() })
        )
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderSuccess(
                    it.operation,
                    0xff006600.toInt(),
                    it.basePreview.width,
                    it.basePreview.height,
                )
            }
        )
        try {
            editor.updateParams { it.copy(exposure = 0.4f) }
            awaitMainUntil {
                editor.hasOpenParameterGesture() && editor.adoptedParamsForTest()?.exposure == 0.4f
            }
            editor.undoEdit()
            awaitMainUntil({ debugDump(editor) }) { gate.reached.isCompleted && editor.uiState.value.historyBusy }
            assertEquals(1, committed.get())
            assertTrue(editor.hasOpenParameterGesture().not())
            assertEquals(0.4f, editor.uiState.value.params.exposure)

            gate.releaseGate.complete(Unit)
            awaitMainUntil {
                editor.uiState.value.params.exposure == 0f &&
                    editor.undoEntryCountForTest() == 0 &&
                    !editor.uiState.value.historyBusy
            }
            assertFalse(editor.uiState.value.message.orEmpty().contains("편집 기록을 정리하는 중입니다"))
        } finally {
            hooks.close()
            gate.releaseGate.complete(Unit)
        }
    }

    @Test
    fun rotationContinuesTheSameInvocationAfterItsOwnParameterHistorySettles() = runBlocking {
        val editor = editorWithDocument()
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderSuccess(it.operation, 0xff006600.toInt())
            }
        )
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil {
            editor.hasOpenParameterGesture() && editor.adoptedParamsForTest()?.exposure == 0.4f
        }
        editor.rotatePreview90()
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        assertEquals(8, checkNotNull(editor.uiState.value.previewBitmap).width)
        gate.releaseGate.complete(Unit)
        awaitMainUntil({ debugDump(editor) }) {
            !editor.uiState.value.isBusy &&
                !editor.uiState.value.historyBusy &&
                editor.undoEntryCountForTest() == 2
        }
        assertEquals(8, checkNotNull(editor.uiState.value.previewBitmap).height)
    }

    @Test
    fun autoRouterContinuesTheSameInvocationAfterItsOwnParameterHistorySettles() = runBlocking {
        val editor = editorWithDocument()
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderSuccess(it.operation, 0xff006600.toInt())
            }
        )
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil {
            editor.hasOpenParameterGesture() && editor.adoptedParamsForTest()?.exposure == 0.4f
        }
        editor.runAutoRouterV0Analysis()
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        gate.releaseGate.complete(Unit)
        awaitMainUntil({ debugDump(editor) }) {
            editor.uiState.value.message.orEmpty().contains("자동 라우터는 현재 분석 전용입니다") &&
                editor.undoEntryCountForTest() == 1
        }
    }

    @Test
    fun cropExtensionActionContinuesAfterItsOwnParameterHistorySettles() = runBlocking {
        val editor = editorWithDocument()
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        harness.ownSeam(
            EditorRenderer.installRendererOverrideForTest {
                renderSuccess(
                    it.operation,
                    0xff006600.toInt(),
                    it.basePreview.width,
                    it.basePreview.height,
                )
            }
        )
        harness.ownSeam(
            installCropTransformForTest { source, crop ->
                val dims = cropTransformedDimensions(source.width, source.height, crop)
                bitmap(0xff006600.toInt(), dims.first, dims.second)
            }
        )
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil {
            editor.hasOpenParameterGesture() && editor.adoptedParamsForTest()?.exposure == 0.4f
        }
        editor.applyCropTransform()
        awaitMainUntil({ debugDump(editor) }) {
            gate.reached.isCompleted && editor.uiState.value.historyBusy
        }
        gate.releaseGate.complete(Unit)
        awaitMainUntil({ debugDump(editor) }) {
            !editor.uiState.value.isBusy &&
                !editor.uiState.value.historyBusy &&
                editor.undoEntryCountForTest() == 2
        }
    }

    @Test
    fun successfulRotationKeepsAdoptedBitmapsLive() = runBlocking {
        val editor = editorWithDocument()
        val oldPreview = editor.uiState.value.previewBitmap
        val oldOriginal = bitmap(0xff00aa00.toInt(), 8, 4)
        val oldMask = bitmap(0xff0000aa.toInt(), 8, 4)
        editor.updateUiState {
            it.copy(
                originalPreviewBitmap = oldOriginal,
                selectionLayers = listOf(SelectionLayer("rotation-mask", "rotation-mask", SelectionLayerKind.Brush, oldMask)),
                activeSelectionLayerId = "rotation-mask",
            )
        }
        editor.rotatePreview90()
        awaitMainUntil({ debugDump(editor) }) {
            !editor.uiState.value.isBusy &&
                !editor.uiState.value.historyBusy
        }
        assertTrue(debugDump(editor), editor.undoEntryCountForTest() == 1)
        val rotated = checkNotNull(editor.uiState.value.previewBitmap)
        assertTrue(rotated !== oldPreview)
        assertFalse(rotated.isRecycled)
        assertEquals(4, rotated.width)
        assertEquals(8, rotated.height)
        val original = checkNotNull(editor.uiState.value.originalPreviewBitmap)
        val mask = editor.uiState.value.selectionLayers.single().bitmap
        assertEquals(4, original.width)
        assertEquals(8, original.height)
        assertEquals(4, mask.width)
        assertEquals(8, mask.height)
        assertFalse(original.isRecycled)
        assertFalse(mask.isRecycled)
        assertEquals(rotated.getPixel(1, 1), rotated.getPixel(1, 1))
        assertEquals(original.getPixel(1, 1), original.getPixel(1, 1))
        assertEquals(mask.getPixel(1, 1), mask.getPixel(1, 1))
        assertTrue(oldPreview!!.isRecycled)
        assertTrue(oldOriginal.isRecycled)
        assertTrue(oldMask.isRecycled)
    }

    private fun editorWithDocument(): EditorViewModel {
        val editor = harness.createEditor()
        awaitMainUntil { editor.startupInitCompletion.isCompleted }
        val preview = bitmap(0xffaa0000.toInt(), 8, 4)
        harness.ownSeam(
            OpenImageTestSeam.install(
                OpenImageTestSeam(
                    sourceTransactionFactory = { app, _ ->
                        IncomingSourceTransaction(
                            app,
                            inputStreamProvider = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                        )
                    },
                    decode = { preview },
                    nativeSessionFactory = { 9191L },
                )
            )
        )
        editor.openImage(Uri.parse("content://admission/document"))
        awaitMainUntil({ debugDump(editor) }) {
            !editor.uiState.value.isBusy &&
                editor.uiState.value.previewBitmap === preview &&
                editor.canEnterEditorActionPure()
        }
        return editor
    }

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun bitmap(color: Int, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun renderSuccess(operation: RenderOperation, color: Int, width: Int = 8, height: Int = 8): RenderResult.Success =
        RenderResult.Success(
            operation = operation,
            requestedRoute = NativeRenderRoute.V1,
            output = bitmap(color, width, height),
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )

    private fun awaitMainUntil(dump: (() -> String)? = null, predicate: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + 15_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadlineNanos) break
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5L)
        }
        if (!predicate()) {
            System.err.println("AWAIT-TIMEOUT ${dump?.invoke() ?: ""}")
        }
        assertTrue(predicate())
    }

    private fun debugDump(editor: EditorViewModel): String {
        val gate = HistoryAdmissionTestSeam.capture()
        val s = editor.uiState.value
        return "gateReached=${gate?.reached?.isCompleted} gateReleased=${gate?.releaseGate?.isCompleted} " +
            "busy=${s.isBusy} historyBusy=${s.historyBusy} revision=${s.revision} " +
            "undo=${editor.undoEntryCountForTest()} canUndo=${s.canUndo} canRedo=${s.canRedo} " +
            "params=${s.params.exposure} gesture=${editor.hasOpenParameterGesture()} msg=${s.message}"
    }
}
