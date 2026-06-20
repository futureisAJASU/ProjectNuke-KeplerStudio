package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.renderBitmapWithSelectionLayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.updateActiveSelectionParamsLive(transform: (EditParams) -> EditParams) {
    val current = uiState.value
    val activeId = current.activeSelectionLayerId
    val base = current.originalPreviewBitmap ?: current.previewBitmap
    if (activeId == null || base == null) {
        updateUiState { it.copy(message = "보정할 선택 마스크가 없습니다.") }
        return
    }

    val nextLayers = current.selectionLayers.map { layer ->
        if (layer.id == activeId) {
            val nextParams = transform(layer.localParams)
            if (nextParams == layer.localParams) layer else layer.copy(localParams = nextParams)
        } else {
            layer
        }
    }
    if (nextLayers == current.selectionLayers) return

    val nextRevision = current.revision + 1
    val nextState = current.copy(
        selectionLayers = nextLayers,
        revision = nextRevision,
        isBusy = true,
        message = "선택 마스크 미리보기를 렌더링하는 중입니다."
    )
    updateUiState { nextState }

    viewModelScope.launch {
        val preview = withContext(Dispatchers.Default) {
            renderLiveSelectionPreview(base, nextState, nextRevision)
        }
        if (uiState.value.revision == nextRevision) {
            updateUiState {
                it.copy(
                    previewBitmap = preview,
                    isBusy = false,
                    message = "선택 마스크 미리보기가 적용되었습니다."
                )
            }
        } else {
            preview.recycle()
        }
    }
}

private fun renderLiveSelectionPreview(base: Bitmap, state: EditorUiState, revision: Int): Bitmap =
    renderBitmapWithSelectionLayers(base, state, revision)
