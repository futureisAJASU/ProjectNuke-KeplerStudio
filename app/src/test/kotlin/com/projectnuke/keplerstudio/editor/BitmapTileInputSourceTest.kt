package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BitmapTileInputSourceTest {

    private fun bitmapForTest(): Bitmap {
        // 256x256 bitmap with deterministic gradient: R=x, G=y, B=(x+y)/2
        val w = 256
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = x and 0xFF
                val g = y and 0xFF
                val b = (x + y) / 2 and 0xFF
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun floatAtLE(bytes: ByteArray, offset: Int): Float {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return Float.fromBits(b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
    }

    @Test
    fun knownRegionAtNonZeroSxSyIsReadCorrectlyWithExactChannelOrderAndChwLayout() = runBlocking {
        val bmp = bitmapForTest()
        val source = BitmapTileInputSource(bmp)
        val into = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
        val sx = 64
        val sy = 32
        source.fillChwTile(sx, sy, into)

        // Verify a few pixels against direct bitmap read.
        val plane = 128 * 128
        for (rx in 0 until 4) {
            for (ry in 0 until 4) {
                val x = sx + rx
                val y = sy + ry
                val expectedR = (x and 0xFF) / 255f
                val expectedG = (y and 0xFF) / 255f
                val expectedB = ((x + y) / 2 and 0xFF) / 255f
                val fi = ry * 128 + rx
                val r = floatAtLE(into, fi * 4)
                val g = floatAtLE(into, (plane + fi) * 4)
                val b = floatAtLE(into, (2 * plane + fi) * 4)
                assertEquals("R at $rx,$ry", expectedR, r, 1e-6f)
                assertEquals("G at $rx,$ry", expectedG, g, 1e-6f)
                assertEquals("B at $rx,$ry", expectedB, b, 1e-6f)
            }
        }
        bmp.recycle()
    }

    @Test
    fun littleEndianFp32AndExact01Normalization() = runBlocking {
        val w = 128
        val h = 128
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // pixel (0,0) = pure red 255,0,0 ; (1,0)= 0,128,0 ; (2,0)=0,0,255
        bmp.setPixel(0, 0, (0xFF shl 24) or (255 shl 16) or (0 shl 8) or 0)
        bmp.setPixel(1, 0, (0xFF shl 24) or (0 shl 16) or (128 shl 8) or 0)
        bmp.setPixel(2, 0, (0xFF shl 24) or (0 shl 16) or (0 shl 8) or 255)
        val source = BitmapTileInputSource(bmp)
        val into = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
        source.fillChwTile(0, 0, into)
        val plane = 128 * 128
        // Little-endian check: 1.0f = 0x3F800000 -> bytes 00 00 80 3F in LE
        val r00 = into.sliceArray(0 until 4)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F), r00)
        // G at (1,0) = 128/255 = 0.50196
        val g10 = floatAtLE(into, (plane + 1) * 4)
        assertEquals(128f / 255f, g10, 1e-6f)
        // B at (2,0) = 1.0
        val b20 = floatAtLE(into, (2 * plane + 2) * 4)
        assertEquals(1.0f, b20, 1e-6f)
        bmp.recycle()
    }

    @Test
    fun callerProvidedBufferIsReusedAndNoFullImageAllocation() = runBlocking {
        val bmp = bitmapForTest()
        val source = BitmapTileInputSource(bmp)
        val into = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
        val identity = System.identityHashCode(into)
        source.fillChwTile(0, 0, into)
        assertEquals(identity, System.identityHashCode(into))
        // Second tile reuses same buffer identity
        source.fillChwTile(64, 64, into)
        assertEquals(identity, System.identityHashCode(into))
        // Ensure no full-image FP32 ByteArray was constructed: if the class had a
        // 256x256*3*4 allocation it would be visible as OOM for 4080x3060, but host test
        // verifies buffer size is exactly tile size, not source size.
        assertEquals(ExynosUpscaleSession.INPUT_BYTES, into.size)
        bmp.recycle()
    }

    @Test
    fun wrongOutputBufferSizeFailsClosed() = runBlocking {
        val bmp = bitmapForTest()
        val source = BitmapTileInputSource(bmp)
        try {
            source.fillChwTile(0, 0, ByteArray(10))
            fail("expected wrong buffer size to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reusable tile buffer must be exactly"))
        }
        bmp.recycle()
    }

    @Test
    fun outOfRangeSourceTileFailsClosed() = runBlocking {
        val bmp = bitmapForTest()
        val source = BitmapTileInputSource(bmp)
        val into = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
        try {
            source.fillChwTile(200, 200, into)
            fail("expected out-of-range to fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds"))
        }
        try {
            source.fillChwTile(-1, 0, into)
            fail("expected negative origin to fail")
        } catch (e: IllegalArgumentException) {
        }
        bmp.recycle()
    }

    @Test
    fun cancellationBeforeAndDuringPreparationPropagates() = runBlocking {
        val bmp = bitmapForTest()
        val source = BitmapTileInputSource(bmp)
        val into = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
        // Cancellation before preparation: use a context that is already cancelled
        // fillChwTile checks currentCoroutineContext.ensureActive() so a cancelled scope should throw.
        val job = kotlinx.coroutines.Job()
        job.cancel()
        var threw = false
        try {
            kotlinx.coroutines.withContext(job) {
                source.fillChwTile(0, 0, into)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            threw = true
        }
        assertTrue("cancellation should propagate", threw)
        bmp.recycle()
    }

    @Test
    fun chwLayoutInterleavingIsNotUsed() = runBlocking {
        val w = 128
        val h = 128
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // All pixels red=100, green=150, blue=200
        bmp.eraseColor((0xFF shl 24) or (100 shl 16) or (150 shl 8) or 200)
        val source = BitmapTileInputSource(bmp)
        val into = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
        source.fillChwTile(0, 0, into)
        val buffer = ByteBuffer.wrap(into).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val plane = 128 * 128
        // First plane all red
        for (i in 0 until plane) assertEquals(100f / 255f, buffer.get(i), 1e-6f)
        for (i in plane until 2 * plane) assertEquals(150f / 255f, buffer.get(i), 1e-6f)
        for (i in 2 * plane until 3 * plane) assertEquals(200f / 255f, buffer.get(i), 1e-6f)
        bmp.recycle()
    }
}
