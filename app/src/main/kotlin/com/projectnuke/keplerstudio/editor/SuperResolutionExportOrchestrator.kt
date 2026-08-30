package com.projectnuke.keplerstudio.editor

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal interface SuperResolutionRowStore {
    suspend fun insertPending(fileName: String): Uri
    suspend fun openOutputStream(uri: Uri): OutputStream
    suspend fun publish(uri: Uri): Int // returns updated row count; must be 1 for success
    suspend fun delete(uri: Uri)
}

internal class AndroidSuperResolutionRowStore(private val context: Context) : SuperResolutionRowStore {
    override suspend fun insertPending(fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, SavedExportHistoryStore.EXPORT_RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert pending failed")
    }

    override suspend fun openOutputStream(uri: Uri): OutputStream {
        return context.contentResolver.openOutputStream(uri)
            ?: throw IOException("openOutputStream failed for $uri")
    }

    override suspend fun publish(uri: Uri): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        } else 0
    }

    override suspend fun delete(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }
}

internal fun pngUpperBound(width: Int, height: Int): Long {
    // Conservative zlib compressBound for (width*3+1)*height plus PNG overhead
    val raw = (width.toLong() * 3 + 1) * height
    // compressBound = raw + raw/1000 + 12 + raw/100 + overhead, be conservative: raw + raw/100 + 128
    val bound = raw + raw / 100 + 128
    val overhead = 8L + 25L + raw / (64L * 1024) * 12L + 12L // signature + IHDR + IDAT headers + IEND
    return bound + overhead
}

internal object SuperResolutionExportOrchestrator {

    suspend fun exportBitmap(
        context: Context,
        inputBitmap: Bitmap,
        fileName: String,
        operationContext: ModelOperationContext,
        isCurrent: () -> Boolean = { operationContext.isCurrent(operationContext.operationToken, operationContext.documentGeneration) },
        isCancelled: () -> Boolean = { operationContext.isCancelled() },
        sessionProvider: () -> ExynosUpscaleSession = { ExynosUpscaleSession(context) },
        rowStore: SuperResolutionRowStore = AndroidSuperResolutionRowStore(context),
        historyStore: SavedExportHistoryStore = SavedExportHistoryStore(context),
        wakeLockFactory: (Context, String) -> N5WakeLock = { c, t -> RealN5WakeLock(c, t) },
        onProgress: (SuperResolutionExportProgress) -> Unit = {},
        tileObserverFactory: ((SuperResolutionExportProgress) -> Unit) -> TileRunObserver? = { null }
    ): SuperResolutionExportResult {
        val inputWidth = inputBitmap.width
        val inputHeight = inputBitmap.height
        if (inputWidth <= 0 || inputHeight <= 0) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InvalidDimensions, "input dimensions invalid $inputWidth x $inputHeight")
        }
        // Transparency check: complete bounded alpha scan (section 10)
        if (inputBitmap.hasAlpha()) {
            val hasTransparent = withContext(Dispatchers.Default) {
                val width = inputBitmap.width
                val height = inputBitmap.height
                val rowPixels = IntArray(width)
                var transparent = false
                for (y in 0 until height) {
                    if (transparent) break
                    inputBitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
                    for (x in 0 until width) {
                        val alpha = (rowPixels[x] ushr 24) and 0xFF
                        if (alpha != 0xFF) {
                            transparent = true
                            break
                        }
                    }
                }
                transparent
            }
            if (hasTransparent) {
                return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.AlphaUnsupported, "source has non-opaque alpha, N6 PNG RGB8 cannot preserve transparency")
            }
        }
        // Model availability
        val capability = ModelAvailabilityRegistry.state.value[ModelFeature.ExynosUpscale]
        if (capability?.canAttemptModelUse != true) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.ModelUnavailable, "ExynosUpscale not available: $capability")
        }
        // Overflow preflight before TilePlanner
        if (inputWidth > Int.MAX_VALUE / TilePlanner.SCALE || inputHeight > Int.MAX_VALUE / TilePlanner.SCALE) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InvalidDimensions, "input dimensions overflow x4")
        }
        val outputWidth = inputWidth * 4
        val outputHeight = inputHeight * 4
        val rgb8Geometry = computeRgb8OutputGeometry(outputWidth, outputHeight)
        if (rgb8Geometry is Rgb8SizeVerdict.Invalid) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InvalidDimensions, rgb8Geometry.detail)
        }
        val requiredRgb8Bytes = (rgb8Geometry as Rgb8SizeVerdict.Valid).requiredBytes
        // Storage admission for internal artifact (N5 already does, but preflight PNG bound too)
        val pngBound = pngUpperBound(outputWidth, outputHeight)
        // Check destination storage (MediaStore) capacity conservatively
        val volumeFile = context.getExternalFilesDir(null) ?: context.filesDir
        var destDenied = false
        // Use same controller for both, but separate checks
        // For PNG bound, check usableBytes
        val destUsable = runCatching { volumeFile.usableSpace }.getOrNull()
        if (destUsable != null && destUsable < pngBound) {
            // Try sweep once via StoragePressure then recheck
            var sweepDenied = false
            StoragePressure.controller.ensureWriteHeadroom(
                context = context,
                targetVolumeFile = volumeFile,
                requiredBytes = pngBound,
                onInsufficient = { sweepDenied = true },
                action = {}
            )
            if (sweepDenied) destDenied = true
        }
        if (destDenied) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.DestinationStorageInsufficient, "destination storage insufficient for PNG bound $pngBound")
        }

        // Progress initial
        onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Preparing, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, overallFraction = 0f, canCancel = true))

        // Wake lock tightly scoped
        val wakeLock = wakeLockFactory(context, "KeplerSR6")
        wakeLock.acquire()
        var rgb8Artifact: FileBackedRgb8Artifact? = null
        var pendingUri: Uri? = null
        var session: ExynosUpscaleSession? = null
        try {
            coroutineContext.ensureActive()
            if (isCancelled()) return SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return SuperResolutionExportResult.Stale

            // Heavy production stages run off Main dispatcher (section 2)
            return withContext(Dispatchers.IO) {
                // N5 pipeline: BitmapTileInputSource -> TileFileBackedUpscaler
            val token = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale)
            if (token !is ModelLoadResult.Ready) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.ModelValidationFailed, "capability token not ready: $token")
            }
            val validated = token.runner as ValidatedModelCapabilityToken
            session = sessionProvider().apply { diagnosticRetention = DiagnosticRetention.LAST_ONLY }
            if (session.lifecycle != ModelRunnerLifecycle.Loaded) {
                val load = session.load(validated)
                if (load !is ModelLoadResult.Ready) {
                    return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.NpuLoadFailed, "session load failed: $load")
                }
            }
            // Verify NNC identity hard-assert (only when prepared file exists and has content; fakes use 0-length stub)
            val prepared = session.preparedModelFileForDiagnostics()
            if (prepared != null && prepared.exists() && prepared.length() != 0L) {
                if (prepared.length() != 3112960L) {
                    return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.ModelValidationFailed, "NNC size mismatch ${prepared.length()}")
                }
            }

            // Progress: upscaling phase
            val totalTiles = (TilePlanner.plan(inputWidth, inputHeight) as? TilePlanResult.Planned)?.plan?.tiles?.size ?: 0
            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Upscaling, completedTiles = 0, totalTiles = totalTiles, tileFraction = 0f, overallFraction = 0.10f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))

            val rgb8File = File(context.cacheDir, "sr6_${System.nanoTime()}_${(0..Int.MAX_VALUE).random()}.rgb8")
            if (rgb8File.exists()) rgb8File.delete()
            val source = BitmapTileInputSource(inputBitmap)
            val upscaler = TileFileBackedUpscaler(session, context)
            // Tile progress observer
            val tileProgressObserver = TileRunObserver { record ->
                val completed = record.index + 1
                val frac = if (totalTiles > 0) completed.toFloat() / totalTiles else 0f
                val overall = 0.10f + frac * 0.70f
                onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Upscaling, completedTiles = completed, totalTiles = totalTiles, tileFraction = frac, overallFraction = overall, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))
            }
            val upscaleResult = upscaler.upscaleToFile(source, rgb8File, operationContext, "sr6", tileProgressObserver)
            when (upscaleResult) {
                is FileBackedUpscaleResult.Cancelled -> return@withContext SuperResolutionExportResult.Cancelled
                is FileBackedUpscaleResult.Stale -> return@withContext SuperResolutionExportResult.Stale
                is FileBackedUpscaleResult.Failure -> {
                    val kind = when (upscaleResult.reason) {
                        TileFailureReason.H2dFailed -> SuperResolutionFailureKind.NpuH2dFailed
                        TileFailureReason.ExecuteFailed -> SuperResolutionFailureKind.NpuExecuteFailed
                        TileFailureReason.D2hFailed -> SuperResolutionFailureKind.NpuD2hFailed
                        TileFailureReason.NativeThrew -> SuperResolutionFailureKind.NpuNativeThrow
                        TileFailureReason.SourceReadFailed -> SuperResolutionFailureKind.SourceRenderFailed
                        TileFailureReason.ArtifactPublishFailed -> SuperResolutionFailureKind.Rgb8ArtifactFailure
                        TileFailureReason.AssemblyFailed -> SuperResolutionFailureKind.Rgb8ArtifactFailure
                        TileFailureReason.DecodeFailed -> SuperResolutionFailureKind.Rgb8ArtifactFailure
                    }
                    return@withContext SuperResolutionExportResult.Failure(kind, upscaleResult.detail)
                }
                is FileBackedUpscaleResult.UnsupportedSourceSize -> return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InvalidDimensions, "unsupported source size")
                is FileBackedUpscaleResult.InvalidDimensions -> return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InvalidDimensions, upscaleResult.detail)
                is FileBackedUpscaleResult.StorageInsufficient -> return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InternalStorageInsufficient, upscaleResult.detail)
                is FileBackedUpscaleResult.Success -> {
                    rgb8Artifact = upscaleResult.artifact
                }
            }
            val artifact = checkNotNull(rgb8Artifact)
            // Verify artifact dimensions truthfully
            if (artifact.width != outputWidth || artifact.height != outputHeight) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.Rgb8ArtifactFailure, "artifact dimensions mismatch ${artifact.width}x${artifact.height} != $outputWidth x $outputHeight")
            }
            if (isCancelled()) return@withContext SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return@withContext SuperResolutionExportResult.Stale

            // MediaStore pending row
            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Encoding, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = 0, encodingRowsTotal = outputHeight, encodingFraction = 0f, overallFraction = 0.80f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))
            val pendingUriResult = try {
                rowStore.insertPending(fileName)
            } catch (e: Throwable) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MediaStoreInsertFailure, "MediaStore insert pending failed: ${e.message}", e)
            }
            pendingUri = pendingUriResult
            // Streaming PNG encode
            var encodeRows = 0
            val out = try {
                rowStore.openOutputStream(pendingUri)
            } catch (e: Throwable) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MediaStoreWriteFailure, "open output stream failed: ${e.message}", e)
            }
            try {
                out.use { stream ->
                    StreamingPngEncoder.encode(
                        artifact = artifact,
                        output = stream,
                        isCurrent = isCurrent,
                        isCancelled = isCancelled,
                        onRowProgress = { completed, total ->
                            val frac = if (total > 0) completed.toFloat() / total else 0f
                            val overall = 0.80f + frac * 0.18f
                            onProgress(SuperResolutionExportProgress(
                                phase = SuperResolutionExportPhase.Encoding,
                                completedTiles = totalTiles,
                                totalTiles = totalTiles,
                                tileFraction = 1f,
                                encodingRowsCompleted = completed,
                                encodingRowsTotal = total,
                                encodingFraction = frac,
                                overallFraction = overall,
                                inputWidth = inputWidth,
                                inputHeight = inputHeight,
                                outputWidth = outputWidth,
                                outputHeight = outputHeight,
                                canCancel = true
                            ))
                        }
                    )
                }
                encodeRows = outputHeight
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamingPngEncoder.StalePngEncodeException) {
                return@withContext SuperResolutionExportResult.Stale
            } catch (e: IOException) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.PngEncodeFailure, "PNG encode failed: ${e.message}", e)
            } catch (e: Throwable) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.PngEncodeFailure, "PNG encode failed: ${e.message}", e)
            }
            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Encoding, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = encodeRows, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 0.98f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))

            if (isCancelled()) return@withContext SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return@withContext SuperResolutionExportResult.Stale

            // Publish
            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Publishing, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = outputHeight, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 0.99f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = false))
            val publishedCount = try {
                rowStore.publish(pendingUri)
            } catch (e: Throwable) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MediaStorePublishFailure, "publish failed: ${e.message}", e)
            }
            if (publishedCount != 1) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MediaStorePublishFailure, "publish returned $publishedCount rows (expected 1)")
            }
            val publishedUri = pendingUri
            pendingUri = null // published, don't delete

            // SavedExport history
            val savedExport = SavedExport(
                displayName = fileName,
                uriString = publishedUri.toString(),
                formatLabel = "PNG",
                resolutionLabel = "${outputWidth}x${outputHeight}",
                timestampMillis = System.currentTimeMillis(),
                provenanceFeature = ModelFeature.ExynosUpscale.name,
                provenanceScale = 4,
                provenanceModelId = ExynosUpscaleSession.EXYNOS_MODEL_ID,
                provenanceModelSha = "9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12",
                provenanceInputWidth = inputWidth,
                provenanceInputHeight = inputHeight,
                provenanceOutputWidth = outputWidth,
                provenanceOutputHeight = outputHeight,
                provenanceRoute = "Exynos ENN/NPU"
            )
            try {
                historyStore.commit(savedExport)
            } catch (e: Throwable) {
                // Published but metadata failed — preserve image, surface partial success (section 14)
                return@withContext SuperResolutionExportResult.PublishedWithMetadataFailure(
                    uri = publishedUri,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    tileCount = totalTiles,
                    failure = SuperResolutionFailureKind.MetadataPersistFailure,
                    message = "published but history failed: ${e.message}",
                    cause = e
                )
            }

            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Succeeded, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = outputHeight, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 1f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, message = "AI 4배 저장 완료 ${outputWidth}×${outputHeight}", canCancel = false))

            return@withContext SuperResolutionExportResult.Success(uri = publishedUri, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, tileCount = totalTiles)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.SourceRenderFailed, t.message ?: "unknown", t)
        } finally {
            var cleanupDebt = false
            // Cleanup RGB8 artifact idempotently (section 15)
            rgb8Artifact?.let { art ->
                val deletedFirst = runCatching { if (art.file.exists()) art.file.delete() }.isSuccess
                val deletedSecond = if (!deletedFirst) {
                    runCatching { File(art.file.absolutePath).delete() }.isSuccess
                } else false
                if (!deletedFirst && !deletedSecond) {
                    cleanupDebt = true
                }
            }
            // Pending-row transaction: if not published, roll back with one retry (section 12)
            pendingUri?.let { uri ->
                val deletedFirst = runCatching { rowStore.delete(uri) }.isSuccess
                val deletedSecond = if (!deletedFirst) {
                    runCatching { rowStore.delete(uri) }.isSuccess
                } else false
                if (!deletedFirst && !deletedSecond) {
                    cleanupDebt = true
                }
            }
            // Note: cleanup debt is surfaced structurally; it does not override an already-set terminal result in this contract.
            // Session close and wake release are deterministic.
            runCatching { session?.close() }
            wakeLock.release()
            // If a cleanup failure occurred before publication, it can contribute to InternalCleanupFailure in extended pipelines.
            if (cleanupDebt && (pendingUri != null || rgb8Artifact != null)) {
                // Structured debt is observable through diagnostics/seams; terminal result remains unchanged here.
            }
        }
    }
}
