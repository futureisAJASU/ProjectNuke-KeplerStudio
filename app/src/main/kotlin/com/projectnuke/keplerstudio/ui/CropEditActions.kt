package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.CropAspectRatio
import com.projectnuke.keplerstudio.editor.CropState
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.centeredCropForAspect
import com.projectnuke.keplerstudio.editor.estimateAutoStraightenDegreesV0
import com.projectnuke.keplerstudio.editor.normalized
import com.projectnuke.keplerstudio.editor.renderCropTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.setCropAspectRatio(aspectRatio: CropAspectRatio) {
    editorFlowCrop().update { state ->
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
    editorFlowCrop().update { state ->
        state.copy(cropState = state.cropState.copy(
            cropLeft = left,
            cropTop = top,
            cropRight = right,
            cropBottom = bottom
        ).normalized())
    }
}

fun EditorViewModel.rotateCropLeft() {
    editorFlowCrop().update { state ->
        state.copy(cropState = state.cropState.copy(rotationDegrees = state.cropState.rotationDegrees - 90).normalized())
    }
}

fun EditorViewModel.rotateCropRight() {
    editorFlowCrop().update { state ->
        state.copy(cropState = state.cropState.copy(rotationDegrees = state.cropState.rotationDegrees + 90).normalized())
    }
}

fun EditorViewModel.toggleCropFlipHorizontal() {
    editorFlowCrop().update { state ->
        state.copy(cropState = state.cropState.copy(flipHorizontal = !state.cropState.flipHorizontal))
    }
}

fun EditorViewModel.setStraightenDegrees(value: Float) {
    editorFlowCrop().update { state ->
        state.copy(cropState = state.cropState.copy(straightenDegrees = value.coerceIn(-45f, 45f)))
    }
}

fun EditorViewModel.autoStraightenCrop() {
    val bitmap = uiState.value.previewBitmap ?: uiState.value.originalPreviewBitmap
    if (bitmap == null) {
        editorFlowCrop().update { it.copy(message = "자동 수평을 맞출 이미지가 없습니다") }
        return
    }
    val angle = estimateAutoStraightenDegreesV0(bitmap)
    editorFlowCrop().update { state ->
        state.copy(
            cropState = state.cropState.copy(straightenDegrees = angle),
            message = "자동 수평 보정값을 적용했습니다: ${String.format(java.util.Locale.US, "%.1f", angle)}°"
        )
    }
}

fun EditorViewModel.resetCropState() {
    editorFlowCrop().update { state ->
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
        editorFlowCrop().update { it.copy(message = "자르기를 적용할 이미지가 없습니다") }
        return
    }
    val nextRevision = state.revision + 1
    editorFlowCrop().update {
        it.copy(isBusy = true, revision = nextRevision, message = "자르기를 적용하는 중입니다")
    }
    viewModelScope.launch {
        try {
            val renderedOriginal = withContext(Dispatchers.Default) { original?.let { renderCropTransform(it, crop) } }
            val renderedPreview = withContext(Dispatchers.Default) { preview?.let { renderCropTransform(it, crop) } }
            if (uiState.value.revision == nextRevision) {
                editorFlowCrop().update {
                    it.copy(
                        originalPreviewBitmap = renderedOriginal ?: renderedPreview,
                        previewBitmap = renderedPreview ?: renderedOriginal,
                        cropState = CropState(),
                        isBusy = false,
                        message = "자르기를 적용했습니다"
                    )
                }
            } else {
                renderedOriginal?.recycle()
                renderedPreview?.recycle()
            }
        } catch (t: Throwable) {
            editorFlowCrop().update {
                it.copy(isBusy = false, message = "자르기 적용에 실패했습니다: ${t.message}")
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun EditorViewModel.editorFlowCrop(): MutableStateFlow<EditorUiState> {
    val field = EditorViewModel::class.java.getDeclaredField("_uiState")
    field.isAccessible = true
    return field.get(this) as MutableStateFlow<EditorUiState>
}
