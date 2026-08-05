package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
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
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 4 coverage: when a parameter render completes but is stale
 * (managed edit invalidated so [isManagedEditCurrent] returns false),
 * the transaction is left with no adopted output. The inactivity-window
 * auto-close must perform an exact rollback — restoring the start-state
 * params, preview bitmap, correction engine state, clearing [isBusy],
 * and firing [onRollbackAdoptedStartState] — not a bare close.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ParameterNoAdoptionAutoCloseProductionTest {
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
    }

    // The render gates on exposure == 0.7f so that the first render
    // never adopts. A second updateParams call invalidates managed edits,
    // making the in-flight render stale. The inactivity window then
    // auto-closes the transaction; the fix ensures exact rollback.
    @Test
    fun noAdoptionAutoCloseRestoresExactStartState() = runBlocking {
        val sourceFile = draftSourceFile("no-adoption-auto-close.png")
        val vm = editor(sourceFile.absolutePath)
        val renderGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        var timerFired = 0
        var commitBegan = 0
        var committed = 0
        var rollbacks = 0
        val closed = mutableListOf<Long>()
        val renderer =
            EditorRenderer.installRendererOverrideForTest {
                renderCalls.incrementAndGet()
                if (it.operation == RenderOperation.NativePreview && it.params.exposure == 0.7f) {
                    renderGate.await()
                }
                RenderResult.Success(
                    operation = RenderOperation.NativePreview,
                    requestedRoute = NativeRenderRoute.V1,
                    output = renderOutput(0xff00aaff.toInt()),
                    actualRoute = NativeRenderRoute.V1,
                    decision = RenderRouteDecision.FollowDocument,
                    usedDebugOverride = false,
                    algorithmVersion = AlgorithmContracts.NATIVE_V1,
                    participation = RenderParticipation(),
                    durationMillis = 0L,
                    knownTransientBytes = 0L,
                )
            }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onInactivityTimerFired = { timerFired++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onTransactionClosed = { closed += it },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            val startExposure = vm.uiState.value.params.exposure
            val startRevision = vm.uiState.value.revision

            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { renderCalls.get() >= 1 && vm.pendingParamRenderRevision() != null }
            assertTrue("gesture open before invalidation", vm.hasOpenParameterGesture())

            vm.invalidateManagedEditsForTest()

            advanceInactivityWindow(vm)

            assertEquals("timer fired once", 1, timerFired)
            renderGate.complete(Unit)
            awaitEvent(vm) { !vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() }

            assertEquals("no commit — no adoption", 0, commitBegan)
            assertEquals("no commit — no adoption", 0, committed)
            assertEquals("exact rollback once", 1, rollbacks)
            assertEquals("closed exactly once", 1, closed.size)
            assertEquals("params restored to start", startExposure, vm.uiState.value.params.exposure)
            assertFalse("isBusy cleared", vm.uiState.value.isBusy)
        } finally {
            renderGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    private fun renderOutput(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
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
                baseContentToken = "no-adoption-base",
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

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(predicate())
    }

    private fun advanceInactivityWindow(vm: EditorViewModel) {
        repeat(4) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }
}
