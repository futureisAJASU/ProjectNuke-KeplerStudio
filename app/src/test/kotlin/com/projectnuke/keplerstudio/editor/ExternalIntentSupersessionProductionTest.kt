package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.installNativeFlareGuardInPlaceForTest
import com.projectnuke.keplerstudio.ui.applyActiveSelectionLocalEdit
import com.projectnuke.keplerstudio.ui.applyActiveSelectionLocalEditNativeBaked
import com.projectnuke.keplerstudio.ui.applyCropTransform
import com.projectnuke.keplerstudio.ui.applyFlareOriginalMvp
import com.projectnuke.keplerstudio.ui.applyMaskAwareRemaster
import com.projectnuke.keplerstudio.ui.applySunFlareOriginalMvp
import com.projectnuke.keplerstudio.ui.updateCropRect
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Phase 1 production coverage: every parameter-supersedable external action
 * settles the open parameter transaction synchronously BEFORE its busy check,
 * so the first user invocation both commits the adopted revision and executes
 * the action — no second tap required.
 *
 * Each adopted test holds ONE open transaction with an adopted revision A
 * (exposure 0.3) plus a suspended newer render B (exposure 0.5), then invokes
 * the action once and proves B is cancelled, A is committed exactly once with
 * matching params and pixels, and the action actually executes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExternalIntentSupersessionProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
        ExperimentalLabController.resetForTest()
    }

    // Crop: adopted A (0.3) + suspended B (0.5). The first crop invocation must
    // commit A exactly once and then actually crop — the crop seam runs for
    // original, preview and mask, one undo entry records the committed A, the
    // crop records a second, and Undo restores A's exact params and pixels.
    @Test
    fun cropExecutesOnFirstInvocationAfterSettlingAdoptedAndPending() = runBlocking {
        val sourceFile = draftSourceFile("supersede-crop.png")
        val vm = editor(sourceFile.absolutePath, withMask = true)
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val transformCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        val commitBegan = mutableListOf<Long>()
        val committed = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        var rollbacks = 0
        val renderer = paramRenderer(pendingGate, renderCalls)
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        val cropTransform = installCropTransformForTest { source, crop ->
            val dims = cropTransformedDimensions(source.width, source.height, crop)
            val color =
                when (transformCalls.incrementAndGet()) {
                    1 -> 0xff0000ff.toInt()
                    2 -> 0xffff0000.toInt()
                    else -> 0xff00ff00.toInt()
                }
            outputBitmap(dims.first, dims.second, color)
        }
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted.isNotEmpty() && vm.hasOpenParameterGesture() }
            assertEquals(0.3f, vm.adoptedParamsForTest()?.exposure)

            vm.updateParams { it.copy(exposure = 0.5f) }
            awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }
            assertTrue("0.5 render must be suspended", renderCalls.get() >= 2)
            assertTrue("busy while B is pending", vm.uiState.value.isBusy)

            vm.updateCropRect(0.25f, 0.25f, 0.75f, 0.75f)
            vm.applyCropTransform()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.message == "변경사항을 적용했습니다." }

            assertEquals("only A adopted", 1, adopted.size)
            assertEquals("B rendered once and never re-rendered", 2, renderCalls.get())
            assertFalse("transaction settled closed on first tap", vm.hasOpenParameterGesture())
            assertEquals("A committed exactly once", 1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals("closed exactly once", 1, closed.size)
            assertEquals("no rollback on the adopted path", 0, rollbacks)
            assertEquals("crop transform ran for original, preview and mask", 3, transformCalls.get())
            assertEquals("adopted params remain committed A", 0.3f, vm.uiState.value.params.exposure)
            assertEquals("preview adopted from crop", 0xffff0000.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
            assertEquals("original adopted from crop", 0xff0000ff.toInt(), uiPixelColor(vm.uiState.value.originalPreviewBitmap))
            assertEquals("mask layer transformed", 0xff00ff00.toInt(), uiPixelColor(vm.uiState.value.selectionLayers.single().bitmap))
            assertEquals("committed A plus crop", 2, vm.undoEntryCountForTest())
            assertFalse("busy cleared", vm.uiState.value.isBusy)

            pendingGate.complete(Unit)
            awaitEvent(vm) { renderCalls.get() == 2 }
            assertEquals("stale B never adopts after release", 1, adopted.size)
            assertEquals("stale B never replaces params", 0.3f, vm.uiState.value.params.exposure)

            vm.undoEdit()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.3f }
            assertEquals("undo restores committed A pixels", 0xffff0000.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
            assertEquals("A committed exactly once even after undo", 1, commitBegan.size)
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            cropTransform.close()
            sourceFile.delete()
        }
    }

    // Crop: no adoption at all. Settlement rolls back to the exact start state
    // and the first invocation still executes the crop from the rolled-back
    // document — one undo entry (the crop only), start params restored.
    @Test
    fun cropWithNoAdoptionRollsBackAndExecutesOnFirstInvocation() = runBlocking {
        val sourceFile = draftSourceFile("supersede-crop-noadopt.png")
        val vm = editor(sourceFile.absolutePath, withMask = false)
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val transformCalls = AtomicInteger(0)
        var adopted = 0
        var commitBegan = 0
        var committed = 0
        var rollbacks = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest {
                renderCalls.incrementAndGet()
                pendingGate.await()
                renderSuccess(RenderOperation.NativePreview, 0xff00aaff.toInt())
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks += it },
                )
            )
        val cropTransform = installCropTransformForTest { source, crop ->
            val dims = cropTransformedDimensions(source.width, source.height, crop)
            transformCalls.incrementAndGet()
            outputBitmap(dims.first, dims.second, 0xffff00aa.toInt())
        }
        try {
            awaitReady(vm)
            val startPixels = uiPixelColor(vm.uiState.value.previewBitmap)

            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { renderCalls.get() >= 1 && vm.pendingParamRenderRevision() != null }
            assertEquals("nothing adopted before the crop", 0, adopted)
            assertTrue("busy while the only render is pending", vm.uiState.value.isBusy)

            vm.updateCropRect(0.5f, 0.5f, 1f, 1f)
            vm.applyCropTransform()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.message == "변경사항을 적용했습니다." }

            assertEquals("pending render never adopts", 0, adopted)
            assertEquals("never committed", 0, commitBegan)
            assertEquals(0, committed)
            assertEquals("exact rollback fired once", 1, rollbacks.size)
            assertEquals("transaction closed once", 1, closed.size)
            assertEquals("crop executed on first invocation", 2, transformCalls.get())
            assertEquals("params rolled back to start", 0f, vm.uiState.value.params.exposure)
            assertFalse("busy cleared", vm.uiState.value.isBusy)
            assertEquals("only the crop recorded", 1, vm.undoEntryCountForTest())
            assertEquals(
                "crop produced a preview from the rolled-back document",
                0xffff00aa.toInt(),
                uiPixelColor(vm.uiState.value.previewBitmap),
            )
            assertFalse("crop input was the rolled-back preview", startPixels == uiPixelColor(vm.uiState.value.previewBitmap))
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            cropTransform.close()
            sourceFile.delete()
        }
    }

    // Flare Original night: adopted A + suspended B. The first invocation must
    // commit A and run the flare kernel exactly once, adopting the kernel
    // result as the original and the rendered preview.
    @Test
    fun flareNightExecutesOnFirstInvocationAfterSettlingAdoptedAndPending() = runBlocking {
        val sourceFile = draftSourceFile("supersede-flare.png")
        val vm = editor(sourceFile.absolutePath, withMask = false)
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val flareCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        val commitBegan = mutableListOf<Long>()
        val committed = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        var rollbacks = 0
        val renderer = paramRenderer(pendingGate, renderCalls) { request ->
            when {
                request.operation == RenderOperation.FlareGuard ->
                    renderSuccess(RenderOperation.FlareGuard, 0xffff00aa.toInt())
                else -> null
            }
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        val flareKernel = installNativeFlareGuardInPlaceForTest { bitmap, _, _, _ ->
            flareCalls.incrementAndGet()
            bitmap.eraseColor(0xff0000cc.toInt())
            0
        }
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted.isNotEmpty() && vm.hasOpenParameterGesture() }

            vm.updateParams { it.copy(exposure = 0.5f) }
            awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }

            vm.applyFlareOriginalMvp()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.message == "규칙 기반 보정으로 번짐을 완화했습니다." }

            assertEquals("only A adopted", 1, adopted.size)
            assertEquals("param A, suspended B and flare render total three", 3, renderCalls.get())
            assertEquals("flare kernel ran exactly once", 1, flareCalls.get())
            assertFalse("transaction settled closed on first tap", vm.hasOpenParameterGesture())
            assertEquals(1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("adopted params remain committed A", 0.3f, vm.uiState.value.params.exposure)
            assertEquals("flare preview adopted", 0xffff00aa.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
            assertEquals("flare original adopted", 0xff0000cc.toInt(), uiPixelColor(vm.uiState.value.originalPreviewBitmap))
            assertEquals(2, vm.undoEntryCountForTest())

            pendingGate.complete(Unit)
            awaitEvent(vm) { renderCalls.get() == 3 }
            assertEquals("stale B never adopts after release", 1, adopted.size)

            vm.undoEdit()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.3f }
            assertEquals("undo restores committed A pixels", 0xffff0000.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            flareKernel.close()
            sourceFile.delete()
        }
    }

    // Flare Original sun: no adoption. The first invocation rolls back exactly
    // and still executes the flare action.
    @Test
    fun flareSunWithNoAdoptionRollsBackAndExecutesOnFirstInvocation() = runBlocking {
        val sourceFile = draftSourceFile("supersede-flare-noadopt.png")
        val vm = editor(sourceFile.absolutePath, withMask = false)
        val pendingGate = CompletableDeferred<Unit>()
        val flareCalls = AtomicInteger(0)
        var adopted = 0
        var commitBegan = 0
        var committed = 0
        var rollbacks = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                if (
                    request.operation == RenderOperation.NativePreview &&
                        request.params.exposure == 0.7f
                ) {
                    pendingGate.await()
                }
                renderSuccess(request.operation, 0xff00aaff.toInt())
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks += it },
                )
            )
        val flareKernel = installNativeFlareGuardInPlaceForTest { bitmap, _, _, _ ->
            flareCalls.incrementAndGet()
            bitmap.eraseColor(0xff0000cc.toInt())
            0
        }
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { vm.pendingParamRenderRevision() != null }

            vm.applySunFlareOriginalMvp()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.message == "규칙 기반 보정으로 번짐을 완화했습니다." }

            assertEquals("pending render never adopts", 0, adopted)
            assertEquals("never committed", 0, commitBegan)
            assertEquals(0, committed)
            assertEquals("exact rollback fired once", 1, rollbacks.size)
            assertEquals(1, closed.size)
            assertEquals("flare executed on first invocation", 1, flareCalls.get())
            assertEquals("params rolled back to start", 0f, vm.uiState.value.params.exposure)
            assertEquals(1, vm.undoEntryCountForTest())
            assertFalse("busy cleared", vm.uiState.value.isBusy)
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            flareKernel.close()
            sourceFile.delete()
        }
    }

    // Mask-aware remaster (V2MaskAware, manual mask): adopted A + suspended B.
    // The first invocation commits A and runs the real mask-aware remaster
    // pipeline (manual mask analysis + V2 process + preview render).
    @Test
    fun maskAwareRemasterExecutesOnFirstInvocationAfterSettlingAdoptedAndPending() = runBlocking {
        val sourceFile = draftSourceFile("supersede-remaster.png")
        val vm = editor(sourceFile.absolutePath, withMask = true)
        ExperimentalLabController.updateDebugOverrides {
            it.copy(remaster = RemasterRoute.V2MaskAware)
        }
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        val commitBegan = mutableListOf<Long>()
        val committed = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        var rollbacks = 0
        val renderer = paramRenderer(pendingGate, renderCalls) { request ->
            when {
                request.operation == RenderOperation.Remaster ->
                    renderSuccess(RenderOperation.Remaster, 0xff0000ff.toInt())
                else -> null
            }
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted.isNotEmpty() && vm.hasOpenParameterGesture() }

            vm.updateParams { it.copy(exposure = 0.5f) }
            awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }

            vm.applyMaskAwareRemaster()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.message == "Edge Masker 기반 마스크 보정을 적용했습니다." }

            assertEquals("only A adopted", 1, adopted.size)
            assertEquals("param A, suspended B and remaster render total three", 3, renderCalls.get())
            assertFalse("transaction settled closed on first tap", vm.hasOpenParameterGesture())
            assertEquals(1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("remaster bakes neutral params", 0f, vm.uiState.value.params.exposure)
            assertEquals("remaster preview adopted", 0xff0000ff.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
            assertEquals("remaster committed A plus remaster", 2, vm.undoEntryCountForTest())

            pendingGate.complete(Unit)
            awaitEvent(vm) { renderCalls.get() == 3 }
            assertEquals("stale B never adopts after release", 1, adopted.size)

            vm.undoEdit()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.params.exposure == 0.3f }
            assertEquals("undo restores committed A pixels", 0xffff0000.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Selection native bake: adopted A + suspended B. The first invocation
    // commits A and bakes the active selection mask into the base document.
    @Test
    fun selectionNativeBakeExecutesOnFirstInvocationAfterSettlingAdoptedAndPending() = runBlocking {
        val sourceFile = draftSourceFile("supersede-bake.png")
        val vm = editor(sourceFile.absolutePath, withMask = true)
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        val commitBegan = mutableListOf<Long>()
        val committed = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        var rollbacks = 0
        val renderer = paramRenderer(pendingGate, renderCalls) { request ->
            when {
                request.operation == RenderOperation.SelectionNativeBake &&
                    request.params.exposure == 0f ->
                    renderSuccess(RenderOperation.SelectionNativeBake, 0xff0000ff.toInt())
                request.operation == RenderOperation.SelectionNativeBake &&
                    request.params.exposure == 0.3f ->
                    renderSuccess(RenderOperation.SelectionNativeBake, 0xff00ff00.toInt())
                else -> null
            }
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted.isNotEmpty() && vm.hasOpenParameterGesture() }

            vm.updateParams { it.copy(exposure = 0.5f) }
            awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }

            vm.applyActiveSelectionLocalEditNativeBaked()
            awaitEvent(vm) {
                !vm.uiState.value.isBusy &&
                    vm.uiState.value.message == "선택 마스크 보정을 원본에 적용했습니다. 저장 결과에도 반영됩니다."
            }

            assertEquals("only A adopted", 1, adopted.size)
            assertFalse("transaction settled closed on first tap", vm.hasOpenParameterGesture())
            assertEquals(1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("bake keeps committed params", 0.3f, vm.uiState.value.params.exposure)
            assertEquals("baked original adopted", 0xff0000ff.toInt(), uiPixelColor(vm.uiState.value.originalPreviewBitmap))
            assertEquals("baked preview adopted", 0xff00ff00.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
            assertTrue("selection layers baked away", vm.uiState.value.selectionLayers.isEmpty())
            assertEquals(2, vm.undoEntryCountForTest())
            assertEquals("param A, suspended B, bake and preview total four", 4, renderCalls.get())

            pendingGate.complete(Unit)
            awaitEvent(vm) { renderCalls.get() == 4 }
            assertEquals("stale B never adopts after release", 1, adopted.size)
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Active-selection local edit: adopted A + suspended B. The first
    // invocation commits A and applies the active layer's local params.
    @Test
    fun selectionLocalEditExecutesOnFirstInvocationAfterSettlingAdoptedAndPending() = runBlocking {
        val sourceFile = draftSourceFile("supersede-localedit.png")
        val vm = editor(sourceFile.absolutePath, withMask = true)
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        val commitBegan = mutableListOf<Long>()
        val committed = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        var rollbacks = 0
        val renderer = paramRenderer(pendingGate, renderCalls) { request ->
            when {
                request.operation == RenderOperation.SelectionLocal ->
                    renderSuccess(RenderOperation.SelectionLocal, 0xff0000ff.toInt())
                request.operation == RenderOperation.SelectionNativeBake ->
                    renderSuccess(RenderOperation.SelectionNativeBake, 0xff00ff00.toInt())
                else -> null
            }
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted.isNotEmpty() && vm.hasOpenParameterGesture() }

            vm.updateParams { it.copy(exposure = 0.5f) }
            awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }

            vm.applyActiveSelectionLocalEdit()
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.uiState.value.message == "선택한 마스크 보정을 적용했습니다." }

            assertEquals("only A adopted", 1, adopted.size)
            assertFalse("transaction settled closed on first tap", vm.hasOpenParameterGesture())
            assertEquals(1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals(0, rollbacks)
            assertEquals("local edit bakes neutral params", 0f, vm.uiState.value.params.exposure)
            assertEquals("local-edit original adopted", 0xff0000ff.toInt(), uiPixelColor(vm.uiState.value.originalPreviewBitmap))
            assertEquals("local-edit preview adopted", 0xff00ff00.toInt(), uiPixelColor(vm.uiState.value.previewBitmap))
            assertEquals("committed A plus local edit", 2, vm.undoEntryCountForTest())
            assertEquals("param A, suspended B, local render and preview total four", 4, renderCalls.get())

            pendingGate.complete(Unit)
            awaitEvent(vm) { renderCalls.get() == 4 }
            assertEquals("stale B never adopts after release", 1, adopted.size)
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Renderer override shared by the adopted tests: revision A (0.3) renders
    // red, revision B (0.5) suspends on the gate. Every invocation increments
    // [renderCalls]. Extra operation renders are delegated to [extra] when
    // present, otherwise colored magenta.
    private fun paramRenderer(
        pendingGate: CompletableDeferred<Unit>,
        renderCalls: AtomicInteger,
        extra: (RenderRequest) -> RenderResult? = { null },
    ): AutoCloseable =
        EditorRenderer.installRendererOverrideForTest { request ->
            renderCalls.incrementAndGet()
            when {
                request.operation == RenderOperation.NativePreview &&
                    request.params.exposure == 0.5f -> {
                    pendingGate.await()
                    renderSuccess(RenderOperation.NativePreview, 0xff00aaff.toInt())
                }
                request.operation == RenderOperation.NativePreview &&
                    request.params.exposure == 0.3f ->
                    renderSuccess(RenderOperation.NativePreview, 0xffff0000.toInt())
                else ->
                    extra(request)
                        ?: renderSuccess(request.operation, 0xffff00ff.toInt())
            }
        }

    private fun renderSuccess(operation: RenderOperation, color: Int): RenderResult.Success {
        val output = outputBitmap(16, 16, color)
        return RenderResult.Success(
            operation = operation,
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

    private fun draftSourceFile(name: String): File {
        val source = context.cacheDir.resolve(name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun editor(sourcePath: String, withMask: Boolean): EditorViewModel {
        val vm = harness.createEditor()
        val previewBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        previewBmp.eraseColor(0xff00ff00.toInt())
        val originalBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        originalBmp.eraseColor(0xff006600.toInt())
        val mask = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        mask.eraseColor(0xffffffff.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "supersede-base",
                previewBitmap = previewBmp,
                originalPreviewBitmap = originalBmp,
                selectionLayers =
                    if (withMask) {
                        listOf(
                            SelectionLayer(
                                id = "supersede-mask",
                                name = "Supersede Mask",
                                kind = SelectionLayerKind.Brush,
                                bitmap = mask,
                            )
                        )
                    } else {
                        emptyList()
                    },
                activeSelectionLayerId = if (withMask) "supersede-mask" else null,
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun outputBitmap(width: Int, height: Int, color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun uiPixelColor(bitmap: Bitmap?): Int {
        val bmp = bitmap ?: error("no bitmap")
        return bmp.getPixel(bmp.width / 2, bmp.height / 2)
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            Thread.sleep(5)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            Thread.sleep(5)
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(5)
        }
        assertTrue(predicate())
    }
}
