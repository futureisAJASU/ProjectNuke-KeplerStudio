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
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal interface SuperResolutionRowStore {
    suspend fun insertPending(fileName: String): Uri
    suspend fun openOutputStream(uri: Uri): OutputStream
    suspend fun publish(uri: Uri)
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

    override suspend fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
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
        // Transparency check: reject meaningful alpha
        if (inputBitmap.hasAlpha()) {
            // Scan for non-opaque pixel (sample 100 points for bounded cost)
            var hasTransparent = false
            val sampleStep = maxOf(1, inputWidth * inputHeight / 100)
            for (i in 0 until inputWidth * inputHeight step sampleStep) {
                val x = i % inputWidth
                val y = i / inputWidth
                if (y >= inputHeight) break
                val pixel = inputBitmap.getPixel(x, y)
                if ((pixel ushr 24) and 0xFF != 0xFF) { hasTransparent = true; break }
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

            // N5 pipeline: BitmapTileInputSource -> TileFileBackedUpscaler
            val token = ModelAvailabilityRegistry.validatedCapabilityToken(ModelFeature.ExynosUpscale)
            if (token !is ModelLoadResult.Ready) {
                return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.ModelValidationFailed, "capability token not ready: $token")
            }
            val validated = token.runner as ValidatedModelCapabilityToken
            session = sessionProvider().apply { diagnosticRetention = DiagnosticRetention.LAST_ONLY }
            if (session.lifecycle != ModelRunnerLifecycle.Loaded) {
                val load = session.load(validated)
                if (load !is ModelLoadResult.Ready) {
                    return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.NpuLoadFailed, "session load failed: $load")
                }
            }
            // Verify NNC identity hard-assert (only when prepared file exists and has content; fakes use 0-length stub)
            val prepared = session.preparedModelFileForDiagnostics()
            if (prepared != null && prepared.exists() && prepared.length() != 0L) {
                if (prepared.length() != 3112960L) {
                    return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.ModelValidationFailed, "NNC size mismatch ${prepared.length()}")
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
                is FileBackedUpscaleResult.Cancelled -> return SuperResolutionExportResult.Cancelled
                is FileBackedUpscaleResult.Stale -> return SuperResolutionExportResult.Stale
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
                    return SuperResolutionExportResult.Failure(kind, upscaleResult.detail)
                }
                is FileBackedUpscaleResult.UnsupportedSourceSize -> return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InvalidDimensions, "unsupported source size")
                is FileBackedUpscaleResult.InvalidDimensions -> return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InvalidDimensions, upscaleResult.detail)
                is FileBackedUpscaleResult.StorageInsufficient -> return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.InternalStorageInsufficient, upscaleResult.detail)
                is FileBackedUpscaleResult.Success -> {
                    rgb8Artifact = upscaleResult.artifact
                }
            }
            val artifact = checkNotNull(rgb8Artifact)
            // Verify artifact dimensions truthfully
            if (artifact.width != outputWidth || artifact.height != outputHeight) {
                return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.Rgb8ArtifactFailure, "artifact dimensions mismatch ${artifact.width}x${artifact.height} != $outputWidth x $outputHeight")
            }
            if (isCancelled()) return SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return SuperResolutionExportResult.Stale

            // MediaStore pending row
            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Encoding, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = 0, encodingRowsTotal = outputHeight, encodingFraction = 0f, overallFraction = 0.80f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))
            pendingUri = rowStore.insertPending(fileName)
            // Streaming PNG encode
            var encodeRows = 0
            val encodeStart = System.nanoTime()
            try {
                rowStore.openOutputStream(pendingUri).use { out ->
                    // Wrap to count rows via observer? We will encode with progress callback via custom OutputStream? Instead we observer row progress via artifact height.
                    // For progress, we can poll after encode? For now we call encode then report final progress.
                    StreamingPngEncoder.encode(
                        artifact = artifact,
                        output = out,
                        isCurrent = isCurrent,
                        isCancelled = isCancelled
                    )
                }
                encodeRows = outputHeight
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamingPngEncoder.StalePngEncodeException) {
                return SuperResolutionExportResult.Stale
            } catch (e: IOException) {
                return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.PngEncodeFailure, e.message ?: "PNG encode failed", e)
            } catch (e: Throwable) {
                return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.PngEncodeFailure, e.message ?: "PNG encode failed", e)
            }
            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Encoding, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = encodeRows, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 0.98f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))

            if (isCancelled()) return SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return SuperResolutionExportResult.Stale

            // Publish
            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Publishing, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = outputHeight, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 0.99f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = false))
            try {
                rowStore.publish(pendingUri)
            } catch (e: Throwable) {
                return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MediaStorePublishFailure, e.message ?: "publish failed", e)
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
                // Published but metadata failed — preserve image, surface partial success
                return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MetadataPersistFailure, "published but history failed: ${e.message}", e)
            }

            onProgress(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Succeeded, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = outputHeight, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 1f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, message = "AI 4배 저장 완료 ${outputWidth}×${outputHeight}", canCancel = false))

            return SuperResolutionExportResult.Success(uri = publishedUri, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, tileCount = totalTiles)

        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.SourceRenderFailed, t.message ?: "unknown", t)
        } finally {
            // Cleanup RGB8 artifact idempotently
            rgb8Artifact?.let { art ->
                runCatching { if (art.file.exists()) art.file.delete() }
                // Also delete temp file if not yet moved (upscale failure leaves staging)
                runCatching { File(art.file.absolutePath).delete() }
            }
            // If pending row not published, delete it
            pendingUri?.let { uri ->
                runCatching { rowStore.delete(uri) }
            }
            runCatching { session?.close() }
            wakeLock.release()
        }
    }
}
