package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BitmapCopyOwnershipProofTest {

    @Test
    fun independentBitmapCopyViaCanvasDrawProvesSeparateOwnership() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xffff0000.toInt())

        val copy = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        canvas.drawBitmap(source, 0f, 0f, null)

        assertTrue("copied bitmap is a different object", copy !== source)
        assertTrue("pixels preserved", copy.getPixel(4, 4) == source.getPixel(4, 4))
        assertTrue("same dimensions", copy.width == source.width && copy.height == source.height)
        assertTrue("same config", copy.config == source.config)

        source.recycle()
        assertTrue("copy survives source recycling", !copy.isRecycled)
        assertTrue("pixels intact after source recycle", copy.getPixel(4, 4) == 0xffff0000.toInt())
        copy.recycle()
        assertTrue("recycling does not cross-own", copy.isRecycled)
    }

    @Test
    fun ownerBoundSeamProducesIndependentBitmap() {
        val source = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff00ff00.toInt())

        val seam = BitmapCopyTestSeam.install()
        try {
            val copy = source.copyOrThrow(config = Bitmap.Config.ARGB_8888, mutable = true)
            assertTrue("seam copy is independent", copy !== source)
            assertTrue("seam copy pixels match", copy.getPixel(4, 4) == source.getPixel(4, 4))
            source.recycle()
            assertTrue("seam copy survives source recycle", !copy.isRecycled)
        } finally {
            seam.close()
        }
    }
}
