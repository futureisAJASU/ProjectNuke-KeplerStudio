package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.MemoryTrackerScope
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.beginMemoryTracking
import com.projectnuke.keplerstudio.editor.centeredCropForAspect
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.estimateAutoStraightenDegreesV0
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.normalized
import com.projectnuke.keplerstudio.editor.renderCropTransform
import com.projectnuke.keplerstudio.editor.LeasedEditorSnapshot
import com.projectnuke.keplerstudio.editor.acquireEditorSnapshot
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.setCropAspectRatio(aspectRatio: CropAspectRatio) {
    if (!canEnterEditorAction()) return
    invalidateCropOperation()
    updateUiState { state ->
        val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
        val next =
            bitmap?.let { centeredCropForAspect(it.width, it.height, aspectRatio) }
                ?: CropState(aspectRatio = aspectRatio)
        state.copy(
            cropState =
                state.cropState.copy(
                    aspectRatio = next.aspectRatio,
                    cropLeft = next.cropLeft,
                    cropTop = next.cropTop,
                    cropRight = next.cropRight,
                    cropBottom = next.cropBottom,
                )
        )
    }
}

fun EditorViewModel.updateCropRect(left: Float, top: Float, right: Float, bottom: Float) {
    if (!canEnterEditorAction()) return
    invalidateCropOperation()
    updateUiState {
        it.copy(
            cropState =
                it.cropState
                    .copy(cropLeft = left, cropTop = top, cropRight = right, cropBottom = bottom)
                    .normalized()
        )
    }
}

fun EditorViewModel.rotateCropLeft() {
    if (!canEnterEditorAction()) return
    invalidateCropOperation()
    updateUiState {
        it.copy(
            cropState =
                it.cropState.copy(rotationDegrees = it.cropState.rotationDegrees - 90).normalized()
        )
    }
}

fun EditorViewModel.rotateCropRight() {
    if (!canEnterEditorAction()) return
    invalidateCropOperation()
    updateUiState {
        it.copy(
            cropState =
                it.cropState.copy(rotationDegrees = it.cropState.rotationDegrees + 90).normalized()
        )
    }
}

fun EditorViewModel.toggleCropFlipHorizontal() {
    if (!canEnterEditorAction()) return
    invalidateCropOperation()
    updateUiState {
        it.copy(cropState = it.cropState.copy(flipHorizontal = !it.cropState.flipHorizontal))
    }
}

fun EditorViewModel.setStraightenDegrees(value: Float) {
    if (!canEnterEditorAction()) return
    invalidateCropOperation()
    updateUiState {
        it.copy(cropState = it.cropState.copy(straightenDegrees = value.coerceIn(-45f, 45f)))
    }
}

fun EditorViewModel.autoStraightenCrop() {
    if (!canEnterEditorAction()) return
    val state = uiState.value
    val bitmap = state.previewBitmap ?: state.originalPreviewBitmap ?: return
    val cropToken = beginCropOperation()
    val cropTracker = beginMemoryTracking("autoStraightenCrop", snapshotState = "analyzing")
    val input =
        runCatching { bitmap.copyOrThrow(mutable = false) }
            .getOrElse { failure ->
                cropTracker?.end()
                updateUiState {
                    it.copy(
                        message =
                            if (failure is BitmapAllocationRejectedException)
                                "메모리가 부족하여 기울기 보정 이미지를 준비하지 못했습니다."
                            else "기울기 보정용 이미지를 준비하지 못했습니다."
                    )
                }
                if (failure is BitmapAllocationRejectedException)
                    requestAllocationRecovery(
                        MemoryRetryAction.AutoStraightenCrop,
                        failure.requiredBytes,
                    )
                return
            }
    cropTracker?.track(input, "autoStraightenCrop:input")
    cropJob?.cancel()
    val handoff =
        PreparedResourceHandoff.create(
            "autoStraighten",
            { if (!input.isRecycled) input.recycle() },
            { cropTracker?.end() },
        )
    val launchedJob =
        viewModelScope.launch {
            if (!handoff.claimForChild()) return@launch
            try {
                val angle =
                    withContext(Dispatchers.Default) { estimateAutoStraightenDegreesV0(input) }
                if (isCropResultCurrent(cropToken, state.revision)) {
                    updateUiState { current ->
                        current.copy(
                            cropState = current.cropState.copy(straightenDegrees = angle),
                            message = "기울기 보정값을 적용했습니다: ${String.format(Locale.US, "%.1f", angle)}°",
                        )
                    }
                    markMemoryRetrySucceeded(MemoryRetryAction.AutoStraightenCrop)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (isCropResultCurrent(cropToken, state.revision))
                    updateUiState { it.copy(message = "기울기 보정에 실패했습니다: ${t.message}") }
            } finally {
                handoff.settleChildOwned()
            }
        }
    cropJob = launchedJob
    launchedJob.invokeOnCompletion {
        handoff.settleCallerOwned()
        if (cropJob === launchedJob) cropJob = null
    }
    if (launchedJob.isCompleted && cropJob === launchedJob) cropJob = null
}

fun EditorViewModel.resetCropState() {
    if (!canEnterEditorAction()) return
    invalidateCropOperation()
    val current = prepareForExternalEdit()
    applySynchronousEditWithHistory { state ->
        val bitmap = current.previewBitmap ?: current.originalPreviewBitmap
        state.copy(
            cropState =
                bitmap?.let { centeredCropForAspect(it.width, it.height, CropAspectRatio.Original) }
                    ?: CropState(),
            message = "변경사항을 되돌렸습니다.",
        )
    }
}

fun EditorViewModel.applyCropTransform() {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return

    val state = prepareForExternalEdit()
    val preview = state.previewBitmap
    val original = state.originalPreviewBitmap
    if (preview == null && original == null) return

    // History snapshot is captured synchronously here because capture may itself reserve
    // scarce in-process memory before a large worker allocation; failing to capture leaves
    // the previous document in the redo stack intact. The actual full-resolution bitmap
    // *copies* are deferred to the worker (see [applyCropTransformBackground]) so Compose
    // event handlers do not block the Main dispatcher.
    var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()

    val crop = state.cropState.normalized()
    val nextRevision = state.revision + 1
    val cropToken = beginCropOperation()
    val sourcePath = state.sourcePath
    val baseContentToken = state.baseContentToken
    val activeSelectionLayerId = state.activeSelectionLayerId
    val capturedSelectionLayers = state.selectionLayers.toList()
    val capturedPreviewWidth = preview?.width ?: 0
    val capturedPreviewHeight = preview?.height ?: 0

    val cropPrepareTracker =
        beginMemoryTracking(
            "applyCropTransform:prepare",
            snapshotState = "copying",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
        )

    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "변경사항을 적용하는 중입니다.") }

    launchManagedEditWithPreparedResources(
        { operationToken ->
            applyCropTransformBackground(
                operationToken = operationToken,
                crop = crop,
                nextRevision = nextRevision,
                cropToken = cropToken,
                sourcePath = sourcePath,
                baseContentToken = baseContentToken,
                activeSelectionLayerId = activeSelectionLayerId,
                capturedSelectionLayers = capturedSelectionLayers,
                capturedPreviewWidth = capturedPreviewWidth,
                capturedPreviewHeight = capturedPreviewHeight,
                originalUndoSnapshotRef = { undoSnapshot },
                cropPrepareTracker = cropPrepareTracker,
                consumeUndoSnapshot = { undoSnapshot = null },
                releaseUndoSnapshot = { undoSnapshot?.let(::recycleHistorySnapshot); undoSnapshot = null },
            )
        },
        handoff =
            PreparedResourceHandoff.create(
                "cropApply",
                {
                    undoSnapshot?.let(::recycleHistorySnapshot)
                    undoSnapshot = null
                },
                { cropPrepareTracker?.end() },
                {
                    val live = uiState.value
                    if (
                        isCropOperationCurrent(cropToken) &&
                            live.revision == nextRevision &&
                            live.sourcePath == sourcePath &&
                            live.baseContentToken == baseContentToken
                    ) {
                        updateUiState { it.copy(isBusy = false) }
                    }
                },
            ),
    )
}

/**
 * Background implementation of [applyCropTransform]. The full-resolution base, original, and
 * mask bitmaps are copied here on `Dispatchers.Default`, after the captured identity gates
 * have been re-checked, so that Compose event handlers do not block the Main dispatcher and a
 * superseded crop request recycles its owned inputs without adopting the document.
 *
 * The captured identity (sourcePath, baseContentToken, activeSelectionLayerId, the captured
 * selection layer list and preview dimensions) is used only to compare against the
 * authoritative state read inside the worker. The worker itself re-reads the live state
 * (rather than the synchronous snapshot) and only copies bitmaps from the authoritative
 * refs, so the worker is robust against an in-flight preview replacement between the
 * synchronous start and the worker launch.
 */
private suspend fun EditorViewModel.applyCropTransformBackground(
    operationToken: Long,
    crop: CropState,
    nextRevision: Int,
    cropToken: Long,
    sourcePath: String?,
    baseContentToken: String,
    activeSelectionLayerId: String?,
    capturedSelectionLayers: List<SelectionLayer>,
    capturedPreviewWidth: Int,
    capturedPreviewHeight: Int,
    originalUndoSnapshotRef: () -> EditorHistorySnapshot?,
    cropPrepareTracker: MemoryTrackerScope?,
    consumeUndoSnapshot: () -> Unit,
    releaseUndoSnapshot: () -> Unit,
) {
    var leasedSnapshot: LeasedEditorSnapshot? = null
    var previewInput: Bitmap? = null
    var originalInput: Bitmap? = null
    val maskInputs = ArrayList<SelectionLayer>(capturedSelectionLayers.size)
    var transformedOriginal: Bitmap? = null
    var transformedPreview: Bitmap? = null
    var transformedMasks: List<SelectionLayer>? = null
    var undoSnapshotOwned: EditorHistorySnapshot? = originalUndoSnapshotRef()
    consumeUndoSnapshot()
    val cropTracker =
        beginMemoryTracking(
            "applyCropTransform",
            snapshotState = "rendering",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
        )

    fun releasePreparedInputs() {
        previewInput?.takeIf { !it.isRecycled }?.recycle()
        if (originalInput !== previewInput) originalInput?.takeIf { !it.isRecycled }?.recycle()
        maskInputs.forEach { it.bitmap.takeIf { !it.isRecycled }?.recycle() }
        maskInputs.clear()
        previewInput = null
        originalInput = null
    }

    try {
        // Worker-side re-read: the authoritative state may differ from the synchronous
        // snapshot (e.g., a param render that completed in between); only the matching
        // identity may proceed.
        val prepared = withContext(Dispatchers.Default) {
            // Validate adoption identity upfront so an obviously-superseded request never
            // performs a full-resolution copy.
            if (!isManagedEditTokenCurrent(operationToken) || !isCropOperationCurrent(cropToken)) return@withContext null
            leasedSnapshot = acquireEditorSnapshot("applyCropTransform") ?: return@withContext null
            val workerState = leasedSnapshot!!.state
            if (workerState.sourcePath != sourcePath) return@withContext null
            if (workerState.baseContentToken != baseContentToken) return@withContext null
            if (workerState.activeSelectionLayerId != activeSelectionLayerId) return@withContext null
            if (workerState.selectionLayers != capturedSelectionLayers) return@withContext null

            val wPreview = workerState.previewBitmap
            val wOriginal = workerState.originalPreviewBitmap
            if (wPreview == null && wOriginal == null) return@withContext null
            // Defense-in-depth against a recycler race: dimensions must match those captured at
            // the synchronous start. If mismatched, treat as superseded.
            if (wPreview != null && (wPreview.width != capturedPreviewWidth || wPreview.height != capturedPreviewHeight)) return@withContext null

            previewInput = wPreview?.copyOrThrow()
            previewInput?.let { cropTracker?.track(it, "crop:previewInput") }
            originalInput =
                if (wOriginal == null || wOriginal === wPreview) previewInput else wOriginal.copyOrThrow()
            originalInput
                ?.takeIf { it !== previewInput }
                ?.let { cropTracker?.track(it, "crop:originalInput") }

            try {
                workerState.selectionLayers.forEach { layer ->
                    try {
                        maskInputs +=
                            layer.copy(
                                bitmap =
                                    layer.bitmap.copyOrThrow().also {
                                        cropTracker?.track(it, "crop:mask:${layer.id}")
                                    }
                            )
                    } catch (t: Throwable) {
                        maskInputs.forEach { created ->
                            created.bitmap.takeIf { !created.bitmap.isRecycled }?.recycle()
                        }
                        throw t
                    }
                }
            } catch (t: Throwable) {
                // Allocation failed: leave the previous document exactly as it was.
                previewInput?.takeIf { !it.isRecycled }?.recycle()
                if (originalInput !== previewInput) originalInput?.takeIf { !it.isRecycled }?.recycle()
                previewInput = null
                originalInput = null
                throw t
            }
            previewInput to originalInput
        }

        if (prepared == null) {
            // Superseded or otherwise not adoptable: settle silently, release transients.
            cropPrepareTracker?.end()
            return
        }

        cropPrepareTracker?.end()

        withContext(Dispatchers.Default) {
            val o = originalInput?.let { renderCropTransform(it, crop) }
            transformedOriginal = o
            o?.let { cropTracker?.track(it, "crop:transformedOriginal") }
        }
        withContext(Dispatchers.Default) {
            val p =
                if (previewInput === originalInput) transformedOriginal
                else previewInput?.let { renderCropTransform(it, crop) }
            transformedPreview = p
            p?.takeIf { it !== transformedOriginal }
                ?.let { cropTracker?.track(it, "crop:transformedPreview") }
        }
        withContext(Dispatchers.Default) {
            val transformed = ArrayList<SelectionLayer>(maskInputs.size)
            try {
                maskInputs.forEach { layer ->
                    transformed +=
                        layer.copy(
                            bitmap =
                                renderCropTransform(layer.bitmap, crop).also {
                                    cropTracker?.track(
                                        it,
                                        "crop:transformedMask:${layer.id}",
                                    )
                                }
                        )
                }
                transformedMasks = transformed
            } catch (t: Throwable) {
                transformed.forEach { created ->
                    created.bitmap.takeIf { !created.bitmap.isRecycled }?.recycle()
                }
                throw t
            }
        }

        val expectedOriginal = transformedOriginal ?: transformedPreview
        val expectedPreview = transformedPreview ?: transformedOriginal
        val expectedTransformedLayers = transformedMasks
        val adoptable =
            isManagedEditCurrent(operationToken, nextRevision) &&
                isCropOperationCurrent(cropToken) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.activeSelectionLayerId == activeSelectionLayerId &&
                uiState.value.selectionLayers == capturedSelectionLayers

        if (adoptable) {
            var stateUpdateException: Throwable? = null
            try {
                updateUiStateAndRecycleReplaced {
                    val adoptedOriginal =
                        expectedOriginal ?: error("missing transformed original")
                    val adoptedPreview =
                        expectedPreview ?: error("missing transformed preview")
                    it.copy(
                        originalPreviewBitmap = adoptedOriginal,
                        previewBitmap = adoptedPreview,
                        baseBitmapDirty = true,
                        baseContentToken = newBaseContentToken(),
                        cropState = CropState(),
                        selectionLayers = checkNotNull(expectedTransformedLayers),
                        isBusy = false,
                        message = "변경사항을 적용했습니다.",
                    )
                }
            } catch (t: Throwable) {
                stateUpdateException = t
                if (t is CancellationException) throw t
            }
            val liveStateAfter = uiState.value
            val originalAdopted = liveStateAfter.originalPreviewBitmap === expectedOriginal
            val previewAdopted = liveStateAfter.previewBitmap === expectedPreview
            val masksAdopted =
                expectedTransformedLayers != null &&
                    liveStateAfter.selectionLayers == expectedTransformedLayers
            val fullyAdopted = originalAdopted && previewAdopted && masksAdopted

            if (fullyAdopted) {
                transformedOriginal = null
                transformedPreview = null
                transformedMasks = null
                markParamsSuccessfullyRendered(liveStateAfter.params)
                settleAdoptedEditHistory(undoSnapshotOwned)
                undoSnapshotOwned = null
                persistDraftSnapshot()
            } else if (stateUpdateException != null) {
                throw stateUpdateException
            }
        } else if (isManagedEditTokenCurrent(operationToken)) {
            updateUiState { it.copy(isBusy = false) }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        val failureAdoptable =
            isManagedEditCurrent(operationToken, nextRevision) &&
                isCropOperationCurrent(cropToken) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.activeSelectionLayerId == activeSelectionLayerId &&
                uiState.value.selectionLayers == capturedSelectionLayers
        val failureManagedCurrent = isManagedEditTokenCurrent(operationToken)
        if (failureAdoptable) {
            updateUiState { it.copy(isBusy = false, message = "자르기에 실패했습니다: ${t.message}") }
        } else if (failureManagedCurrent) {
            updateUiState { it.copy(isBusy = false) }
        }
    } finally {
        val retained = identityBitmapSetForFinally()
        uiState.value.originalPreviewBitmap?.let(retained::add)
        uiState.value.previewBitmap?.let(retained::add)
        uiState.value.selectionLayers.forEach { retained.add(it.bitmap) }
        transformedOriginal?.takeIf { it !in retained && !it.isRecycled }?.recycle()
        if (transformedPreview !== transformedOriginal)
            transformedPreview?.takeIf { it !in retained && !it.isRecycled }?.recycle()
        transformedMasks?.forEach { layer ->
            layer.bitmap.takeIf { it !in retained && !it.isRecycled }?.recycle()
        }
        releasePreparedInputs()
        undoSnapshotOwned?.let(::recycleHistorySnapshot)
        undoSnapshotOwned = null
        releaseUndoSnapshot()
        leasedSnapshot?.close()
        cropTracker?.end()
    }
}

private fun EditorViewModel.identityBitmapSetForFinally(): MutableSet<Bitmap> =
    Collections.newSetFromMap(IdentityHashMap())
