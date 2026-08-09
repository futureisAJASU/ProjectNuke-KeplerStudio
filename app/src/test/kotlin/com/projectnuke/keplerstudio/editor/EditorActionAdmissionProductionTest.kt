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
        harness = OwnedEditorViewModelHarness(context)
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
        awaitMainUntil { !editor.uiState.value.historyBusy && editor.undoEntryCountForTest() == 1 }
        editor.updateParams { it.copy(exposure = 0.75f) }
        awaitMainUntil { editor.hasOpenParameterGesture() }
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
    fun undoContinuesTheSameInvocationAfterItsOwnParameterHistorySettles() = runBlocking {
        val editor = editorWithDocument()
        val committed = AtomicInteger(0)
        val hooks = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(onTransactionCommitted = { committed.incrementAndGet() })
        )
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        try {
            editor.updateParams { it.copy(exposure = 0.4f) }
            awaitMainUntil { editor.hasOpenParameterGesture() }
            editor.undoEdit()
            awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
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
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil { editor.hasOpenParameterGesture() }
        editor.rotatePreview90()
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        assertEquals(8, checkNotNull(editor.uiState.value.previewBitmap).width)
        gate.releaseGate.complete(Unit)
        awaitMainUntil {
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
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil { editor.hasOpenParameterGesture() }
        editor.runAutoRouterV0Analysis()
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        gate.releaseGate.complete(Unit)
        awaitMainUntil {
            editor.uiState.value.message.orEmpty().contains("자동 라우터는 현재 분석 전용입니다") &&
                editor.undoEntryCountForTest() == 1
        }
    }

    @Test
    fun cropExtensionActionContinuesAfterItsOwnParameterHistorySettles() = runBlocking {
        val editor = editorWithDocument()
        val gate = HistoryAdmissionTestSeam()
        harness.ownSeam(HistoryAdmissionTestSeam.install(gate))
        editor.updateParams { it.copy(exposure = 0.4f) }
        awaitMainUntil { editor.hasOpenParameterGesture() }
        editor.applyCropTransform()
        awaitMainUntil { gate.reached.isCompleted && editor.uiState.value.historyBusy }
        gate.releaseGate.complete(Unit)
        awaitMainUntil {
            !editor.uiState.value.isBusy &&
                !editor.uiState.value.historyBusy &&
                editor.undoEntryCountForTest() == 2
        }
    }

    @Test
    fun successfulRotationKeepsAdoptedBitmapsLive() = runBlocking {
        val editor = editorWithDocument()
        val oldPreview = editor.uiState.value.previewBitmap
        editor.rotatePreview90()
        awaitMainUntil {
            !editor.uiState.value.isBusy &&
                !editor.uiState.value.historyBusy &&
                editor.undoEntryCountForTest() == 1
        }
        val rotated = checkNotNull(editor.uiState.value.previewBitmap)
        assertTrue(rotated !== oldPreview)
        assertFalse(rotated.isRecycled)
        rotated.getPixel(0, 0)
        assertEquals(8, rotated.width)
        assertEquals(8, rotated.height)
        assertEquals(0xffaa0000.toInt(), rotated.getPixel(0, 0))
    }

    private fun editorWithDocument(): EditorViewModel {
        val editor = harness.createEditor()
        awaitMainUntil { editor.startupInitCompletion.isCompleted }
        val preview = bitmap(0xffaa0000.toInt())
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
        awaitMainUntil {
            !editor.uiState.value.isBusy &&
                editor.uiState.value.previewBitmap === preview &&
                editor.canEnterEditorAction()
        }
        return editor
    }

    private fun bitmap(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun renderSuccess(operation: RenderOperation, color: Int): RenderResult.Success =
        RenderResult.Success(
            operation = operation,
            requestedRoute = NativeRenderRoute.V1,
            output = bitmap(color),
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )

    private fun awaitMainUntil(predicate: () -> Boolean) {
        repeat(2500) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }
}
