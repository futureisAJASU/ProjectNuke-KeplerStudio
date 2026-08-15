package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Phase 3 coverage: the parameter gesture's [ParamRenderPhase] is set to
 * [EditorViewModel.ParamRenderPhase.Rendering] for EVERY started render, not only the first
 * render that prepares history. Multiple sequential adoptions in one gesture
 * must cycle Rendering → Adopted → Rendering → Adopted, and a pending newer
 * render must never leave the phase at Adopted while a render is in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ParameterRenderPhaseProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        deleteDirectoryIfPresentForTest(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresentForTest(draftGenerationsRoot(context))
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        deleteDirectoryIfPresentForTest(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresentForTest(draftGenerationsRoot(context))
    }

    // Two sequential adoptions (0.3, then 0.5), then a third (0.7). While each
    // newer render is in flight the phase must be Rendering; once it adopts
    // the phase must be Adopted.
    @Test
    fun everyStartedRenderMarksRenderingAcrossSequentialAdoptions() = runBlocking {
        val sourceFile = draftSourceFile("phase-multi.png")
        val vm = editor(sourceFile.absolutePath)
        val gateB = CompletableDeferred<Unit>()
        val gateC = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val adopted = mutableListOf<Int>()
        val commitBegan = mutableListOf<Long>()
        val committed = mutableListOf<Int>()
        val closed = mutableListOf<Long>()
        var rollbacks = 0
        val renderer =
            EditorRenderer.installRendererOverrideForTest { request ->
                renderCalls.incrementAndGet()
                when {
                    request.operation == RenderOperation.NativePreview &&
                        request.params.exposure == 0.3f ->
                        renderSuccess(RenderOperation.NativePreview, 0xffff0000.toInt())
                    request.operation == RenderOperation.NativePreview &&
                        request.params.exposure == 0.5f -> {
                        gateB.await()
                        renderSuccess(RenderOperation.NativePreview, 0xff00aaff.toInt())
                    }
                    request.operation == RenderOperation.NativePreview &&
                        request.params.exposure == 0.7f -> {
                        gateC.await()
                        renderSuccess(RenderOperation.NativePreview, 0xff00ff00.toInt())
                    }
                    else -> renderSuccess(request.operation, 0xffff00ff.toInt())
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
            assertEquals("no gesture yet", null, vm.paramRenderPhaseForTest())

            vm.updateParams { it.copy(exposure = 0.3f) }
            awaitEvent(vm) { adopted.size == 1 && vm.hasOpenParameterGesture() }
            assertEquals("first adoption leaves phase Adopted", EditorViewModel.ParamRenderPhase.Adopted, vm.paramRenderPhaseForTest())
            assertEquals(0.3f, vm.uiState.value.params.exposure)

            vm.updateParams { it.copy(exposure = 0.5f) }
            awaitEvent(vm) { renderCalls.get() >= 2 && vm.pendingParamRenderRevision() != null }
            assertEquals("later render in flight must be Rendering", EditorViewModel.ParamRenderPhase.Rendering, vm.paramRenderPhaseForTest())

            gateB.complete(Unit)
            awaitEvent(vm) { adopted.size == 2 }
            assertEquals("second adoption leaves phase Adopted", EditorViewModel.ParamRenderPhase.Adopted, vm.paramRenderPhaseForTest())
            assertEquals(0.5f, vm.uiState.value.params.exposure)

            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { renderCalls.get() >= 3 && vm.pendingParamRenderRevision() != null }
            assertEquals("third render in flight must be Rendering", EditorViewModel.ParamRenderPhase.Rendering, vm.paramRenderPhaseForTest())

            gateC.complete(Unit)
            awaitEvent(vm) { adopted.size == 3 }
            assertEquals("third adoption leaves phase Adopted", EditorViewModel.ParamRenderPhase.Adopted, vm.paramRenderPhaseForTest())
            assertEquals(0.7f, vm.uiState.value.params.exposure)
            val ownedRevisions = vm.paramRenderRevisionPhasesForTest()
            assertEquals(EditorViewModel.ParamRenderRevisionPhase.Closed, ownedRevisions[adopted[0]])
            assertEquals(EditorViewModel.ParamRenderRevisionPhase.Closed, ownedRevisions[adopted[1]])
            assertEquals(EditorViewModel.ParamRenderRevisionPhase.Adopted, ownedRevisions[adopted[2]])

            val result = vm.settleParameterTransactionBeforeExternalEdit()
            assertEquals(EditorViewModel.SettlementResult.Committed::class, result::class)
            awaitEvent(vm) { !vm.uiState.value.isBusy }
            assertEquals("all three adopted", 3, adopted.size)
            assertEquals("committed exactly once", 1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals("closed exactly once", 1, closed.size)
            assertEquals(0, rollbacks)
            assertNull("no phase after close", vm.paramRenderPhaseForTest())
        } finally {
            gateB.complete(Unit)
            gateC.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // A pending render that never adopts: the phase must stay Rendering while
    // the render is in flight, and settlement rolls the gesture back and
    // closes it with no phase observable afterwards.
    @Test
    fun pendingNeverAdoptedRenderKeepsRenderingThenRollsBack() = runBlocking {
        val sourceFile = draftSourceFile("phase-rollback.png")
        val vm = editor(sourceFile.absolutePath)
        val renderGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        var adopted = 0
        var commitBegan = 0
        var rollbacks = 0
        val closed = mutableListOf<Long>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest {
                renderCalls.incrementAndGet()
                renderGate.await()
                renderSuccess(RenderOperation.NativePreview, 0xff00aaff.toInt())
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adopted++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { renderCalls.get() >= 1 && vm.pendingParamRenderRevision() != null }
            assertEquals("in-flight render is Rendering", EditorViewModel.ParamRenderPhase.Rendering, vm.paramRenderPhaseForTest())

            val result = vm.settleParameterTransactionBeforeExternalEdit()
            assertEquals(EditorViewModel.SettlementResult.RolledBack::class, result::class)
            awaitEvent(vm) { !vm.uiState.value.isBusy && vm.hasOpenParameterGesture().not() }
            assertEquals("nothing adopted", 0, adopted)
            assertEquals("never committed", 0, commitBegan)
            assertEquals("exact rollback once", 1, rollbacks)
            assertEquals("closed exactly once", 1, closed.size)
            assertNull("no phase after close", vm.paramRenderPhaseForTest())
            assertFalse("params rolled back", vm.uiState.value.params.exposure != 0f)
        } finally {
            renderGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    private fun renderSuccess(operation: RenderOperation, color: Int): RenderResult.Success {
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        output.eraseColor(color)
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

    private fun editor(sourcePath: String): EditorViewModel {
        val vm = harness.createEditor()
        val previewBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        previewBmp.eraseColor(0xff00ff00.toInt())
        val originalBmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        originalBmp.eraseColor(0xff006600.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "phase-base",
                previewBitmap = previewBmp,
                originalPreviewBitmap = originalBmp,
            )
        }
        awaitInit(vm)
        return vm
    }

    private fun awaitInit(vm: EditorViewModel) {
        repeat(2000) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private suspend fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
            if (predicate()) return
        }
        repeat(5000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            withContext(Dispatchers.Default) { yield() }
        }
        assertTrue(predicate())
    }
}
