package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
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
import com.projectnuke.keplerstudio.editor.EditorViewModel.SettlementReason
import com.projectnuke.keplerstudio.editor.EditorViewModel.SettlementResult
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 7: the 900ms parameter-gesture inactivity window. The window must
 * fire at most once per request generation (a newer request resets it), must
 * never fire after settlement or close, and when it fires it must commit an
 * adopted revision automatically without an explicit settlement — but must
 * not close a gesture whose render is still in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ParameterInactivityWindowProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        harness = OwnedEditorViewModelHarness(context)
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
    }

    @After
    fun cleanDraftAfter() {
        harness.close()
        deleteDirectoryIfPresent(context.filesDir.resolve("editor_history_v3"))
        clearCurrentDraftGenerationPointer(context)
        deleteDirectoryIfPresent(draftGenerationsRoot(context))
    }

    private fun deleteDirectoryIfPresent(directory: File) {
        runCatching { if (directory.isDirectory) directory.deleteRecursively() }
    }

    private val red = 0xffff3333.toInt()
    private val green = 0xff33ff33.toInt()

    // Test 1: with an adopted revision and no further input, the window
    // expiry auto-commits the gesture — no explicit settlement needed.
    @Test
    fun windowExpiryAutoCommitsAdoptedRevisionWithoutSettlement() {
        val sourceFile = draftSourceFile("inactivity-auto-source.png")
        val vm = editor(sourceFile.absolutePath)
        var timerFired = 0
        var commitBegan = 0
        var committed = 0
        val closed = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(red),
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
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { vm.uiState.value.params.exposure == 0.2f && !vm.uiState.value.isBusy }
            assertTrue("gesture open before expiry", vm.hasOpenParameterGesture())
            assertEquals(0, timerFired)

            advanceInactivityWindow(vm)

            assertEquals("timer fired exactly once", 1, timerFired)
            assertEquals("auto-commit began once", 1, commitBegan)
            assertEquals(1, committed)
            assertEquals(1, closed.size)
            assertFalse("gesture closed by auto-commit", vm.hasOpenParameterGesture())
            assertEquals("adopted params retained", 0.2f, vm.uiState.value.params.exposure)
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
            awaitEvent(vm) { vm.uiState.value.canUndo }
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 2: a newer request before expiry cancels the old window; only the
    // latest generation's timer fires, and the auto-commit uses the LATEST
    // adopted revision.
    @Test
    fun newerRequestResetsWindowAndOnlyLatestTimerFires() {
        val sourceFile = draftSourceFile("inactivity-reset-source.png")
        val vm = editor(sourceFile.absolutePath)
        var timerFired = 0
        var commitBegan = 0
        var committed = 0
        val renderCalls = AtomicInteger(0)
        val renderer = EditorRenderer.installRendererOverrideForTest {
            val call = renderCalls.incrementAndGet()
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(if (call == 1) red else green),
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
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { vm.uiState.value.params.exposure == 0.2f && !vm.uiState.value.isBusy }
            // second request arrives well inside the first window
            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitEvent(vm) { vm.uiState.value.params.exposure == 0.4f && !vm.uiState.value.isBusy }

            advanceInactivityWindow(vm)

            assertEquals("only the latest window fires", 1, timerFired)
            assertEquals(1, commitBegan)
            assertEquals(1, committed)
            assertFalse(vm.hasOpenParameterGesture())
            assertEquals("auto-commit uses the latest adopted revision", 0.4f, vm.uiState.value.params.exposure)
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 3: settlement before expiry cancels the window; no timer ever
    // fires afterward, and the settled commit is the only one.
    @Test
    fun settlementCancelsWindowAndNoTimerEverFires() {
        val sourceFile = draftSourceFile("inactivity-settle-source.png")
        val vm = editor(sourceFile.absolutePath)
        var timerFired = 0
        var commitBegan = 0
        var committed = 0
        val renderer = EditorRenderer.installRendererOverrideForTest {
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(red),
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
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { vm.uiState.value.params.exposure == 0.2f && !vm.uiState.value.isBusy }

            val settled = vm.settleParameterTransaction(SettlementReason.ExternalEdit)
            assertTrue(settled is SettlementResult.Committed)
            assertEquals(1, commitBegan)
            assertEquals(1, committed)
            assertFalse(vm.hasOpenParameterGesture())

            advanceInactivityWindow(vm)
            advanceInactivityWindow(vm)

            assertEquals("no timer after settlement", 0, timerFired)
            assertEquals("no second commit", 1, commitBegan)
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 4: the timer fires during a suspended render but must NOT close
    // the gesture; the auto-commit waits for the adoption and then commits.
    @Test
    fun timerFiresDuringSuspendedRenderButCommitWaitsForAdoption() {
        val sourceFile = draftSourceFile("inactivity-gated-source.png")
        val vm = editor(sourceFile.absolutePath)
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        var timerFired = 0
        var commitBegan = 0
        var committed = 0
        val closed = mutableListOf<Long>()
        val renderer = EditorRenderer.installRendererOverrideForTest {
            renderCalls.incrementAndGet()
            pendingGate.await()
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(red),
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
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { vm.pendingParamRenderRevision() != null && renderCalls.get() >= 1 }

            advanceInactivityWindow(vm)

            assertEquals("timer fired during the suspended render", 1, timerFired)
            assertTrue("gesture still open while render in flight", vm.hasOpenParameterGesture())
            assertEquals("no commit while render in flight", 0, commitBegan)

            pendingGate.complete(Unit)
            awaitEvent(vm) {
                vm.uiState.value.params.exposure == 0.2f &&
                    !vm.uiState.value.isBusy &&
                    !vm.hasOpenParameterGesture()
            }

            assertEquals("auto-commit after adoption", 1, commitBegan)
            assertEquals(1, committed)
            assertEquals(1, closed.size)
            awaitEvent(vm) { vm.undoEntryCountForTest() == 1 }
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    // Test 5: a failed render rolls the gesture back immediately; the window
    // is cancelled and never fires, and no commit ever happens.
    @Test
    fun failedRenderClosesGestureAndNoTimerEverFires() {
        val sourceFile = draftSourceFile("inactivity-fail-source.png")
        val vm = editor(sourceFile.absolutePath)
        var timerFired = 0
        var commitBegan = 0
        var committed = 0
        var rollbacks = 0
        val renderer = EditorRenderer.installRendererOverrideForTest {
            RenderResult.Failure(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                attemptedRoute = NativeRenderRoute.V1,
                kind = RenderFailureKind.Unexpected,
                message = "phase7 forced failure",
            )
        }
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onInactivityTimerFired = { timerFired++ },
                    onTransactionCommitBegan = { commitBegan++ },
                    onTransactionCommitted = { committed++ },
                    onRollbackAdoptedStartState = { rollbacks++ },
                )
            )
        try {
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { !vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() }
            assertEquals(1, rollbacks)
            assertEquals("params rolled back", 0f, vm.uiState.value.params.exposure)

            advanceInactivityWindow(vm)
            advanceInactivityWindow(vm)

            assertEquals("no timer after rollback", 0, timerFired)
            assertEquals(0, commitBegan)
            assertEquals(0, committed)
            assertEquals(0, vm.undoEntryCountForTest())
        } finally {
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
    }

    private fun advanceInactivityWindow(vm: EditorViewModel) {
        repeat(4) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun draftSourceFile(name: String): File {
        val source = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff00ff00.toInt())
        try {
            source.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        return source
    }

    private fun renderOutput(color: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun editor(sourcePath: String): EditorViewModel {
        val vm = harness.createEditor()
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        base.eraseColor(0xff00ff00.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "inactivity-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        // Drain the startup init coroutine before the test body so no export-
        // history IO outlives the test sandbox.
        awaitInit(vm)
        return vm
    }

    private fun awaitReady(vm: EditorViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            Thread.sleep(5L)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun awaitInit(vm: EditorViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (vm.startupInitCompletion.isCompleted) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            Thread.sleep(5L)
        }
        assertTrue("startup init must complete", vm.startupInitCompletion.isCompleted)
    }

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
            if (predicate()) return
            Thread.sleep(5L)
        }
        assertTrue(predicate())
    }
}
