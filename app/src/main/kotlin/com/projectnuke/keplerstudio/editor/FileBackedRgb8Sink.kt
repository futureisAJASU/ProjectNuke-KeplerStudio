package com.projectnuke.keplerstudio.editor

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Canonical internal pixel format identifier for the N5 file-backed artifact. */
internal const val RGB8_PIXEL_FORMAT = "RGB8"

/**
 * Internal, file-backed RGB8 artifact (N5). This is an INTERMEDIATE, not a JPEG/HEIF
 * product export. It carries only a file handle and exact geometry/format truth for N6 to
 * consume later — never hundreds of MiB of image bytes in memory.
 */
internal data class FileBackedRgb8Artifact(
    val file: File,
    val width: Int,
    val height: Int,
    /** Bytes per destination row = width * 3 (tightly packed interleaved RGB). */
    val rowStride: Int,
    val pixelFormat: String,
    val byteCount: Long,
)

/**
 * N5 file-backed output seam. [writeTile] writes only a [TilePlacement.dest] ownership
 * rectangle; [finish] validates, forces, closes, and atomically publishes the artifact;
 * [invalidate] settles partial staging state on cancellation/staleness/failure.
 */
internal interface Rgb8TileSink {
    val outputWidth: Int
    val outputHeight: Int
    suspend fun writeTile(outputCrop: Rect, dest: Rect, tileOutput: ByteArray)
    fun invalidate()
    fun finish(): FileBackedRgb8Artifact
}

/**
 * Low-level I/O seam for [FileBackedRgb8TileSink]. Production is a real [FileChannel];
 * tests inject a fake to deterministically drive short/zero-progress writes, force/close
 * failures, and rename failures without touching the host filesystem critically.
 */
internal interface FileBackedSinkIo {
    /** Open the staging file exclusively and pre-size it to [length] bytes. */
    fun createTruncate(file: File, length: Long)

    /** Positional write of `length` bytes from `bytes[offset..]`; may write fewer. */
    fun write(position: Long, bytes: ByteArray, offset: Int, length: Int): Int

    /** fsync-equivalent flush of all writes. */
    fun force()

    /** Close the underlying channel (idempotent). */
    fun close()

    /** Current file length in bytes. */
    fun length(): Long

    /** Same-volume atomic rename. */
    fun rename(from: File, to: File): Boolean

    /** Best-effort delete. */
    fun delete(file: File): Boolean
}

/** Real [FileChannel]-backed I/O. */
internal class RealFileBackedSinkIo : FileBackedSinkIo {
    private var channel: FileChannel? = null

    override fun createTruncate(file: File, length: Long) {
        channel =
            FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).apply { truncate(length) }
    }

    override fun write(position: Long, bytes: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(channel) { "sink channel not open" }
            .write(ByteBuffer.wrap(bytes, offset, length), position)

    override fun force() {
        requireNotNull(channel) { "sink channel not open" }.force(true)
    }

    override fun close() {
        channel?.close()
        channel = null
    }

    override fun length(): Long = requireNotNull(channel) { "sink channel not open" }.size()

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)

    override fun delete(file: File): Boolean = file.delete()
}

/**
 * Production file-backed RGB8 tile sink.
 *
 * Never holds the full x4 output in memory. Each retained tile ([TilePlacement.outputCrop])
 * is converted FP32 -> clamped+half-even RGB8 (the accepted N3 conversion), interleaved to
 * RGB, and positional-written at its exact destination offsets using bounded row scratch and
 * an explicit short-write-handling loop.
 */
internal class FileBackedRgb8TileSink(
    val destinationFile: File,
    override val outputWidth: Int,
    override val outputHeight: Int,
    private val operationToken: String,
    private val io: FileBackedSinkIo = RealFileBackedSinkIo(),
) : Rgb8TileSink {

    private enum class State { Open, Invalidated, HandedOff }

    private val channels = ExynosUpscaleSession.OUTPUT_CHANNELS
    private val tileWidth = TilePlanner.TILE_SIZE * TilePlanner.SCALE
    private val planeSize = tileWidth * tileWidth
    private val rowStride = outputWidth * channels
    private val requiredBytes = outputWidth.toLong() * outputHeight * channels.toLong()

    internal val stagingFile: File =
        File(
            destinationFile.absoluteFile.parentFile ?: File("."),
            "${destinationFile.name}.$operationToken.tmp",
        )

    @Volatile
    private var state = State.Open

    private var closed = false

    /** Bounded row scratch: at most 512 pixels x 3 bytes interleaved RGB8. */
    private val rowScratch = ByteArray(tileWidth * channels)

    /** Parked-seam for deterministic cancellation/failure tests before each row write. */
    @Volatile
    internal var preWriteCheck: (suspend () -> Unit)? = null

    init {
        require(outputWidth > 0 && outputHeight > 0) { "output dimensions must be positive" }
        require(rowStride > 0) { "row stride overflows Int for width $outputWidth" }
        try {
            io.createTruncate(stagingFile, requiredBytes)
        } catch (t: Throwable) {
            runCatching { io.delete(stagingFile) }
            throw t
        }
    }

    override suspend fun writeTile(outputCrop: Rect, dest: Rect, tileOutput: ByteArray) {
        check(state == State.Open) { "sink is sealed/invalidated; cannot write (state=$state)" }
        require(tileOutput.size == ExynosUpscaleSession.OUTPUT_BYTES) {
            "tile output must be exactly ${ExynosUpscaleSession.OUTPUT_BYTES} bytes"
        }
        require(outputCrop.right <= tileWidth && outputCrop.bottom <= tileWidth) {
            "output crop (${outputCrop}) exceeds tile output"
        }
        require(dest.right <= outputWidth && dest.bottom <= outputHeight) {
            "dest (${dest}) exceeds ${outputWidth}x$outputHeight output"
        }
        require(outputCrop.width == dest.width && outputCrop.height == dest.height) {
            "crop/dest size mismatch (crop=${outputCrop.width}x${outputCrop.height}, dest=${dest.width}x${dest.height})"
        }
        val rowBytes = outputCrop.width * channels
        for (ry in 0 until outputCrop.height) {
            preWriteCheck?.invoke()
            currentCoroutineContext().ensureActive()
            convertRow(outputCrop, ry, tileOutput, rowScratch)
            val filePos =
                channels.toLong() * (((dest.top + ry).toLong() * outputWidth) + dest.left)
            writeFully(filePos, rowScratch, 0, rowBytes)
        }
    }

    override fun invalidate() {
        if (state != State.Open) return
        state = State.Invalidated
        closeQuietly()
        runCatching { io.delete(stagingFile) }
    }

    override fun finish(): FileBackedRgb8Artifact {
        check(state == State.Open) {
            "finish after invalidate/finish is invalid (state=$state)"
        }
        try {
            io.force()
            val actual = io.length()
            if (actual != requiredBytes) {
                throw IOException("artifact length $actual != expected $requiredBytes")
            }
            io.close()
            closed = true
            if (destinationFile.exists()) {
                throw IOException("destination ${destinationFile.name} already exists; refusing to overwrite")
            }
            if (!io.rename(stagingFile, destinationFile)) {
                throw IOException("same-volume rename to ${destinationFile.name} failed")
            }
            state = State.HandedOff
            return FileBackedRgb8Artifact(
                file = destinationFile,
                width = outputWidth,
                height = outputHeight,
                rowStride = rowStride,
                pixelFormat = RGB8_PIXEL_FORMAT,
                byteCount = requiredBytes,
            )
        } catch (t: Throwable) {
            state = State.Invalidated
            closeQuietly()
            runCatching { io.delete(stagingFile) }
            throw t
        }
    }

    /** Converts one retained output row from CHW FP32 into interleaved RGB8. */
    private fun convertRow(outputCrop: Rect, ry: Int, tile: ByteArray, out: ByteArray) {
        val py = outputCrop.top + ry
        var o = 0
        for (rx in 0 until outputCrop.width) {
            val px = outputCrop.left + rx
            val fi = py * tileWidth + px
            out[o++] = quantizeFp32PixelToUint8(floatAt(tile, fi * Float.SIZE_BYTES)).toByte()
            out[o++] =
                quantizeFp32PixelToUint8(floatAt(tile, (planeSize + fi) * Float.SIZE_BYTES)).toByte()
            out[o++] =
                quantizeFp32PixelToUint8(floatAt(tile, (2 * planeSize + fi) * Float.SIZE_BYTES)).toByte()
        }
    }

    /** Little-endian FP32 read without allocation (canonical D2H payload byte order). */
    private fun floatAt(bytes: ByteArray, offset: Int): Float {
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        return Float.fromBits(b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24))
    }

    /** Short-write-safe positional write; a zero-progress write raises a bounded failure. */
    private fun writeFully(position: Long, bytes: ByteArray, offset: Int, length: Int) {
        var pos = position
        var off = offset
        var remaining = length
        while (remaining > 0) {
            val written = io.write(pos, bytes, off, remaining)
            if (written <= 0) throw IOException("sink write made no progress (written=$written)")
            pos += written
            off += written
            remaining -= written
        }
    }

    private fun closeQuietly() {
        if (closed) return
        closed = true
        runCatching { io.close() }
    }
}