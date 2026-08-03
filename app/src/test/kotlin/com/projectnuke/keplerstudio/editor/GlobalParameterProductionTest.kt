package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GlobalParameterProductionTest {
    @Test
    fun updateParamsUsesWorkerRenderAndCreatesOneUndoEntry() {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication() as Application)
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
            await { vm.canEnterEditorAction() }
            vm.updateParams { it.copy(exposure = 0.3f) }
            await { vm.uiState.value.previewBitmap === output && vm.uiState.value.canUndo }
            assertSame(output, vm.uiState.value.previewBitmap)
            assertEquals(0.3f, vm.uiState.value.params.exposure)
            assertTrue(vm.uiState.value.canUndo)
        } finally {
            EditorRenderer.clearRendererOverrideForTest()
            if (!base.isRecycled) base.recycle()
            if (!output.isRecycled) output.recycle()
        }
    }

    @Test
    fun rapidTicksKeepTheExactFirstGestureStateForUndo() {
        val vm = EditorViewModel(RuntimeEnvironment.getApplication() as Application)
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
        EditorRenderer.installRendererOverrideForTest {
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
            EditorRenderer.clearRendererOverrideForTest()
            if (!base.isRecycled) base.recycle()
        }
    }

    private fun await(predicate: () -> Boolean) {
        repeat(500) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(5)
        }
        assertTrue(predicate())
    }
}
