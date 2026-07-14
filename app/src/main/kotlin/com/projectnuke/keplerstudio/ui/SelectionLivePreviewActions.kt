package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.renderBitmapWithSelectionLayers
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.copyBitmapsOwned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.updateActiveSelectionParamsLive(transform: (EditParams) -> EditParams) {
    var current = prepareForExternalEdit()
    var activeId = current.activeSelectionLayerId
    var base = current.originalPreviewBitmap ?: current.previewBitmap
    if (activeId == null || base == null) {
        updateUiState { it.copy(message = "보정할 선택 마스크가 없습니다.") }
        return
    }

    if (!beginSelectionParamGesture()) {
        updateUiState { it.copy(message = "선택 마스크 편집 기록을 준비하지 못했습니다.") }
        return
    }
    current = prepareForExternalEdit()
    activeId = current.activeSelectionLayerId
    base = current.originalPreviewBitmap ?: current.previewBitmap
    if (activeId == null || base == null) return

    val nextLayers = current.selectionLayers.map { layer ->
        if (layer.id == activeId) {
            val nextParams = transform(layer.localParams)
            if (nextParams == layer.localParams) layer else layer.copy(localParams = nextParams)
        } else {
            layer
        }
    }
    if (nextLayers == current.selectionLayers) return
    val ownedBase = runCatching { base.copyOrThrow() }.getOrElse {
        invalidateSelectionPreview()
        updateUiState { it.copy(message = "선택 마스크 미리보기용 이미지를 준비하지 못했습니다.") }
        return
    }
    val ownedLayers = runCatching {
        nextLayers.copyBitmapsOwned()
    }.getOrElse {
        ownedBase.recycle()
        invalidateSelectionPreview()
        updateUiState { it.copy(message = "선택 마스크 미리보기를 준비하지 못했습니다.") }
        return
    }

    val nextRevision = current.revision + 1
    val nextState = current.copy(
        selectionLayers = nextLayers,
        revision = nextRevision,
        isBusy = true,
        message = "선택 마스크 미리보기를 렌더링하는 중입니다."
    )
    updateUiState { nextState }

    selectionLivePreviewJob?.cancel()
    val previewToken = beginSelectionPreview()
    selectionLivePreviewJob = viewModelScope.launch {
        var preview: Bitmap? = null
        try {
            delay(120L)
            preview = withContext(Dispatchers.Default) {
                renderLiveSelectionPreview(ownedBase, nextState.copy(selectionLayers = ownedLayers), nextRevision)
            }
            if (isSelectionPreviewCurrent(previewToken, nextRevision, current.baseContentToken, activeId)) {
                val adopted = preview ?: error("missing selection live preview")
                updateUiState {
                    it.copy(
                        previewBitmap = adopted,
                        isBusy = false,
                        message = "선택 마스크 미리보기가 적용되었습니다."
                    )
                }
                preview = null
                markSelectionPreviewSucceeded(previewToken)
            } else {
                preview?.recycle()
                preview = null
            }
        } catch (ce: CancellationException) {
            preview?.recycle()
            throw ce
        } catch (t: Throwable) {
            preview?.recycle()
            if (isSelectionPreviewCurrent(previewToken, nextRevision, current.baseContentToken, activeId)) {
                invalidateSelectionPreview()
                updateUiState {
                    it.copy(
                        isBusy = false,
                        message = "선택 마스크 미리보기를 적용하지 못했습니다: ${t.message}"
                    )
                }
            }
        } finally {
            ownedBase.recycle()
            ownedLayers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }
}

fun EditorViewModel.finishActiveSelectionParamsGesture() {
    finishSelectionParamGesture()
}

private fun renderLiveSelectionPreview(base: Bitmap, state: EditorUiState, revision: Int): Bitmap =
    renderBitmapWithSelectionLayers(base, state, revision)
