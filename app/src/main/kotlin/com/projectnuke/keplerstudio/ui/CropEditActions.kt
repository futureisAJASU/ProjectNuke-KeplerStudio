package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.centeredCropForAspect
import com.projectnuke.keplerstudio.editor.estimateAutoStraightenDegreesV0
import com.projectnuke.keplerstudio.editor.normalized
import com.projectnuke.keplerstudio.editor.renderCropTransform
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.setCropAspectRatio(aspectRatio: CropAspectRatio) {
    updateUiState { state ->
        val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
        val next = if (bitmap != null) {
            centeredCropForAspect(bitmap.width, bitmap.height, aspectRatio)
        } else {
            CropState(aspectRatio = aspectRatio)
        }
        state.copy(
            cropState = state.cropState.copy(
                aspectRatio = next.aspectRatio,
                cropLeft = next.cropLeft,
                cropTop = next.cropTop,
                cropRight = next.cropRight,
                cropBottom = next.cropBottom
            )
        )
    }
}

fun EditorViewModel.updateCropRect(left: Float, top: Float, right: Float, bottom: Float) {
    updateUiState { state ->
        state.copy(
            cropState = state.cropState.copy(
                cropLeft = left,
                cropTop = top,
                cropRight = right,
                cropBottom = bottom
            ).normalized()
        )
    }
}

fun EditorViewModel.rotateCropLeft() {
    updateUiState { state ->
        state.copy(cropState = state.cropState.copy(rotationDegrees = state.cropState.rotationDegrees - 90).normalized())
    }
}

fun EditorViewModel.rotateCropRight() {
    updateUiState { state ->
        state.copy(cropState = state.cropState.copy(rotationDegrees = state.cropState.rotationDegrees + 90).normalized())
    }
}

fun EditorViewModel.toggleCropFlipHorizontal() {
    updateUiState { state ->
        state.copy(cropState = state.cropState.copy(flipHorizontal = !state.cropState.flipHorizontal))
    }
}

fun EditorViewModel.setStraightenDegrees(value: Float) {
    updateUiState { state ->
        state.copy(cropState = state.cropState.copy(straightenDegrees = value.coerceIn(-45f, 45f)))
    }
}

fun EditorViewModel.autoStraightenCrop() {
    val bitmap = uiState.value.previewBitmap ?: uiState.value.originalPreviewBitmap
    if (bitmap == null) {
        updateUiState { it.copy(message = "기울기를 보정할 이미지가 없습니다.") }
        return
    }
    val angle = estimateAutoStraightenDegreesV0(bitmap)
    updateUiState { state ->
        state.copy(
            cropState = state.cropState.copy(straightenDegrees = angle),
            message = "기울기 보정값을 적용했습니다: ${String.format(Locale.US, "%.1f", angle)}°"
        )
    }
}

fun EditorViewModel.resetCropState() {
    val current = prepareForExternalEdit()
    recordUserEditForUndo(clearRedo = true)
    updateUiState { state ->
        val bitmap = current.previewBitmap ?: current.originalPreviewBitmap
        val next = if (bitmap != null) {
            centeredCropForAspect(bitmap.width, bitmap.height, CropAspectRatio.Original)
        } else {
            CropState()
        }
        state.copy(cropState = next, message = "변경사항을 되돌렸습니다.")
    }
}

fun EditorViewModel.applyCropTransform() {
    val state = prepareForExternalEdit()
    if (state.isBusy) return
    val preview = state.previewBitmap
    val original = state.originalPreviewBitmap
    val crop = state.cropState.normalized()
    if (preview == null && original == null) {
        updateUiState { it.copy(message = "자르기할 이미지가 없습니다.") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    val nextRevision = state.revision + 1
    updateUiState {
        it.copy(isBusy = true, revision = nextRevision, message = "변경사항을 적용하는 중입니다.")
    }
    viewModelScope.launch {
        var renderedOriginal: Bitmap? = null
        var renderedPreview: Bitmap? = null
        try {
            renderedOriginal = withContext(Dispatchers.Default) { original?.let { renderCropTransform(it, crop) } }
            renderedPreview = withContext(Dispatchers.Default) { preview?.let { renderCropTransform(it, crop) } }
            if (uiState.value.revision == nextRevision) {
                updateUiState {
                    it.copy(
                        originalPreviewBitmap = renderedOriginal ?: renderedPreview,
                        previewBitmap = renderedPreview ?: renderedOriginal,
                        baseBitmapDirty = true,
                        baseContentVersion = it.baseContentVersion + 1L,
                        cropState = CropState(),
                        isBusy = false,
                        message = "변경사항을 적용했습니다."
                    )
                }
                renderedOriginal = null
                renderedPreview = null
                persistDraftSnapshot()
            } else {
                renderedOriginal?.recycle()
                renderedPreview?.recycle()
                renderedOriginal = null
                renderedPreview = null
            }
        } catch (ce: CancellationException) {
            renderedOriginal?.recycle()
            renderedPreview?.recycle()
            throw ce
        } catch (t: Throwable) {
            renderedOriginal?.recycle()
            renderedPreview?.recycle()
            if (uiState.value.revision == nextRevision) {
                updateUiState {
                    it.copy(isBusy = false, message = "자르기에 실패했습니다: ${t.message}")
                }
            }
        }
    }
}
