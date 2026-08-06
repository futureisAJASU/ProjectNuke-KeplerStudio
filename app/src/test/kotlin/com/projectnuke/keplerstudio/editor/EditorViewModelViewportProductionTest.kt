package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EditorViewModelViewportProductionTest {
    private lateinit var harness: OwnedEditorViewModelHarness
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    @Before
    fun setUpHarness() {
        harness = OwnedEditorViewModelHarness(context)
    }

    @After
    fun tearDown() {
        harness.close()
    }

    @Test
    fun updateViewportCoercesNegativeDimensionsToZero() {
        val vm = harness.createEditor()
        val preview = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        try {
            installPreview(vm, preview)
            vm.updateViewport(
                ViewportState(
                    scale = 2f,
                    offset = Offset(40f, -20f),
                    viewportWidth = -8,
                    viewportHeight = -4,
                ),
            )
            val viewport = vm.uiState.value.viewport
            assertEquals(0, viewport.viewportWidth)
            assertEquals(0, viewport.viewportHeight)
            assertEquals(Offset.Zero, viewport.offset)
            assertEquals(2f, viewport.scale)
        } finally {
            if (!preview.isRecycled) preview.recycle()
        }
    }

    @Test
    fun updateViewportNormalizesInvalidScaleToOne() {
        val scales =
            listOf(
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY,
                0f,
                0.5f,
                -2f,
            )
        for (scale in scales) {
            val vm = harness.createEditor()
            val preview = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            try {
                installPreview(vm, preview)
                vm.updateViewport(
                    ViewportState(
                        scale = scale,
                        offset = Offset(30f, -12f),
                        viewportWidth = 800,
                        viewportHeight = 600,
                    ),
                )
                val viewport = vm.uiState.value.viewport
                assertEquals(1f, viewport.scale)
                assertEquals(Offset.Zero, viewport.offset)
                assertEquals(800, viewport.viewportWidth)
                assertEquals(600, viewport.viewportHeight)
            } finally {
                if (!preview.isRecycled) preview.recycle()
            }
        }
    }

    @Test
    fun updateViewportPreservesClampedPanForPositiveDimensions() {
        val vm = harness.createEditor()
        val preview = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        try {
            installPreview(vm, preview)
            vm.updateViewport(
                ViewportState(
                    scale = 2f,
                    offset = Offset(500f, 500f),
                    viewportWidth = 800,
                    viewportHeight = 800,
                ),
            )
            val expected =
                PreviewGeometry(
                    container = IntSize(800, 800),
                    imageWidth = 400,
                    imageHeight = 400,
                    zoom = 2f,
                    pan = Offset(500f, 500f),
                ).clampedPan()
            val viewport = vm.uiState.value.viewport
            assertEquals(expected, viewport.offset)
            assertTrue(viewport.offset.x < 500f)
            assertTrue(viewport.offset.y < 500f)
            assertEquals(800, viewport.viewportWidth)
            assertEquals(800, viewport.viewportHeight)
            assertEquals(2f, viewport.scale)
        } finally {
            if (!preview.isRecycled) preview.recycle()
        }
    }

    @Test
    fun updateViewportFallsBackToZeroPanWhenNoImage() {
        val vm = harness.createEditor()
        vm.updateViewport(
            ViewportState(
                scale = 2f,
                offset = Offset(120f, -80f),
                viewportWidth = 800,
                viewportHeight = 600,
            ),
        )
        val viewport = vm.uiState.value.viewport
        assertEquals(800, viewport.viewportWidth)
        assertEquals(600, viewport.viewportHeight)
        assertEquals(2f, viewport.scale)
        assertEquals(Offset.Zero, viewport.offset)
    }

    private fun installPreview(vm: EditorViewModel, preview: Bitmap) {
        vm.updateUiState {
            it.copy(
                sourcePath = "viewport-production-test",
                baseContentToken = "viewport-production-base",
                previewBitmap = preview,
                originalPreviewBitmap = preview,
            )
        }
    }
}
