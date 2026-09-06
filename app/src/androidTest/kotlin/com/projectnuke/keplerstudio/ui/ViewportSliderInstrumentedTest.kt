package com.projectnuke.keplerstudio.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.waitForIdle as ComposeWaitForIdle as ComposeWaitForIdle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.keplerstudio.editor.RenderResult
import com.projectnuke.keplerstudio.editor.V2AdjustmentSlider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@ExperimentalTestApi
class ViewportSliderInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private fun successRender(request: com.projectnuke.keplerstudio.editor.RenderRequest, width: Int, height: Int): RenderResult.Success {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFF112233.toInt())
        return RenderResult.Success(
            operation = request.operation,
            requestedRoute = com.projectnuke.keplerstudio.editor.NativeRenderRoute.V1,
            output = out,
            actualRoute = com.projectnuke.keplerstudio.editor.NativeRenderRoute.V1,
            decision = com.projectnuke.keplerstudio.editor.RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = com.projectnuke.keplerstudio.editor.AlgorithmContracts.NATIVE_V1,
            participation = com.projectnuke.keplerstudio.editor.RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )
    }

    @Test
    fun accessibilitySetProgressOnProductionSliderCommitsOneHistory() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = com.projectnuke.keplerstudio.editor.EditorViewModel(app)
        val renderer = com.projectnuke.keplerstudio.editor.EditorRenderer.installRendererOverrideForTest { request ->
            successRender(request, 160, 160)
        }
        try {
            while (!vm.startupInitCompletion.isCompleted) {
                compose.runOnIdle {}
                Thread.sleep(30)
            }
            val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(0xFF808080.toInt())
            vm.updateUiState {
                it.copy(
                    sourcePath = "instr-test-${System.nanoTime()}",
                    baseContentToken = "instr-base-${bmp.hashCode()}",
                    previewBitmap = bmp,
                    originalPreviewBitmap = bmp,
                    viewport = com.projectnuke.keplerstudio.editor.ViewportState(scale = 2f, offset = androidx.compose.ui.geometry.Offset(10f, -5f), viewportWidth = 800, viewportHeight = 600),
                )
            }
            compose.runOnIdle {}
            Thread.sleep(50)
            val initialUndo = vm.undoEntryCountForTest()
            val viewportBefore = vm.uiState.value.viewport
            var finishCount = 0
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().wrapContentSize()) {
                        V2AdjustmentSlider(
                            "샤프닝",
                            vm.uiState.value.params.sharpness,
                            0f,
                            1f,
                            true,
                            onValue = { v -> vm.updateParams { it.copy(sharpness = v) } },
                            onValueChangeFinished = { finishCount++; vm.finishContinuousParameterEdit() },
                        )
                    }
                }
            }
            compose.runOnIdle {}
            compose.onNodeWithContentDescription("샤프닝").performSemanticsAction(SemanticsActions.SetProgress) { action ->
                assertTrue("SetProgress action should accept Float", action is Function1<*, *>)
                @Suppress("UNCHECKED_CAST")
                val setProgressAction = action as (Float) -> Unit
                val target = 0.75f
                setProgressAction(target)
            }
            compose.runOnIdle {}
            compose.waitUntil(timeoutMillis = 5000L) {
                !vm.uiState.value.isBusy && vm.undoEntryCountForTest() == initialUndo + 1
            }
            compose.runOnIdle {
                assertEquals("history must gain exactly one entry", initialUndo + 1, vm.undoEntryCountForTest())
                assertEquals("scale preserved", viewportBefore.scale, vm.uiState.value.viewport.scale, 1e-6f)
                assertEquals("offset.x preserved", viewportBefore.offset.x, vm.uiState.value.viewport.offset.x, 1e-6f)
                assertEquals("offset.y preserved", viewportBefore.offset.y, vm.uiState.value.viewport.offset.y, 1e-6f)
                assertEquals("final value should be target", 0.75f, vm.uiState.value.params.sharpness, 1e-6f)
                assertEquals("finish callback should be invoked", 1, finishCount)
            }
            bmp.recycle()
        } finally {
            renderer.close()
        }
    }

    @Test
    fun productionSliderForwardDragUpdatesValueAndFinishesOnce() {
        var value by mutableStateOf(0.3f)
        var finishCount = 0
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider(
                    "노이즈 감소",
                    value,
                    0f,
                    1f,
                    true,
                    onValue = { value = it },
                    onValueChangeFinished = { finishCount++ },
                )
            }
        }
        compose.runOnIdle {}
        compose.onNodeWithContentDescription("노이즈 감소").performTouchInput {
            val start = percentOffset(0.15f, 0.5f)
            val end = percentOffset(0.85f, 0.5f)
            down(pointerId = 0, position = start)
            moveTo(pointerId = 0, position = end, delayMillis = 20L)
            up(pointerId = 0)
        }
        compose.waitForIdle()
        assertTrue("drag right must increase the value", value > 0.3f)
        assertTrue("drag must approach end value", value > 0.7f)
        assertEquals("single drag finishes once", 1, finishCount)
    }

    @Test
    fun productionSliderReverseDragKeepsFinalPosition() {
        var value by mutableStateOf(0.5f)
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider("색조", value, 0f, 1f, true, onValue = { value = it }, onValueChangeFinished = {})
            }
        }
        compose.runOnIdle {}
        compose.onNodeWithContentDescription("색조").performTouchInput {
            val start = percentOffset(0.5f, 0.5f)
            val endRight = percentOffset(0.9f, 0.5f)
            val endLeft = percentOffset(0.1f, 0.5f)
            down(pointerId = 0, position = start)
            moveTo(pointerId = 0, position = endRight, delayMillis = 20L)
            moveTo(pointerId = 0, position = endLeft, delayMillis = 20L)
            up(pointerId = 0)
        }
        compose.waitForIdle()
        assertEquals("reverse drag keeps final position", 0.1f, value, 1e-4f)
        compose.waitUntil(timeoutMillis = 2000L) { value == 0.1f }
    }

    @Test
    fun productionSliderSecondPointerDoesNotStealDrag() {
        var value by mutableStateOf(0.5f)
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider("디테일 보호", value, 0f, 1f, true, onValue = { value = it }, onValueChangeFinished = {})
            }
        }
        compose.runOnIdle {}
        compose.onNodeWithContentDescription("디테일 보호").performTouchInput {
            val firstStart = percentOffset(0.20f, 0.5f)
            val firstEnd = percentOffset(0.60f, 0.5f)
            val secondStart = percentOffset(0.80f, 0.5f)
            val secondEnd = percentOffset(0.95f, 0.5f)
            down(pointerId = 0, position = firstStart)
            down(pointerId = 1, position = secondStart)
            moveTo(pointerId = 1, position = secondEnd, delayMillis = 20L)
            moveTo(pointerId = 0, position = firstEnd, delayMillis = 20L)
            up(pointerId = 0)
            moveTo(pointerId = 1, position = secondStart, delayMillis = 20L)
            up(pointerId = 1)
        }
        compose.waitForIdle()
        assertEquals("first pointer position inherited", 0.6f, value, 1e-4f)
        compose.waitUntil(timeoutMillis = 2000L) {
            value == 0.6f
        }
    }

    @Test
    fun productionSliderDisabledDuringDragTransition() {
        var enabled by mutableStateOf(true)
        var value by mutableStateOf(0.4f)
        var finishCount = 0
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider(
                    "채도",
                    value,
                    0f,
                    1f,
                    enabled,
                    onValue = { value = it },
                    onValueChangeFinished = { finishCount++ },
                )
            }
        }
        compose.runOnIdle {}
        compose.onNodeWithContentDescription("채도").performTouchInput {
            val start = percentOffset(0.7f, 0.5f)
            val mid = percentOffset(0.8f, 0.5f)
            down(pointerId = 0, position = start)
            moveTo(pointerId = 0, position = mid, delayMillis = 20L)
        }
        compose.waitForIdle()
        enabled = false
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider(
                    "채도",
                    value,
                    0f,
                    1f,
                    enabled,
                    onValue = { value = it },
                    onValueChangeFinished = { finishCount++ },
                )
            }
        }
        compose.runOnIdle {}
        val valueBeforeDisable = value
        compose.waitUntil(timeoutMillis = 2000L) {
            value == valueBeforeDisable
        }
        assertEquals("disabled slider should not change value", valueBeforeDisable, value, 1e-4f)
        assertEquals("finish callback should be invoked once", 1, finishCount)
        enabled = true
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider(
                    "채도",
                    value,
                    0f,
                    1f,
                    enabled,
                    onValue = { value = it },
                    onValueChangeFinished = { finishCount++ },
                )
            }
        }
        compose.runOnIdle {}
        compose.onNodeWithContentDescription("채도").performTouchInput {
            val start = percentOffset(0.9f, 0.5f)
            val end = percentOffset(0.1f, 0.5f)
            down(pointerId = 0, position = start)
            moveTo(pointerId = 0, position = end, delayMillis = 20L)
            up(pointerId = 0)
        }
        compose.waitForIdle()
        assertTrue("re-enabled slider should respond to new gesture", value < 0.4f)
    }

    @Test
    fun productionSliderReEnabledAfterDisabled() {
        // This test is covered by productionSliderDisabledDuringDragTransition re-enable case
    }
}