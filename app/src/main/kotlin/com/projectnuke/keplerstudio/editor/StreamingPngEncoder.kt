package com.projectnuke.keplerstudio.editor

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

internal object StreamingPngEncoder {

    private const val CHUNK_SIZE = 64 * 1024
    private const val PROGRESS_COALESCE_ROWS = 32

    suspend fun encode(
        artifact: FileBackedRgb8Artifact,
        output: OutputStream,
        isCurrent: () -> Boolean = { true },
        isCancelled: () -> Boolean = { false },
        onRowProgress: suspend (completedRows: Int, totalRows: Int) -> Unit = { _, _ -> },
    ) {
        validate(artifact)
        val file = artifact.file
        if (!file.exists()) throw IOException("RGB8 artifact does not exist: ${file.absolutePath}")
        if (file.length() != artifact.byteCount) throw IOException("artifact length ${file.length()} != expected ${artifact.byteCount}")
        if (artifact.width <= 0 || artifact.height <= 0) throw IOException("invalid dimensions ${artifact.width}x${artifact.height}")
        if (artifact.rowStride != artifact.width * 3) throw IOException("rowStride ${artifact.rowStride} != width*3")
        if (artifact.pixelFormat != RGB8_PIXEL_FORMAT) throw IOException("unsupported pixelFormat ${artifact.pixelFormat}")

        // Deflater ownership wrapped in total try/finally contract (section 4)
        val deflater = Deflater(6)
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

                val ihdr = ByteBuffer.allocate(13)
                ihdr.putInt(artifact.width)
                ihdr.putInt(artifact.height)
                ihdr.put(8)
                ihdr.put(2)
                ihdr.put(0)
                ihdr.put(0)
                ihdr.put(0)
                writeChunk(output, "IHDR", ihdr.array())

                val rawRow = ByteArray(artifact.rowStride + 1)
                val compBuffer = ByteArray(CHUNK_SIZE)
                val pendingBaos = java.io.ByteArrayOutputStream()

                fun drainPendingToIdat() {
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

                fun appendCompressed(n: Int) {
                    if (n > 0) pendingBaos.write(compBuffer, 0, n)
                }

                val rowBytes = artifact.rowStride
                val totalRows = artifact.height
                var completedRows = 0
                var lastEmittedRows = 0

                for (y in 0 until totalRows) {
                    // Use actual calling coroutine cancellation (section 3)
                    coroutineContext.ensureActive()
                    if (isCancelled()) throw kotlinx.coroutines.CancellationException("encode cancelled at row $y")
                    if (!isCurrent()) throw StalePngEncodeException("stale before row $y")

                    rawRow[0] = 0
                    val filePos = y.toLong() * rowBytes
                    readFully(channel, filePos, rawRow, 1, rowBytes)

                    deflater.setInput(rawRow, 0, rawRow.size)
                    while (!deflater.needsInput()) {
                        val n = deflater.deflate(compBuffer)
                        if (n > 0) {
                            appendCompressed(n)
                            if (pendingBaos.size() >= CHUNK_SIZE) drainPendingToIdat()
                        } else break
                    }

                    completedRows++
                    // Emit coalesced progress
                    if (completedRows - lastEmittedRows >= PROGRESS_COALESCE_ROWS || completedRows == totalRows) {
                        onRowProgress(completedRows, totalRows)
                        lastEmittedRows = completedRows
                    }
                }

                deflater.finish()
                while (!deflater.finished()) {
                    val n = deflater.deflate(compBuffer)
                    if (n > 0) {
                        appendCompressed(n)
                        if (pendingBaos.size() >= CHUNK_SIZE) drainPendingToIdat()
                    } else if (deflater.needsInput()) break
                }

                if (pendingBaos.size() > 0) drainPendingToIdat()

                // Always emit final height/height on success
                if (lastEmittedRows != totalRows) {
                    onRowProgress(totalRows, totalRows)
                }

                writeChunk(output, "IEND", ByteArray(0))
                output.flush()
            }
        } finally {
            deflater.end()
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
            if (read <= 0) throw IOException("RGB8 source read made no progress at pos $pos remaining $remaining (possible partial/zero-progress)")
            pos += read
            off += read
            remaining -= read
        }
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val len = data.size
        out.write((len shr 24) and 0xFF)
        out.write((len shr 16) and 0xFF)
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(typeBytes)
        if (data.isNotEmpty()) out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        if (data.isNotEmpty()) crc.update(data)
        val c = crc.value
        out.write(((c shr 24) and 0xFF).toInt())
        out.write(((c shr 16) and 0xFF).toInt())
        out.write(((c shr 8) and 0xFF).toInt())
        out.write((c and 0xFF).toInt())
    }

    internal class StalePngEncodeException(msg: String) : IOException(msg)
}
