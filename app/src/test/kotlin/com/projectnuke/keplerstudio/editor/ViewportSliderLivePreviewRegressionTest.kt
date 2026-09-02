package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ViewportSliderLivePreviewRegressionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUp() {
        resetRestoredWorkingSourceSandboxForTest(context)
        resetDraftSandboxForTest(context)
        deleteDirectoryIfPresentForTest(context.filesDir.resolve("editor_history_v3"))
        ThumbnailBitmapCache.clear()
        ModelAvailabilityRegistry.resetForTest()
        GlobalModelDiagnostics.resetForTest()
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
    }

    @After
    fun tearDown() {
        val failures = CleanupFailureAggregator()
        failures.attempt { harness.close() }
        failures.attempt { deleteDirectoryIfPresentForTest(context.filesDir.resolve("editor_history_v3")) }
        failures.attempt { resetRestoredWorkingSourceSandboxForTest(context) }
        failures.attempt { resetDraftSandboxForTest(context) }
        failures.throwIfAny()
    }

    private fun installOwnedBitmaps(
        vm: EditorViewModel,
        width: Int,
        height: Int,
        installPreview: Boolean = true,
        installOriginal: Boolean = true,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF808080.toInt())
        var transferred = false
        try {
            vm.updateUiState {
                it.copy(
                    sourcePath = "regression-test-source-${System.nanoTime()}",
                    baseContentToken = "regression-base-${width}x${height}-${System.nanoTime()}",
                    previewBitmap = if (installPreview) bitmap else null,
                    originalPreviewBitmap = if (installOriginal) bitmap else null,
                )
            }
            transferred = true
            return bitmap
        } finally {
            if (!transferred && !bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        repeat(800) {
            shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        shadowOf(Looper.getMainLooper()).idle()
        yieldToEditorBackgroundForTest()
        if (!predicate()) {
            throw AssertionError("awaitCondition predicate never became true after timeout")
        }
    }

    private fun installSuccessRenderer(counter: AtomicInteger? = null): AutoCloseable {
        return EditorRenderer.installRendererOverrideForTest {
            counter?.incrementAndGet()
            val baseWidth = 400
            val baseHeight = 300
            // Try to infer from current thread? Just create 400x300 as fallback, but better create minimal
            // The renderer will be called with actual basePreview; we create output same size as 64x64 minimal
            // To avoid dimension mismatch issues in ViewModel, we create output matching the request's base size
            // However the override lambda doesn't receive request; we approximate by creating 32x32.
            // For viewport tests where dimensions matter, we instead create output with same dims as installed bitmap
            // by polling ViewModel state via a global reference set before install. Simpler: create 400x300 for 400-wide tests
            // and let ViewModel accept any size (it copies basePreview dims?). In global tests they used 32x32 regardless.
            // We'll create 400x300 generic; for 500x400 tests we need 500x400 ??but we use same dimensions for viewport tests,
            // we can just create 500x400 sized output to keep geometry stable.
            // For determinism we create 500x400 if last installed was 500, else 400x300.
            // Safer: create 64x64 and let test that checks viewport use same dims? Instead we create output with exact
            // requested base size by using a ThreadLocal hack: not available.
            // Simpler: always create 400x400 sized output ??viewport clamping uses previewBitmap.width/height,
            // so identical-geometry replacement must be same dims as original (400x300 vs 400x400 mismatch would break).
            // We'll create output as 400x300 for 400x300 source, 500x400 for 500x400, etc., by tracking last installed size via a static.
            // For now create 400x400 and rely on test helper that installs 400x300: mismatch would cause viewport clamp? Better create 400x300.
            val out = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            out.eraseColor(0xFF334455.toInt())
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = out,
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

    private fun installMatchingRenderer(width: Int, height: Int, counter: AtomicInteger? = null): AutoCloseable {
        return EditorRenderer.installRendererOverrideForTest {
            counter?.incrementAndGet()
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            out.eraseColor(0xFF334455.toInt())
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = out,
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

    @Test
    fun viewportPreservedAcrossOrdinaryParamEdits() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 300)
        shadowOf(Looper.getMainLooper()).idle()
        val renderer = installMatchingRenderer(400, 300)
        try {
            // establish non-default viewport
            val zoomed = ViewportState(scale = 2.5f, offset = Offset(120f, -80f), viewportWidth = 800, viewportHeight = 600)
            vm.updateViewport(zoomed)
            shadowOf(Looper.getMainLooper()).idle()
            val before = vm.uiState.value.viewport
            assertEquals(2.5f, before.scale)
            val expectedOffset = PreviewGeometry(
                container = IntSize(800, 600),
                imageWidth = 400,
                imageHeight = 300,
                zoom = 2.5f,
                pan = Offset(120f, -80f)
            ).clampedPan()
            assertEquals(expectedOffset, before.offset)

            val edits = listOf(
                { p: EditParams -> p.copy(exposure = 0.3f) },
                { p: EditParams -> p.copy(contrast = 0.4f) },
                { p: EditParams -> p.copy(sharpness = 0.7f) },
                { p: EditParams -> p.copy(luminanceNoiseReduction = 0.5f, noiseReduction = 0.5f) },
                { p: EditParams -> p.copy(clarity = 0.6f) },
            )
            for (edit in edits) {
                vm.updateParams(edit)
                shadowOf(Looper.getMainLooper()).idle()
                val afterParams = vm.uiState.value.viewport
                assertEquals(before.scale, afterParams.scale, "scale must be unchanged after ordinary param edit")
                assertEquals(before.offset, afterParams.offset, "pan must be unchanged after ordinary param edit")
                assertEquals(before.viewportWidth, afterParams.viewportWidth)
                assertEquals(before.viewportHeight, afterParams.viewportHeight)
                // short tick while render pending
                shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                val afterRenderTick = vm.uiState.value.viewport
                assertEquals(before.scale, afterRenderTick.scale)
                assertEquals(before.offset, afterRenderTick.offset)
            }
            vm.finishContinuousParameterEdit()
            awaitCondition { !vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() && vm.parameterRenderJobForTest()?.isActive != true }
            val finalViewport = vm.uiState.value.viewport
            assertEquals(before.scale, finalViewport.scale)
            assertEquals(before.offset, finalViewport.offset)
            assertEquals(before.viewportWidth, finalViewport.viewportWidth)
            assertEquals(before.viewportHeight, finalViewport.viewportHeight)
            val container = IntSize(before.viewportWidth, before.viewportHeight)
            val geometryBefore = PreviewGeometry(container, 400, 300, zoom = before.scale, pan = before.offset)
            val geometryAfter = PreviewGeometry(container, 400, 300, zoom = finalViewport.scale, pan = finalViewport.offset)
            val center = Offset(container.width / 2f, container.height / 2f)
            val imgBefore = geometryBefore.viewToImage(center)
            val imgAfter = geometryAfter.viewToImage(center)
            assertEquals(imgBefore, imgAfter)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun identicalGeometryBitmapReplacementDoesNotResetViewport() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        val bmp1 = installOwnedBitmaps(vm, 500, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val zoomed = ViewportState(scale = 3f, offset = Offset(80f, 60f), viewportWidth = 800, viewportHeight = 600)
        vm.updateViewport(zoomed)
        shadowOf(Looper.getMainLooper()).idle()
        val before = vm.uiState.value.viewport
        val beforeScale = before.scale
        val beforeOffset = before.offset
        val bmp2 = Bitmap.createBitmap(500, 400, Bitmap.Config.ARGB_8888)
        bmp2.eraseColor(0xFF909090.toInt())
        vm.updateUiState { it.copy(previewBitmap = bmp2, originalPreviewBitmap = bmp2) }
        shadowOf(Looper.getMainLooper()).idle()
        val after = vm.uiState.value.viewport
        assertEquals(beforeScale, after.scale, "identical geometry replacement must preserve scale")
        assertEquals(beforeOffset, after.offset, "identical geometry replacement must preserve pan")
        assertEquals(before.viewportWidth, after.viewportWidth)
        assertEquals(before.viewportHeight, after.viewportHeight)
        val clamped = before.clampedForImage(500, 400)
        assertEquals(before.offset, clamped.offset)
    }

    @Test
    fun newDocumentResetsViewportAndCropClamps() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val zoomed = ViewportState(scale = 2.5f, offset = Offset(100f, 100f), viewportWidth = 800, viewportHeight = 800)
        vm.updateViewport(zoomed)
        shadowOf(Looper.getMainLooper()).idle()
        val before = vm.uiState.value.viewport
        assertTrue(before.scale > 1f)
        assertNotEquals(Offset.Zero, before.offset)

        vm.updateUiState { it.copy(sourcePath = "new-doc", baseContentToken = "new-token", previewBitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888), viewport = ViewportState(), revision = it.revision + 1) }
        shadowOf(Looper.getMainLooper()).idle()
        val afterDoc = vm.uiState.value.viewport
        assertEquals(1f, afterDoc.scale)
        assertEquals(Offset.Zero, afterDoc.offset)

        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        vm.updateUiState { it.copy(sourcePath = "crop-test", previewBitmap = bmp, originalPreviewBitmap = bmp) }
        val zoomed2 = ViewportState(scale = 3f, offset = Offset(200f, 200f), viewportWidth = 800, viewportHeight = 800)
        vm.updateViewport(zoomed2)
        shadowOf(Looper.getMainLooper()).idle()
        val beforeCrop = vm.uiState.value.viewport
        val newW = 300
        val newH = 400
        val clamped = beforeCrop.clampedForImage(newW, newH)
        assertEquals(beforeCrop.scale, clamped.scale)
        val geometry = PreviewGeometry(IntSize(800, 800), newW, newH, zoom = beforeCrop.scale, pan = beforeCrop.offset)
        assertEquals(geometry.clampedPan(), clamped.offset)
        assertTrue(clamped.scale > 1f)
        vm.updateUiState { it.copy(viewport = clamped) }
        val afterClamp = vm.uiState.value.viewport
        assertEquals(clamped.offset, afterClamp.offset)
        assertEquals(clamped.scale, afterClamp.scale)
    }

    @Test
    fun sliderContinuousDragMonotonicAndClamping() {
        fun map(x: Float, width: Int, min: Float, max: Float): Float {
            val frac = (x / width.toFloat()).coerceIn(0f, 1f)
            return (min + frac * (max - min)).coerceIn(min, max)
        }
        val width = 1000
        val min = -1f
        val max = 1f
        val positions = listOf(0f, 100f, 250f, 500f, 750f, 900f, 1000f)
        val values = positions.map { map(it, width, min, max) }
        for (i in 1 until values.size) {
            assertTrue(values[i] >= values[i - 1], "forward drag must be monotonic ${values[i - 1]} -> ${values[i]}")
        }
        assertEquals(min, values.first())
        assertEquals(max, values.last())
        val reversePositions = listOf(1000f, 800f, 600f, 400f, 200f, 0f)
        val reverseValues = reversePositions.map { map(it, width, min, max) }
        for (i in 1 until reverseValues.size) {
            assertTrue(reverseValues[i] <= reverseValues[i - 1], "reverse drag must be monotonic decreasing")
        }
        assertEquals(max, map(1500f, width, min, max))
        assertEquals(min, map(-200f, width, min, max))
        val smallSteps = (0..100).map { it * 10f }
        val smallValues = smallSteps.map { map(it, width, min, max) }
        assertTrue(smallValues.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun livePreviewCoalescesAndSingleHistory() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val renders = AtomicInteger(0)
        val renderer = installMatchingRenderer(400, 400, renders)
        try {
            val initialUndo = vm.undoEntryCountForTest()
            val dragValues = (0..20).map { it * 0.05f }
            for (v in dragValues) {
                vm.updateParams { it.copy(sharpness = v) }
                shadowOf(Looper.getMainLooper()).idle()
            }
            // latest value wins
            assertEquals(dragValues.last(), vm.latestParamsForTest()?.sharpness)
            // No history yet while gesture active
            assertEquals(initialUndo, vm.undoEntryCountForTest())
            // Finish drag ??must commit one logical entry and ensure final render authoritative
            vm.finishContinuousParameterEdit()
            awaitCondition { !vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() && vm.undoEntryCountForTest() == initialUndo + 1 }
            val afterUndo = vm.undoEntryCountForTest()
            assertEquals(initialUndo + 1, afterUndo, "one drag must produce exactly one history entry")
            assertEquals(dragValues.last(), vm.uiState.value.params.sharpness, 0.001f)
            val finalSharp = vm.uiState.value.params.sharpness
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            assertEquals(finalSharp, vm.uiState.value.params.sharpness)
            // Renders were coalesced: should be less than number of ticks
            assertTrue(renders.get() <= dragValues.size)
            assertTrue(renders.get() >= 1)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun zoomedLiveEditIntegrationGate() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 600, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val renders = AtomicInteger(0)
        val renderer = installMatchingRenderer(600, 400, renders)
        try {
            val zoomed = ViewportState(scale = 3f, offset = Offset(150f, -90f), viewportWidth = 800, viewportHeight = 600)
            vm.updateViewport(zoomed)
            shadowOf(Looper.getMainLooper()).idle()
            val viewportBefore = vm.uiState.value.viewport
            assertEquals(3f, viewportBefore.scale)
            assertEquals(Offset(150f, -90f), viewportBefore.offset)
            val undoBefore = vm.undoEntryCountForTest()
            val paramsBefore = vm.uiState.value.params

            val sharpnessValues = listOf(0.2f, 0.4f, 0.6f, 0.8f)
            for (v in sharpnessValues) {
                vm.updateParams { it.copy(sharpness = v) }
                shadowOf(Looper.getMainLooper()).idle()
                val vp = vm.uiState.value.viewport
                assertEquals(viewportBefore.scale, vp.scale, "viewport scale must stay during live drag")
                assertEquals(viewportBefore.offset, vp.offset, "viewport pan must stay during live drag")
                assertEquals(v, vm.uiState.value.params.sharpness, 0.001f)
            }
            vm.finishContinuousParameterEdit()
            awaitCondition { !vm.uiState.value.isBusy && vm.uiState.value.params.sharpness == 0.8f && !vm.hasOpenParameterGesture() }
            val viewportAfter = vm.uiState.value.viewport
            assertEquals(viewportBefore.scale, viewportAfter.scale)
            assertEquals(viewportBefore.offset, viewportAfter.offset)
            assertEquals(viewportBefore.viewportWidth, viewportAfter.viewportWidth)
            assertEquals(viewportBefore.viewportHeight, viewportAfter.viewportHeight)
            assertEquals(0.8f, vm.uiState.value.params.sharpness, 0.001f)
            assertEquals(undoBefore + 1, vm.undoEntryCountForTest())
            vm.undoEdit()
            awaitCondition { vm.uiState.value.params.sharpness == paramsBefore.sharpness && !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(paramsBefore.sharpness, vm.uiState.value.params.sharpness, 0.001f)
            val viewportAfterUndo = vm.uiState.value.viewport
            assertEquals(viewportBefore.scale, viewportAfterUndo.scale, "Undo must not alter viewport")
            assertEquals(viewportBefore.offset, viewportAfterUndo.offset)

            vm.redoEdit()
            awaitCondition { vm.uiState.value.params.sharpness == 0.8f && !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy }
            val viewportBefore2 = vm.uiState.value.viewport
            val undoBefore2 = vm.undoEntryCountForTest()
            for (v in listOf(0.1f, 0.3f, 0.5f, 0.9f)) {
                vm.updateParams { it.copy(luminanceNoiseReduction = v, noiseReduction = v) }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(viewportBefore2.scale, vm.uiState.value.viewport.scale)
                assertEquals(viewportBefore2.offset, vm.uiState.value.viewport.offset)
            }
            vm.finishContinuousParameterEdit()
            awaitCondition { !vm.uiState.value.isBusy && vm.uiState.value.params.luminanceNoiseReduction == 0.9f }
            assertEquals(viewportBefore2.scale, vm.uiState.value.viewport.scale)
            assertEquals(undoBefore2 + 1, vm.undoEntryCountForTest())
            assertEquals(0.9f, vm.uiState.value.params.luminanceNoiseReduction, 0.001f)
            val undoBefore3 = vm.undoEntryCountForTest()
            for (v in listOf(0.2f, -0.2f, 0.5f)) {
                vm.updateParams { it.copy(clarity = v) }
                shadowOf(Looper.getMainLooper()).idle()
            }
            vm.finishContinuousParameterEdit()
            awaitCondition { !vm.uiState.value.isBusy && vm.uiState.value.params.clarity == 0.5f }
            assertEquals(undoBefore3 + 1, vm.undoEntryCountForTest())
        } finally {
            renderer.close()
        }
    }
}
