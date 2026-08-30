package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Phase N5 — bounded tile input source.
 *
 * Production-scale callers must NOT be forced to materialize a full-image FP32 CHW
 * ByteArray. [TileInputSource] fills exactly one 128x128 LR source tile (a
 * [TilePlacement.source] rectangle) into a caller-provided reusable buffer of exactly
 * [ExynosUpscaleSession.INPUT_BYTES] bytes.
 *
 * Exact accepted semantics, identical to the N3/N4 canonical path:
 *  - RGB channels normalized into [0,1] (`pixel / 255.0`, no mean/std offset),
 *  - CHW planar layout,
 *  - little-endian FP32.
 *
 * No full-image FP32 duplicate is ever produced by the production implementation.
 */
internal interface TileInputSource {
    val sourceWidth: Int
    val sourceHeight: Int

    /**
     * Fill [into] (exactly [ExynosUpscaleSession.INPUT_BYTES]) with the
     * [TilePlanner.TILE_SIZE]x[TilePlanner.TILE_SIZE] CHW FP32 source tile whose top-left LR
     * coordinate is ([sx],[sy]). Fails closed on buffer-size, dimension, or out-of-range
     * errors. Cancellation is checked around potentially expensive tile preparation.
     */
    suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray)
}

/**
 * Compatibility ByteArray-backed source for the existing N4 tests/path.
 *
 * Retains the full CHW FP32 source (bounded test/correctness scope only) and copies the
 * requested tile out of it byte-for-byte. This is the N4 contract: it never re-encodes, so
 * the produced tile is byte-identical to the canonical `extractChwSubTile` result.
 */
internal class ByteArrayTileInputSource(
    private val source: ByteArray,
    override val sourceWidth: Int,
    override val sourceHeight: Int,
) : TileInputSource {

    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions must be positive" }
        val expected =
            ExynosUpscaleSession.INPUT_CHANNELS.toLong() * sourceWidth * sourceHeight * Float.SIZE_BYTES
        require(source.size.toLong() == expected) {
            "source must be exactly $expected CHW FP32 bytes (got ${source.size})"
        }
    }

    override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) {
        currentCoroutineContext().ensureActive()
        require(into.size == ExynosUpscaleSession.INPUT_BYTES) {
            "reusable tile buffer must be exactly ${ExynosUpscaleSession.INPUT_BYTES} bytes"
        }
        require(sx >= 0 && sy >= 0) { "tile origin must be non-negative" }
        require(sx + TILE_SIZE <= sourceWidth && sy + TILE_SIZE <= sourceHeight) {
            "tile (${sx},${sy}) exceeds ${sourceWidth}x$sourceHeight source bounds"
        }
        val channels = ExynosUpscaleSession.INPUT_CHANNELS
        val rowBytes = TILE_SIZE * Float.SIZE_BYTES
        for (c in 0 until channels) {
            val srcPlane = c * sourceWidth * sourceHeight * Float.SIZE_BYTES
            val dstPlane = c * TILE_SIZE * TILE_SIZE * Float.SIZE_BYTES
            for (ty in 0 until TILE_SIZE) {
                val srcRow = srcPlane + ((sy + ty) * sourceWidth + sx) * Float.SIZE_BYTES
                val dstRow = dstPlane + ty * rowBytes
                System.arraycopy(source, srcRow, into, dstRow, rowBytes)
            }
        }
    }

    private companion object {
        const val TILE_SIZE = TilePlanner.TILE_SIZE
    }
}

/**
 * Production Bitmap-backed source: reads and converts ONLY the requested 128x128 source
 * region into the reusable tile buffer. It never converts the whole Bitmap into a
 * full-image FP32 array. A reusable 128x128 IntArray scratch is the only additional
 * bounded allocation; normalization and channel semantics exactly match the production
 * `preprocess` path (`channel / 255.0`, CHW, little-endian FP32).
 */
internal class BitmapTileInputSource(
    private val bitmap: Bitmap,
) : TileInputSource {

    override val sourceWidth: Int = bitmap.width
    override val sourceHeight: Int = bitmap.height

    private val tilePixelCount = TILE_SIZE * TILE_SIZE
    // Bounded scratch: 128x128 IntArray pixel buffer (64 KiB). No FloatArray scratch;
    // channel values are written directly into the caller-provided reusable FP32 buffer.
    private val tilePixels = IntArray(tilePixelCount)

    init {
        require(sourceWidth >= TILE_SIZE && sourceHeight >= TILE_SIZE) {
            "source must be at least ${TILE_SIZE}x$TILE_SIZE (got ${sourceWidth}x$sourceHeight)"
        }
    }

    override suspend fun fillChwTile(sx: Int, sy: Int, into: ByteArray) {
        currentCoroutineContext().ensureActive()
        require(into.size == ExynosUpscaleSession.INPUT_BYTES) {
            "reusable tile buffer must be exactly ${ExynosUpscaleSession.INPUT_BYTES} bytes"
        }
        require(sx >= 0 && sy >= 0) { "tile origin must be non-negative" }
        require(sx + TILE_SIZE <= sourceWidth && sy + TILE_SIZE <= sourceHeight) {
            "tile (${sx},${sy}) exceeds ${sourceWidth}x$sourceHeight source bounds"
        }
        bitmap.getPixels(tilePixels, 0, TILE_SIZE, sx, sy, TILE_SIZE, TILE_SIZE)
        currentCoroutineContext().ensureActive()
        // Write directly into the caller-provided reusable FP32 buffer: CHW planar,
        // channels normalized to [0,1] (pixel /255), little-endian FP32, no intermediate
        // FloatArray allocation.
        val floatBuffer = ByteBuffer.wrap(into).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val plane = tilePixelCount
        for (i in 0 until plane) {
            val color = tilePixels[i]
            floatBuffer.put(i, ((color shr 16) and 0xFF) / 255f)
            floatBuffer.put(plane + i, ((color shr 8) and 0xFF) / 255f)
            floatBuffer.put(2 * plane + i, (color and 0xFF) / 255f)
        }
    }

    private companion object {
        const val TILE_SIZE = TilePlanner.TILE_SIZE
    }
}