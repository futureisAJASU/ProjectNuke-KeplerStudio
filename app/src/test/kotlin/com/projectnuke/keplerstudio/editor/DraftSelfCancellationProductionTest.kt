package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DraftSelfCancellationProductionTest {
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

    // Test 1: active adopted transaction — save-and-leave before inactivity fires.
    @Test
    fun saveAndLeaveInActiveAdoptedTransactionCommitsExactlyOnce() = runBlocking {
        val sourceFile = draftSourceFile("draft-active-adopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output = renderOutput(0xff224466.toInt())
        var adoptedRevisions = mutableListOf<Int>()
        var inactivityFired = 0
        var commitBegan = mutableListOf<Long>()
        var committed = mutableListOf<Int>()
        var closed = mutableListOf<Long>()
        var draftCaptures = mutableListOf<Long>()
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
        val hooks =
            ParameterLifecycleTestHook.install(
                ParameterLifecycleHooks(
                    onRenderOutputAdopted = { adoptedRevisions += it },
                    onInactivityTimerFired = { inactivityFired++ },
                    onTransactionCommitBegan = { commitBegan += it },
                    onTransactionCommitted = { committed += it },
                    onTransactionClosed = { closed += it },
                    onDraftCaptureBegan = { draftCaptures += it },
                )
            )
        try {
            awaitReady(vm)
            val epochBefore = vm.draftEpochForTest()
            assertEquals(0f, vm.uiState.value.params.exposure)

            vm.updateParams { it.copy(exposure = 0.3f) }
            // prove adoption through the seam, not canUndo
            awaitEvent(vm) { adoptedRevisions.isNotEmpty() }

            // Transaction must still be open: adoption happened, inactivity did not fire
            assertTrue("transaction must still be open", vm.hasOpenParameterGesture())
            assertEquals(0, inactivityFired)
            assertFalse("undo flag must still be false before commit", vm.uiState.value.canUndo)

            val saved = persistDraftForTest(vm)
            assertTrue("save-and-leave must succeed", saved)

            // Draft epoch advanced by exactly one (only the current save)
            assertEquals(epochBefore + 1L, vm.draftEpochForTest())
            assertEquals(1, draftCaptures.size)
            // commit began exactly once for the transaction, then closed
            assertEquals(1, commitBegan.size)
            assertEquals(1, committed.size)
            assertEquals(1, closed.size)
            assertEquals(1, adoptedRevisions.size)
            // transaction closed by settlement
            assertFalse("transaction must close after settlement", vm.hasOpenParameterGesture())
            // no replacement autosave is scheduled
            assertFalse("no autosave may be queued after save-and-leave", vm.hasActiveDraftSaveJobForTest())

            // before-history commits exactly once
            assertEquals(1, vm.undoEntryCountForTest())

            val validated = validateCurrentDraftGeneration(context)
                ?: error("draft must validate after save")
            assertEquals("draft must record adopted exposure", 0.3f, validated.manifest.params.exposure)
            assertTrue(validated.sourceFile.isFile)
            assertTrue(validated.thumbnailFile.isFile)
            assertPixelClose(outputPixelColor(output), thumbnailPixelColor(validated.thumbnailFile), "draft thumbnail pixels must match adopted output")
        } finally {
            hooks.close()
            renderer.close()
            if (!output.isRecycled) output.recycle()
            sourceFile.delete()
        }
    }

    // Test 2: adopted revision plus pending newer render, then save-and-leave.
    @Test
    fun saveDuringPendingNewerRenderCommitsAdoptedRevision() = runBlocking {
        val sourceFile = draftSourceFile("draft-pending-adopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output1 = renderOutput(0xff224466.toInt())
        val output2 = renderOutput(0xff4466AA.toInt())
        val pendingGate = CompletableDeferred<Unit>()
        val renderCalls = AtomicInteger(0)
        val requests = mutableListOf<Int>()
        var adopted = mutableListOf<Int>()
        var closed = 0
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            val call = renderCalls.incrementAndGet()
            if (request.params.exposure == 0.4f) {
                pendingGate.await()
            }
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = when (request.params.exposure) {
                    0.4f -> output2
                    else -> output1
                },
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
                    onRenderRequestStarted = { requests += it },
                    onRenderOutputAdopted = { adopted += it },
                    onTransactionClosed = { closed++ },
                )
            )
        try {
            awaitReady(vm)

            // adopt 0.2 inside an open transaction
            vm.updateParams { it.copy(exposure = 0.2f) }
            awaitEvent(vm) { adopted.isNotEmpty() && vm.hasOpenParameterGesture() }
            assertEquals(0.2f, vm.adoptedParamsForTest()?.exposure)
            assertTrue(vm.hasOpenParameterGesture())

            // request 0.4; the renderer suspends after starting (no adoption)
            vm.updateParams { it.copy(exposure = 0.4f) }
            awaitEvent(vm) { requests.size >= 2 && vm.pendingParamRenderRevision() != null }
            assertTrue("0.4 render must be suspended", requests.size >= 2)
            assertEquals("adopted params stay 0.2", 0.2f, vm.adoptedParamsForTest()?.exposure)
            assertEquals("latest optimistic params are 0.4", 0.4f, vm.latestParamsForTest()?.exposure)
            assertEquals("visible pixels stay the 0.2 output", outputPixelColor(output1), uiPixelColor(vm))
            assertTrue("transaction remains open", vm.hasOpenParameterGesture())

            // save-and-leave cancels the pending 0.4 render and commits 0.2
            val saved = persistDraftForTest(vm)
            assertTrue("manual save must not be canceled", saved)

            // 0.4 never adopts
            assertTrue("only one adoption (0.2)", adopted.size == 1)
            // UI settles to 0.2 params and pixels
            assertEquals(0.2f, vm.uiState.value.params.exposure)
            assertFalse("busy clears synchronously", vm.uiState.value.isBusy)
            assertFalse(vm.hasOpenParameterGesture())
            assertEquals(1, closed)
            assertEquals("one parameter Undo entry", 1, vm.undoEntryCountForTest())

            val validated = validateCurrentDraftGeneration(context) ?: error("no validated draft")
            assertEquals("draft records 0.2", 0.2f, validated.manifest.params.exposure)
            assertPixelClose(outputPixelColor(output1), thumbnailPixelColor(validated.thumbnailFile), "draft pixels record 0.2 output")
        } finally {
            pendingGate.complete(Unit)
            hooks.close()
            renderer.close()
            if (!output1.isRecycled) output1.recycle()
            if (!output2.isRecycled) output2.recycle()
            sourceFile.delete()
        }
    }

    // Test 3: no adoption — save rolls back to exact start state.
    @Test
    fun saveBeforeAnyAdoptionRollsBackExactStartState() = runBlocking {
        val sourceFile = draftSourceFile("draft-noadopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val suspended = CompletableDeferred<Unit>()
        val startPixels = uiPixelColor(vm)
        var adopted = 0
        val renderer = EditorRenderer.installRendererOverrideForTest {
            suspended.await()
            RenderResult.Success(
                operation = RenderOperation.NativePreview,
                requestedRoute = NativeRenderRoute.V1,
                output = renderOutput(0xffff0000.toInt()),
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
                    onRenderOutputAdopted = { adopted++ },
                )
            )
        try {
            awaitReady(vm)
            assertEquals(0f, vm.uiState.value.params.exposure)

            vm.updateParams { it.copy(exposure = 0.7f) }
            awaitEvent(vm) { vm.pendingParamRenderRevision() != null }
            assertEquals(0, adopted)

            // save before any adoption: settlement rolls back to start
            val epochBefore = vm.draftEpochForTest()
            val saved = persistDraftForTest(vm)
            assertTrue("save must complete", saved)
            assertEquals(epochBefore + 1L, vm.draftEpochForTest())

            assertEquals("params roll back to start", 0f, vm.uiState.value.params.exposure)
            assertEquals("pixels roll back to start", startPixels, uiPixelColor(vm))
            assertFalse("busy clears synchronously", vm.uiState.value.isBusy)
            assertFalse("transaction closed", vm.hasOpenParameterGesture())
            assertEquals(0, adopted)
            assertEquals("no undo entry for rolled-back gesture", 0, vm.undoEntryCountForTest())

            val validated = validateCurrentDraftGeneration(context) ?: error("no validated draft")
            assertEquals("draft records start params", 0f, validated.manifest.params.exposure)
            assertPixelClose(startPixels, thumbnailPixelColor(validated.thumbnailFile), "draft records start pixels")
        } finally {
            suspended.complete(Unit)
            hooks.close()
            renderer.close()
            sourceFile.delete()
        }
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
                baseContentToken = "draft-cancel-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        // Drain the startup init coroutine before the test body so no export-
        // history IO outlives the test sandbox. The MediaStore rebuild can take
        // seconds in Robolectric, so give it a generous budget.
        awaitInit(vm)
        return vm
    }

    private fun uiPixelColor(vm: EditorViewModel): Int {
        val preview = vm.uiState.value.previewBitmap ?: error("no preview")
        return preview.getPixel(8, 8)
    }

    private fun outputPixelColor(output: Bitmap): Int = output.getPixel(8, 8)

    private fun assertPixelClose(expected: Int, actual: Int, message: String) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val e = (expected shr shift) and 0xff
            val a = (actual shr shift) and 0xff
            assertTrue("$message (channel shift $shift): expected $e got $a", kotlin.math.abs(e - a) <= 3)
        }
    }

    private fun thumbnailPixelColor(file: File): Int {
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: error("thumbnail decode failed")
        try {
            return decoded.getPixel(decoded.width / 2, decoded.height / 2)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            shadowOf(android.os.Looper.getMainLooper()).idle()
            yieldToEditorBackgroundForTest()
        }
        assertTrue(vm.canEnterEditorAction())
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

    private fun persistDraftForTest(vm: EditorViewModel): Boolean {
        val callerScope = CoroutineScope(Dispatchers.Default)
        val deferred = callerScope.async {
            vm.persistDraftSnapshotNow()
        }
        try {
            awaitEditorCompletionForTest(
                description = "draft save caller must complete",
                completion = deferred,
                timeoutMillis = 30_000L,
                pumpMain = { shadowOf(android.os.Looper.getMainLooper()).idle() },
                diagnostic = { "leave=${vm.editorLeaveState.value}" },
            )
            return runBlocking { deferred.await() }
        } finally {
            deferred.cancel()
            callerScope.cancel()
        }
    }

    private fun awaitEvent(vm: EditorViewModel, predicate: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + 15_000_000_000L
        while (!predicate()) {
            if (System.nanoTime() > deadlineNanos) break
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5L)
        }
        assertTrue(predicate())
    }
}
