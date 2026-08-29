package com.projectnuke.keplerstudio.editor

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Phase N5 — bounded-memory, file-backed full-image execution.
 *
 * Runs the SAME [TilePlanner]/[TilePlacement]/[ExynosUpscaleSession] geometry and native
 * ENN lifecycle as N4, but:
 *  - reads tiles from a bounded [TileInputSource] (never a full-image FP32 duplicate),
 *  - reuses ONE input tile buffer and ONE output tile buffer for the whole operation,
 *  - writes retained tiles to a file-backed RGB8 artifact via [FileBackedRgb8TileSink],
 *  - admits storage headroom through the existing [StoragePressure] policy BEFORE any NPU
 *    work,
 *  - keeps only bounded progress/diagnostic state (no unbounded [TileRunRecord] list),
 *  - settles the staging artifact on cancellation/staleness/any failure — never publishes a
 *    partial artifact.
 *
 * Backpressure is natural: the orchestrator is sequential, so at most one completed tile is
 * ever waiting for sink consumption, and the two reusable buffers are overwritten only after
 * the sink has fully consumed the previous tile.
 */
internal class TileFileBackedUpscaler(
    private val session: ExynosUpscaleSession,
    private val context: Context,
    private val planner: (sourceWidth: Int, sourceHeight: Int) -> TilePlanResult =
        { w, h -> TilePlanner.plan(w, h) },
    private val operationTokenFactory: () -> String = { defaultOperationToken() },
    private val sinkIoFactory: () -> FileBackedSinkIo = { RealFileBackedSinkIo() },
    private val sinkConfigurator: (FileBackedRgb8TileSink) -> Unit = {},
) {

    suspend fun upscaleToFile(
        source: TileInputSource,
        destinationFile: File,
        operationContext: ModelOperationContext,
        attemptLabel: String? = null,
        observer: TileRunObserver? = null,
    ): FileBackedUpscaleResult {
        val sourceWidth = source.sourceWidth
        val sourceHeight = source.sourceHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return FileBackedUpscaleResult.InvalidDimensions(
                sourceWidth,
                sourceHeight,
                "source dimensions must be positive",
            )
        }

        val planResult = planner(sourceWidth, sourceHeight)
        if (planResult is TilePlanResult.UnsupportedSourceSize) {
            return FileBackedUpscaleResult.UnsupportedSourceSize(sourceWidth, sourceHeight)
        }
        val plan = (planResult as TilePlanResult.Planned).plan

        val geometry =
            computeRgb8OutputGeometry(plan.outputWidth, plan.outputHeight)
        if (geometry is Rgb8SizeVerdict.Invalid) {
            return FileBackedUpscaleResult.InvalidDimensions(
                plan.outputWidth,
                plan.outputHeight,
                geometry.detail,
            )
        }
        val requiredBytes = (geometry as Rgb8SizeVerdict.Valid).requiredBytes

        // Storage admission BEFORE any physical NPU work, reusing the production policy.
        val volumeFile = destinationFile.absoluteFile.parentFile ?: context.filesDir
        var denied = false
        StoragePressure.controller.ensureWriteHeadroom(
            context = context,
            targetVolumeFile = volumeFile,
            requiredBytes = requiredBytes,
            onInsufficient = { denied = true },
            action = { },
        )
        coroutineContext.ensureActive()
        if (denied) {
            return FileBackedUpscaleResult.StorageInsufficient(
                "cannot admit $requiredBytes bytes of write headroom at ${volumeFile.absolutePath}",
            )
        }

        val sink =
            try {
                FileBackedRgb8TileSink(
                    destinationFile = destinationFile,
                    outputWidth = plan.outputWidth,
                    outputHeight = plan.outputHeight,
                    operationToken = operationTokenFactory(),
                    io = sinkIoFactory(),
                ).also(sinkConfigurator)
            } catch (t: Throwable) {
                return FileBackedUpscaleResult.Failure(
                    completedTiles = 0,
                    failedTileIndex = -1,
                    reason = TileFailureReason.AssemblyFailed,
                    detail = t.message ?: t.javaClass.simpleName,
                )
            }

        val inputBuffer = ByteArray(ExynosUpscaleSession.INPUT_BYTES)
        val outputBuffer = ByteArray(ExynosUpscaleSession.OUTPUT_BYTES)

        var completedTiles = 0
        var aggregateInferenceNanos = 0L
        var aggregatePrepNanos = 0L
        var aggregateSinkNanos = 0L
        var lastRecord: TileRunRecord? = null

        try {
            for (tile in plan.tiles) {
                if (operationContext.isCancelled()) {
                    sink.invalidate()
                    return FileBackedUpscaleResult.Cancelled(completedTiles)
                }
                if (!operationContext.isCurrent(
                        operationContext.operationToken,
                        operationContext.documentGeneration,
                    )
                ) {
                    sink.invalidate()
                    return FileBackedUpscaleResult.Stale(completedTiles)
                }
                coroutineContext.ensureActive()

                val prepStart = System.nanoTime()
                try {
                    source.fillChwTile(tile.source.left, tile.source.top, inputBuffer)
                } catch (cancelled: CancellationException) {
                    sink.invalidate()
                    throw cancelled
                } catch (t: Throwable) {
                    sink.invalidate()
                    return FileBackedUpscaleResult.Failure(
                        completedTiles = completedTiles,
                        failedTileIndex = tile.index,
                        reason = TileFailureReason.SourceReadFailed,
                        detail = t.message ?: t.javaClass.simpleName,
                    )
                }
                val prepNanos = System.nanoTime() - prepStart

                val started = System.nanoTime()
                val raw =
                    session.runRawFp32ChwInto(
                        inputBuffer,
                        outputBuffer,
                        operationContext,
                        if (attemptLabel == null) "n5-tile-${tile.index}" else "$attemptLabel/tile-${tile.index}",
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
                    lastRecord = record
                    return FileBackedUpscaleResult.Failure(
                        completedTiles = completedTiles,
                        failedTileIndex = tile.index,
                        reason = rawToFailureReason(raw),
                        detail =
                            buildString {
                                append("tile ${tile.index} raw run incomplete")
                                raw.throwableStage?.let { append("; threw at $it") }
                                raw.throwableDetail?.let { append(": $it") }
                            },
                    )
                }

                val sinkStart = System.nanoTime()
                try {
                    sink.writeTile(tile.outputCrop, tile.dest, outputBuffer)
                } catch (cancelled: CancellationException) {
                    sink.invalidate()
                    throw cancelled
                } catch (t: Throwable) {
                    sink.invalidate()
                    return FileBackedUpscaleResult.Failure(
                        completedTiles = completedTiles,
                        failedTileIndex = tile.index,
                        reason = TileFailureReason.AssemblyFailed,
                        detail = t.message ?: t.javaClass.simpleName,
                    )
                }
                val sinkNanos = System.nanoTime() - sinkStart

                completedTiles++
                aggregateInferenceNanos += durationNanos
                aggregatePrepNanos += prepNanos
                aggregateSinkNanos += sinkNanos
                lastRecord = record
                observer?.onTileRun(record)
            }
        } catch (cancelled: CancellationException) {
            sink.invalidate()
            throw cancelled
        } catch (t: Throwable) {
            sink.invalidate()
            return FileBackedUpscaleResult.Failure(
                completedTiles = completedTiles,
                failedTileIndex =
                    if (completedTiles < plan.tiles.size) plan.tiles[completedTiles].index else -1,
                reason = TileFailureReason.AssemblyFailed,
                detail = t.message ?: t.javaClass.simpleName,
            )
        }

        // Cancellation/staleness immediately after the LAST tile but BEFORE publication must
        // not publish success.
        if (operationContext.isCancelled()) {
            sink.invalidate()
            return FileBackedUpscaleResult.Cancelled(completedTiles)
        }
        if (!operationContext.isCurrent(
                operationContext.operationToken,
                operationContext.documentGeneration,
            )
        ) {
            sink.invalidate()
            return FileBackedUpscaleResult.Stale(completedTiles)
        }
        coroutineContext.ensureActive()

        val artifact =
            try {
                sink.finish()
            } catch (cancelled: CancellationException) {
                sink.invalidate()
                throw cancelled
            } catch (t: Throwable) {
                sink.invalidate()
                return FileBackedUpscaleResult.Failure(
                    completedTiles = completedTiles,
                    failedTileIndex = -1,
                    reason = TileFailureReason.ArtifactPublishFailed,
                    detail = t.message ?: t.javaClass.simpleName,
                )
            }

        return FileBackedUpscaleResult.Success(
            artifact = artifact,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            tileCount = plan.tileCount,
            completedTiles = completedTiles,
            summary =
                TileRunSummary(
                    totalTiles = plan.tileCount,
                    completedTiles = completedTiles,
                    aggregateInferenceNanos = aggregateInferenceNanos,
                    aggregatePrepNanos = aggregatePrepNanos,
                    aggregateSinkNanos = aggregateSinkNanos,
                    lastTile = lastRecord,
                ),
        )
    }

    private fun rawToFailureReason(raw: ExynosRawRunResult): TileFailureReason =
        when {
            raw.threw -> TileFailureReason.NativeThrew
            raw.h2dStatus != EnnStatus.SUCCESS -> TileFailureReason.H2dFailed
            raw.executeStatus != EnnStatus.SUCCESS -> TileFailureReason.ExecuteFailed
            raw.d2hStatus != EnnStatus.SUCCESS -> TileFailureReason.D2hFailed
            raw.outputBytes == null -> TileFailureReason.DecodeFailed
            else -> TileFailureReason.DecodeFailed
        }

    companion object {
        private val OPERATION_TOKEN_SEQUENCE = AtomicLong(0)

        private fun defaultOperationToken(): String =
            "n5-${System.nanoTime()}-${OPERATION_TOKEN_SEQUENCE.incrementAndGet()}"
    }
}

/** Optional per-tile observation seam; the engine itself never accumulates an unbounded list. */
internal fun interface TileRunObserver {
    fun onTileRun(record: TileRunRecord)
}

/** Bounded production progress/diagnostic summary (replaces N4's unbounded record list). */
internal data class TileRunSummary(
    val totalTiles: Int,
    val completedTiles: Int,
    val aggregateInferenceNanos: Long,
    val aggregatePrepNanos: Long,
    val aggregateSinkNanos: Long,
    val lastTile: TileRunRecord?,
)

internal sealed interface FileBackedUpscaleResult {
    data class Success(
        val artifact: FileBackedRgb8Artifact,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val tileCount: Int,
        val completedTiles: Int,
        val summary: TileRunSummary,
    ) : FileBackedUpscaleResult

    data class UnsupportedSourceSize(val width: Int, val height: Int) : FileBackedUpscaleResult
    data class InvalidDimensions(val width: Int, val height: Int, val detail: String) :
        FileBackedUpscaleResult

    data class StorageInsufficient(val detail: String) : FileBackedUpscaleResult
    data class Cancelled(val completedTiles: Int) : FileBackedUpscaleResult
    data class Stale(val completedTiles: Int) : FileBackedUpscaleResult

    data class Failure(
        val completedTiles: Int,
        val failedTileIndex: Int,
        val reason: TileFailureReason,
        val detail: String,
    ) : FileBackedUpscaleResult
}

/** Overflow-safe, Long-based RGB8 output size computation. */
internal sealed interface Rgb8SizeVerdict {
    data class Valid(val outputWidth: Int, val outputHeight: Int, val requiredBytes: Long) :
        Rgb8SizeVerdict

    data class Invalid(val detail: String) : Rgb8SizeVerdict
}

internal fun computeRgb8OutputGeometry(outputWidth: Int, outputHeight: Int): Rgb8SizeVerdict {
    if (outputWidth <= 0 || outputHeight <= 0) {
        return Rgb8SizeVerdict.Invalid("output dimensions must be positive")
    }
    val w = outputWidth.toLong()
    val h = outputHeight.toLong()
    if (w * h > Long.MAX_VALUE / 3L) {
        return Rgb8SizeVerdict.Invalid("output pixel count overflows 64-bit byte size")
    }
    val rowStride = w * RGB8_CHANNELS
    if (rowStride > Int.MAX_VALUE) {
        return Rgb8SizeVerdict.Invalid("output row stride overflows Int")
    }
    val requiredBytes = w * h * RGB8_CHANNELS
    return Rgb8SizeVerdict.Valid(outputWidth, outputHeight, requiredBytes)
}

private const val RGB8_CHANNELS = 3L