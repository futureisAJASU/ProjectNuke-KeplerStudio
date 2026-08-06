package com.projectnuke.keplerstudio.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PreviewGeometryTest {

    @Test
    fun `portrait image is letterboxed horizontally`() {
        val size = Size(600f, 900f)
        val rect = fittedImageRect(size, 300, 900)
        assertTrue(rect.width > 0)
        assertTrue(rect.left > 0, "should be horizontally centered")
        assertEquals(0f, rect.top, "should touch top")
        assertEquals(size.height, rect.height, "portrait image fills height")
    }

    @Test
    fun `ultra-wide image is letterboxed vertically`() {
        val size = Size(800f, 400f)
        val rect = fittedImageRect(size, 2400, 600)
        assertTrue(rect.height > 0)
        assertTrue(rect.top > 0, "should be vertically centered")
        assertEquals(0f, rect.left, "should touch left")
        assertEquals(size.width, rect.width, "ultra-wide fills width")
    }

    @Test
    fun `matching aspect fills exactly`() {
        val size = Size(400f, 400f)
        val rect = fittedImageRect(size, 100, 100)
        assertEquals(0f, rect.left)
        assertEquals(0f, rect.top)
        assertEquals(400f, rect.width)
        assertEquals(400f, rect.height)
    }

    @Test
    fun `zero size returns zero rect`() {
        assertTrue(fittedImageRect(Size.Zero, 100, 100).isEmpty)
        assertTrue(fittedImageRect(Size(100f, 100f), 0, 100).isEmpty)
    }

    @Test
    fun `previewGeometry computes correct image rect`() {
        // 600x600 square container with 400x200 image (2:1 aspect)
        val geom = PreviewGeometry(
            container = IntSize(600, 600),
            imageWidth = 400, imageHeight = 200,
            padding = 16f,
        )
        val rect = geom.imageRect
        assertTrue(rect.width > 0, "image rect should have positive width")
        assertTrue(rect.height > 0, "image rect should have positive height")
    }

    @Test
    fun `viewToImage returns null outside fitted rect`() {
        val geom = PreviewGeometry(
            container = IntSize(600, 900),
            imageWidth = 300, imageHeight = 400,
            padding = 16f,
        )
        val result = geom.viewToImage(Offset(0f, 0f))
        assertNull(result, "point outside fitted rect should return null")
    }

    @Test
    fun `viewToImage returns valid coord for central point`() {
        val geom = PreviewGeometry(
            container = IntSize(800, 800),
            imageWidth = 400, imageHeight = 400,
            padding = 0f,
        )
        val result = geom.viewToImage(Offset(400f, 400f))
        assertNotNull(result)
        assertEquals(200f, result!!.first, 1f)
        assertEquals(200f, result.second, 1f)
    }

    @Test
    fun `clamped pan stays within bounds`() {
        val geom = PreviewGeometry(
            container = IntSize(800, 800),
            imageWidth = 400, imageHeight = 400,
            padding = 0f,
            zoom = 2f,
            pan = Offset(500f, 500f),
        )
        val clamped = geom.clampedPan()
        assertTrue(clamped.x < 500f, "should clamp overshoot")
        assertTrue(clamped.y < 500f, "should clamp overshoot")
    }

    @Test
    fun `image and view mappings round trip with center pivot`() {
        val cases = listOf(
            PreviewGeometry(IntSize(800, 600), 400, 300, padding = 12f, zoom = 1f),
            PreviewGeometry(IntSize(800, 600), 300, 900, padding = 24f, zoom = 1.75f, pan = Offset(30f, -42f)),
            PreviewGeometry(IntSize(1200, 500), 2400, 600, padding = 8f, zoom = 2.25f, pan = Offset(-70f, 18f)),
        )
        for (geometry in cases) {
            val image = 0.37f * geometry.imageWidth to 0.61f * geometry.imageHeight
            val view = geometry.imageToView(image.first, image.second)
            assertNotNull(view)
            val roundTrip = geometry.viewToImage(view!!)
            assertNotNull(roundTrip)
            assertEquals(image.first, roundTrip!!.first, 0.01f)
            assertEquals(image.second, roundTrip.second, 0.01f)
        }
    }

    @Test
    fun `padding and letterbox remain outside mapped image`() {
        val geometry = PreviewGeometry(IntSize(600, 900), 300, 400, padding = 20f)
        val rect = geometry.imageRect
        assertNull(geometry.viewToImage(Offset(20f, 20f)))
        assertNull(geometry.viewToImage(Offset(rect.left - 1f, rect.center.y)))
        assertNotNull(geometry.viewToImage(rect.center))
    }

    @Test
    fun `invalid geometry and resize safely reject input`() {
        assertNull(PreviewGeometry(IntSize(0, 0), 100, 100).viewToImage(Offset.Zero))
        assertTrue(PreviewGeometry(IntSize(100, 100), 100, 100, zoom = Float.NaN).clampedPan() == Offset.Zero)
        val resized = PreviewGeometry(IntSize(1000, 400), 400, 400, padding = 16f)
        assertTrue(resized.imageRect.width > 0f)
        assertTrue(resized.imageRect.height > 0f)
    }

    @Test
    fun `v1 zero padding and v2 content padding stay aligned to their real nodes`() {
        val v1 = PreviewGeometry(IntSize(800, 600), 400, 300, padding = 0f)
        val v2 = PreviewGeometry(IntSize(800, 600), 400, 300, padding = 8f)
        assertEquals(0f, v1.imageRect.left)
        assertTrue(v2.imageRect.left > 8f)
        assertEquals(584f * (400f / 300f), v2.imageRect.width, 0.001f)
    }

    @Test
    fun `resize clamps a previously valid zoom pan`() {
        val before =
            PreviewGeometry(
                IntSize(1200, 800),
                1200,
                800,
                padding = 8f,
                zoom = 3f,
                pan = Offset(500f, -500f),
            )
        val after =
            PreviewGeometry(
                IntSize(400, 400),
                1200,
                800,
                padding = 8f,
                zoom = 3f,
                pan = before.clampedPan(),
            )
        val settled = after.clampedPan()
        assertTrue(settled.x in -after.imageRect.width..after.imageRect.width)
        assertTrue(settled.y in -after.imageRect.height..after.imageRect.height)
    }

    @Test
    fun `updateViewport settlement coerces negative dimensions to zero`() {
        val settled =
            settleUpdateViewport(
                ViewportState(scale = 2f, offset = Offset(40f, -20f), viewportWidth = -8, viewportHeight = -4),
                imageWidth = 400,
                imageHeight = 300,
            )
        assertEquals(0, settled.viewportWidth)
        assertEquals(0, settled.viewportHeight)
    }

    @Test
    fun `updateViewport settlement yields zero pan for invalid dimensions`() {
        val settled =
            settleUpdateViewport(
                ViewportState(scale = 2f, offset = Offset(120f, -80f), viewportWidth = -1, viewportHeight = 0),
                imageWidth = 400,
                imageHeight = 300,
            )
        assertEquals(Offset.Zero, settled.offset)
    }

    @Test
    fun `updateViewport settlement preserves clamped pan for positive dimensions`() {
        val input =
            ViewportState(scale = 2f, offset = Offset(500f, 500f), viewportWidth = 800, viewportHeight = 800)
        val settled = settleUpdateViewport(input, imageWidth = 400, imageHeight = 400)
        val expected =
            PreviewGeometry(
                container = IntSize(800, 800),
                imageWidth = 400,
                imageHeight = 400,
                zoom = 2f,
                pan = Offset(500f, 500f),
            ).clampedPan()
        assertEquals(expected, settled.offset)
        assertTrue(settled.offset.x < 500f)
        assertTrue(settled.offset.y < 500f)
        assertEquals(800, settled.viewportWidth)
        assertEquals(800, settled.viewportHeight)
    }

    @Test
    fun `updateViewport settlement normalizes non-finite and below-minimum scale to one`() {
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, 0.5f, -2f)) {
            val settled =
                settleUpdateViewport(
                    ViewportState(scale = scale, offset = Offset(30f, -12f), viewportWidth = 800, viewportHeight = 600),
                    imageWidth = 400,
                    imageHeight = 300,
                )
            assertEquals(1f, settled.scale)
            assertEquals(Offset.Zero, settled.offset)
        }
    }

    /**
     * Mirrors [EditorViewModel.updateViewport] settlement when a preview image is present:
     * sanitize dimensions first, then build geometry and clamp pan.
     */
    private fun settleUpdateViewport(
        viewport: ViewportState,
        imageWidth: Int,
        imageHeight: Int,
    ): ViewportState {
        val safeScale = viewport.scale.takeIf { it.isFinite() && it >= 1f } ?: 1f
        val safeViewportWidth = viewport.viewportWidth.coerceAtLeast(0)
        val safeViewportHeight = viewport.viewportHeight.coerceAtLeast(0)
        val geometry =
            PreviewGeometry(
                container = IntSize(safeViewportWidth, safeViewportHeight),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                zoom = safeScale,
                pan = viewport.offset,
            )
        return viewport.copy(
            scale = safeScale,
            offset = geometry.clampedPan(),
            viewportWidth = safeViewportWidth,
            viewportHeight = safeViewportHeight,
        )
    }
}
