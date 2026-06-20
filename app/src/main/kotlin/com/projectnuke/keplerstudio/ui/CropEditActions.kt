package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.centeredCropForAspect
import com.projectnuke.keplerstudio.editor.estimateAutoStraightenDegreesV0
import com.projectnuke.keplerstudio.editor.normalized
import com.projectnuke.keplerstudio.editor.renderCropTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.setCropAspectRatio(aspectRatio: CropAspectRatio) {
    updateUiState { state ->
        val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
        val next = if (bitmap != null) centeredCropForAspect(bitmap.width, bitmap.height, aspectRatio) else CropState(aspectRatio = aspectRatio)
        state.copy(cropState = state.cropState.copy(
            aspectRatio = next.aspectRatio,
            cropLeft = next.cropLeft,
            cropTop = next.cropTop,
            cropRight = next.cropRight,
            cropBottom = next.cropBottom
        ))
    }
}

fun EditorViewModel.updateCropRect(left: Float, top: Float, right: Float, bottom: Float) {
    updateUiState { state ->
        state.copy(cropState = state.cropState.copy(
            cropLeft = left,
            cropTop = top,
            cropRight = right,
            cropBottom = bottom
        ).normalized())
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
        updateUiState { it.copy(message = "?먮룞 ?섑룊??留욎텧 ?대?吏媛 ?놁뒿?덈떎") }
        return
    }
    val angle = estimateAutoStraightenDegreesV0(bitmap)
    updateUiState { state ->
        state.copy(
            cropState = state.cropState.copy(straightenDegrees = angle),
            message = "자동 수평 보정값을 적용했습니다: ${String.format(java.util.Locale.US, "%.1f", angle)}°"
        )
    }
}

fun EditorViewModel.resetCropState() {
    updateUiState { state ->
        val bitmap = state.previewBitmap ?: state.originalPreviewBitmap
        val next = if (bitmap != null) centeredCropForAspect(bitmap.width, bitmap.height, CropAspectRatio.Original) else CropState()
        state.copy(cropState = next, message = "자르기 설정을 초기화했습니다")
    }
}

fun EditorViewModel.applyCropTransform() {
    val state = uiState.value
    val preview = state.previewBitmap
    val original = state.originalPreviewBitmap
    val crop = state.cropState
    if (preview == null && original == null) {
        updateUiState { it.copy(message = "?먮Ⅴ湲곕? ?곸슜???대?吏媛 ?놁뒿?덈떎") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    val nextRevision = state.revision + 1
    updateUiState {
        it.copy(isBusy = true, revision = nextRevision, message = "?먮Ⅴ湲곕? ?곸슜?섎뒗 以묒엯?덈떎")
    }
    viewModelScope.launch {
        try {
            val renderedOriginal = withContext(Dispatchers.Default) { original?.let { renderCropTransform(it, crop) } }
            val renderedPreview = withContext(Dispatchers.Default) { preview?.let { renderCropTransform(it, crop) } }
            if (uiState.value.revision == nextRevision) {
                updateUiState {
                    it.copy(
                        originalPreviewBitmap = renderedOriginal ?: renderedPreview,
                        previewBitmap = renderedPreview ?: renderedOriginal,
                        cropState = CropState(),
                        isBusy = false,
                        message = "?먮Ⅴ湲곕? ?곸슜?덉뒿?덈떎"
                    )
                }
                persistDraftSnapshot()
            } else {
                renderedOriginal?.recycle()
                renderedPreview?.recycle()
            }
        } catch (t: Throwable) {
            updateUiState {
                it.copy(isBusy = false, message = "?먮Ⅴ湲??곸슜???ㅽ뙣?덉뒿?덈떎: ${t.message}")
            }
        }
    }
}
