package com.projectnuke.keplerstudio.bridge

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeExactGoldenTest {
    @Test
    fun zeroStrengthSpecialEffectsAndFlareCorrectionAreExactIdentity() = runBlocking {
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
    fun fullSelectionBlendMatchesRecordedPixels() = runBlocking {
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
    fun halfOpacitySelectionBlendMatchesRecordedPixels() = runBlocking {
        val target =
            bitmap(
                3,
                1,
                intArrayOf(0xff010203.toInt(), 0xff102030.toInt(), 0xff405060.toInt()),
            )
        val local =
            bitmap(
                3,
                1,
                intArrayOf(0x80112233.toInt(), 0xffa0b0c0.toInt(), 0x00445566),
            )
        val mask = bitmap(1, 1, intArrayOf(0xffffffff.toInt()))

        assertEquals(
            0,
            NativePhotoCore.nativeBlendSelectionLayerInPlace(
                target,
                local,
                mask,
                false,
                0.5f,
            ),
        )
        assertArrayEquals(
            intArrayOf(0xff09121b.toInt(), 0xff586878.toInt(), 0xff425363.toInt()),
            pixels(target),
        )
    }

    @Test
    fun flareMaskWithoutBlurMatchesRecordedLumaMapping() = runBlocking {
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
    fun cropRotationAndFlipCombinationsAreDeterministicForOddAndNarrowFixtures() = runBlocking {
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
    fun nonzeroMainRenderHasStableHash() = runBlocking {
        val bitmap = fixture()
        val result = NativePhotoCore.nativeRenderPreviewInPlace(
            bitmap,
            exposure = 0.10f,
            contrast = 1.02f,
            shadows = 0.03f,
            highlights = -0.05f,
            whites = 0.01f,
            blacks = -0.01f,
            temperature = 0.05f,
            tint = 0.02f,
            saturation = 1.03f,
            vibrance = 0.04f,
            clarity = 0.03f,
            dehaze = 0.02f,
            sharpness = 0.03f,
            noiseReduction = 0.02f,
            luminanceNoiseReduction = 0.02f,
            colorNoiseReduction = 0.01f,
            noiseDetailProtection = 0.50f,
            noiseEngine = 0,
            detailEngine = 0,
            toneEngine = 0,
            hazeEngine = 0,
            revision = 31,
        )
        assertEquals(31, result)
        assertEquals(8252045260985128563L, exactHash(pixels(bitmap)))
    }

    @Test
    fun nonzeroFlareMaskWithBlurMatchesStableHash() = runBlocking {
        val source = fixture()
        val mask = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        assertEquals(0, NativePhotoCore.nativeCreateFlareMask(source, mask, 0.92f, 2, 1))
        assertEquals(649638774562468513L, exactHash(pixels(mask)))
    }

    @Test
    fun nonzeroNightModeMapsToPredictablePixels() = runBlocking {
        val bitmap = fixture()
        assertEquals(41, NativePhotoCore.nativeApplyFlareGuardInPlace(bitmap, 0, 0.28f, 41))
        assertEquals(-98438237269600328L, exactHash(pixels(bitmap)))
    }

    @Test
    fun nonzeroDayModeMapsToPredictablePixels() = runBlocking {
        val bitmap = fixture()
        assertEquals(41, NativePhotoCore.nativeApplyFlareGuardInPlace(bitmap, 1, 0.24f, 41))
        assertEquals(-7628775503570520508L, exactHash(pixels(bitmap)))
    }

    @Test
    fun everyNonzeroSpecialEffectMatchesRecordedHash() = runBlocking {
        val expected =
            longArrayOf(
                289374068137732291L,
                -5217437593436956710L,
                7403062178545612789L,
                -7823268298983010661L,
            )
        for (effect in 0..3) {
            val bitmap = fixture()
            assertEquals(
                "effect=$effect revision",
                17,
                NativePhotoCore.nativeApplySpecialEffectInPlace(bitmap, effect, 0.5f, 17),
            )
            assertEquals("effect=$effect", expected[effect], exactHash(pixels(bitmap)))
        }
    }

    @Test
    fun cropNinetyRotationWithFlipMatchesRecordedHash() = runBlocking {
        val source = fixture()
        val dest = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        assertEquals(
            23,
            NativePhotoCore.nativeRenderCropTransform(source, dest, 0f, 0f, 1f, 1f, 90f, true, 23),
        )
        assertEquals(2178894102342394339L, exactHash(pixels(dest)))
    }

    @Test
    fun invalidMaskLayoutIsRejectedWithoutChangingSource() = runBlocking {
        val source = fixture()
        val before = pixels(source)
        val wrongSize = Bitmap.createBitmap(source.width + 1, source.height, Bitmap.Config.ARGB_8888)

        assertEquals(-3, NativePhotoCore.nativeCreateFlareMask(source, wrongSize, 0.8f, 1, 1))
        assertArrayEquals(before, pixels(source))
    }

    @Test
    fun unsupportedBitmapAliasesAreRejectedWithoutModification() = runBlocking {
        val crop = fixture()
        val cropBefore = pixels(crop)
        assertEquals(
            -13,
            NativePhotoCore.nativeRenderCropTransform(
                crop,
                crop,
                0f,
                0f,
                1f,
                1f,
                0f,
                false,
                31,
            ),
        )
        assertArrayEquals(cropBefore, pixels(crop))

        val flare = fixture()
        val flareBefore = pixels(flare)
        assertEquals(-13, NativePhotoCore.nativeCreateFlareMask(flare, flare, 0.8f, 1, 1))
        assertArrayEquals(flareBefore, pixels(flare))

        val selection = fixture()
        val selectionBefore = pixels(selection)
        val mask = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        mask.eraseColor(0xffffffff.toInt())
        assertEquals(
            -13,
            NativePhotoCore.nativeBlendSelectionLayerInPlace(
                selection,
                selection,
                mask,
                false,
                1f,
            ),
        )
        assertArrayEquals(selectionBefore, pixels(selection))
    }

    @Test
    fun fixtureVersionAndHashStayStable() {
        assertEquals(1, FIXTURE_VERSION)
        assertEquals(289374068137732291L, exactHash(pixels(fixture())))
    }

    @Test
    fun experimentalV2CorrectionMatchesHostGoldenAndZeroIsIdentity() = runBlocking {
        val source = v2Fixture()
        val sourceBefore = pixels(source)
        val destination =
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val params =
            NativeCorrectionV2Params(
                detail = 0.62f,
                luminanceNoise = 0.38f,
                chromaNoise = 0.55f,
                highlightProtection = 0.72f,
                shadowProtection = 0.68f,
                chromaticAberration = 0.46f,
                vignette = 0.34f,
                spotCleanup = 0.41f,
            )

        assertEquals(0, NativePhotoCore.nativeApplyCorrectionsV2(source, destination, params))
        assertEquals(-7938035531949190446L, exactRgbaHash(pixels(destination)))
        assertArrayEquals(sourceBefore, pixels(source))

        val identity =
            Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        assertEquals(
            0,
            NativePhotoCore.nativeApplyCorrectionsV2(
                source,
                identity,
                NativeCorrectionV2Params(
                    highlightProtection = 0f,
                    shadowProtection = 0f,
                ),
            ),
        )
        assertArrayEquals(sourceBefore, pixels(identity))
    }

    @Test
    fun experimentalV2RejectsAliasWithoutChangingSource() = runBlocking {
        val source = v2Fixture()
        val before = pixels(source)
        var rejected = false
        try {
            NativePhotoCore.nativeApplyCorrectionsV2(
                source,
                source,
                NativeCorrectionV2Params(detail = 0.5f),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        org.junit.Assert.assertTrue(rejected)
        assertArrayEquals(before, pixels(source))
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

    private fun v2Fixture(): Bitmap {
        val values =
            IntArray(9 * 7) { index ->
                val x = index % 9
                val y = index / 9
                val base = 22 + x * 17 + y * 9
                val r = (base + if (x == 7) 74 else 0).coerceAtMost(255)
                val g = (base + 6 + if (y == 1) 28 else 0).coerceAtMost(255)
                val b = (base + 12 + if (x == 1) 52 else 0).coerceAtMost(255)
                val a = if (x == 0) 96 else 255
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        return bitmap(9, 7, values)
    }

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

    private fun exactRgbaHash(values: IntArray): Long {
        var hash = -0x340d631b7bdddcdbL
        values.forEach { value ->
            val rgba =
                intArrayOf(
                    (value ushr 16) and 0xff,
                    (value ushr 8) and 0xff,
                    value and 0xff,
                    (value ushr 24) and 0xff,
                )
            rgba.forEach { channel ->
                hash = (hash xor channel.toLong()) * 0x100000001b3L
            }
        }
        return hash
    }

    private companion object {
        const val FIXTURE_VERSION = 1
    }
}
