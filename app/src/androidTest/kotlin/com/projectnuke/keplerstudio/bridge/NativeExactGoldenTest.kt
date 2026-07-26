package com.projectnuke.keplerstudio.bridge

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeExactGoldenTest {
    @Test
    fun zeroStrengthSpecialEffectsAndFlareCorrectionAreExactIdentity() {
        for (effect in 0..3) {
            val bitmap = fixture()
            val expected = pixels(bitmap)
            assertEquals(17, NativePhotoCore.nativeApplySpecialEffectInPlace(bitmap, effect, 0f, 17))
            assertArrayEquals("effect=$effect", expected, pixels(bitmap))
        }
        for (mode in 0..1) {
            val bitmap = fixture()
            val expected = pixels(bitmap)
            assertEquals(19, NativePhotoCore.nativeApplyFlareGuardInPlace(bitmap, mode, 0f, 19))
            assertArrayEquals("mode=$mode", expected, pixels(bitmap))
        }
    }

    @Test
    fun fullSelectionBlendMatchesRecordedPixels() {
        val target = bitmap(3, 1, intArrayOf(0xff010203.toInt(), 0xff102030.toInt(), 0xff405060.toInt()))
        val local = bitmap(3, 1, intArrayOf(0x80112233.toInt(), 0xffa0b0c0.toInt(), 0x00445566))
        val mask = bitmap(1, 1, intArrayOf(0xffffffff.toInt()))

        assertEquals(0, NativePhotoCore.nativeBlendSelectionLayerInPlace(target, local, mask, false, 1f))

        assertArrayEquals(
            intArrayOf(0xff112233.toInt(), 0xffa0b0c0.toInt(), 0xff445566.toInt()),
            pixels(target),
        )
    }

    @Test
    fun flareMaskWithoutBlurMatchesRecordedLumaMapping() {
        val source = fixture()
        val mask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)

        assertEquals(0, NativePhotoCore.nativeCreateFlareMask(source, mask, 0.8f, 0, 0))

        val expected =
            pixels(source).map { pixel ->
                val r = (pixel ushr 16) and 0xff
                val g = (pixel ushr 8) and 0xff
                val b = pixel and 0xff
                val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                val value = (((luma - 0.8f) / 0.2f).coerceIn(0f, 1f) * 255f).roundToInt()
                0xff000000.toInt() or (value shl 16) or (value shl 8) or value
            }.toIntArray()
        assertArrayEquals(expected, pixels(mask))
    }

    @Test
    fun cropRotationAndFlipCombinationsAreDeterministicForOddAndNarrowFixtures() {
        val sources = listOf(fixture(), bitmap(1, 5, IntArray(5) { 0xff000000.toInt() or (it * 41 shl 16) }))
        for (source in sources) {
            for (rotation in listOf(0f, 90f, 180f, 270f)) {
                for (flip in listOf(false, true)) {
                    val first = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    val second = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    assertEquals(
                        23,
                        NativePhotoCore.nativeRenderCropTransform(source, first, 0f, 0f, 1f, 1f, rotation, flip, 23),
                    )
                    assertEquals(
                        23,
                        NativePhotoCore.nativeRenderCropTransform(source, second, 0f, 0f, 1f, 1f, rotation, flip, 23),
                    )
                    assertEquals(exactHash(pixels(first)), exactHash(pixels(second)))
                    assertArrayEquals(pixels(first), pixels(second))
                }
            }
        }
    }

    @Test
    fun invalidMaskLayoutIsRejectedWithoutChangingSource() {
        val source = fixture()
        val before = pixels(source)
        val wrongSize = Bitmap.createBitmap(source.width + 1, source.height, Bitmap.Config.ARGB_8888)

        assertEquals(-3, NativePhotoCore.nativeCreateFlareMask(source, wrongSize, 0.8f, 1, 1))
        assertArrayEquals(before, pixels(source))
    }

    @Test
    fun fixtureVersionAndHashStayStable() {
        assertEquals(1, FIXTURE_VERSION)
        assertEquals(289374068137732291L, exactHash(pixels(fixture())))
    }

    private fun fixture(): Bitmap =
        bitmap(
            5,
            3,
            intArrayOf(
                0xff000000.toInt(), 0xffffffff.toInt(), 0xfffff000.toInt(), 0xff001020.toInt(), 0x0080ff40,
                0xff102030.toInt(), 0xff405060.toInt(), 0xff708090.toInt(), 0xffa0b0c0.toInt(), 0xffd0e0f0.toInt(),
                0xffff00ff.toInt(), 0xff00ffff.toInt(), 0xffffff00.toInt(), 0xff7f7f7f.toInt(), 0xff010203.toInt(),
            ),
        )

    private fun bitmap(width: Int, height: Int, values: IntArray): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(values, 0, width, 0, 0, width, height)
        }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun exactHash(values: IntArray): Long {
        var hash = -0x340d631b7bdddcdbL
        values.forEach { value ->
            repeat(4) { byteIndex ->
                hash = hash xor ((value ushr (byteIndex * 8)) and 0xff).toLong()
                hash *= 0x100000001b3L
            }
        }
        return hash
    }

    private companion object {
        const val FIXTURE_VERSION = 1
    }
}
