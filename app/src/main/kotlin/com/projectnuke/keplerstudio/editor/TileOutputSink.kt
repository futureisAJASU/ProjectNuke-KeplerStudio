package com.projectnuke.keplerstudio.editor

/**
 * Phase N4 — bounded output assembly for the tiled upscaler.
 *
 * [TileOutputSink] is the smallest seam between the geometry-correct tiled engine and
 * whatever holds the assembled full output. N4 uses the bounded in-memory
 * [BoundedMemoryTileSink]; a later phase (N5) may replace it with a memory-bounded or
 * file-backed sink without touching [TilePlanner] or [TileInferenceOrchestrator].
 *
 * Every copy is a raw FP32 byte-preserving copy (no float round-trip), so the assembled
 * output is italic-exact: the same retained bytes the NNC produced, cropped and placed.
 */
internal interface TileOutputSink {
    val outputWidth: Int
    val outputHeight: Int

    /** Copy the [outputCrop] region of one 512x512 tile output into [dest] of the full output. */
    fun writeTile(outputCrop: Rect, dest: Rect, tileOutput: ByteArray)

    /** Dispose partial state after a cancelled/failed run; a later [finish] must fail. */
    fun invalidate()

    /** Return the assembled FP32 CHW RGB full-output bytes after a fully successful run. */
    fun finish(): ByteArray
}

/**
 * In-memory bounded sink (N4 correctness scope only).
 *
 * It assembles a full FP32 CHW RGB output of `3 * outputWidth * outputHeight * 4` bytes
 * and is constrained by [MAX_BOUNDED_OUTPUT_BYTES] so it cannot be misused as an
 * unrestricted full-12MP x4 production buffer (see N4.18 — that is a later phase's
 * streaming/memory problem). Construction throws [IllegalStateException] when the
 * requested output exceeds the bound.
 */
internal class BoundedMemoryTileSink(
    override val outputWidth: Int,
    override val outputHeight: Int,
) : TileOutputSink {

    private val channels = ExynosUpscaleSession.OUTPUT_CHANNELS
    private val totalOutputBytes = channels.toLong() * outputWidth * outputHeight * Float.SIZE_BYTES

    private var buffer: ByteArray
    private var sealed: Boolean = false

    init {
        require(outputWidth > 0 && outputHeight > 0) { "output dimensions must be positive" }
        check(
            totalOutputBytes <= MAX_BOUNDED_OUTPUT_BYTES,
        ) {
            "bounded N4 sink refuses ${totalOutputBytes} bytes (limit $MAX_BOUNDED_OUTPUT_BYTES); " +
                "full-resolution streaming sinks belong to a later phase"
        }
        buffer = ByteArray(totalOutputBytes.toInt())
    }

    override fun writeTile(outputCrop: Rect, dest: Rect, tileOutput: ByteArray) {
        check(!sealed) { "sink is already sealed/invalidated" }
        require(dest.right <= outputWidth && dest.bottom <= outputHeight) { "dest outside full output" }
        val tileWidth = TilePlanner.TILE_SIZE * TilePlanner.SCALE
        require(outputCrop.right <= tileWidth && outputCrop.bottom <= tileWidth) { "crop outside tile output" }
        require(dest.width == outputCrop.width && dest.height == outputCrop.height) { "crop/dest size mismatch" }
        require(tileOutput.size == channels * tileWidth * tileWidth * Float.SIZE_BYTES) { "tile output size mismatch" }

        val copyBytes = outputCrop.width * Float.SIZE_BYTES
        for (c in 0 until channels) {
            val tilePlane = c * tileWidth * tileWidth * Float.SIZE_BYTES
            val fullPlane = c * outputWidth * outputHeight * Float.SIZE_BYTES
            for (ry in 0 until outputCrop.height) {
                val src = tilePlane + ((outputCrop.top + ry) * tileWidth + outputCrop.left) * Float.SIZE_BYTES
                val dst = fullPlane + ((dest.top + ry) * outputWidth + dest.left) * Float.SIZE_BYTES
                System.arraycopy(tileOutput, src, buffer, dst, copyBytes)
            }
        }
    }

    override fun invalidate() {
        if (!sealed) {
            sealed = true
            buffer = ByteArray(0)
        }
    }

    override fun finish(): ByteArray {
        check(!sealed) { "sink was invalidated; no assembled result is available" }
        sealed = true
        return buffer
    }

    companion object {
        /** N4 bounded-sink safety ceiling (256 MiB). This is NOT the production limit. */
        const val MAX_BOUNDED_OUTPUT_BYTES: Long = 256L * 1024L * 1024L
    }
}