package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StreamingPngEncoderTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() { tmpDir = Files.createTempDirectory("pngtest").toFile() }

    @After
    fun tearDown() { tmpDir.deleteRecursively() }

    private fun artifactFor(width: Int, height: Int, pixels: (Int, Int) -> Triple<Int,Int,Int>): FileBackedRgb8Artifact {
        val byteCount = width.toLong()*height*3
        val file = File(tmpDir, "a_${width}x$height.rgb8")
        FileOutputStream(file).use { out ->
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val (r,g,b) = pixels(x,y)
                    out.write(r); out.write(g); out.write(b)
                }
            }
        }
        assertEquals(byteCount, file.length())
        return FileBackedRgb8Artifact(file, width, height, width*3, RGB8_PIXEL_FORMAT, byteCount)
    }

    private fun decodePngBytes(bytes: ByteArray): Bitmap {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw AssertionError("decode failed")
    }

    private fun readerFactory(bytes: ByteArray, chunk: Int, zeroProgress: Boolean = false, eofAt: Int? = null) =
        Rgb8ReaderFactory { _ ->
            object : Rgb8Reader {
                override fun read(position: Long, into: ByteBuffer): Int {
                    if (zeroProgress) return 0
                    val start = position.toInt()
                    if (eofAt != null && start >= eofAt) return -1
                    if (start >= bytes.size) return -1
                    val count = minOf(chunk, into.remaining(), bytes.size - start, (eofAt ?: bytes.size) - start)
                    if (count <= 0) return -1
                    into.put(bytes, start, count)
                    return count
                }
                override fun close() = Unit
            }
        }

    @Test
    fun encode1x1BlackAndWhitePrimaryColors() = runBlocking {
        val black = artifactFor(1,1) { _,_ -> Triple(0,0,0) }
        val out = ByteArrayOutputStream()
        StreamingPngEncoder.encode(black, out)
        val bmp = decodePngBytes(out.toByteArray())
        assertEquals(1, bmp.width); assertEquals(1, bmp.height)
        assertEquals(0xFF000000.toInt(), bmp.getPixel(0,0))
        val white = artifactFor(1,1) { _,_ -> Triple(255,255,255) }
        val out2 = ByteArrayOutputStream()
        StreamingPngEncoder.encode(white, out2)
        val bmp2 = decodePngBytes(out2.toByteArray())
        assertEquals(0xFFFFFFFF.toInt(), bmp2.getPixel(0,0))
    }

    @Test
    fun oddDimensionsAndRgbOrdering() = runBlocking {
        val art = artifactFor(3,5) { x,y -> Triple((x*50)%256, (y*30)%256, ((x+y)*20)%256) }
        val out = ByteArrayOutputStream()
        StreamingPngEncoder.encode(art, out)
        val bmp = decodePngBytes(out.toByteArray())
        assertEquals(3, bmp.width); assertEquals(5, bmp.height)
        for (y in 0 until 5) for (x in 0 until 3) {
            val (r,g,b) = Triple((x*50)%256, (y*30)%256, ((x+y)*20)%256)
            val expected = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            assertEquals("pixel $x,$y", expected, bmp.getPixel(x,y))
        }
    }

    @Test
    fun randomDeterministicByteIdentical() = runBlocking {
        val w=7; val h=7
        val art = artifactFor(w,h) { x,y -> Triple((x*7+y*13)%256, (x*11+y*17)%256, (x*19+y*23)%256) }
        val out = ByteArrayOutputStream()
        StreamingPngEncoder.encode(art, out)
        val bmp = decodePngBytes(out.toByteArray())
        for (y in 0 until h) for (x in 0 until w) {
            val (r,g,b) = Triple((x*7+y*13)%256, (x*11+y*17)%256, (x*19+y*23)%256)
            assertEquals((0xFF shl 24) or (r shl 16) or (g shl 8) or b, bmp.getPixel(x,y))
        }
    }

    @Test
    fun invalidArtifactSizeFails() = runBlocking {
        val file = File(tmpDir, "bad.rgb8"); file.writeBytes(ByteArray(10))
        val art = FileBackedRgb8Artifact(file, 2,2,6,RGB8_PIXEL_FORMAT, 12)
        try { StreamingPngEncoder.encode(art, ByteArrayOutputStream()); fail("expected") } catch (e: IOException) { assertTrue(e.message!!.contains("length")) }
    }

    @Test
    fun partialPositiveReadsReconstructRow() = runBlocking {
        val art = artifactFor(4,2) { x,y -> Triple(x*50, y*30, 128) }
        val source = art.file.readBytes()
        val out = ByteArrayOutputStream()
        StreamingPngEncoder.encode(art, out, readerFactory = readerFactory(source, chunk = 2))
        val decoded = decodePngBytes(out.toByteArray())
        for (y in 0 until 2) for (x in 0 until 4) {
            assertEquals((0xFF shl 24) or (x * 50 shl 16) or (y * 30 shl 8) or 128, decoded.getPixel(x, y))
        }
        decoded.recycle()
    }

    @Test
    fun zeroProgressReadBoundedlyThrows() = runBlocking {
        val art = artifactFor(2,2) { _,_ -> Triple(10,20,30) }
        val out = ByteArrayOutputStream()
        try {
            StreamingPngEncoder.encode(art, out, readerFactory = readerFactory(art.file.readBytes(), chunk = 1, zeroProgress = true))
            fail("expected zero-progress failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("no progress"))
        }
    }

    @Test
    fun eofBeforeRowCompleteFailsBoundedly() = runBlocking {
        val art = artifactFor(3, 2) { x,y -> Triple(x, y, 7) }
        try {
            StreamingPngEncoder.encode(
                art,
                ByteArrayOutputStream(),
                readerFactory = readerFactory(art.file.readBytes(), chunk = 2, eofAt = 4),
            )
            fail("expected EOF failure")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("no progress"))
        }
    }

    @Test
    fun outputWriteFailurePropagates() = runBlocking {
        val art = artifactFor(2,2) { _,_ -> Triple(100,150,200) }
        val failingOut = object : ByteArrayOutputStream() {
            override fun write(b: Int) { throw IOException("disk full") }
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("disk full") }
        }
        try { StreamingPngEncoder.encode(art, failingOut); fail("expected") } catch (e: IOException) { assertTrue(e.message!!.contains("disk full")) }
    }

    @Test
    fun cancellationMidEncodeFails() = runBlocking {
        val art = artifactFor(10,40) { x,y -> Triple(x,y,128) }
        var call=0
        try {
            StreamingPngEncoder.encode(art, ByteArrayOutputStream(), isCancelled = { ++call>5 }, isCurrent = { true })
            fail("expected cancel")
        } catch (e: CancellationException) { }
    }

    @Test
    fun staleMidEncodeFails() = runBlocking {
        val art = artifactFor(10,10) { _,_ -> Triple(10,20,30) }
        try {
            StreamingPngEncoder.encode(art, ByteArrayOutputStream(), isCancelled = { false }, isCurrent = { false })
            fail("expected stale")
        } catch (e: IOException) { assertTrue(e.message!!.contains("stale")) }
    }

    @Test
    fun pngHeaderAndCrcValid() = runBlocking {
        val art = artifactFor(2,2) { x,y -> Triple(x*100, y*100, 50) }
        val out = ByteArrayOutputStream()
        StreamingPngEncoder.encode(art, out)
        val bytes = out.toByteArray()
        assertEquals(0x89.toByte(), bytes[0]); assertEquals(0x50.toByte(), bytes[1])
        val str = String(bytes, Charsets.ISO_8859_1)
        assertTrue(str.contains("IHDR")); assertTrue(str.contains("IDAT")); assertTrue(str.contains("IEND"))
        val w = java.nio.ByteBuffer.wrap(bytes, 16, 4).int
        val h = java.nio.ByteBuffer.wrap(bytes, 20, 4).int
        assertEquals(2,w); assertEquals(2,h)
        // Recompute CRC32 for every chunk (section 5)
        var pos = 8 // after PNG signature
        while (pos < bytes.size - 4) {
            val len = java.nio.ByteBuffer.wrap(bytes, pos, 4).int
            if (pos + 4 + 4 + len > bytes.size) break // incomplete chunk
            val typeBytes = bytes.copyOfRange(pos+4, pos+8)
            val chunkData = if (len > 0) bytes.copyOfRange(pos+8, pos+8+len) else ByteArray(0)
            val crcBytes = bytes.copyOfRange(pos+8+len, pos+8+len+4)
            val computedCrc = java.util.zip.CRC32().apply {
                update(typeBytes)
                if (len > 0) update(chunkData)
            }.value
            val storedCrc = java.nio.ByteBuffer.wrap(crcBytes).int.toLong() and 0xFFFFFFFFL
            assertEquals("CRC mismatch at chunk $typeBytes", computedCrc, storedCrc)
            pos += 4 + 4 + len + 4
        }
    }

    @Test
    fun multiIdatForLargeImage() = runBlocking {
        val art = artifactFor(256,256) { x,y -> Triple((x+y)%256, (x*2)%256, (y*2)%256) }
        val out = ByteArrayOutputStream()
        StreamingPngEncoder.encode(art, out)
        val bytes = out.toByteArray()
        val idatCount = String(bytes, Charsets.ISO_8859_1).split("IDAT").size -1
        assertTrue("expected >=2 IDAT chunks for 256x256", idatCount >= 2)
    }

    @Test
    fun progressMonotonic() = runBlocking {
        val art = artifactFor(64, 8) { x, y -> Triple(x*10, y*10, 128) }
        val progressRows = mutableListOf<Int>()
        val out = ByteArrayOutputStream()
        StreamingPngEncoder.encode(art, out, isCurrent = { true }, isCancelled = { false }, onRowProgress = { completed, total -> progressRows.add(completed) })
        assertTrue(progressRows.isNotEmpty())
        // Monotonic
        for (i in 1 until progressRows.size) assertTrue(progressRows[i] >= progressRows[i-1])
        assertEquals(8, progressRows.last())
    }

    @Test
    fun cancelMidEncodeUsingJob() = runBlocking {
        val art = artifactFor(10,40) { x,y -> Triple(x,y,128) }
        val out = ByteArrayOutputStream()
        var cancelled = false
        try {
            StreamingPngEncoder.encode(art, out, isCurrent = { true }, isCancelled = { cancelled }, onRowProgress = { _,_ ->
                cancelled = true
            })
            fail("expected cancel")
        } catch (e: CancellationException) {
            cancelled = true
        }
        assertTrue("expected cancellation", cancelled)
    }
}
