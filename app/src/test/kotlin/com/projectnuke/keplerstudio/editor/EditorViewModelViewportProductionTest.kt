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
        installOwnedBitmaps(vm, width = 400, height = 300)
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
            installOwnedBitmaps(vm, width = 400, height = 300)
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
        }
    }

    @Test
    fun updateViewportPreservesClampedPanForPositiveDimensions() {
        val vm = harness.createEditor()
        installOwnedBitmaps(vm, width = 400, height = 400)
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

    @Test
    fun updateViewportUsesOriginalWhenPreviewAbsent() {
        val vm = harness.createEditor()
        installOwnedBitmaps(
            vm,
            width = 400,
            height = 400,
            installPreview = false,
            installOriginal = true,
        )
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
        assertTrue(viewport.offset != Offset(500f, 500f))
        assertEquals(800, viewport.viewportWidth)
        assertEquals(800, viewport.viewportHeight)
        assertEquals(2f, viewport.scale)
    }

    /**
     * Creates a Bitmap and transfers ownership into ViewModel state on success.
     * Recycles only if installation throws before ownership transfer completes.
     */
    private fun installOwnedBitmaps(
        vm: EditorViewModel,
        width: Int,
        height: Int,
        installPreview: Boolean = true,
        installOriginal: Boolean = true,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var ownershipTransferred = false
        try {
            vm.updateUiState {
                it.copy(
                    sourcePath = "viewport-production-test",
                    baseContentToken = "viewport-production-base",
                    previewBitmap = if (installPreview) bitmap else null,
                    originalPreviewBitmap = if (installOriginal) bitmap else null,
                )
            }
            ownershipTransferred = true
            return bitmap
        } finally {
            if (!ownershipTransferred && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}
