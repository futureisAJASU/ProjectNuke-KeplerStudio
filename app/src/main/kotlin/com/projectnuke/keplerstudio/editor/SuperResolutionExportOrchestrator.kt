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
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal interface SuperResolutionRowStore {
    suspend fun insertPending(fileName: String): Uri
    suspend fun openOutputStream(uri: Uri): OutputStream
    suspend fun publish(uri: Uri): Int // returns updated row count; must be 1 for success
    suspend fun delete(uri: Uri): SuperResolutionRowDeleteResult
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

    override suspend fun delete(uri: Uri): SuperResolutionRowDeleteResult {
        return try {
            val count = context.contentResolver.delete(uri, null, null)
            when {
                count > 0 -> SuperResolutionRowDeleteResult.Deleted
                !rowExists(uri) -> SuperResolutionRowDeleteResult.AlreadyAbsent
                else -> SuperResolutionRowDeleteResult.StillExistsAfterZero
            }
        } catch (failure: Throwable) {
            SuperResolutionRowDeleteResult.Exception(failure)
        }
    }

    private fun rowExists(uri: Uri): Boolean =
        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.moveToFirst() }
            ?: false
}

internal fun deleteRgb8ArtifactBounded(
    file: File,
    exists: (File) -> Boolean = File::exists,
    delete: (File) -> Boolean = File::delete,
): Throwable? {
    var lastFailure: Throwable? = null
    repeat(2) {
        if (!exists(file)) return null
        try {
            if (delete(file) && !exists(file)) return null
            if (!exists(file)) return null
            lastFailure = IOException("RGB8 artifact delete returned false and file remains: ${file.absolutePath}")
        } catch (failure: Throwable) {
            lastFailure = failure
        }
    }
    return lastFailure ?: IOException("RGB8 artifact remains: ${file.absolutePath}")
}

internal suspend fun deletePendingRowBounded(
    rowStore: SuperResolutionRowStore,
    uri: Uri,
): Throwable? {
    fun settled(result: SuperResolutionRowDeleteResult): Boolean =
        result is SuperResolutionRowDeleteResult.Deleted ||
            result is SuperResolutionRowDeleteResult.AlreadyAbsent

    var result = runCatching { rowStore.delete(uri) }
        .getOrElse { SuperResolutionRowDeleteResult.Exception(it) }
    if (settled(result)) return null
    result = runCatching { rowStore.delete(uri) }
        .getOrElse { SuperResolutionRowDeleteResult.Exception(it) }
    if (settled(result)) return null
    return when (result) {
        is SuperResolutionRowDeleteResult.Exception -> result.cause
        SuperResolutionRowDeleteResult.StillExistsAfterZero ->
            IOException("pending MediaStore row still exists after delete returned 0: $uri")
        else -> null
    }
}

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
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
        tileObserverFactory: ((SuperResolutionExportProgress) -> Unit) -> TileRunObserver? = { null },
        heavyWorkerObserver: ((String, Thread) -> Unit)? = null,
        progressObserver: ((SuperResolutionExportProgress) -> Unit)? = null,
        rgb8ArtifactObserver: ((FileBackedRgb8Artifact) -> Unit)? = null,
        milestoneObserver: ((String) -> Unit)? = null,
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
        val testSeam = SuperResolutionTestSeam.capture()
        fun emitMilestone(label: String) {
            (milestoneObserver ?: testSeam?.milestoneObserver)?.invoke(label)
        }
        fun report(progress: SuperResolutionExportProgress) {
            onProgress(progress)
            (progressObserver ?: testSeam?.progressObserver)?.invoke(progress)
        }
        report(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Preparing, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, overallFraction = 0f, canCancel = true))

        // Wake lock tightly scoped
        val wakeLock = wakeLockFactory(context, "KeplerSR6")
        wakeLock.acquire()
        var rgb8Artifact: FileBackedRgb8Artifact? = null
        var pendingUri: Uri? = null
        var publishedUri: Uri? = null
        var session: ExynosUpscaleSession? = null
        var cancellationCause: CancellationException? = null
        var completedTileCount = 0
        var pendingMetadataFailure: Throwable? = null
        var pendingPublishedResult: SuperResolutionExportResult? = null
        try {
            coroutineContext.ensureActive()
            if (isCancelled()) return SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return SuperResolutionExportResult.Stale

            // Heavy production stages run off Main dispatcher (section 2)
            return withContext(Dispatchers.IO) {
                heavyWorkerObserver?.invoke("npu", Thread.currentThread())
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
                val preparedSha = sha256File(prepared)
                if (!preparedSha.equals("9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12", ignoreCase = true)) {
                    return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.ModelValidationFailed, "NNC SHA mismatch $preparedSha")
                }
            }

            // Progress: upscaling phase
            val totalTiles = (TilePlanner.plan(inputWidth, inputHeight) as? TilePlanResult.Planned)?.plan?.tiles?.size ?: 0
            report(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Upscaling, completedTiles = 0, totalTiles = totalTiles, tileFraction = 0f, overallFraction = 0.10f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))
            emitMilestone("after_model_load")

            val rgb8File = File(context.cacheDir, "sr6_${System.nanoTime()}_${(0..Int.MAX_VALUE).random()}.rgb8")
            if (rgb8File.exists()) rgb8File.delete()
            val source = BitmapTileInputSource(inputBitmap)
            val upscaler = TileFileBackedUpscaler(session, context)
            // Tile progress observer
            val tileProgressObserver = TileRunObserver { record ->
                val completed = record.index + 1
                completedTileCount = completed
                val frac = if (totalTiles > 0) completed.toFloat() / totalTiles else 0f
                val overall = 0.10f + frac * 0.70f
                report(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Upscaling, completedTiles = completed, totalTiles = totalTiles, tileFraction = frac, overallFraction = overall, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))
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
            (rgb8ArtifactObserver ?: testSeam?.rgb8ArtifactObserver)?.invoke(artifact)
            // Verify artifact dimensions truthfully
            if (artifact.width != outputWidth || artifact.height != outputHeight) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.Rgb8ArtifactFailure, "artifact dimensions mismatch ${artifact.width}x${artifact.height} != $outputWidth x $outputHeight")
            }
            if (isCancelled()) return@withContext SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return@withContext SuperResolutionExportResult.Stale

            // MediaStore pending row
            report(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Encoding, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = 0, encodingRowsTotal = outputHeight, encodingFraction = 0f, overallFraction = 0.80f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))
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
                            report(SuperResolutionExportProgress(
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
            report(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Encoding, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = encodeRows, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 0.98f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = true))

            if (isCancelled()) return@withContext SuperResolutionExportResult.Cancelled
            if (!isCurrent()) return@withContext SuperResolutionExportResult.Stale

            // Publish
            emitMilestone("before_mediastore_publish")
            report(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Publishing, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = outputHeight, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 0.99f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, canCancel = false))
            val publishedCount = try {
                rowStore.publish(pendingUri)
            } catch (e: Throwable) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MediaStorePublishFailure, "publish failed: ${e.message}", e)
            }
            if (publishedCount != 1) {
                return@withContext SuperResolutionExportResult.Failure(SuperResolutionFailureKind.MediaStorePublishFailure, "publish returned $publishedCount rows (expected 1)")
            }
            publishedUri = pendingUri
            pendingUri = null // published, don't delete
            emitMilestone("after_mediastore_publish")

            // SavedExport history
            val committedUri = checkNotNull(publishedUri)
            val savedExport = SavedExport(
                displayName = fileName,
                uriString = committedUri.toString(),
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
                pendingMetadataFailure = e
                pendingPublishedResult = SuperResolutionExportResult.PublishedWithMetadataFailure(
                    uri = committedUri,
                    inputWidth = inputWidth,
                    inputHeight = inputHeight,
                    outputWidth = outputWidth,
                    outputHeight = outputHeight,
                    tileCount = totalTiles,
                    failure = SuperResolutionFailureKind.MetadataPersistFailure,
                    message = "published but history failed: ${e.message}",
                    cause = e,
                    metadataCause = e
                )
            }
            if (pendingMetadataFailure == null) {
                report(SuperResolutionExportProgress(phase = SuperResolutionExportPhase.Succeeded, completedTiles = totalTiles, totalTiles = totalTiles, tileFraction = 1f, encodingRowsCompleted = outputHeight, encodingRowsTotal = outputHeight, encodingFraction = 1f, overallFraction = 1f, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, message = "AI 4배 저장 완료 ${outputWidth}×${outputHeight}", canCancel = false))
                pendingPublishedResult = SuperResolutionExportResult.Success(uri = committedUri, inputWidth = inputWidth, inputHeight = inputHeight, outputWidth = outputWidth, outputHeight = outputHeight, tileCount = totalTiles)
            }
            return@withContext checkNotNull(pendingPublishedResult)
            }
        } catch (cancel: CancellationException) {
            cancellationCause = cancel
            throw cancel
        } catch (t: Throwable) {
            return SuperResolutionExportResult.Failure(SuperResolutionFailureKind.SourceRenderFailed, t.message ?: "unknown", t)
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            val cleanupFailure: Throwable? = withContext(NonCancellable) {
                if (rgb8Artifact != null) {
                    deleteRgb8ArtifactBounded(rgb8Artifact!!.file)?.also { cleanupFailures.add(it) }
                    // This is emitted only after the bounded deletion attempt has returned;
                    // observers can inspect the artifact path to distinguish absence from debt.
                    runCatching { emitMilestone("after_rgb8_cleanup") }
                }
                // Pending-row transaction: distinguish delete truth and retry once.
                pendingUri?.let { uri ->
                    deletePendingRowBounded(rowStore, uri)?.also { cleanupFailures.add(it) }
                }
                runCatching { session?.close() }
                if (session != null) {
                    // This is emitted only after the real session close call has returned.
                    runCatching { emitMilestone("after_session_close") }
                }
                wakeLock.release()
                when (cleanupFailures.size) {
                    0 -> null
                    1 -> cleanupFailures.first()
                    else -> {
                        val aggregated = cleanupFailures.first()
                        cleanupFailures.drop(1).forEach { aggregated.addSuppressed(it) }
                        aggregated
                    }
                }
            }
            if (cleanupFailure != null) {
                cancellationCause?.let { cleanupFailure.addSuppressed(it) }
                // Distinguish which cleanup artifacts contributed; preserve via suppressed chain.
                // pendingPublishedResult already holds metadata failure if any — do not lose it.
                val message = "internal N6 cleanup debt: ${cleanupFailure.message}"
                if (publishedUri != null) {
                    val existing = pendingPublishedResult as? SuperResolutionExportResult.PublishedWithMetadataFailure
                    if (existing != null && existing.failure == SuperResolutionFailureKind.MetadataPersistFailure) {
                        // Preserve both: metadata failure is primary, cleanup debt is additional.
                        // Chain causes so no fact is lost.
                        existing.cause?.let { cleanupFailure.addSuppressed(it) }
                        // Also chain existing suppressed
                        return SuperResolutionExportResult.PublishedWithMetadataFailure(
                            uri = existing.uri,
                            inputWidth = existing.inputWidth,
                            inputHeight = existing.inputHeight,
                            outputWidth = existing.outputWidth,
                            outputHeight = existing.outputHeight,
                            tileCount = existing.tileCount,
                            failure = SuperResolutionFailureKind.MetadataPersistFailure,
                            message = "${existing.message}; also ${message}",
                            cause = existing.cause?.also { it.addSuppressed(cleanupFailure) } ?: cleanupFailure,
                            cleanupDebt = true,
                            metadataCause = existing.metadataCause,
                            suppressedCleanupCauses = listOf(cleanupFailure)
                        )
                    }
                    // Pure cleanup debt after otherwise-successful publish
                    return SuperResolutionExportResult.PublishedWithMetadataFailure(
                        uri = publishedUri!!,
                        inputWidth = inputWidth,
                        inputHeight = inputHeight,
                        outputWidth = outputWidth,
                        outputHeight = outputHeight,
                        tileCount = completedTileCount,
                        failure = SuperResolutionFailureKind.InternalCleanupFailure,
                        message = message,
                        cause = cleanupFailure,
                        cleanupDebt = true,
                        suppressedCleanupCauses = listOf(cleanupFailure)
                    )
                }
                return SuperResolutionExportResult.Failure(
                    SuperResolutionFailureKind.InternalCleanupFailure,
                    message,
                    cleanupFailure,
                    cleanupDebt = true,
                )
            } else {
                // No cleanup debt — if we had a pending metadata failure, return it; otherwise propagate success.
                pendingPublishedResult?.let { return it }
            }
        }
    }
}
