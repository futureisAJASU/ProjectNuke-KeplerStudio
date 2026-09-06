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
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.movePointerTo
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.up
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performGesture
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.projectnuke.keplerstudio.editor.RenderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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
            Thread.sleep(50)
            compose.onNodeWithContentDescription("샤프닝").performSemanticsAction(
                SemanticsActions.SetProgress,
                { _ -> },
            )
            compose.runOnIdle {}
            Thread.sleep(50)
            var done = false
            while (!done) {
                compose.runOnIdle {
                    if (!vm.uiState.value.isBusy && !vm.hasOpenParameterGesture() && vm.undoEntryCountForTest() == initialUndo + 1) done = true
                }
                Thread.sleep(30)
            }
            compose.runOnIdle {
                assertEquals("history must gain exactly one entry", initialUndo + 1, vm.undoEntryCountForTest())
                assertEquals("scale preserved", viewportBefore.scale, vm.uiState.value.viewport.scale, 1e-6f)
                assertEquals("offset.x preserved", viewportBefore.offset.x, vm.uiState.value.viewport.offset.x, 1e-6f)
                assertEquals("offset.y preserved", viewportBefore.offset.y, vm.uiState.value.viewport.offset.y, 1e-6f)
            }
            bmp.recycle()
        } finally {
            renderer.close()
        }
    }

    @Test
    fun productionSliderGestureDragUpdatesValueAndFinishesOnce() {
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
        Thread.sleep(30)
        compose.onNodeWithContentDescription("노이즈 감소").performGesture {
            val start = percentOffset(0.15f, 0.5f)
            val end = percentOffset(0.85f, 0.5f)
            down(start)
            moveTo(end)
            up()
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        assertTrue("drag right must increase the value", value > 0.3f)
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
        Thread.sleep(30)
        compose.onNodeWithContentDescription("색조").performGesture {
            val x0 = percentOffset(0.5f, 0.5f)
            val endX = (0.9f * getWidth()).toInt()
            val startX = (0.5f * getWidth()).toInt()
            down(x0)
            moveTo(endX, 1L)
            moveTo(startX, 1L)
            up(1)
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        assertEquals("reverse drag keeps final position", 0.5f, value, 1e-4f)
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
        Thread.sleep(30)
        compose.onNodeWithContentDescription("디테일 보호").performGesture {
            val c = percentOffset(0.2f, 0.5f)
            val endX2 = (0.8f * getWidth()).toInt()
            val endX1 = (0.6f * getWidth()).toInt()
            down(c)
            movePointerTo(2, percentOffset(0.8f, 0.5f))
            moveTo(endX2, 2L)
            moveTo(endX1, 1L)
            up(2)
            up(1)
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        assertEquals("first pointer position inherited", 0.6f, value, 1e-4f)
    }

    @Test
    fun productionSliderDisabledIgnoresGesturesThenResumes() {
        var enabled = true
        var value by mutableStateOf(0.4f)
        var finishCount = 0
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider("채도", value, 0f, 1f, enabled, onValue = { value = it }, onValueChangeFinished = { finishCount++ })
            }
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        compose.onNodeWithContentDescription("채도").performGesture {
            val x = percentOffset(0.7f, 0.5f)
            down(x)
            moveTo((0.9f * getWidth()).toInt(), 1L)
            up(1)
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        assertTrue("drag must move value when enabled", value > 0.4f)
        assertTrue("finish must be invoked", finishCount >= 1)
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider("채도", value, 0f, 1f, enabled, onValue = { value = it }, onValueChangeFinished = { finishCount++ })
            }
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        val valueBefore = value
        compose.onNodeWithContentDescription("채도").performGesture {
            val x = percentOffset(0.9f, 0.5f)
            down(x)
            moveTo((0.1f * getWidth()).toInt(), 1L)
            up(1)
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        assertEquals("disabled slider ignores gestures", valueBefore, value, 1e-4f)
        enabled = true
        compose.setContent {
            MaterialTheme {
                V2AdjustmentSlider("채도", value, 0f, 1f, enabled, onValue = { value = it }, onValueChangeFinished = { finishCount++ })
            }
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        compose.onNodeWithContentDescription("채도").performGesture {
            val x = percentOffset(0.9f, 0.5f)
            down(x)
            moveTo((0.1f * getWidth()).toInt(), 1L)
            up(1)
        }
        compose.runOnIdle {}
        Thread.sleep(30)
        assertTrue("re-enabled slider responds", value < valueBefore)
    }
}
