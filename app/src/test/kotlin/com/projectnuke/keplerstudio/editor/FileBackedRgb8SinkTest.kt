package com.projectnuke.keplerstudio.editor

import android.app.Application
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase N5 — file-backed RGB8 sink correctness: geometry, RGB ordering, clamp/rounding,
 * exact byte count, short-write handling, atomic lifecycle, and BYTE-IDENTICAL parity with
 * the N4 [BoundedMemoryTileSink] + accepted FP32->RGB8 conversion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FileBackedRgb8SinkTest {

    private val app: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = File(app.filesDir, "n5_sink_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    // --- helpers -----------------------------------------------------------

    private fun floatsToBytesLittleEndian(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    private fun floatAtLe(bytes: ByteArray, offset: Int): Float {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return Float.fromBits(b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
    }

    private fun referenceRgb8FromFp32Chw(fp32: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3)
        val plane = w * h
        var o = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                out[o++] = quantizeFp32PixelToUint8(floatAtLe(fp32, i * 4)).toByte()
                out[o++] = quantizeFp32PixelToUint8(floatAtLe(fp32, (plane + i) * 4)).toByte()
                out[o++] = quantizeFp32PixelToUint8(floatAtLe(fp32, (2 * plane + i) * 4)).toByte()
            }
        }
        return out
    }

    private fun syntheticTileFp32(): ByteArray {
        val plane = 512 * 512
        val floats = FloatArray(3 * plane)
        for (c in 0 until 3) {
            for (py in 0 until 512) {
                for (px in 0 until 512) {
                    val t = (c * 1000 + py * 7 + px * 3) % 300 - 20
                    floats[c * plane + py * 512 + px] = t / 100f
                }
            }
        }
        return floatsToBytesLittleEndian(floats)
    }

    private fun singleTileSink(name: String): Pair<FileBackedRgb8TileSink, File> {
        val target = File(workDir, name)
        return FileBackedRgb8TileSink(target, 512, 512, "tok") to target
    }

    // --- geometry / conversion --------------------------------------------

    @Test
    fun singleFullTileWritesExactByteCountAndRgbOrdering() = runBlocking {
        val (sink, target) = singleTileSink("single.rgb8")
        val floats = FloatArray(3 * 512 * 512) { 0.5f }
        // Distinguish channel order at pixel (10, 20): R=0.0, G=0.5, B=1.0
        val plane = 512 * 512
        val idx = 20 * 512 + 10
        floats[idx] = 0.0f
        floats[plane + idx] = 0.5f
        floats[2 * plane + idx] = 1.0f
        val bytes = floatsToBytesLittleEndian(floats)

        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), bytes)
        val artifact = sink.finish()

        assertEquals(512, artifact.width)
        assertEquals(512, artifact.height)
        assertEquals(512 * 3, artifact.rowStride)
        assertEquals(RGB8_PIXEL_FORMAT, artifact.pixelFormat)
        assertEquals(3L * 512 * 512, artifact.byteCount)
        assertTrue(artifact.file.exists())
        assertEquals(artifact.byteCount, artifact.file.length())

        val raw = target.readBytes()
        val off = idx * 3
        // R=0 (clamp 0), G=0.5 -> 128, B=1.0 -> 255
        assertEquals(0, raw[off].toInt() and 0xFF)
        assertEquals(128, raw[off + 1].toInt() and 0xFF)
        assertEquals(255, raw[off + 2].toInt() and 0xFF)
    }

    @Test
    fun clampAndHalfEvenRoundingMatchAcceptedConversion() = runBlocking {
        val (sink, target) = singleTileSink("clamp.rgb8")
        val plane = 512 * 512
        val floats = FloatArray(3 * plane) { 0f }
        val cases = listOf(-1f, -0.25f, 0f, 0.25f, 0.5f, 1f, 1.5f, 2f)
        cases.forEachIndexed { i, v -> floats[i] = v }
        val bytes = floatsToBytesLittleEndian(floats)

        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), bytes)
        sink.finish()
        val raw = target.readBytes()

        val expected = cases.map { quantizeFp32PixelToUint8(it) }
        cases.indices.forEach { i ->
            assertEquals(
                "value ${cases[i]} must map consistently",
                expected[i],
                raw[i * 3].toInt() and 0xFF,
            )
        }
        // Explicit half-even expectation: 0.5 * 255 = 127.5 rounds to 128.
        assertEquals(128, quantizeFp32PixelToUint8(0.5f))
        assertEquals(0, quantizeFp32PixelToUint8(-1f))
        assertEquals(255, quantizeFp32PixelToUint8(1.5f))
    }

    // --- pixel parity with N4 BoundedMemoryTileSink ------------------------

    @Test
    fun fileSinkIsByteIdenticalToMemorySinkForIrregularGeometries() = runBlocking {
        val geometries = listOf(188 to 188, 257 to 191, 191 to 257, 257 to 257, 301 to 227)
        for ((w, h) in geometries) {
            val result = TilePlanner.plan(w, h)
            assertTrue("$w x $h must plan", result is TilePlanResult.Planned)
            val plan = (result as TilePlanResult.Planned).plan
            val outW = w * 4
            val outH = h * 4

            val memSink = BoundedMemoryTileSink(outW, outH)
            val fileSink = FileBackedRgb8TileSink(File(workDir, "parity_${w}x$h.rgb8"), outW, outH, "tok")

            for (tile in plan.tiles) {
                val tileBytes = syntheticTileFp32()
                memSink.writeTile(tile.outputCrop, tile.dest, tileBytes)
                fileSink.writeTile(tile.outputCrop, tile.dest, tileBytes)
            }

            val memBytes = memSink.finish()
            val artifact = fileSink.finish()

            assertEquals(3L * outW * outH, artifact.file.length())
            val expected = referenceRgb8FromFp32Chw(memBytes, outW, outH)
            assertArrayEquals("RGB8 parity failed for ${w}x$h", expected, artifact.file.readBytes())
        }
    }

    // --- short-write / hard-failure I/O (fake seam) ------------------------

    private class FakeIo : FileBackedSinkIo {
        var writeBehavior: (Long, ByteArray, Int, Int) -> Int = { _, _, _, l -> l }
        var forceThrows: Throwable? = null
        var renameResult = true
        var atomicMoveThrows: Throwable? = null
        var atomicMoveCalled = false
        var deleteResult = true
        val created = mutableListOf<File>()
        val deleted = mutableListOf<File>()

        override fun createTruncate(file: File, length: Long) {
            created += file
        }

        override fun write(position: Long, bytes: ByteArray, offset: Int, length: Int): Int =
            writeBehavior(position, bytes, offset, length)

        override fun force() {
            forceThrows?.let { throw it }
        }

        override fun close() = Unit

        override fun length(): Long = 3L * 512 * 512

        override fun atomicMove(from: File, to: File) {
            atomicMoveCalled = true
            atomicMoveThrows?.let { throw it }
            if (!renameResult) throw java.io.IOException("atomic move failed")
        }

        override fun rename(from: File, to: File): Boolean = renameResult

        override fun delete(file: File): Boolean {
            deleted += file
            return deleteResult
        }
    }

    @Test
    fun shortWritesEventuallyComplete() = runBlocking {
        val writeCalls = java.util.concurrent.atomic.AtomicInteger()
        val io = FakeIo().apply {
            writeBehavior = { _, _, _, length ->
                writeCalls.incrementAndGet()
                minOf(3, length)
            }
        }
        val sink = FileBackedRgb8TileSink(File(workDir, "short.rgb8"), 512, 512, "tok", io)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        sink.finish()
        // A 512-row tile must have triggered far more than 512 writes if the short-write loop
        // really retried partial writes to completion.
        assertTrue("expected partial writes to be retried, got ${writeCalls.get()}", writeCalls.get() > 512)
        Unit
    }

    @Test
    fun zeroProgressWriteFailsBoundedlyAndDoesNotPublish() = runBlocking {
        val io = FakeIo().apply { writeBehavior = { _, _, _, _ -> 0 } }
        val sink = FileBackedRgb8TileSink(File(workDir, "zero.rgb8"), 512, 512, "tok", io)
        try {
            sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
            fail("expected zero-progress write to fail")
        } catch (expected: Throwable) {
            assertTrue(expected is java.io.IOException || expected is IllegalStateException)
        }
        // The sink must settle staging (delete) after the write failure.
        // invalidate must be safe to call and must delete staging exactly once.
        sink.invalidate()
        sink.invalidate()
        assertEquals(1, io.deleted.size)
    }

    @Test
    fun forceFailureSettlesStagingAndFinishAfterInvalidateRejects() = runBlocking {
        val io = FakeIo().apply { forceThrows = java.io.IOException("force boom") }
        val sink = FileBackedRgb8TileSink(File(workDir, "forcefail.rgb8"), 512, 512, "tok", io)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        try {
            sink.finish()
            fail("expected force failure")
        } catch (expected: Throwable) {
            assertEquals("force boom", expected.message)
        }
        // finish after failure must be rejected; staging deleted.
        try {
            sink.finish()
            fail("finish after invalidate must fail")
        } catch (expected: Throwable) {
        }
        assertEquals(1, io.deleted.size)
        assertFalse(File(workDir, "forcefail.rgb8").exists())
    }

    @Test
    fun renameFailureSettlesStagingAndPublishesNothing() = runBlocking {
        val io = FakeIo().apply { renameResult = false }
        val sink = FileBackedRgb8TileSink(File(workDir, "renamefail.rgb8"), 512, 512, "tok", io)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        try {
            sink.finish()
            fail("expected rename failure")
        } catch (expected: Throwable) {
        }
        assertFalse(File(workDir, "renamefail.rgb8").exists())
        assertEquals(1, io.deleted.size)
    }

    @Test
    fun successfulFinishTransfersOwnershipAndNeverDeletesAfter() = runBlocking {
        val (sink, target) = singleTileSink("owned.rgb8")
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        val artifact = sink.finish()
        assertTrue(artifact.file.exists())
        // invalidate after handoff must be a no-op (caller now owns the artifact).
        sink.invalidate()
        assertTrue(artifact.file.exists())
        // Late cleanup by the caller (simulated) must succeed.
        assertTrue(artifact.file.delete())
    }

    @Test
    fun atomicPublicationSuccessUsesAtomicMove() = runBlocking {
        val io = FakeIo()
        val sink = FileBackedRgb8TileSink(File(workDir, "atomic.rgb8"), 512, 512, "tok", io)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        sink.finish()
        assertTrue("atomicMove must be invoked", io.atomicMoveCalled)
        assertEquals(0, io.deleted.size)
    }

    @Test
    fun atomicMoveUnsupportedFailsAndSettlesStaging() = runBlocking {
        val io = FakeIo().apply { atomicMoveThrows = java.nio.file.AtomicMoveNotSupportedException("src", "dst", "unsupported") }
        val sink = FileBackedRgb8TileSink(File(workDir, "atomicfail.rgb8"), 512, 512, "tok", io)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        try {
            sink.finish()
            fail("expected atomic move unsupported failure")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("atomic"))
        }
        assertFalse(File(workDir, "atomicfail.rgb8").exists())
        assertEquals(1, io.deleted.size)
    }

    @Test
    fun existingDestinationRefusalDoesNotOverwrite() = runBlocking {
        val target = File(workDir, "exists.rgb8")
        target.writeBytes(ByteArray(10))
        val sink = FileBackedRgb8TileSink(target, 512, 512, "tok2")
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        try {
            sink.finish()
            fail("expected existing destination refusal")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("already exists"))
        }
        // Original file must remain
        assertTrue(target.exists())
        assertEquals(10, target.length().toInt())
    }

    @Test
    fun noStagingLeakOnAtomicPublicationFailure() = runBlocking {
        val io = FakeIo().apply { renameResult = false }
        val sink = FileBackedRgb8TileSink(File(workDir, "leak.rgb8"), 512, 512, "tok", io)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        try { sink.finish() } catch (_: Throwable) {}
        assertEquals(1, io.deleted.size)
        assertFalse(File(workDir, "leak.rgb8").exists())
        // Staging tmp should be deleted
        assertTrue(io.deleted.first().name.contains("leak.rgb8"))
    }

    @Test
    fun publicationGuardPreventsAtomicMove() = runBlocking {
        val io = FakeIo()
        val sink = FileBackedRgb8TileSink(File(workDir, "guard.rgb8"), 512, 512, "tok", io)
        sink.writeTile(Rect(0, 0, 512, 512), Rect(0, 0, 512, 512), syntheticTileFp32())
        try {
            sink.finish { false }
            fail("expected guard rejection")
        } catch (e: PublicationGuardException) {
        }
        assertFalse(io.atomicMoveCalled)
        assertEquals(1, io.deleted.size)
        assertFalse(File(workDir, "guard.rgb8").exists())
    }
}