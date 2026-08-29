package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CancellationException

/**
 * Phase N4 — sequential tiled NPU orchestration (bounded correctness version).
 *
 * Runs a [TilePlan] over an already-loaded [ExynosUpscaleSession] (the SAME session
 * throughout; no reload per tile, no new ENN runtime owner, no Quantized path), one
 * 128x128 source tile per native run, using the raw FP32 CHW seam
 * ([ExynosUpscaleSession.runRawFp32Chw]) so the assembled output is byte-exact with no
 * repeated Bitmap conversion.
 *
 * Cancellation/staleness boundaries:
 *  - before EVERY tile the orchestrator re-checks [ModelOperationContext] cancellation
 *    and currency (the authoritative native-side checks are additionally enforced inside
 *    each [ExynosUpscaleSession] run);
 *  - a tile already inside EnnExecuteModel is NOT magically cancellable — the boundary is
 *    between tiles and before each native execute (N4.10);
 *  - after a cancellation/staleness/failure the bounded sink is invalidated and NO partial
 *    image is ever published as a success.
 *
 * This is NOT the full-resolution production/export pipeline (N4.18): it assembles a
 * bounded in-memory buffer sized for the correctness corpus, never a full 12MP x4 output.
 */
internal class TileInferenceOrchestrator(
    private val session: ExynosUpscaleSession,
    private val planner: (sourceWidth: Int, sourceHeight: Int) -> TilePlanResult =
        { w, h -> TilePlanner.plan(w, h) },
    private val sinkFactory: (outputWidth: Int, outputHeight: Int) -> TileOutputSink =
        { w, h -> BoundedMemoryTileSink(w, h) },
) {

    suspend fun upscaleRaw(
        source: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        operationContext: ModelOperationContext,
        attemptLabel: String? = null,
    ): TiledUpscaleResult {
        val expected = ExynosUpscaleSession.INPUT_CHANNELS.toLong() * sourceWidth * sourceHeight * Float.SIZE_BYTES
        if (sourceWidth <= 0 || sourceHeight <= 0 || source.size.toLong() != expected) {
            return TiledUpscaleResult.SourceSizeMismatch(source.size, expected)
        }

        val planResult = planner(sourceWidth, sourceHeight)
        if (planResult is TilePlanResult.UnsupportedSourceSize) {
            return TiledUpscaleResult.UnsupportedSourceSize(sourceWidth, sourceHeight)
        }
        val plan = (planResult as TilePlanResult.Planned).plan

        val sink =
            try {
                sinkFactory(plan.outputWidth, plan.outputHeight)
            } catch (t: Throwable) {
                return TiledUpscaleResult.Failure(
                    completedTiles = 0,
                    failedTileIndex = -1,
                    reason = TileFailureReason.AssemblyFailed,
                    detail = t.message ?: t.javaClass.simpleName,
                )
            }

        val records = mutableListOf<TileRunRecord>()
        try {
            for (tile in plan.tiles) {
                // Before EVERY new tile: cancellation + currency. If either trips after
                // tile N, tile N+1 never begins.
                if (operationContext.isCancelled()) {
                    sink.invalidate()
                    return TiledUpscaleResult.Cancelled(completedTiles = records.size)
                }
                if (!operationContext.isCurrent(operationContext.operationToken, operationContext.documentGeneration)) {
                    sink.invalidate()
                    return TiledUpscaleResult.Stale(completedTiles = records.size)
                }

                val tileInput = extractChwSubTile(source, sourceWidth, sourceHeight, tile.source.left, tile.source.top)
                val started = System.nanoTime()
                val raw = session.runRawFp32Chw(
                    tileInput,
                    operationContext,
                    if (attemptLabel == null) "n4-tile-${tile.index}" else "$attemptLabel/tile-${tile.index}",
                )
                val durationNanos = System.nanoTime() - started

                val record =
                    TileRunRecord(
                        index = tile.index,
                        source = tile.source,
                        dest = tile.dest,
                        h2dStatus = raw.h2dStatus,
                        executeStatus = raw.executeStatus,
                        d2hStatus = raw.d2hStatus,
                        durationNanos = durationNanos,
                    )

                if (!raw.succeeded) {
                    sink.invalidate()
                    val reason = rawTooFailureReason(raw)
                    return TiledUpscaleResult.Failure(
                        completedTiles = records.size,
                        failedTileIndex = tile.index,
                        reason = reason,
                        detail =
                            buildString {
                                append("tile ${tile.index} raw run incomplete")
                                raw.throwableStage?.let { append("; threw at $it") }
                                raw.throwableDetail?.let { append(": $it") }
                            },
                    )
                }
                val tileOutput = checkNotNull(raw.outputBytes)
                try {
                    sink.writeTile(tile.outputCrop, tile.dest, tileOutput)
                } catch (t: Throwable) {
                    sink.invalidate()
                    return TiledUpscaleResult.Failure(
                        completedTiles = records.size,
                        failedTileIndex = tile.index,
                        reason = TileFailureReason.AssemblyFailed,
                        detail = t.message ?: t.javaClass.simpleName,
                    )
                }
                records += record
            }
        } catch (cancelled: CancellationException) {
            sink.invalidate()
            throw cancelled
        } catch (t: Throwable) {
            sink.invalidate()
            return TiledUpscaleResult.Failure(
                completedTiles = records.size,
                failedTileIndex =
                    if (records.size < plan.tiles.size) plan.tiles[records.size].index else -1,
                reason = TileFailureReason.AssemblyFailed,
                detail = t.message ?: t.javaClass.simpleName,
            )
        }

        val outputBytes = sink.finish()
        return TiledUpscaleResult.Success(
            outputBytes = outputBytes,
            outputWidth = plan.outputWidth,
            outputHeight = plan.outputHeight,
            tileCount = plan.tileCount,
            completedTiles = records.size,
            tiles = records,
        )
    }

    private fun rawTooFailureReason(raw: ExynosRawRunResult): TileFailureReason =
        when {
            raw.threw -> TileFailureReason.NativeThrew
            raw.h2dStatus != EnnStatus.SUCCESS -> TileFailureReason.H2dFailed
            raw.executeStatus != EnnStatus.SUCCESS -> TileFailureReason.ExecuteFailed
            raw.d2hStatus != EnnStatus.SUCCESS -> TileFailureReason.D2hFailed
            raw.outputBytes == null -> TileFailureReason.DecodeFailed
            else -> TileFailureReason.DecodeFailed
        }

    companion object {
        /**
         * Copy the 128x128 CHW FP32 sub-tile at ([sx],[sy]) out of a CHW FP32 full image
         * of `channels x sourceWidth x sourceHeight` little-endian floats. Byte-exact.
         */
        internal fun extractChwSubTile(
            source: ByteArray,
            sourceWidth: Int,
            sourceHeight: Int,
            sx: Int,
            sy: Int,
        ): ByteArray {
            val channels = ExynosUpscaleSession.INPUT_CHANNELS
            val tileSize = TilePlanner.TILE_SIZE
            val out = ByteArray(channels * tileSize * tileSize * Float.SIZE_BYTES)
            val rowBytes = tileSize * Float.SIZE_BYTES
            for (c in 0 until channels) {
                val srcPlane = c * sourceWidth * sourceHeight * Float.SIZE_BYTES
                val dstPlane = c * tileSize * tileSize * Float.SIZE_BYTES
                for (ty in 0 until tileSize) {
                    val srcRow = srcPlane + ((sy + ty) * sourceWidth + sx) * Float.SIZE_BYTES
                    val dstRow = dstPlane + ty * rowBytes
                    System.arraycopy(source, srcRow, out, dstRow, rowBytes)
                }
            }
            return out
        }
    }
}

internal enum class TileFailureReason {
    H2dFailed,
    ExecuteFailed,
    D2hFailed,
    NativeThrew,
    DecodeFailed,
    AssemblyFailed,
}

internal data class TileRunRecord(
    val index: Int,
    val source: Rect,
    val dest: Rect,
    val h2dStatus: Int?,
    val executeStatus: Int?,
    val d2hStatus: Int?,
    val durationNanos: Long,
)

internal sealed interface TiledUpscaleResult {
    data class Success(
        val outputBytes: ByteArray,
        val outputWidth: Int,
        val outputHeight: Int,
        val tileCount: Int,
        val completedTiles: Int,
        val tiles: List<TileRunRecord>,
    ) : TiledUpscaleResult

    data class UnsupportedSourceSize(val width: Int, val height: Int) : TiledUpscaleResult
    data class SourceSizeMismatch(val actualBytes: Int, val expectedBytes: Long) : TiledUpscaleResult
    data class Cancelled(val completedTiles: Int) : TiledUpscaleResult
    data class Stale(val completedTiles: Int) : TiledUpscaleResult

    data class Failure(
        val completedTiles: Int,
        val failedTileIndex: Int,
        val reason: TileFailureReason,
        val detail: String,
    ) : TiledUpscaleResult
}