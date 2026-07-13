package com.projectnuke.keplerstudio.ui

import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
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
import kotlinx.coroutines.withContext

fun EditorViewModel.setCropAspectRatio(aspectRatio: CropAspectRatio) {
    updateUiState { state ->
        val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
        val next = bitmap?.let { centeredCropForAspect(it.width, it.height, aspectRatio) } ?: CropState(aspectRatio = aspectRatio)
        state.copy(cropState = state.cropState.copy(aspectRatio = next.aspectRatio, cropLeft = next.cropLeft, cropTop = next.cropTop, cropRight = next.cropRight, cropBottom = next.cropBottom))
    }
}

fun EditorViewModel.updateCropRect(left: Float, top: Float, right: Float, bottom: Float) {
    updateUiState { it.copy(cropState = it.cropState.copy(cropLeft = left, cropTop = top, cropRight = right, cropBottom = bottom).normalized()) }
}

fun EditorViewModel.rotateCropLeft() = updateUiState { it.copy(cropState = it.cropState.copy(rotationDegrees = it.cropState.rotationDegrees - 90).normalized()) }
fun EditorViewModel.rotateCropRight() = updateUiState { it.copy(cropState = it.cropState.copy(rotationDegrees = it.cropState.rotationDegrees + 90).normalized()) }
fun EditorViewModel.toggleCropFlipHorizontal() = updateUiState { it.copy(cropState = it.cropState.copy(flipHorizontal = !it.cropState.flipHorizontal)) }
fun EditorViewModel.setStraightenDegrees(value: Float) = updateUiState { it.copy(cropState = it.cropState.copy(straightenDegrees = value.coerceIn(-45f, 45f))) }

fun EditorViewModel.autoStraightenCrop() {
    val state = uiState.value
    val bitmap = state.previewBitmap ?: state.originalPreviewBitmap ?: return
    val input = runCatching { bitmap.copyOrThrow(mutable = false) }.getOrElse {
        updateUiState { current -> current.copy(message = "기울기 보정용 이미지를 준비하지 못했습니다.") }
        return
    }
    launchManagedEdit { token ->
        try {
            val angle = withContext(Dispatchers.Default) { estimateAutoStraightenDegreesV0(input) }
            if (isManagedEditCurrent(token, state.revision)) {
                updateUiState { current -> current.copy(cropState = current.cropState.copy(straightenDegrees = angle), message = "기울기 보정값을 적용했습니다: ${String.format(Locale.US, "%.1f", angle)}°") }
            }
        } finally {
            input.recycle()
        }
    }
}

fun EditorViewModel.resetCropState() {
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
    val undo = captureCurrentHistorySnapshot() ?: return
    val crop = state.cropState.normalized()
    val nextRevision = state.revision + 1
    val previewInput = preview?.copyOrThrow()
    val originalInput = if (original == null || original === preview) previewInput else original.copyOrThrow()
    val masks = state.selectionLayers.map { it.copy(bitmap = it.bitmap.copyOrThrow()) }
    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "변경사항을 적용하는 중입니다.") }
    launchManagedEdit { token ->
        var renderedOriginal: android.graphics.Bitmap? = null
        var renderedPreview: android.graphics.Bitmap? = null
        var renderedMasks: List<SelectionLayer>? = null
        var adopted = false
        try {
            renderedOriginal = withContext(Dispatchers.Default) { originalInput?.let { renderCropTransform(it, crop) } }
            renderedPreview = if (previewInput === originalInput) renderedOriginal else withContext(Dispatchers.Default) { previewInput?.let { renderCropTransform(it, crop) } }
            renderedMasks = withContext(Dispatchers.Default) { masks.map { it.copy(bitmap = renderCropTransform(it.bitmap, crop)) } }
            if (!isManagedEditCurrent(token, nextRevision)) return@launchManagedEdit
            commitUndoSnapshot(undo, clearRedo = true)
            updateUiState {
                it.copy(originalPreviewBitmap = renderedOriginal ?: renderedPreview, previewBitmap = renderedPreview ?: renderedOriginal, baseBitmapDirty = true, baseContentToken = java.util.UUID.randomUUID().toString(), cropState = CropState(), selectionLayers = checkNotNull(renderedMasks), isBusy = false, message = "변경사항을 적용했습니다.")
            }
            renderedOriginal = null
            renderedPreview = null
            renderedMasks = null
            adopted = true
            persistDraftSnapshot()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (isManagedEditCurrent(token, nextRevision)) updateUiState { it.copy(isBusy = false, message = "자르기에 실패했습니다: ${t.message}") }
        } finally {
            renderedOriginal?.recycle()
            if (renderedPreview !== renderedOriginal) renderedPreview?.recycle()
            renderedMasks?.forEach { it.bitmap.recycle() }
            previewInput?.recycle()
            if (originalInput !== previewInput) originalInput?.recycle()
            masks.forEach { it.bitmap.recycle() }
            if (!adopted) recycleHistorySnapshot(undo)
        }
    }
}
