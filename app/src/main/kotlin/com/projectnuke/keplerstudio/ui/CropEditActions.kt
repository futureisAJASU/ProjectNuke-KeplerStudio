package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.centeredCropForAspect
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.estimateAutoStraightenDegreesV0
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.normalized
import com.projectnuke.keplerstudio.editor.renderCropTransform
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.setCropAspectRatio(aspectRatio: CropAspectRatio) {
    invalidateCropOperation()
    updateUiState { state ->
        val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
        val next = bitmap?.let { centeredCropForAspect(it.width, it.height, aspectRatio) } ?: CropState(aspectRatio = aspectRatio)
        state.copy(cropState = state.cropState.copy(aspectRatio = next.aspectRatio, cropLeft = next.cropLeft, cropTop = next.cropTop, cropRight = next.cropRight, cropBottom = next.cropBottom))
    }
}

fun EditorViewModel.updateCropRect(left: Float, top: Float, right: Float, bottom: Float) {
    invalidateCropOperation()
    updateUiState { it.copy(cropState = it.cropState.copy(cropLeft = left, cropTop = top, cropRight = right, cropBottom = bottom).normalized()) }
}

fun EditorViewModel.rotateCropLeft() { invalidateCropOperation(); updateUiState { it.copy(cropState = it.cropState.copy(rotationDegrees = it.cropState.rotationDegrees - 90).normalized()) } }
fun EditorViewModel.rotateCropRight() { invalidateCropOperation(); updateUiState { it.copy(cropState = it.cropState.copy(rotationDegrees = it.cropState.rotationDegrees + 90).normalized()) } }
fun EditorViewModel.toggleCropFlipHorizontal() { invalidateCropOperation(); updateUiState { it.copy(cropState = it.cropState.copy(flipHorizontal = !it.cropState.flipHorizontal)) } }
fun EditorViewModel.setStraightenDegrees(value: Float) { invalidateCropOperation(); updateUiState { it.copy(cropState = it.cropState.copy(straightenDegrees = value.coerceIn(-45f, 45f))) } }

fun EditorViewModel.autoStraightenCrop() {
    val state = uiState.value
    val bitmap = state.previewBitmap ?: state.originalPreviewBitmap ?: return
    val cropToken = beginCropOperation()
    val input = runCatching { bitmap.copyOrThrow(mutable = false) }.getOrElse {
        updateUiState { it.copy(message = "기울기 보정용 이미지를 준비하지 못했습니다.") }
        return
    }
    cropJob?.cancel()
    cropJob = viewModelScope.launch {
        try {
            val angle = withContext(Dispatchers.Default) { estimateAutoStraightenDegreesV0(input) }
            if (isCropResultCurrent(cropToken, state.revision)) {
                updateUiState { current -> current.copy(cropState = current.cropState.copy(straightenDegrees = angle), message = "기울기 보정값을 적용했습니다: ${String.format(Locale.US, "%.1f", angle)}°") }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (isCropResultCurrent(cropToken, state.revision)) updateUiState { it.copy(message = "기울기 보정에 실패했습니다: ${t.message}") }
        } finally {
            input.recycle()
        }
    }
}

fun EditorViewModel.resetCropState() {
    invalidateCropOperation()
    val current = prepareForExternalEdit()
    recordUserEditForUndo(clearRedo = true)
    updateUiState { state ->
        val bitmap = current.previewBitmap ?: current.originalPreviewBitmap
        state.copy(cropState = bitmap?.let { centeredCropForAspect(it.width, it.height, CropAspectRatio.Original) } ?: CropState(), message = "변경사항을 되돌렸습니다.")
    }
}

fun EditorViewModel.applyCropTransform() {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return

    val state = prepareForExternalEdit()
    val preview = state.previewBitmap
    val original = state.originalPreviewBitmap
    if (preview == null && original == null) return

    var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
    if (undoSnapshot == null) {
        updateUiState { it.copy(message = "자르기 준비에 실패했습니다. 기존 편집 상태를 유지합니다.") }
        return
    }

    var previewInput: Bitmap? = null
    var originalInput: Bitmap? = null
    val maskInputs = ArrayList<SelectionLayer>(state.selectionLayers.size)

    try {
        previewInput = preview?.copyOrThrow()
        originalInput = if (original == null || original === preview) previewInput else original.copyOrThrow()

        state.selectionLayers.forEach { layer ->
            try {
                maskInputs += layer.copy(bitmap = layer.bitmap.copyOrThrow())
            } catch (t: Throwable) {
                maskInputs.forEach { created -> created.bitmap.takeIf { !it.isRecycled }?.recycle() }
                throw t
            }
        }
    } catch (t: Throwable) {
        previewInput?.takeIf { !it.isRecycled }?.recycle()
        if (originalInput !== previewInput) originalInput?.takeIf { !it.isRecycled }?.recycle()
        maskInputs.forEach { it.bitmap.takeIf { !it.isRecycled }?.recycle() }
        undoSnapshot?.let(::recycleHistorySnapshot)
        updateUiState { it.copy(message = "자르기 준비에 실패했습니다. 기존 편집 상태를 유지합니다.") }
        return
    }

    val crop = state.cropState.normalized()
    val nextRevision = state.revision + 1
    val cropToken = beginCropOperation()
    val sourcePath = state.sourcePath
    val baseContentToken = state.baseContentToken
    val activeSelectionLayerId = state.activeSelectionLayerId
    val capturedSelectionLayers = state.selectionLayers.toList()

    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "변경사항을 적용하는 중입니다.") }

    launchManagedEdit { operationToken ->
        var transformedOriginal: Bitmap? = null
        var transformedPreview: Bitmap? = null
        var transformedMasks: List<SelectionLayer>? = null
        var adoptionConfirmed = false
        var undoSnapshotOwned: EditorHistorySnapshot? = undoSnapshot
        var previewInputOwned: Bitmap? = previewInput
        var originalInputOwned: Bitmap? = originalInput
        val maskInputsOwned = maskInputs.toList()
        undoSnapshot = null
        previewInput = null
        originalInput = null

        try {
            withContext(Dispatchers.Default) {
                val o = originalInputOwned?.let { renderCropTransform(it, crop) }
                transformedOriginal = o
            }

            withContext(Dispatchers.Default) {
                val p = if (previewInputOwned === originalInputOwned) {
                    transformedOriginal
                } else {
                    previewInputOwned?.let { renderCropTransform(it, crop) }
                }
                transformedPreview = p
            }

            withContext(Dispatchers.Default) {
                val transformed = ArrayList<SelectionLayer>(maskInputsOwned.size)
                try {
                    maskInputsOwned.forEach { layer ->
                        transformed += layer.copy(bitmap = renderCropTransform(layer.bitmap, crop))
                    }
                    transformedMasks = transformed
                } catch (t: Throwable) {
                    transformed.forEach { created -> created.bitmap.takeIf { !it.isRecycled }?.recycle() }
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

            val managedCurrent = isManagedEditTokenCurrent(operationToken)

            if (adoptable) {
                var stateUpdateException: Throwable? = null
                try {
                    updateUiStateAndRecycleReplaced {
                        val adoptedOriginal = expectedOriginal ?: error("missing transformed original")
                        val adoptedPreview = expectedPreview ?: error("missing transformed preview")
                        it.copy(
                            originalPreviewBitmap = adoptedOriginal,
                            previewBitmap = adoptedPreview,
                            baseBitmapDirty = true,
                            baseContentToken = newBaseContentToken(),
                            cropState = CropState(),
                            selectionLayers = checkNotNull(expectedTransformedLayers),
                            isBusy = false,
                            message = "변경사항을 적용했습니다."
                        )
                    }
                } catch (t: Throwable) {
                    stateUpdateException = t
                    if (t is CancellationException) throw t
                }

                val liveStateAfter = uiState.value
                val originalAdopted = liveStateAfter.originalPreviewBitmap === expectedOriginal
                val previewAdopted = liveStateAfter.previewBitmap === expectedPreview
                val masksAdopted = expectedTransformedLayers != null &&
                    liveStateAfter.selectionLayers == expectedTransformedLayers
                val fullyAdopted = originalAdopted && previewAdopted && masksAdopted

                if (fullyAdopted) {
                    adoptionConfirmed = true
                    transformedOriginal = null
                    transformedPreview = null
                    transformedMasks = null
                    markParamsSuccessfullyRendered(liveStateAfter.params)
                    commitUndoSnapshot(checkNotNull(undoSnapshotOwned), clearRedo = true)
                    undoSnapshotOwned = null
                    persistDraftSnapshot()
                } else if (stateUpdateException != null) {
                    throw stateUpdateException
                }
            } else if (managedCurrent) {
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
            val retained = mutableSetOf<Bitmap>()
            uiState.value.originalPreviewBitmap?.let(retained::add)
            uiState.value.previewBitmap?.let(retained::add)
            uiState.value.selectionLayers.forEach { retained.add(it.bitmap) }

            transformedOriginal?.takeIf { it !in retained && !it.isRecycled }?.recycle()
            if (transformedPreview !== transformedOriginal) {
                transformedPreview?.takeIf { it !in retained && !it.isRecycled }?.recycle()
            }
            transformedMasks?.forEach { layer ->
                layer.bitmap.takeIf { it !in retained && !it.isRecycled }?.recycle()
            }

            previewInputOwned?.takeIf { !it.isRecycled }?.recycle()
            if (originalInputOwned !== previewInputOwned) originalInputOwned?.takeIf { !it.isRecycled }?.recycle()
            maskInputsOwned.forEach { it.bitmap.takeIf { !it.isRecycled }?.recycle() }
            if (!adoptionConfirmed) undoSnapshotOwned?.let(::recycleHistorySnapshot)
        }
    }
}