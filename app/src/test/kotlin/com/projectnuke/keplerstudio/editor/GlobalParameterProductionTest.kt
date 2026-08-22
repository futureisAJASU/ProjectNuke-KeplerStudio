package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GlobalParameterProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        RestoredWorkingSourceOwnership.clearForTest()
        deleteDirectoryIfPresentForTest(context.filesDir.resolve("editor_history_v3"))
        resetDraftSandboxForTest(context)
    }

    @After
    fun closeHarness() {
        harness.close()
        RestoredWorkingSourceOwnership.clearForTest()
        resetDraftSandboxForTest(context)
    }

    @Test
    fun updateParamsUsesWorkerRenderAndCreatesOneUndoEntry() {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        output.eraseColor(0xff224466.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = "global-params-test",
                baseContentToken = "global-params-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        val renderer = EditorRenderer.installRendererOverrideForTest {
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = output,
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
            await { vm.canEnterEditorAction() }
            vm.updateParams { it.copy(exposure = 0.3f) }
            await { vm.uiState.value.previewBitmap === output && vm.uiState.value.canUndo }
            assertSame(output, vm.uiState.value.previewBitmap)
            assertEquals(0.3f, vm.uiState.value.params.exposure)
            assertTrue(vm.uiState.value.canUndo)
        } finally {
            renderer.close()
            if (!base.isRecycled) base.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    @Test
    fun rapidTicksKeepTheExactFirstGestureStateForUndo() {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val renders = AtomicInteger()
        vm.updateUiState {
            it.copy(
                sourcePath = "global-rapid-test",
                baseContentToken = "global-rapid-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        val renderer = EditorRenderer.installRendererOverrideForTest {
            renders.incrementAndGet()
            val output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            output.eraseColor(0xff336699.toInt())
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = output,
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
            await { vm.canEnterEditorAction() }
            vm.updateParams { it.copy(exposure = 0.1f) }
            vm.updateParams { it.copy(exposure = 0.2f) }
            vm.updateParams { it.copy(exposure = 0.3f) }
            await { vm.uiState.value.canUndo && renders.get() > 0 && vm.uiState.value.params.exposure == 0.3f }
            assertEquals(0.3f, vm.uiState.value.params.exposure)
            vm.undoEdit()
            await { vm.uiState.value.params.exposure == 0f }
            assertEquals(0f, vm.uiState.value.params.exposure)
        } finally {
            renderer.close()
            if (!base.isRecycled) base.recycle()
        }
    }

    private fun await(predicate: () -> Boolean) {
        repeat(6000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(predicate())
    }

    // Test 3: an update that yields identical params is a no-op: no gesture,
    // no render, no state change.
    @Test
    fun identicalParamsUpdateIsNoopWithoutRenderOrTransaction() {
        val vm = editor()
        val renderCalls = AtomicInteger(0)
        val renderer = installRenderer(renderCalls) { successOutput(red) }
        try {
            awaitReady(vm)
            vm.updateParams { it }
            assertEquals(0, renderCalls.get())
            assertFalse(vm.hasOpenParameterGesture())
            assertEquals(0f, vm.uiState.value.params.exposure)
        } finally {
            renderer.close()
        }
    }

    // Test 4: updateParams is rejected while the editor is busy; no gesture
    // and no render may be created.
    @Test
    fun updateParamsRejectedWhileBusyLeavesNoTransaction() {
        val vm = editor()
        val renderCalls = AtomicInteger(0)
        val renderer = installRenderer(renderCalls) { successOutput(red) }
        try {
            awaitReady(vm)
            vm.updateUiState { it.copy(isBusy = true) }
            vm.updateParams { it.copy(exposure = 0.5f) }
            assertEquals(0, renderCalls.get())
            assertFalse(vm.hasOpenParameterGesture())
            assertEquals(0f, vm.uiState.value.params.exposure)
        } finally {
            vm.updateUiState { it.copy(isBusy = false) }
            renderer.close()
        }
    }

    // Test 5: a failed FIRST render restores the exact start state (no
    // adoption, no history entry), and undo has nothing to navigate.
    @Test
    fun failedFirstRenderRestoresExactStartAndUndoHasNothing() {
        val vm = editor()
        val renderCalls = AtomicInteger(0)
        val renderer =
            installRenderer(renderCalls) {
                RenderResult.Failure(
                    operation = RenderOperation.NativePreview,
                    requestedRoute = NativeRenderRoute.V1,
                    attemptedRoute = NativeRenderRoute.V1,
                    kind = RenderFailureKind.NativeV1Failed,
                    message = "phase10 forced failure",
                )
            }
        try {
            awaitReady(vm)
            val startPixels = uiPixelColor(vm)
            vm.updateParams { it.copy(exposure = 0.7f) }
            assertTrue(await(vm) { renderCalls.get() >= 1 && !vm.uiState.value.isBusy })
            assertEquals(0f, vm.uiState.value.params.exposure, "params restored to start")
            assertEquals(startPixels, uiPixelColor(vm), "pixels restored to start")
            vm.undoEdit()
            assertEquals(0f, vm.uiState.value.params.exposure)
            assertEquals(startPixels, uiPixelColor(vm))
            assertEquals(0, vm.undoEntryCountForTest())
            assertEquals("되돌리기 편집 기록이 없습니다.", vm.uiState.value.message)
        } finally {
            renderer.close()
        }
    }

    // Test 6: a failed NEWER render retains the previously adopted output;
    // undo still lands on the exact gesture-start state.
    @Test
    fun failedNewerRenderRetainsAdoptedOutputAndUndoRestoresExactStart() {
        val vm = editor()
        val renderCalls = AtomicInteger(0)
        val renderer =
            installRenderer(renderCalls) { request ->
                if (request.params.exposure == 0.4f) {
                    RenderResult.Failure(
                        operation = request.operation,
                        requestedRoute = NativeRenderRoute.V1,
                        attemptedRoute = NativeRenderRoute.V1,
                        kind = RenderFailureKind.NativeV1Failed,
                        message = "phase10 forced failure",
                    )
                } else {
                    successOutput(if (request.params.exposure == 0f) green else red)
                }
            }
        try {
            awaitReady(vm)
            val startPixels = uiPixelColor(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            assertTrue(await(vm) { uiPixelColor(vm) == red && !vm.uiState.value.isBusy })
            vm.updateParams { it.copy(exposure = 0.4f) }
            assertTrue(await(vm) { renderCalls.get() >= 2 && !vm.uiState.value.isBusy })
            assertEquals(0.2f, vm.uiState.value.params.exposure, "failed newer render retains adopted params")
            assertEquals(red, uiPixelColor(vm))
            vm.undoEdit()
            assertTrue(
                await(vm) {
                    vm.uiState.value.params.exposure == 0f &&
                        uiPixelColor(vm) == startPixels &&
                        !vm.uiState.value.isBusy
                },
                "undo settles the open adopted gesture then restores the exact start state",
            )
            assertEquals(0f, vm.uiState.value.params.exposure, "undo restores exact start params")
            assertEquals(startPixels, uiPixelColor(vm), "undo restores exact start pixels")
            assertEquals(0, vm.undoEntryCountForTest())
        } finally {
            renderer.close()
        }
    }

    // Test 7: undo/redo navigate exactly through the committed gesture: one
    // undo entry, redo restores the last adopted state.
    @Test
    fun undoRedoNavigateExactlyThroughCommittedGestureState() {
        val vm = editor()
        val renderCalls = AtomicInteger(0)
        val renderer =
            installRenderer(renderCalls) { request ->
                successOutput(if (request.params.exposure == 0.4f) blue else red)
            }
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            assertTrue(await(vm) { uiPixelColor(vm) == red && !vm.uiState.value.isBusy })
            vm.updateParams { it.copy(exposure = 0.4f) }
            assertTrue(await(vm) { uiPixelColor(vm) == blue && !vm.uiState.value.isBusy })
            assertEquals(0.4f, vm.uiState.value.params.exposure)
            assertTrue(vm.hasOpenParameterGesture(), "gesture still open before settle")
            assertEquals(0, vm.undoEntryCountForTest())

            assertTrue(vm.settleForEditorAction())
            assertTrue(await(vm) { vm.undoEntryCountForTest() == 1 })

            vm.undoEdit()
            assertTrue(
                await(vm) {
                    uiPixelColor(vm) != blue && vm.uiState.value.params.exposure == 0f
                },
                "undo restores the exact gesture-start state",
            )
            assertEquals(0f, vm.uiState.value.params.exposure)
            assertEquals(0, vm.undoEntryCountForTest())

            vm.redoEdit()
            val redone =
                await(vm) {
                    if (uiPixelColor(vm) == blue && vm.uiState.value.params.exposure == 0.4f) {
                        true
                    } else {
                        vm.redoEdit()
                        false
                    }
                }
            assertTrue(redone, "redo restores the last adopted state")
            assertEquals(1, vm.undoEntryCountForTest())
        } finally {
            renderer.close()
        }
    }

    // Test 8: settlement commits the adopted state and schedules the draft
    // autosave exactly once.
    @Test
    fun settlementCommitsAdoptedStateAndSchedulesDraftAutosave() {
        val vm = editor()
        val renderCalls = AtomicInteger(0)
        val renderer = installRenderer(renderCalls) { successOutput(red) }
        try {
            awaitReady(vm)
            val epochBefore = vm.draftEpochForTest()
            vm.updateParams { it.copy(exposure = 0.3f) }
            assertTrue(await(vm) { uiPixelColor(vm) == red && !vm.uiState.value.isBusy })
            assertTrue(vm.settleForEditorAction())
            assertEquals(0.3f, vm.uiState.value.params.exposure, "adopted params committed")
            assertTrue(await(vm) { vm.undoEntryCountForTest() == 1 })
            assertTrue(await(vm) { vm.draftEpochForTest() > epochBefore })
        } finally {
            renderer.close()
        }
    }

    private fun editor(): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = "global-params-source",
                baseContentToken = "global-params-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun installRenderer(
        renderCalls: AtomicInteger,
        renderer: suspend (RenderRequest) -> RenderResult,
    ): AutoCloseable =
        EditorRenderer.installRendererOverrideForTest { request ->
            renderCalls.incrementAndGet()
            renderer(request)
        }

    private fun successOutput(color: Int): RenderResult.Success =
        RenderResult.Success(
            operation = RenderOperation.NativePreview,
            requestedRoute = NativeRenderRoute.V1,
            output = outputBitmap(color),
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )

    private fun outputBitmap(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun uiPixelColor(vm: EditorViewModel): Int {
        val preview = vm.uiState.value.previewBitmap ?: error("no preview")
        return preview.getPixel(8, 8)
    }

    private fun awaitReady(vm: EditorViewModel) {
        assertTrue(await(vm) { vm.canEnterEditorAction() })
    }

    private fun awaitInit(vm: EditorViewModel) {
        awaitEditorCompletionForTest(
            description = "startup init must complete",
            completion = vm.startupInitCompletion,
            timeoutMillis = 30_000L,
            pumpMain = {
                shadowOf(android.os.Looper.getMainLooper()).idle()
            },
            diagnostic = { startupDiagnosticForTest(vm = vm, context = context) },
        )
    }

    private fun await(vm: EditorViewModel, predicate: () -> Boolean): Boolean {
        // Main-pump cycle for arbitrary predicate settlement.
        repeat(6000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return true
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        return false
    }

    companion object {
        private const val red = 0xffff0000.toInt()
        private const val green = 0xff00ff00.toInt()
        private const val blue = 0xff0000ff.toInt()
    }
}
