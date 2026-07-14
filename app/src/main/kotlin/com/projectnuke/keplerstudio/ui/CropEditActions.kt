package com.projectnuke.keplerstudio.ui

import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.centeredCropForAspect
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.estimateAutoStraightenDegreesV0
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
    val state = prepareForExternalEdit()
    if (state.isBusy) return
    val preview = state.previewBitmap
    val original = state.originalPreviewBitmap
    if (preview == null && original == null) return
    val cropToken = beginCropOperation()
    var undo: EditorHistorySnapshot? = null
    var previewInput: android.graphics.Bitmap? = null
    var originalInput: android.graphics.Bitmap? = null
    val masks = ArrayList<SelectionLayer>(state.selectionLayers.size)
    try {
        undo = captureCurrentHistorySnapshot() ?: return
        previewInput = preview?.copyOrThrow()
        originalInput = if (original == null || original === preview) previewInput else original.copyOrThrow()
        state.selectionLayers.forEach { layer ->
            try {
                masks += layer.copy(bitmap = layer.bitmap.copyOrThrow())
            } catch (t: Throwable) {
                masks.forEach { created -> created.bitmap.takeIf { !it.isRecycled }?.recycle() }
                throw t
            }
        }
    } catch (t: Throwable) {
        previewInput?.takeIf { !it.isRecycled }?.recycle()
        if (originalInput !== previewInput) originalInput?.takeIf { !it.isRecycled }?.recycle()
        masks.forEach { it.bitmap.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
        undo?.let(::recycleHistorySnapshot)
        updateUiState { it.copy(message = "자르기 준비에 실패했습니다. 기존 편집 상태를 유지합니다.") }
        return
    }
    val crop = state.cropState.normalized()
    val nextRevision = state.revision + 1
    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "변경사항을 적용하는 중입니다.") }
    launchManagedEdit { token ->
        var renderedOriginal: android.graphics.Bitmap? = null
        var renderedPreview: android.graphics.Bitmap? = null
        var renderedMasks: List<SelectionLayer>? = null
        var adopted = false
        try {
            renderedOriginal = withContext(Dispatchers.Default) { originalInput?.let { renderCropTransform(it, crop) } }
            renderedPreview = if (previewInput === originalInput) renderedOriginal else withContext(Dispatchers.Default) { previewInput?.let { renderCropTransform(it, crop) } }
            renderedMasks = withContext(Dispatchers.Default) {
                val transformed = ArrayList<SelectionLayer>(masks.size)
                try {
                    masks.forEach { layer ->
                        transformed += layer.copy(bitmap = renderCropTransform(layer.bitmap, crop))
                    }
                    transformed
                } catch (t: Throwable) {
                    transformed.forEach { created -> created.bitmap.takeIf { !it.isRecycled }?.recycle() }
                    throw t
                }
            }
            if (!isManagedEditCurrent(token, nextRevision) || !isCropOperationCurrent(cropToken)) return@launchManagedEdit
            updateUiState { it.copy(originalPreviewBitmap = renderedOriginal ?: renderedPreview, previewBitmap = renderedPreview ?: renderedOriginal, baseBitmapDirty = true, baseContentToken = java.util.UUID.randomUUID().toString(), cropState = CropState(), selectionLayers = checkNotNull(renderedMasks), isBusy = false, message = "변경사항을 적용했습니다.") }
            renderedOriginal = null
            renderedPreview = null
            renderedMasks = null
            adopted = true
            commitUndoSnapshot(checkNotNull(undo), clearRedo = true)
            undo = null
            persistDraftSnapshot()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (isManagedEditCurrent(token, nextRevision) && isCropOperationCurrent(cropToken)) updateUiState { it.copy(isBusy = false, message = "자르기에 실패했습니다: ${t.message}") }
        } finally {
            renderedOriginal?.recycle()
            if (renderedPreview !== renderedOriginal) renderedPreview?.recycle()
            renderedMasks?.forEach { it.bitmap.recycle() }
            previewInput?.takeIf { !it.isRecycled }?.recycle()
            if (originalInput !== previewInput) originalInput?.takeIf { !it.isRecycled }?.recycle()
            masks.forEach { it.bitmap.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
            if (!adopted) undo?.let(::recycleHistorySnapshot)
        }
    }
}
