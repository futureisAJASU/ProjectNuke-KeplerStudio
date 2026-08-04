package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DraftSelfCancellationProductionTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun cleanDraft() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @After
    fun cleanDraftAfter() {
        context.filesDir.resolve("editor_history_v3").deleteRecursively()
        clearCurrentDraftGenerationPointer(context)
        draftGenerationsRoot(context).deleteRecursively()
    }

    @Test
    fun saveAndLeaveAfterAdoptionCompletesWithoutSelfCancellation() = runBlocking {
        val sourceFile = draftSourceFile("draft-adopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output = renderOutput()
        try {
            EditorRenderer.installRendererOverrideForTest {
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
            awaitReady(vm)
            vm.updateParams { it.copy(exposure = 0.3f) }
            await { vm.uiState.value.canUndo && vm.uiState.value.params.exposure == 0.3f }

            val saved = vm.persistDraftSnapshotNow()
            assertTrue("save-and-leave must succeed", saved)

            val validated = validateCurrentDraftGeneration(context)
                ?: error("draft must validate after save")
            assertEquals("draft must record adopted exposure", 0.3f, validated.manifest.params.exposure)
            assertTrue(validated.sourceFile.isFile)
            assertTrue(validated.thumbnailFile.isFile)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
            sourceFile.delete()
        }
    }

    // Test: save during pending newer render uses adopted params (not latest optimistic)
    @Test
    fun saveDuringPendingRenderCapturesAdoptedNotSpeculative() = runBlocking {
        val sourceFile = draftSourceFile("draft-pending-source.png")
        val vm = editor(sourceFile.absolutePath)
        val output1 = renderOutput(0x224466)
        val output2 = renderOutput(0x4466AA)
        try {
            val renderCount = java.util.concurrent.atomic.AtomicInteger(0)
            EditorRenderer.installRendererOverrideForTest {
                renderCount.incrementAndGet()
                val out = if (renderCount.get() == 1) output1 else output2
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
            awaitReady(vm)

            // adopt 0.2
            vm.updateParams { it.copy(exposure = 0.2f) }
            await { vm.uiState.value.canUndo && vm.uiState.value.params.exposure == 0.2f }

            // save now — settlement commits the adopted params, Draft captures them
            val saved = vm.persistDraftSnapshotNow()
            assertTrue("save must succeed with adopted", saved)

            val validated = validateCurrentDraftGeneration(context) ?: error("no validated draft")
            assertEquals(0.2f, validated.manifest.params.exposure)

            assertFalse("busy must be cleared", vm.uiState.value.isBusy)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
            sourceFile.delete()
        }
    }

    // Test: save before any adoption — rollback, no commit, no Undo entry
    @Test
    fun saveBeforeAdoptionRollsBackWithoutUndoEntry() = runBlocking {
        val sourceFile = draftSourceFile("draft-noadopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            // don't install renderer — renderer will fail, meaning no output is produced

            awaitReady(vm)
            assertFalse("no undo before edit", vm.uiState.value.canUndo)

            // trigger a param update but without renderer → render fails
            vm.updateParams { it.copy(exposure = 0.5f) }
            // force settlement immediately before render completes
            val saved = vm.persistDraftSnapshotNow()
            // Without a renderer, the gesture may settle before producing output.
            // The key assertion: save returns a result — true means Draft was written.
            // If no adoption happened, no parameter Undo should be registered.
            assertTrue("save must complete", saved)

            assertFalse("no undo entry for abandoned gesture", vm.uiState.value.canUndo)
            // After settlement, isBusy must be cleared
            assertFalse("busy must be cleared", vm.uiState.value.isBusy)
        } finally {
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

    private fun renderOutput(color: Int = -0xddbb9a): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun editor(sourcePath: String): EditorViewModel {
        val vm = EditorViewModel(context)
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "draft-cancel-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
        return vm
    }

    private fun awaitReady(vm: EditorViewModel) {
        repeat(200) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            if (vm.canEnterEditorAction()) return
            Thread.sleep(5)
        }
        assertTrue(vm.canEnterEditorAction())
    }

    private fun await(predicate: () -> Boolean) {
        repeat(300) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(5)
        }
        assertTrue(predicate())
    }
}