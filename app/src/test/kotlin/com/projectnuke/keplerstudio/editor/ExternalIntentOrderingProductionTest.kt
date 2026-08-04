package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExternalIntentOrderingProductionTest {
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

    // Scenario: adopted output + pending newer render, then Undo
    @Test
    fun undoBeforeInactivityAfterAdoptedCommitsOnFirstInvocation() {
        val vm = EditorViewModel(context)
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        output.eraseColor(0xff224466.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = "external-undo-test",
                baseContentToken = "external-undo-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
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
        try {
            awaitReady(vm)

            vm.updateParams { it.copy(exposure = 0.2f) }
            await { vm.uiState.value.canUndo && vm.uiState.value.params.exposure == 0.2f }

            vm.updateParams { it.copy(exposure = 0.4f) }
            await { vm.uiState.value.params.exposure == 0.4f }

            // Undo should settle the pending 0.4 render and commit 0.2, then undo it
            vm.undoEdit()
            await { vm.uiState.value.params.exposure == 0f }
            assertEquals(0f, vm.uiState.value.params.exposure)
            assertFalse("busy must be cleared", vm.uiState.value.isBusy)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
            if (!base.isRecycled) base.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    // Scenario: adopted output + pending newer render, then rotate on first invocation
    @Test
    fun rotateBeforeInactivityExecutesOnFirstInvocation() {
        val vm = EditorViewModel(context)
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        output.eraseColor(0xff224466.toInt())
        vm.updateUiState {
            it.copy(
                sourcePath = "external-rotate-test",
                baseContentToken = "external-rotate-base",
                previewBitmap = base,
                originalPreviewBitmap = base,
            )
        }
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
        try {
            awaitReady(vm)

            vm.updateParams { it.copy(exposure = 0.3f) }
            await { vm.uiState.value.params.exposure == 0.3f && vm.uiState.value.canUndo }

            // trigger a newer render that may still be pending
            vm.updateParams { it.copy(exposure = 0.5f) }
            await { vm.uiState.value.params.exposure == 0.5f }

            // rotate should settle and execute on first call
            vm.rotatePreview90()
            await { !vm.uiState.value.isBusy }
            assertFalse("busy must be cleared", vm.uiState.value.isBusy)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
            if (!base.isRecycled) base.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    // Scenario: no adopted output + external action, exact rollback
    @Test
    fun externalActionWithNoAdoptionRollsBackExactlyOnce() = runBlocking {
        val sourceFile = draftSourceFile("external-noadopt-source.png")
        val vm = editor(sourceFile.absolutePath)
        try {
            EditorRenderer.installRendererOverrideForTest {
                kotlinx.coroutines.delay(999_999L)
                RenderResult.Success(
                    operation = RenderOperation.NativePreview,
                    requestedRoute = NativeRenderRoute.V1,
                    output = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
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
            assertFalse("no undo before edits", vm.uiState.value.canUndo)

            // begin a render but it is stalled — no adoption occurs
            vm.updateParams { it.copy(exposure = 0.7f) }
            await { vm.uiState.value.params.exposure == 0.7f && vm.uiState.value.isBusy }

            // save while render is in-flight — settlement should cancel the
            // stalled render and roll back to start params
            val saved = vm.persistDraftSnapshotNow()

            assertEquals("params must revert to start", 0f, vm.uiState.value.params.exposure)
            assertFalse("busy must be cleared", vm.uiState.value.isBusy)
            assertFalse("no undo for abandoned gesture", vm.uiState.value.canUndo)
            assertTrue("save must complete", saved)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
            sourceFile.delete()
        }
    }

    private fun draftSourceFile(name: String = "external-source.png"): java.io.File {
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
        val vm = EditorViewModel(context)
        val base = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        vm.updateUiState {
            it.copy(
                sourcePath = sourcePath,
                baseContentToken = "external-base",
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