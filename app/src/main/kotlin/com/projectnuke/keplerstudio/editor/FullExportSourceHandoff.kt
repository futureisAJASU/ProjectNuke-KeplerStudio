package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The geometry contract for the existing Full export path.  This is deliberately
 * metadata-only: it never decodes pixels or allocates a Bitmap.
 */
internal data class FullExportSourceGeometry(val width: Int, val height: Int)

internal fun resolveFullExportSourceGeometry(
    state: EditorUiState,
    sourceBoundsReader: (String) -> FullExportSourceGeometry? = ::readFullExportSourceGeometry,
): FullExportSourceGeometry? {
    val sourceGeometry =
        if (state.baseBitmapDirty) {
            val base = state.originalPreviewBitmap ?: state.previewBitmap
            base?.let { FullExportSourceGeometry(it.width, it.height) }
        } else {
            if (state.sourcePath != null) {
                sourceBoundsReader(state.sourcePath)
            } else {
                state.previewBitmap?.let { FullExportSourceGeometry(it.width, it.height) }
            }
        } ?: return null
    val (width, height) =
        if (state.cropState == CropState()) {
            sourceGeometry.width to sourceGeometry.height
        } else {
            cropTransformedDimensions(sourceGeometry.width, sourceGeometry.height, state.cropState)
        }
    return FullExportSourceGeometry(width, height)
}

/**
 * Transfers only app-managed source families into operation ownership. Gallery and arbitrary
 * external paths intentionally receive no lease and remain owned by their original provider.
 */
internal fun acquireFullExportSourceOperationLease(sourcePath: String?): AutoCloseable? {
    val source = sourcePath?.let { runCatching { File(it).canonicalFile }.getOrNull() } ?: return null
    val parent = source.parentFile ?: return null
    return when {
        parent.name == "editor_sources" && RestoredWorkingSourceOwnership.isOwnedName(source.name) ->
            RestoredWorkingSourceOwnership.acquireOperation(source)
        parent.name == "current" && parent.parentFile?.name == "drafts" &&
            LegacyDraftSourceOwnership.isOwnedSourceName(source.name) ->
            LegacyDraftSourceOwnership.acquireOperation(source.absolutePath)
        else -> null
    }
}

private fun readFullExportSourceGeometry(sourcePath: String): FullExportSourceGeometry? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourcePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > FULL_EXPORT_MAX_SIDE) sample *= 2
    var width = ((bounds.outWidth.toLong() + sample - 1L) / sample).toInt()
    var height = ((bounds.outHeight.toLong() + sample - 1L) / sample).toInt()
    val orientation =
        runCatching {
            ExifInterface(sourcePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
    ) {
        val oldWidth = width
        width = height
        height = oldWidth
    }
    return FullExportSourceGeometry(width, height)
}

/**
 * Immutable export intent plus explicitly owned, bounded resources.  No ViewModel
 * or UI state object is retained by a service request.
 */
internal class FullExportSourceRequest private constructor(
    val sourcePath: String?,
    val baseBitmapDirty: Boolean,
    val baseWidth: Int?,
    val baseHeight: Int?,
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
    val params: EditParams,
    val engines: EngineSelection,
    val assignedDocumentEngine: CorrectionEngine,
    val visiblePreview: VisiblePreviewState,
    val presetLook: PresetColorLook?,
    val cropState: CropState,
    val quickEffects: List<ActiveQuickEffect>,
    private var ownedBaseBitmap: Bitmap?,
    private var ownedSelectionLayers: List<SelectionLayer>,
    private var sourceLease: AutoCloseable?,
) : AutoCloseable {
    private val baseOwnership = AtomicBoolean(true)
    private val layerOwnership = AtomicBoolean(true)

    internal fun takeBaseBitmap(): Bitmap? = synchronized(this) {
        if (!baseOwnership.compareAndSet(true, false)) null else ownedBaseBitmap.also { ownedBaseBitmap = null }
    }

    internal fun takeSelectionLayers(): List<SelectionLayer> = synchronized(this) {
        if (!layerOwnership.compareAndSet(true, false)) emptyList()
        else ownedSelectionLayers.also { ownedSelectionLayers = emptyList() }
    }

    internal fun ownedBaseBitmapForTest(): Bitmap? = synchronized(this) { ownedBaseBitmap }

    override fun close() {
        val base = synchronized(this) {
            if (!baseOwnership.compareAndSet(true, false)) null
            else ownedBaseBitmap.also { ownedBaseBitmap = null }
        }
        val layers = synchronized(this) {
            if (!layerOwnership.compareAndSet(true, false)) emptyList()
            else ownedSelectionLayers.also { ownedSelectionLayers = emptyList() }
        }
        base?.takeUnless(Bitmap::isRecycled)?.recycle()
        layers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        synchronized(this) { sourceLease.also { sourceLease = null } }?.close()
    }

    companion object {
        internal fun capture(state: EditorUiState, documentGeneration: String): FullExportSourceRequest {
            val baseSource =
                if (state.baseBitmapDirty) state.originalPreviewBitmap ?: state.previewBitmap
                else if (state.sourcePath == null) state.previewBitmap
                else null
            if (state.baseBitmapDirty && baseSource != null) {
                val required =
                    BitmapMemoryBudget.saturatingAdd(
                        BitmapMemoryBudget.bytes(baseSource),
                        BitmapMemoryBudget.bytes(baseSource.width, baseSource.height, Bitmap.Config.ARGB_8888),
                    )
                if (MemoryRecoveryTestSeam.capture()?.rejectExportPreparation == true ||
                    !BitmapMemoryBudget.canAllocate(required)
                ) {
                    throw BitmapAllocationRejectedException(required)
                }
            }
            if (!state.baseBitmapDirty && state.sourcePath != null &&
                !canPreflightCleanExport(state.sourcePath, ExportResolution.Full)
            ) {
                throw BitmapAllocationRejectedException(
                    estimateCleanExportPeakBytes(state.sourcePath, ExportResolution.Full),
                )
            }
            val base =
                if (state.baseBitmapDirty || state.sourcePath == null) {
                    baseSource?.copyOrThrow(
                        Bitmap.Config.ARGB_8888,
                        true,
                    )
                } else {
                    null
                }
            val sourceLease =
                runCatching {
                    if (!state.baseBitmapDirty) acquireFullExportSourceOperationLease(state.sourcePath)
                    else null
                }
                    .getOrElse { failure ->
                        base?.takeUnless(Bitmap::isRecycled)?.recycle()
                        throw failure
                    }
            val layers = try {
                state.selectionLayers.copyBitmapsOwned()
            } catch (failure: Throwable) {
                base?.takeUnless(Bitmap::isRecycled)?.recycle()
                sourceLease?.close()
                throw failure
            }
            return FullExportSourceRequest(
                sourcePath = state.sourcePath,
                baseBitmapDirty = state.baseBitmapDirty,
                baseWidth = base?.width,
                baseHeight = base?.height,
                documentGeneration = documentGeneration,
                baseContentToken = state.baseContentToken,
                revision = state.revision,
                params = state.params,
                engines = state.engineSelection(),
                assignedDocumentEngine = state.correctionEngineState.documentEngine,
                visiblePreview = state.correctionEngineState.visiblePreview,
                presetLook = state.presetLook,
                cropState = state.cropState,
                quickEffects = state.activeQuickEffects.toList(),
                ownedBaseBitmap = base,
                ownedSelectionLayers = layers,
                sourceLease = sourceLease,
            )
        }
    }
}

private fun resolveFullExportSourceGeometry(
    request: FullExportSourceRequest,
): FullExportSourceGeometry? {
    val source =
        if (request.baseBitmapDirty || request.sourcePath == null) {
            if (request.baseWidth != null && request.baseHeight != null) {
                FullExportSourceGeometry(request.baseWidth, request.baseHeight)
            } else {
                null
            }
        } else {
            request.sourcePath.let(::readGeometryForRequestPath)
        } ?: return null
    val (width, height) =
        if (request.cropState == CropState()) source.width to source.height
        else cropTransformedDimensions(source.width, source.height, request.cropState)
    return FullExportSourceGeometry(width, height)
}

private fun readGeometryForRequestPath(path: String): FullExportSourceGeometry? {
    val state = EditorUiState(sourcePath = path)
    return resolveFullExportSourceGeometry(state)
}

internal fun FullExportSourceRequest.renderRequest(
    operation: RenderOperation,
    base: Bitmap,
    layers: List<SelectionLayer>,
    diagnostics: MemoryTrackerScope?,
): RenderRequest? {
    val visible = visiblePreview as? VisiblePreviewState.Rendered ?: return null
    val actualRoute = visible.actualRoute
    return RenderRequest(
        operation = operation,
        basePreview = base,
        params = params,
        engines = engines,
        assignedDocumentEngine = assignedDocumentEngine,
        identity = RenderIdentity(documentGeneration, baseContentToken, revision + 1),
        storedRequestedRoute = visible.requestedRoute,
        exactRoute = actualRoute,
        storedDecision = visible.decision,
        storedAlgorithmVersion = visible.algorithmVersion,
        storedParticipation = visible.participation,
        fallbackPolicy = FallbackPolicy.NoFallback,
        look = presetLook,
        quickEffects = quickEffects,
        selectionLayers = layers,
        diagnostics = diagnostics,
    )
}

internal suspend fun prepareFullExportSourceBitmapFromRequest(
    request: FullExportSourceRequest,
    diagnostics: MemoryTrackerScope? = null,
): Bitmap {
    val layers = request.takeSelectionLayers()
    var output: Bitmap? = null
    try {
        output =
            if (request.baseBitmapDirty) {
                renderEditedExportFromBitmap(
                    ownedBaseBitmap = checkNotNull(request.takeBaseBitmap()) {
                        "dirty document has no base bitmap"
                    },
                    resolution = ExportResolution.Full,
                    selectionLayers = layers,
                    diagnostics = diagnostics,
                ) { base, preparedLayers ->
                    request.renderRequest(
                        RenderOperation.ExportDirty,
                        base,
                        preparedLayers,
                        diagnostics,
                    )
                }
            } else if (request.sourcePath != null) {
                renderEditedExport(
                    sourcePath = request.sourcePath,
                    resolution = ExportResolution.Full,
                    selectionLayers = layers,
                    diagnostics = diagnostics,
                ) { base, preparedLayers ->
                    request.renderRequest(
                        RenderOperation.ExportClean,
                        base,
                        preparedLayers,
                        diagnostics,
                    )
                }
            } else {
                val base = checkNotNull(request.takeBaseBitmap()) { "document has no source bitmap" }
                // Frozen parent semantics: a clean document with no source path already owns
                // the settled preview. Full export copied it and only then applied crop; it did
                // not route the pixels through a new ExportDirty render decision.
                base
            }
        if (request.cropState != CropState()) {
            val cropped = renderCropTransform(checkNotNull(output), request.cropState)
            if (cropped !== output) output?.takeUnless(Bitmap::isRecycled)?.recycle()
            output = cropped
        }
        val prepared = checkNotNull(output)
        val expected = resolveFullExportSourceGeometry(request)
        // The request itself is authoritative for rendering; this check only catches drift
        // between the metadata-only preflight contract and the existing preparation result.
        if (expected != null && (expected.width != prepared.width || expected.height != prepared.height)) {
            throw IllegalStateException(
                "Full export geometry drift: prepared=${prepared.width}x${prepared.height} expected=${expected.width}x${expected.height}",
            )
        }
        return prepared.also { output = null }
    } finally {
        output?.takeUnless(Bitmap::isRecycled)?.recycle()
        request.close()
    }
}

private const val FULL_EXPORT_MAX_SIDE = 8192
