package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BitmapCopyOwnershipProofTest {

    @Test
    fun mutableCopyPreservesPixelsAlphaAndIndependentRecycleOwnership() {
        val seam = BitmapCopyTestSeam.install()
        try {
            val source = fixture()
            val copy = source.copyOrThrow(Bitmap.Config.ARGB_8888, mutable = true)

            assertTrue(copy !== source)
            assertTrue(copy.isMutable)
            assertEquals(source.config, copy.config)
            assertEquals(source.width, copy.width)
            assertEquals(source.height, copy.height)
            assertEquals(source.density, copy.density)
            assertEquals(source.hasAlpha(), copy.hasAlpha())
            assertEquals(source.getPixel(2, 2), copy.getPixel(2, 2))

            source.recycle()
            assertFalse(copy.isRecycled)
            assertEquals(0x66112233, copy.getPixel(2, 2))
            copy.recycle()

            val reverseSource = fixture()
            val reverseCopy = reverseSource.copyOrThrow(Bitmap.Config.ARGB_8888, mutable = true)
            reverseCopy.recycle()
            assertFalse(reverseSource.isRecycled)
            assertEquals(0x66112233, reverseSource.getPixel(2, 2))
            reverseSource.recycle()
        } finally {
            seam.close()
        }
    }

    @Test
    fun immutableCopyPreservesPixelsAlphaAndIndependentRecycleOwnership() {
        val seam = BitmapCopyTestSeam.install()
        try {
            val source = fixture()
            val copy = source.copyOrThrow(Bitmap.Config.ARGB_8888, mutable = false)

            assertTrue(copy !== source)
            assertFalse(copy.isMutable)
            assertEquals(source.config, copy.config)
            assertEquals(source.width, copy.width)
            assertEquals(source.height, copy.height)
            assertEquals(source.density, copy.density)
            assertEquals(source.hasAlpha(), copy.hasAlpha())
            assertEquals(source.getPixel(2, 2), copy.getPixel(2, 2))

            source.recycle()
            assertFalse(copy.isRecycled)
            assertEquals(0x66112233, copy.getPixel(2, 2))
            copy.recycle()

            val reverseSource = fixture()
            val reverseCopy = reverseSource.copyOrThrow(Bitmap.Config.ARGB_8888, mutable = false)
            reverseCopy.recycle()
            assertFalse(reverseSource.isRecycled)
            assertEquals(0x66112233, reverseSource.getPixel(2, 2))
            reverseSource.recycle()
        } finally {
            seam.close()
        }
    }

    private fun fixture(): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(true)
            it.density = 240
            it.eraseColor(0x66112233)
        }
}
