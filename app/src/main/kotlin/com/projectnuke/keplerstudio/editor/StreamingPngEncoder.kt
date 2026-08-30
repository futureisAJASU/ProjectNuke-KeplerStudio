package com.projectnuke.keplerstudio.editor

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Phase N6 — bounded streaming PNG encoder for FileBackedRgb8Artifact.
 *
 * Contract:
 * - signature IHDR bit depth 8 color type 2 (RGB) comp 0 filter 0 interlace 0
 * - one bounded raw scanline buffer (width*3 + 1)
 * - filter byte per row (0 = None)
 * - bounded compressed chunk buffer (64-256 KiB)
 * - Deflater zlib stream, valid IDAT CRC32, IEND
 * - no whole-image Bitmap/ByteArray
 * - reads N5 RGB8 artifact row-by-row with partial-read handling, zero-progress bounded failure
 */
internal object StreamingPngEncoder {

    private const val CHUNK_SIZE = 64 * 1024 // bounded IDAT chunk

    fun encode(
        artifact: FileBackedRgb8Artifact,
        output: OutputStream,
        isCurrent: () -> Boolean = { true },
        isCancelled: () -> Boolean = { false },
    ) {
        validate(artifact)
        // Check artifact file length exactly
        val file = artifact.file
        if (!file.exists()) throw IOException("RGB8 artifact does not exist: ${file.absolutePath}")
        if (file.length() != artifact.byteCount) throw IOException("artifact length ${file.length()} != expected ${artifact.byteCount}")
        if (artifact.width <= 0 || artifact.height <= 0) throw IOException("invalid dimensions ${artifact.width}x${artifact.height}")
        if (artifact.rowStride != artifact.width * 3) throw IOException("rowStride ${artifact.rowStride} != width*3")
        if (artifact.pixelFormat != RGB8_PIXEL_FORMAT) throw IOException("unsupported pixelFormat ${artifact.pixelFormat}")

        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            // Write PNG signature
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

            // IHDR
            val ihdr = ByteBuffer.allocate(13)
            ihdr.putInt(artifact.width)
            ihdr.putInt(artifact.height)
            ihdr.put(8) // bit depth
            ihdr.put(2) // color type RGB
            ihdr.put(0) // compression
            ihdr.put(0) // filter
            ihdr.put(0) // interlace
            writeChunk(output, "IHDR", ihdr.array())

            // IDAT streaming via Deflater
            val deflater = Deflater(6) // default compression
            val rawRow = ByteArray(artifact.rowStride + 1) // +1 filter byte
            val compBuffer = ByteArray(CHUNK_SIZE)
            val pendingBaos = java.io.ByteArrayOutputStream()

            fun drainPendingToIdat() {
                // Write pendingBaos content as bounded IDAT chunks
                val bytes = pendingBaos.toByteArray()
                var offset = 0
                while (offset < bytes.size) {
                    val chunkSize = minOf(CHUNK_SIZE, bytes.size - offset)
                    val chunk = bytes.copyOfRange(offset, offset + chunkSize)
                    writeChunk(output, "IDAT", chunk)
                    offset += chunkSize
                }
                pendingBaos.reset()
            }

            fun flushIfNeeded(force: Boolean = false) {
                if (force) {
                    // Drain any pending after finish
                    // First collect remaining deflater output
                }
                if (pendingBaos.size() >= CHUNK_SIZE) {
                    drainPendingToIdat()
                }
            }

            fun appendCompressed(n: Int) {
                if (n > 0) pendingBaos.write(compBuffer, 0, n)
            }

            // Edge: ensure we handle source reads with partial
            val rowBytes = artifact.rowStride
            for (y in 0 until artifact.height) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("encode cancelled at row $y")
                if (!isCurrent()) throw StalePngEncodeException("stale before row $y")
                coroutineContextOrNullEnsureActive()

                // filter byte 0
                rawRow[0] = 0
                val filePos = y.toLong() * rowBytes
                readFully(channel, filePos, rawRow, 1, rowBytes)

                // Feed to deflater
                deflater.setInput(rawRow, 0, rawRow.size)
                while (!deflater.needsInput()) {
                    val n = deflater.deflate(compBuffer)
                    if (n > 0) {
                        appendCompressed(n)
                        if (pendingBaos.size() >= CHUNK_SIZE) drainPendingToIdat()
                    } else break
                }
                // Check cancellation between rows
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("encode cancelled after row $y")
                if (!isCurrent()) throw StalePngEncodeException("stale after row $y")
            }
            // Finish deflater
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(compBuffer)
                if (n > 0) {
                    appendCompressed(n)
                    if (pendingBaos.size() >= CHUNK_SIZE) drainPendingToIdat()
                } else if (deflater.needsInput()) break
            }
            // Drain remaining
            if (pendingBaos.size() > 0) drainPendingToIdat()
            deflater.end()

            // IEND
            writeChunk(output, "IEND", ByteArray(0))
            output.flush()
        }
    }

    private fun validate(artifact: FileBackedRgb8Artifact) {
        require(artifact.width > 0 && artifact.height > 0) { "invalid dimensions" }
        require(artifact.rowStride == artifact.width * 3) { "rowStride mismatch" }
        require(artifact.byteCount == artifact.width.toLong() * artifact.height * 3) { "byteCount mismatch" }
    }

    private fun readFully(channel: FileChannel, position: Long, buf: ByteArray, offset: Int, length: Int) {
        var pos = position
        var off = offset
        var remaining = length
        while (remaining > 0) {
            val read = channel.read(ByteBuffer.wrap(buf, off, remaining), pos)
            if (read <= 0) throw IOException("RGB8 source read made no progress at pos $pos remaining $remaining")
            pos += read
            off += read
            remaining -= read
        }
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val len = data.size
        // length
        out.write((len shr 24) and 0xFF)
        out.write((len shr 16) and 0xFF)
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        // type
        out.write(typeBytes)
        // data
        if (data.isNotEmpty()) out.write(data)
        // CRC over type + data
        val crc = CRC32()
        crc.update(typeBytes)
        if (data.isNotEmpty()) crc.update(data)
        val c = crc.value
        out.write(((c shr 24) and 0xFF).toInt())
        out.write(((c shr 16) and 0xFF).toInt())
        out.write(((c shr 8) and 0xFF).toInt())
        out.write((c and 0xFF).toInt())
    }

    private fun coroutineContextOrNullEnsureActive() {
        // In unit tests there may be no coroutine context; ignore
        try {
            kotlinx.coroutines.runBlocking { coroutineContext.ensureActive() }
        } catch (_: Throwable) { }
    }

    internal class StalePngEncodeException(msg: String) : IOException(msg)
}
