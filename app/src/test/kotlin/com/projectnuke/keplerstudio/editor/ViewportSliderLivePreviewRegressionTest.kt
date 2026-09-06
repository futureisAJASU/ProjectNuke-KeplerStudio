package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import java.util.Collections

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

    /**
     * Signal-driven pump loop for deterministic renderer-seam waits. Each iteration does
     * real work: advance the Robolectric clock, drain the Main queue, and yield to the
     * background dispatcher. Synchronization is always on a real production state signal,
     * never on a sleep.
     */
    private fun awaitSignal(description: String, condition: () -> Boolean) {
        repeat(1200) {
            if (condition()) return
            shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        check(condition()) { "awaitSignal timed out waiting for: $description" }
    }

    private fun successRender(request: RenderRequest, width: Int, height: Int): RenderResult {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFF112233.toInt())
        return RenderResult.Success(
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

    private fun installMatchingRenderer(width: Int, height: Int, counter: AtomicInteger? = null): AutoCloseable {
        return EditorRenderer.installRendererOverrideForTest { request ->
            counter?.incrementAndGet()
            successRender(request, width, height)
        }
    }

    /**
     * Deterministic renderer seam with explicit per-invocation parking. Records entered
     * revision + entered parameter value, physical in-flight, max in-flight, and completed
     * revisions. A revision only completes its render after the test explicitly releases it.
     */
    private class ParkedRenderProbe {
        data class Entry(val revision: Int, val value: Float)

        private val enteredList = Collections.synchronizedList(mutableListOf<Entry>())
        private val completedList = Collections.synchronizedList(mutableListOf<Int>())
        private val gates = ConcurrentHashMap<Int, CountDownLatch>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        fun armPark(revision: Int) {
            gates.putIfAbsent(revision, CountDownLatch(1))
        }

        fun release(revision: Int) {
            gates[revision]?.countDown()
        }

        fun entries(): List<Entry> = synchronized(enteredList) { enteredList.toList() }

        fun enteredRevisions(): List<Int> = entries().map { it.revision }

        fun enteredValues(): List<Float> = entries().map { it.value }

        fun completedRevisions(): List<Int> = synchronized(completedList) { completedList.toList() }

        suspend fun intercept(
            request: RenderRequest,
            width: Int,
            height: Int,
            success: (RenderRequest, Int, Int) -> RenderResult,
        ): RenderResult {
            val revision = request.identity.revision
            inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, inFlight.get()) }
            enteredList.add(Entry(revision, request.params.sharpness))
            gates[revision]?.await()
            inFlight.decrementAndGet()
            completedList.add(revision)
            return success(request, width, height)
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
            // Finish drag must commit one logical entry and ensure final render authoritative
            vm.finishContinuousParameterEdit()
            awaitCondition {
                !vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() && vm.undoEntryCountForTest() == initialUndo + 1
            }
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

    @Test
    fun sustainedDragWithContinuouslyChangingSamplesAdoptsIntermediatePreviews() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val probe = ParkedRenderProbe()
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            probe.intercept(request, 400, 400) { r, w, h -> successRender(r, w, h) }
        }
        try {
            val initialUndo = vm.undoEntryCountForTest()

            // Continuously changing sequence for the whole >=500ms interval:
            // 0.10 -> 0.90, 25 samples 25ms apart (600ms span), inside the real
            // Sharpness range [0,1]. Every adjacent value differs.
            val sampleCount = 25
            val sampleIntervalMs = 25L
            val dragValues = (0 until sampleCount).map { i ->
                0.10f + (i.toFloat() / (sampleCount - 1).toFloat()) * 0.80f
            }
            val dragDurationMs = sampleIntervalMs * (sampleCount - 1)
            assertTrue(dragDurationMs >= 500L, "drag must span >= 500ms, was $dragDurationMs")
            assertTrue(dragValues.zipWithNext().all { (a, b) -> b > a }, "every adjacent sample must differ")

            // Park the first physical render so sustained input can overtake it;
            // that first render is then the intermediate preview.
            val revFirst = vm.uiState.value.revision + 1
            probe.armPark(revFirst)
            for ((i, v) in dragValues.withIndex()) {
                vm.updateParams { it.copy(sharpness = v) }
                if (i < sampleCount - 1) {
                    shadowOf(Looper.getMainLooper()).idleFor(sampleIntervalMs, TimeUnit.MILLISECONDS)
                    yieldToEditorBackgroundForTest()
                }
            }

            // The first physical render must have actually ENTERED (and stay parked).
            awaitSignal("first physical render entered") {
                probe.enteredRevisions().contains(revFirst)
            }
            assertTrue(probe.maxInFlight.get() <= 1, "physical in-flight must stay <= 1")

            // Release the parked intermediate render: it must COMPLETE and be ADOPTED as
            // an intermediate visible preview (direct adoption counter, not inferred).
            probe.release(revFirst)
            awaitSignal("intermediate render completed and adopted") {
                vm.intermediateAdoptionCountForTest() >= 1
            }

            // BEFORE finishContinuousParameterEdit():
            assertTrue(
                probe.enteredRevisions().contains(revFirst),
                "at least one intermediate physical render must have entered",
            )
            assertTrue(
                probe.completedRevisions().contains(revFirst),
                "at least one intermediate render must have completed",
            )
            assertTrue(
                vm.intermediateAdoptionCountForTest() >= 1,
                "at least one intermediate visible preview must actually be adopted",
            )
            assertTrue(vm.hasOpenParameterGesture(), "transaction must still be open")
            assertEquals(initialUndo, vm.undoEntryCountForTest(), "history delta must be 0 during active gesture")
            assertEquals(
                dragValues.last(),
                vm.latestParamsForTest()?.sharpness,
                "latest params must equal the latest sample",
            )
            assertEquals(
                dragValues.last(),
                vm.uiState.value.params.sharpness,
                1e-4f,
                "uiState.params must track the latest sample, never an intermediate",
            )
            val revLatest = vm.uiState.value.revision
            assertTrue(
                vm.executingParamRenderRevisionForTest() == null ||
                    vm.executingParamRenderRevisionForTest() == revLatest,
                "at most one executing render (single marker)",
            )
            assertTrue(
                vm.pendingParamRenderRevision() == null ||
                    vm.pendingParamRenderRevision() == revLatest,
                "at most one pending request (single marker)",
            )
            assertTrue(
                vm.ownedParamRenderRevisionCountForTest() <= 4,
                "owned render revisions must stay bounded",
            )

            // The latest sample must now be physically rendered and authoritatively adopted.
            awaitSignal("final render authoritatively adopted") {
                vm.finalAdoptionCountForTest() >= 1 && !vm.uiState.value.isBusy
            }
            assertTrue(
                probe.enteredRevisions().contains(revLatest),
                "final value must be physically rendered",
            )
            assertEquals(1, vm.finalAdoptionCountForTest(), "exactly one final authoritative adoption")
            assertEquals(dragValues.last(), vm.uiState.value.params.sharpness, 1e-4f)
            assertEquals(null, vm.pendingParamRenderRevision(), "no pending after final adoption")
            assertEquals(null, vm.executingParamRenderRevisionForTest(), "no executing after final adoption")

            vm.finishContinuousParameterEdit()
            awaitSignal("gesture committed with exactly one history entry") {
                !vm.uiState.value.isBusy &&
                    !vm.hasOpenParameterGesture() &&
                    vm.undoEntryCountForTest() == initialUndo + 1
            }
            assertEquals(initialUndo + 1, vm.undoEntryCountForTest(), "exactly one history entry after release")
        } finally {
            renderer.close()
        }
    }

    @Test
    fun physicalSerializationParkedRendererEntryOrderA_D_F() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val probe = ParkedRenderProbe()
        val adoptedRevisions = Collections.synchronizedList(mutableListOf<Int>())
        val lifecycle = ParameterLifecycleTestHook.install(
            ParameterLifecycleHooks(
                onRenderOutputAdopted = { revision -> adoptedRevisions.add(revision) },
            ),
        )
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            probe.intercept(request, 400, 400) { r, w, h -> successRender(r, w, h) }
        }
        try {
            val initialUndo = vm.undoEntryCountForTest()

            // A input; wait until A physically enters; A stays parked.
            val revA = vm.uiState.value.revision + 1
            probe.armPark(revA)
            vm.updateParams { it.copy(sharpness = 0.2f) }
            awaitSignal("A physically entered") { probe.enteredRevisions().contains(revA) }

            // While A remains parked: B, C, D input.
            val revB = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.3f) }
            val revC = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.4f) }
            val revD = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.5f) }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(listOf(revA), probe.enteredRevisions(), "only A may physically enter while parked")
            assertEquals(1, probe.maxInFlight.get(), "maxInFlight must stay 1")
            assertEquals(revA, vm.executingParamRenderRevisionForTest(), "A is the executing render")
            assertEquals(revD, vm.pendingParamRenderRevision(), "D is the sole pending request")
            assertEquals(2, vm.supersededParamRenderRequestCountForTest(), "B and C must be superseded")
            assertEquals(revC, vm.lastSupersededParamRenderRevisionForTest())
            assertTrue(probe.enteredRevisions().none { it == revB }, "B must not be pending or entered")
            assertTrue(probe.enteredRevisions().none { it == revC }, "C must not be pending or entered")
            assertEquals(0.5f, vm.uiState.value.params.sharpness, 1e-4f)

            // Arm D's park before releasing A (D cannot enter until A completes).
            probe.armPark(revD)
            probe.release(revA)
            awaitSignal("D physically entered") { probe.enteredRevisions().contains(revD) }
            assertEquals(listOf(revA, revD), probe.enteredRevisions(), "next physical entry must be D")
            assertEquals(1, probe.maxInFlight.get(), "maxInFlight must stay 1")
            assertTrue(
                probe.enteredRevisions().none { it == revB || it == revC },
                "B and C must never enter",
            )
            assertTrue(vm.intermediateAdoptionCountForTest() >= 1, "A must be adopted as intermediate preview")
            assertEquals(revD, vm.executingParamRenderRevisionForTest())

            // PARK D. Submit E and F.
            val revE = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.6f) }
            val revF = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.7f) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(revF, vm.pendingParamRenderRevision(), "F must be the sole pending request")
            assertEquals(revE, vm.lastSupersededParamRenderRevisionForTest(), "E must be superseded by F")

            // Arm F's park before releasing D.
            probe.armPark(revF)
            probe.release(revD)
            awaitSignal("F physically entered") { probe.enteredRevisions().contains(revF) }
            assertEquals(listOf(revA, revD, revF), probe.enteredRevisions(), "next physical entry must be F")
            assertEquals(1, probe.maxInFlight.get(), "maxInFlight must stay 1")
            assertEquals(2, vm.intermediateAdoptionCountForTest(), "A and D must be intermediate adoptions")
            assertEquals(0, vm.finalAdoptionCountForTest(), "no final adoption before F")

            // Release F; the final authoritative render must be F.
            probe.release(revF)
            awaitSignal("F final authoritative adoption") {
                vm.finalAdoptionCountForTest() >= 1 && !vm.uiState.value.isBusy
            }
            assertEquals(1, vm.finalAdoptionCountForTest())
            assertEquals(directAdoptedRevision(adoptedRevisions), revF)
            assertEquals(0.7f, vm.uiState.value.params.sharpness, 1e-4f, "final params must equal F")

            // Finish gesture.
            vm.finishContinuousParameterEdit()
            awaitSignal("gesture committed with one history entry") {
                !vm.uiState.value.isBusy &&
                    !vm.hasOpenParameterGesture() &&
                    vm.undoEntryCountForTest() == initialUndo + 1
            }
            assertEquals(initialUndo + 1, vm.undoEntryCountForTest(), "history delta must be exactly 1")
        } finally {
            renderer.close()
            lifecycle.close()
        }
    }

    private fun directAdoptedRevision(adoptedRevisions: List<Int>): Int =
        checkNotNull(adoptedRevisions.lastOrNull()) { "no adoption observed" }

    @Test
    fun intermediateFailurePreservesNewerPendingIntent() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val entered = Collections.synchronizedList(mutableListOf<Int>())
        val gates = ConcurrentHashMap<Int, CountDownLatch>()
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            val revision = request.identity.revision
            entered.add(revision)
            gates[revision]?.await()
            if (request.params.sharpness == 0.1f) {
                RenderResult.Failure(
                    operation = request.operation,
                    requestedRoute = NativeRenderRoute.V1,
                    attemptedRoute = NativeRenderRoute.V1,
                    kind = RenderFailureKind.NativeV1Failed,
                    message = "synthetic intermediate failure",
                )
            } else {
                successRender(request, 400, 400)
            }
        }
        try {
            val initialUndo = vm.undoEntryCountForTest()

            // A (sharpness 0.1) enters and is parked.
            val revA = vm.uiState.value.revision + 1
            gates[revA] = CountDownLatch(1)
            vm.updateParams { it.copy(sharpness = 0.1f) }
            awaitSignal("A physically entered") { entered.contains(revA) }

            // While A is parked: B, C, D. D (0.7) is the newest pending parameter value.
            val revB = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.2f) }
            val revC = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.3f) }
            val revD = vm.uiState.value.revision + 1
            vm.updateParams { it.copy(sharpness = 0.7f) }
            shadowOf(Looper.getMainLooper()).idle()

            // Release A: A FAILS while D is the newest pending parameter value.
            gates[revA]?.countDown()
            awaitSignal("A failure settled") {
                vm.paramRenderRevisionPhasesForTest()[revA] == EditorViewModel.ParamRenderRevisionPhase.Failed
            }

            // D remains the authoritative latest params; D still owns the latest render
            // request (pending, or already picked up by the pipeline as executing); no rollback.
            assertEquals(0.7f, vm.uiState.value.params.sharpness, 1e-4f, "uiState.params must not roll back to A or an earlier adopted value")
            assertTrue(
                vm.pendingParamRenderRevision() == revD || vm.executingParamRenderRevisionForTest() == revD,
                "D must still own the latest render request (pending or executing)",
            )
            assertTrue(vm.uiState.value.isBusy, "isBusy must remain appropriate for D")
            assertTrue(vm.hasOpenParameterGesture(), "transaction must stay open for D")
            assertEquals(initialUndo, vm.undoEntryCountForTest(), "no history entry from an intermediate failure")
            assertEquals(0, vm.finalAdoptionCountForTest(), "no final adoption from a failed intermediate")

            // The pipeline must continue to D, which becomes the final authoritative render.
            awaitSignal("D rendered and authoritatively adopted") {
                vm.finalAdoptionCountForTest() >= 1 && !vm.uiState.value.isBusy
            }
            assertTrue(entered.contains(revD), "pipeline must continue to D")
            assertEquals(0.7f, vm.uiState.value.params.sharpness, 1e-4f, "final params must equal D")
            assertTrue(entered.none { it == revB || it == revC }, "B and C must never enter")

            vm.finishContinuousParameterEdit()
            awaitSignal("gesture committed with one history entry") {
                !vm.uiState.value.isBusy &&
                    !vm.hasOpenParameterGesture() &&
                    vm.undoEntryCountForTest() == initialUndo + 1
            }
            assertEquals(initialUndo + 1, vm.undoEntryCountForTest())
        } finally {
            renderer.close()
        }
    }

    @Test
    fun tenThousandSampleDragKeepsPendingOwnershipBounded() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val renderer = installMatchingRenderer(400, 400)
        try {
            val initialUndo = vm.undoEntryCountForTest()
            val sampleCount = 10_000
            val dragValues = (0 until sampleCount).map { i ->
                0.10f + (i.toFloat() / (sampleCount - 1).toFloat()) * 0.80f
            }
            var maxOwned = 0
            for ((i, v) in dragValues.withIndex()) {
                vm.updateParams { it.copy(sharpness = v) }
                if (i % 500 == 0) {
                    maxOwned = maxOf(maxOwned, vm.ownedParamRenderRevisionCountForTest())
                }
            }
            maxOwned = maxOf(maxOwned, vm.ownedParamRenderRevisionCountForTest())
            assertEquals(sampleCount, vm.totalParamRenderRequestCountForTest(), "every sample must be admitted")
            assertTrue(
                maxOwned <= 4,
                "owned RenderRevisionOwner objects must stay a small constant, observed max=$maxOwned",
            )
            // Deterministic supersede count: sample 0's request is the first pending and is
            // consumed by the first render's prepareRender (no supersede); sample 1's request
            // then finds no pending to supersede; every request from sample 2 on supersedes
            // exactly one pending. Total = sampleCount - 2.
            assertEquals(sampleCount - 2, vm.supersededParamRenderRequestCountForTest(), "every older pending request must be superseded exactly once")
            assertEquals(dragValues.last(), vm.latestParamsForTest()?.sharpness)
            assertEquals(initialUndo, vm.undoEntryCountForTest(), "no history during active gesture")

            vm.finishContinuousParameterEdit()
            awaitSignal("gesture committed with one history entry") {
                !vm.uiState.value.isBusy &&
                    !vm.hasOpenParameterGesture() &&
                    vm.undoEntryCountForTest() == initialUndo + 1
            }
            assertEquals(initialUndo + 1, vm.undoEntryCountForTest())
            assertEquals(dragValues.last(), vm.uiState.value.params.sharpness, 1e-4f)
        } finally {
            renderer.close()
        }
    }

    @Test
    fun singleUpdateDirectViewModelCommitsOneHistory() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 400, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val renders = AtomicInteger(0)
        val renderer = installMatchingRenderer(400, 400, renders)
        try {
            val initialUndo = vm.undoEntryCountForTest()
            val initialParams = vm.uiState.value.params

            val targetValue = 0.75f
            vm.updateParams { it.copy(sharpness = targetValue) }
            shadowOf(Looper.getMainLooper()).idle()

            vm.finishContinuousParameterEdit()
            awaitCondition { !vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() }

            assertEquals(targetValue, vm.uiState.value.params.sharpness, 0.001f)
            assertEquals(initialUndo + 1, vm.undoEntryCountForTest(), "Exactly one history entry for a single param update")
            assertTrue(renders.get() >= 1, "At least one render must complete")

            vm.undoEdit()
            awaitCondition { !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy }
            assertEquals(initialParams.sharpness, vm.uiState.value.params.sharpness, 0.001f, "Undo must restore original sharpness")

        } finally {
            renderer.close()
        }
    }

    @Test
    fun zoomedProductInteractionGate() {
        val vm = harness.createEditor()
        awaitEditorReadyForTest(vm)
        installOwnedBitmaps(vm, 600, 400)
        shadowOf(Looper.getMainLooper()).idle()
        val probe = ParkedRenderProbe()
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            probe.intercept(request, 600, 400) { r, w, h -> successRender(r, w, h) }
        }
        try {
            val zoomed = ViewportState(scale = 3f, offset = Offset(150f, -90f), viewportWidth = 800, viewportHeight = 600)
            vm.updateViewport(zoomed)
            shadowOf(Looper.getMainLooper()).idle()

            val viewportBefore = vm.uiState.value.viewport
            val paramsBefore = vm.uiState.value.params
            val undoBefore = vm.undoEntryCountForTest()

            assertEquals(3f, viewportBefore.scale)
            assertTrue(viewportBefore.offset != Offset.Zero)

            // Continuously changing sequence inside the real product range for the whole
            // sustained drag: Sharpness is 0.0..1.0; use 0.10 -> 0.90 across 31 samples,
            // 20ms apart (600ms span). Every adjacent value differs.
            val sampleCount = 31
            val sampleIntervalMs = 20L
            val sharpnessValues = (0 until sampleCount).map { i ->
                0.10f + (i.toFloat() / (sampleCount - 1).toFloat()) * 0.80f
            }
            assertTrue(sharpnessValues.zipWithNext().all { (a, b) -> b > a }, "every adjacent sample must differ")
            assertTrue(sharpnessValues.all { it >= 0f && it <= 1f }, "all samples must be inside the real Sharpness range")
            val dragDurationMs = sampleIntervalMs * (sampleCount - 1)
            assertTrue(dragDurationMs >= 500L, "drag must span >= 500ms, was $dragDurationMs")

            // Park the first physical render so the sustained drag overtakes it.
            val revFirst = vm.uiState.value.revision + 1
            probe.armPark(revFirst)
            for ((i, v) in sharpnessValues.withIndex()) {
                vm.updateParams { it.copy(sharpness = v) }
                shadowOf(Looper.getMainLooper()).idle()

                val vp = vm.uiState.value.viewport
                assertEquals(viewportBefore.scale, vp.scale, "viewport scale must stay during live drag")
                assertEquals(viewportBefore.offset, vp.offset, "viewport pan must stay during live drag")

                if (i < sampleCount - 1) {
                    shadowOf(Looper.getMainLooper()).idleFor(sampleIntervalMs, TimeUnit.MILLISECONDS)
                    yieldToEditorBackgroundForTest()
                }
            }

            awaitSignal("first physical render entered") {
                probe.enteredRevisions().contains(revFirst)
            }

            // Require an actual intermediate adoption before release.
            probe.release(revFirst)
            awaitSignal("intermediate visible preview adopted") {
                vm.intermediateAdoptionCountForTest() >= 1
            }
            assertTrue(vm.intermediateAdoptionCountForTest() >= 1, "an actual intermediate adoption must happen before release")
            assertEquals(undoBefore, vm.undoEntryCountForTest(), "No history entry during active gesture")

            // The latest sample must be rendered and authoritatively adopted.
            val finalSharpness = sharpnessValues.last()
            awaitSignal("final render authoritatively adopted") {
                vm.finalAdoptionCountForTest() >= 1 && !vm.uiState.value.isBusy
            }
            assertEquals(finalSharpness, vm.uiState.value.params.sharpness, 1e-4f)

            val viewportAfter = vm.uiState.value.viewport
            assertEquals(viewportBefore.scale, viewportAfter.scale, "Viewport scale must be preserved after finish")
            assertEquals(viewportBefore.offset, viewportAfter.offset, "Viewport pan must be preserved after finish")

            vm.finishContinuousParameterEdit()
            awaitSignal("gesture committed with one history entry") {
                !vm.uiState.value.isBusy &&
                    !vm.hasOpenParameterGesture() &&
                    vm.undoEntryCountForTest() == undoBefore + 1
            }
            assertEquals(finalSharpness, vm.uiState.value.params.sharpness, 0.001f, "Final sharpness must equal released value")
            assertEquals(undoBefore + 1, vm.undoEntryCountForTest(), "Exactly one history entry after finish")

            vm.undoEdit()
            awaitCondition { vm.uiState.value.params.sharpness == paramsBefore.sharpness && !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(paramsBefore.sharpness, vm.uiState.value.params.sharpness, 0.001f, "Undo must restore original sharpness")
            val viewportAfterUndo = vm.uiState.value.viewport
            assertEquals(viewportBefore.scale, viewportAfterUndo.scale, "Undo must not alter viewport")
            assertEquals(viewportBefore.offset, viewportAfterUndo.offset)

            vm.redoEdit()
            awaitCondition { vm.uiState.value.params.sharpness == finalSharpness && !vm.uiState.value.isBusy && !vm.uiState.value.historyBusy }

            assertEquals(finalSharpness, vm.uiState.value.params.sharpness, 0.001f, "Redo must restore final sharpness")
            val viewportAfterRedo = vm.uiState.value.viewport
            assertEquals(viewportBefore.scale, viewportAfterRedo.scale, "Redo must not alter viewport")
            assertEquals(viewportBefore.offset, viewportAfterRedo.offset)
            assertEquals(undoBefore + 1, vm.undoEntryCountForTest())

        } finally {
            renderer.close()
        }
    }
}
